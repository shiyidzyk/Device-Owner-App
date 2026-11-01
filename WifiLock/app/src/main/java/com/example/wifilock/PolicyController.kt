package com.example.wifilock

import android.app.admin.DevicePolicyManager
import android.app.admin.IDevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.IInterface
import android.os.UserManager
import com.rosan.dhizuku.api.Dhizuku

/**
 * 核心逻辑：检测权限来源（直连 Device Owner / Dhizuku 代理），
 * 并统一执行 createUserRestriction(DISALLOW_CONFIG_WIFI)。
 *
 * 注意：Dhizuku 模式下所有 DPM 调用必须传入 Dhizuku 的 owner 组件，
 * 而不是本应用自己的 AdminReceiver —— 这是代理 DPM 最常见的踩坑点。
 *
 * 契约分代（已核对 AOSP main）：
 * - Android ≤14（含 ColorOS 11.1）：`addUserRestriction(admin, key)`，无包名校验；
 *   走「mService 热替换 + platform 公共 API」的老路径即可。
 * - Android 15/16（OriginOS 6 = API 36）：客户端封装改为
 *   `setUserRestriction(admin, callerPackage, key, enable, parent)`，系统端用
 *   `getCallerIdentity(admin, callerPackage)` 校验「callerPackage 必须属于真实调用方」。
 *   经 Dhizuku 转发后，系统看到的调用方是 Dhizuku server 的 uid，若沿用 platform
 *   公共 API（package 参数 = 本应用包名）就会抛
 *   "Caller with uid xxx is not com.example.wifilock"。
 *   解法：直接发 AIDL 调用，把 callerPackage 传成 Dhizuku 的包名 —— 转发后与
 *   server uid 归属完全一致，校验通过；admin 组件校验同理（Dhizuku 的 active admin）。
 *
 * 实现：反射取系统 DPM 内部 mService（hidden 字段，HiddenApiBypass 已豁免）得到
 * 系统真实 binder，再由 Dhizuku.binderWrapper 包装为代理，直接以新契约调用。
 */
class PolicyController(private val context: Context) {

    enum class Mode { NONE, DIRECT, DHIZUKU }

    companion object {
        /** Android 15 起 setUserRestriction/callerPackage 新契约 */
        private const val API_NEW_RESTRICTION_CONTRACT = 35

        /** 系统端校验 callerPackage 时必须与「真实调用方 uid」一致；经 Dhizuku 转发即为 server 包 */
        private const val CALLER_PACKAGE = "com.rosan.dhizuku"
    }

    private val dhizukuPackage = "com.rosan.dhizuku"

    private val directAdmin: ComponentName
        get() = ComponentName(context, AdminReceiver::class.java)

    /** 是否安装了 Dhizuku */
    fun dhizukuInstalled(): Boolean = try {
        context.packageManager.getPackageInfo(dhizukuPackage, 0)
        true
    } catch (_: Exception) {
        false
    }

    /** 检测当前可用的管理权限来源 */
    fun detectMode(): Mode {
        val dpm = context.getSystemService(DevicePolicyManager::class.java)
        // 优先：本应用自身是 Device Owner（直连模式）
        if (dpm != null && dpm.isDeviceOwnerApp(context.packageName)) return Mode.DIRECT
        // 其次：Dhizuku 已安装 + 已成为 owner + 已授予本应用调用权
        if (dhizukuInstalled()) {
            return try {
                if (!Dhizuku.init(context)) return Mode.NONE
                val owner = Dhizuku.getOwnerComponent()
                if (owner != null && Dhizuku.isPermissionGranted()) Mode.DHIZUKU else Mode.NONE
            } catch (_: Throwable) {
                Mode.NONE
            }
        }
        return Mode.NONE
    }

    /**
     * 取系统 DevicePolicyManager 内部的 IDevicePolicyManager binder（反射 mService）。
     * 这是构建 Dhizuku 代理的原料（wrapper 的 target）。
     */
    private fun systemIdpmAsInterface(): IDevicePolicyManager {
        val native = context.getSystemService(DevicePolicyManager::class.java)
            ?: throw IllegalStateException("获取 DevicePolicyManager 失败")
        val serviceField = try {
            DevicePolicyManager::class.java.getDeclaredField("mService").apply {
                isAccessible = true
            }
        } catch (t: NoSuchFieldException) {
            throw IllegalStateException(
                "当前系统框架缺少标准 mService 字段（定制框架重排了 DPM 内部结构），" +
                    "Dhizuku 代理在该系统不可用；可改用直连 Device Owner 模式。", t
            )
        }
        val iIfc = serviceField.get(native) as IInterface
        return IDevicePolicyManager.Stub.asInterface(Dhizuku.binderWrapper(iIfc.asBinder()))
    }

