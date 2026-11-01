package com.example.wifilock

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import rikka.shizuku.Shizuku

/**
 * Shizuku 客户端封装。全部 API 均与 dev.rikka.shizuku:api 13.1.5 的
 * rikka.shizuku.Shizuku 源码逐一核对（pingBinder/getUid/checkSelfPermission/
 * requestPermission/OnRequestPermissionResultListener/bindUserService/UserServiceArgs）。
 *
 * 角色：本模式即「以 Shizuku 的 shell/root 身份执行 dpm set-device-owner」，
 * 等价于用户手工 adb，但不需要电脑 —— App 内点按钮即可完成 Device Owner 设置。
 * 之后锁定功能走 DIRECT 模式，不再需要任何代理。
 */
object ShizukuClient {

    /** 申请权限的 requestCode（App 与 ShizukuManager 的匹配码，无系统约束） */
    const val REQ_PERMISSION = 30001

    /** Shizuku 管理器包名：新版（v13 时代，见 rikka.shizuku.ShizukuProvider 常量） */
    private const val MANAGER_NEW = "moe.shizuku.privileged.api"
    /** Shizuku 管理器包名：旧版 */
    private const val MANAGER_OLD = "moe.shizuku.manager"

    /** UserService 版本号：服务代码更新时递增，server 会重建进程 */
    private const val USER_SERVICE_VERSION = 1

    fun installed(context: Context): Boolean {
        val pm = context.packageManager
        return listOf(MANAGER_NEW, MANAGER_OLD).any { pkg ->
            try {
                pm.getPackageInfo(pkg, 0)
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    fun ping(): Boolean = try {
        Shizuku.pingBinder()
    } catch (_: Throwable) {
        false
    }

    fun granted(): Boolean {
        if (!ping()) return false
        return try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Throwable) {
            false
        }
    }

    /** -1 = 未知 / pre-v11 未授权；2000 = adb 启动；0 = root 启动 */
    fun uid(): Int = try {
        Shizuku.getUid()
    } catch (_: Throwable) {
        -1
    }

    /**
     * 通过 Shizuku UserService 执行 dpm set-device-owner。
     * @param adminFlatten 例如 "com.example.wifilock/.AdminReceiver"
     * @param onFailure 仅用于 ex 输出，成功与否以 output 文本判定（exit=0）
     */
    fun setDeviceOwner(
        context: Context,
        adminFlatten: String,
        onResult: (output: String, success: Boolean) -> Unit
    ) {
        val args = Shizuku.UserServiceArgs(ComponentName(context, ShellUserService::class.java))
            .daemon(false)              // 挂掉即消亡（调用方进程死了服务也死）
            .version(USER_SERVICE_VERSION)
            .debuggable(false)
            .processNameSuffix("shizuku") // UserServiceArgs 许可的进程名后缀（必须设置，否则 NPE）
            .tag("wifilock")

        var service: IShellUserService? = null
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                service = IShellUserService.Stub.asInterface(binder)
                Thread {
                    val output = try {
                        service?.exec(
                            arrayOf("dpm", "set-device-owner", adminFlatten)
                        ) ?: "未取得 UserService 绑定"
                    } catch (t: Throwable) {
                        "${t.javaClass.simpleName}: ${t.message}"
                    }
                    Handler(Looper.getMainLooper()).post {
                        try {
                            Shizuku.unbindUserService(args, this, false)
                        } catch (_: Throwable) {
                        }
                        onResult(output, output.startsWith("exit=0"))
                    }
                }.start()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                service = null
            }
        }
        try {
            Shizuku.bindUserService(args, conn)
        } catch (t: Throwable) {
            throw IllegalStateException("无法启动 Shizuku UserService：${t.message}", t)
        }
    }

    /** 状态描述，直接用于 UI文本 */
    fun statusText(context: Context): String = when {
        !installed(context) -> "未安装（应用商店获取 Shizuku）"
        !ping() -> "已安装，但 Shizuku 未启动（手机上打开 Shizuku App，无线调试或 Root 启动）"
        !granted() -> "已启动，尚未授权"
        else -> when (uid()) {
            0 -> "已授权（root 模式，uid=0）"
            2000 -> "已授权（adb 模式，uid=2000）"
            else -> "已授权（uid=${uid()}）"
        }
    }
}
