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
            Item(UserManager.DISALLOW_CONFIG_BLUETOOTH, "禁蓝牙配置", "设置内蓝牙开关不可改"),
            Item(UserManager.DISALLOW_CONFIG_TETHERING, "禁网络共享", "USB/蓝牙共享设置不可改"),
            Item(UserManager.DISALLOW_WIFI_TETHERING, "禁 Wi-Fi 热点", "热点开关不可用"),
            Item(UserManager.DISALLOW_AIRPLANE_MODE, "禁飞行模式", "快捷磁贴/设置不可开飞行模式"),
            Item(UserManager.DISALLOW_CONFIG_MOBILE_NETWORKS, "禁改移动网络", "移动数据开关/APN 等不可改"),
            Item(UserManager.DISALLOW_CONFIG_VPN, "禁改 VPN", "VPN 配置不可增删改")
        )),
        Section("应用", listOf(
            Item(UserManager.DISALLOW_INSTALL_APPS, "禁安装应用", "无法安装任何新应用"),
            Item(UserManager.DISALLOW_UNINSTALL_APPS, "禁卸载应用", "已装应用不可卸载"),
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