    /**
     * 新契约直调（Android 15+，OriginOS 6）：callerPackage 传 Dhizuku 包名，
     * 转发后与系统看到的调用方 uid 归属一致，通过 getCallerIdentity 校验。
     */
    private fun applyDhizukuRestriction(enable: Boolean) {
        val owner = Dhizuku.getOwnerComponent()
            ?: throw IllegalStateException("Dhizuku 尚未激活（未取得 owner）")
        val proxy = systemIdpmAsInterface()
        proxy.setUserRestriction(
            owner,
            CALLER_PACKAGE,
            UserManager.DISALLOW_CONFIG_WIFI,
            enable,
            false
        )
    }

    /** 新契约下读取锁定状态 */
    private fun isDhizukuLocked(): Boolean {
        val owner = Dhizuku.getOwnerComponent() ?: return false
        return try {
            val proxy = systemIdpmAsInterface()
            proxy.getUserRestrictions(owner, CALLER_PACKAGE, false)
                .getBoolean(UserManager.DISALLOW_CONFIG_WIFI)
        } catch (t: NoSuchFieldException) {
            false
        }
    }

    /**
     * 旧契约（Android ≤14）：临时把系统 DPM 的 mService 指向经 Dhizuku 转发的代理，
     * 用 platform 公共 API（无 callerPackage 参数）执行操作，然后恢复原 binder。
     */
    private fun <T> withDhizukuDpm(action: (DevicePolicyManager, ComponentName) -> T): T {
        val native = context.getSystemService(DevicePolicyManager::class.java)
            ?: throw IllegalStateException("获取 DevicePolicyManager 失败")
        val owner = Dhizuku.getOwnerComponent()
            ?: throw IllegalStateException("Dhizuku 尚未激活（未取得 owner）")

        val serviceField = try {
            DevicePolicyManager::class.java.getDeclaredField("mService").apply {
                isAccessible = true
            }
        } catch (t: NoSuchFieldException) {
            throw IllegalStateException(
                "当前系统框架缺少标准 mService 字段（定制框架重排了 DPM 内部结构），" +
                    "Dhizuku 代理在该系统不可用；可改用直连 Device Owner 模式。", t
            )
        }
        val original = serviceField.get(native) as IInterface
        serviceField.set(
            native,
            IDevicePolicyManager.Stub.asInterface(Dhizuku.binderWrapper(original.asBinder()))
        )
        try {
            return action(native, owner)
        } finally {
            try {
                serviceField.set(native, original)
            } catch (_: Throwable) {
            }
        }
    }

    /** 直连模式的系统 DPM */
    private fun directDpm(): DevicePolicyManager =
        context.getSystemService(DevicePolicyManager::class.java)
            ?: throw IllegalStateException("获取 DevicePolicyManager 失败")

    private fun applyLock(dpm: DevicePolicyManager, admin: ComponentName, enable: Boolean) {
        if (enable) {
            dpm.addUserRestriction(admin, UserManager.DISALLOW_CONFIG_WIFI)
        } else {
            dpm.clearUserRestriction(admin, UserManager.DISALLOW_CONFIG_WIFI)
        }
    }

    /** 上锁 / 解锁（UserManager.DISALLOW_CONFIG_WIFI = "no_config_wifi"） */
    fun setLock(enable: Boolean) {
        when (detectMode()) {
            Mode.DIRECT -> applyLock(directDpm(), directAdmin, enable)
            Mode.DHIZUKU -> {
                if (Build.VERSION.SDK_INT >= API_NEW_RESTRICTION_CONTRACT) {
                    applyDhizukuRestriction(enable)
                } else {
                    withDhizukuDpm { dpm, owner -> applyLock(dpm, owner, enable) }
                }
            }
            Mode.NONE -> throw IllegalStateException("尚未取得管理权限，请先用 adb 完成授权")
        }
    }

    /** 读取当前锁定状态 */
    fun isLocked(): Boolean = try {
        when (detectMode()) {
            Mode.DIRECT -> directDpm()
                .getUserRestrictions(directAdmin)
                .getBoolean(UserManager.DISALLOW_CONFIG_WIFI)
            Mode.DHIZUKU -> {
                if (Build.VERSION.SDK_INT >= API_NEW_RESTRICTION_CONTRACT) {
                    isDhizukuLocked()
                } else {
                    withDhizukuDpm { dpm, owner ->
                        dpm.getUserRestrictions(owner)
                            .getBoolean(UserManager.DISALLOW_CONFIG_WIFI)
                    }
                }
            }
            else -> false
        }
    } catch (_: Throwable) {
        false
    }
}
