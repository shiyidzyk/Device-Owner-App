package com.example.wifilock

import android.os.UserManager

/**
 * 企业 MDM 常用「用户限制」清单（均经 `addUserRestriction` 下发，三种授权通道复用）。
 *
 * 合法性核对（AOSP UserRestrictionsUtils，master 与 android-11 同构）：
 * - Device Owner 可设任意有效限制，例外仅三条（IMMUTABLE_BY_OWNERS）：
 *   DISALLOW_RECORD_AUDIO / DISALLOW_WALLPAPER / DISALLOW_OEM_UNLOCK —— 本清单不含；
 * - DISALLOW_OUTGOING_CALLS / DISALLOW_SMS 仅对"托管资料(工作资料)"生效，主用户设了无效，不收录；
 * - DISALLOW_WIFI_TETHERING 属 DEVICE_OWNER_ONLY 集合 —— 本 App/Dhizuku 均以 DO 身份下发，可用。
 *
 * special = SCREEN_CAPTURE 的条目不是用户限制，而是 DPM 公开 API
 * `setScreenCaptureDisabled`（Android 15+ 的 Dhizuku 代理未适配该契约，UI 侧按需禁用）。
 */
object RestrictionCatalog {

    const val SPECIAL_SCREEN_CAPTURE = "screen_capture"

    class Item(
        /** UserManager.DISALLOW_*；为 null 时看 special */
        val key: String?,
        val title: String,
        val desc: String,
        val special: String? = null
    )

    class Section(val title: String, val items: List<Item>)

    val sections: List<Section> = listOf(
        Section("网络", listOf(
            // DISALLOW_CONFIG_BLUETOOTH：界面级（设置 App 自觉灰化）。
            // 控制中心不检查用户限制（SystemUI 快速磁贴从不读限制，原生 AOSP 亦然），
            // 要彻底锁死请用下一项 DISALLOW_BLUETOOTH（服务级）。
            Item(UserManager.DISALLOW_CONFIG_BLUETOOTH, "禁蓝牙配置（界面级）",
                "设置内蓝牙开关/配对不可改。注意：只对“设置”界面生效，控制中心仍可能绕过；要彻底锁死用「蓝牙锁定（服务级冻结）」"),
            // DISALLOW_BLUETOOTH = "no_bluetooth"：AOSP 公开常量（Android 14 起标记弃用，功能仍在），
            // 属 PRIMARY_USER_ONLY 集合——Device Owner 设置后由 BluetoothManagerService 强制：
            // enable()/disable() 均被直接拒绝（R 源码 BluetoothManagerService 1054/1088 行，
            // isBluetoothDisallowed() 检查的是用户限制而非调用方），与哪块界面发起无关——
            // 控制中心切换等于没切。注意双向冻结：开启时蓝牙若正开着会冻在“开”。
            Item(UserManager.DISALLOW_BLUETOOTH, "蓝牙锁定（服务级冻结）",
                "蓝牙状态整体冻结：设置/控制中心/任何开关都改不动。⚠️ 开启时蓝牙若正开着，会冻在“开”——想保持关请先关掉蓝牙再启用"),
            Item(UserManager.DISALLOW_CONFIG_TETHERING, "禁网络共享", "USB/蓝牙共享设置不可改"),
            Item(UserManager.DISALLOW_WIFI_TETHERING, "禁 Wi-Fi 热点", "热点开关不可用"),
            Item(UserManager.DISALLOW_AIRPLANE_MODE, "禁飞行模式", "快捷磁贴/设置不可开飞行模式"),
            Item(UserManager.DISALLOW_CONFIG_MOBILE_NETWORKS, "禁改移动网络", "移动数据开关/APN 等不可改"),
            Item(UserManager.DISALLOW_CONFIG_VPN, "禁改 VPN", "VPN 配置不可增删改")
        )),
        Section("应用", listOf(
            Item(UserManager.DISALLOW_INSTALL_APPS, "禁安装应用", "无法安装任何新应用"),
            // PMS.deletePackageAsUser 对该限制统一拒绝（DELETE_FAILED_USER_RESTRICTED，
            // R 源码 PackageManagerService 18169 行）；isUserRestricted 检查的是目标用户的
            // 限制而非调用方身份——因此连 `adb shell pm uninstall`（shell 权限）都会失败，
            // 仅 root 可绕过。同限制还令普通调用方的“停用/挂起应用”被拒
            // （PMS isSuspendAllowedForUser 同步检查 DISALLOW_UNINSTALL_APPS）。
            Item(UserManager.DISALLOW_UNINSTALL_APPS, "防卸载（禁止卸载应用）",
                "所有已装应用不可卸载/停用（含本应用与 Dhizuku）。系统在包管理器层面直接拦截，连 adb uninstall 也会失败；想卸载任何应用前先关闭本项"),
            Item(UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES, "禁未知来源", "无法从侧载渠道安装应用")
        )),
        Section("系统与安全", listOf(
            Item(UserManager.DISALLOW_DEBUGGING_FEATURES, "禁调试功能", "USB 调试/开发者选项调试类功能关闭"),
            Item(UserManager.DISALLOW_SAFE_BOOT, "禁安全模式", "重启进安全模式被阻止"),
            Item(UserManager.DISALLOW_FACTORY_RESET, "禁恢复出厂", "设置内“恢复出厂”不可用"),
            Item(UserManager.DISALLOW_ADD_USER, "禁添加用户", "多用户/访客不可新增"),
            Item(UserManager.DISALLOW_CONFIG_DATE_TIME, "禁改日期时间", "时间/时区不可手动修改")
        )),
        Section("外设与数据", listOf(
            Item(UserManager.DISALLOW_USB_FILE_TRANSFER, "禁 USB 文件传输", "MTP/PTP 文件传输不可用"),
            Item(UserManager.DISALLOW_MOUNT_PHYSICAL_MEDIA, "禁挂载外部介质", "SD 卡/U 盘不可挂载"),
            Item(UserManager.DISALLOW_CROSS_PROFILE_COPY_PASTE, "禁跨资料剪贴板", "个人/工作资料间不能复制粘贴"),
            Item(UserManager.DISALLOW_PRINTING, "禁打印", "打印服务不可用")
        )),
        Section("硬件与媒体", listOf(
            // UserManager.DISALLOW_CAMERA 是隐藏常量（SDK 不导出），AOSP 15 原文为 "no_camera"
            Item("no_camera", "禁相机", "相机硬件级禁用"),
            // Android 12 起“禁用麦克风”API 已移除，仅剩隐私开关锁定（DO 专属限制）
            Item(UserManager.DISALLOW_MICROPHONE_TOGGLE, "锁麦克风开关", "用户无法切换麦克风隐私开关（麦克风保持当前状态）"),
            Item(UserManager.DISALLOW_FUN, "禁多媒体娱乐", "游戏/多媒体娱乐类内容被禁"),
            Item(null, "禁截屏", "全局禁止截屏（DPM API，非用户限制）", SPECIAL_SCREEN_CAPTURE)
        ))
    )
}
