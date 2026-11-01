# WiFi 锁（WifiLock）

Windows 原生客户端风格的 Android 设备管理小工具：通过 Device Owner 用户限制
`DISALLOW_CONFIG_WIFI`（no_config_wifi）把系统的 WiFi 开关禁用，让设备上的 WiFi
无法被手动关闭。

支持两种授权模式：

| 模式 | 前提 | 说明 |
|---|---|---|
| DIRECT 直连 | `adb dpm set-device-owner` 指向本应用 | 最简单，单工具使用 |
| DHIZUKU 代理 | Dhizuku 是 Device Owner，本应用向其申请授权 | 已由 Dhizuku 托管的设备；免重复 dpm，多个小工具可共用 |

## 构建

- 推荐：用 Android Studio 打开本项目根目录（自动处理 Gradle）
- 命令行（需 JDK 17+ 与 Android SDK；本机实测 Gradle 8.9 + AGP 8.7.3 + JDK 21 可一次通过）：

```
gradlew assembleDebug
产物位于 app/build/outputs/apk/debug/app-debug.apk
```

> 当前包未内置 Gradle Wrapper，Android Studio 首次打开会自动生成。

### 依赖说明

- `io.github.iamr0s:Dhizuku-API:2.7.0` —— Dhizuku 官方客户端 API（Maven Central 自动解析）
- `org.lsposed.hiddenapibypass:hiddenapibypass:4.3` —— Dhizuku 代理 DPM 需要反射
  `DevicePolicyManager.mService` 隐藏字段（Android 9+ 受限），该库负责豁免
- `app/src/main/java/android/app/admin/IDevicePolicyManager.java` 是编译期占位
  stub（仅让调用电通过编译）；运行时以 framework 真实类为准

## 部署步骤

### 1) 安装 Dhizuku 并设为 Device Owner（推荐路径）

从 https://github.com/iamr0s/Dhizuku 的 Releases 下载 APK 侧载安装，然后：

```
adb shell dpm set-device-owner com.rosan.dhizuku/.server.DhizukuAdminReceiver
```

- 必须使用**新版 Dhizuku**（Releases 最新版 v2.13.0，2026-10-30 起）：
  本应用使用的 Dhizuku-API 2.7.0 走新一代协议，旧版服务端无法连接。
- 新版 Dhizuku 的 Device Owner 组件可能为 `com.rosan.dhizuku/.server.DhizukuDAReceiver`，
  **务必以 Dhizuku App 内"申请授权"页面展示的命令为准**（复制即可）。
- 要求设备处于"无账户"状态（新机或恢复出厂后、未登录任何账号时执行）。

### 2) 安装本应用

```
adb install app-debug.apk
```

打开「WiFi 锁」→ 点「申请 Dhizuku 授权」→ Dhizuku 弹框放行 → 打开上锁开关。

### 直连模式（备选）

不经过 Dhizuku，直接把本应用设为 Device Owner：

```
adb shell dpm set-device-owner com.example.wifilock/.AdminReceiver
```

注意：一台设备只能有一个 Device Owner，直连后 Dhizuku 将无法再设置为 owner（反之亦然）。

## Shizuku 模式（推荐：手机上免电脑完成授权）

新 APK 已内置 Shizuku 支持（`dev.rikka.shizuku:api/provider 13.1.5`，
API 用法已逐一对 rikka.shizuku 源码核实）：

- 原理：App 通过「Shizuku UserService」以 shell（uid 2000，无线调试启动）或 root 身份
  在本应用内执行真实的 `dpm set-device-owner` 命令 —— 与电脑 adb 完全等效，
  但**不需要电脑**。设置成功后锁定功能走直连 Device Owner 模式，稳定最简。
- 使用步骤：
  1. 官网/应用商店安装 Shizuku（`moe.shizuku.privileged.api`）；
  2. 手机上启动 Shizuku（无线调试配对，或 Root 一键启动）；
  3. 打开本 App，点「申请 Shizuku 授权」，允许后直接点
     「Shizuku 一键：设本应用为 Device Owner」（或先装 Dhizuku 再一键设 Dhizuku 上位）；
  4. App 内输出窗会显示命令的 exit code 与完整回显，便于排错。
- 已知限制（与 adb 相同的系统规则）：设备上不能已有 Device Owner；非 test-only
  owner 时要求设备无账户（新机或恢复出厂后成功率最高）。
- 若之前用其他方式已设过 owner，Shizuku 按钮会返回系统原文报错，正常现象。

## 验证与撤销

- 验证：设置 → WLAN 顶部开关应为灰色不可点；ColorOS 快捷中心请实机测试。
- 解锁：关闭 App 内的开关即可。
- 彻底解除：`adb shell dpm remove-active-admin ...`，或恢复出厂设置。

## OriginOS 6（vivo，Android 16）适配要点

- 本包已按 Android 16 适配验证：`HiddenApiBypass 6.2`（Android 16 官方开发文档
  认可的隐藏 API 豁免库）、Dhizuku 代理姿势与 AOSP 16 框架兼容（`mService`
  字段已核对 AOSP 最新分支仍存在）。
- **Dhizuku 请用 v2.13.0（2026-10-30）**：该版修了服务启动竞态、权限授予兼容性，
  下 APKS 区任选（minSdk 23/29/32 均可在 Android 16 上安装）。
- Android 15/16 契约修复（重要）：AOSP 自 Android 15 起新增
  `setUserRestriction(admin, callerPackage, key, enable, parent)` 的包名归属校验
  （`Caller with uid xxx is not yyy` 即其报错）。经 Dhizuku 转发后，系统看到的
  调用方是 Dhizuku server；本包改为直接发起 AIDL 调用并把 callerPackage 传成
  Dhizuku 的包名（协议层自洽，见 PolicyController.kt 注释与
  app/src/main/aidl/android/app/admin/IDevicePolicyManager.aidl）。
- OriginOS 6 的 Device Owner 组件为 `com.rosan.dhizuku/.server.DhizukuDAReceiver`
  （App 内展示的命令为准）。
- vivo 侧准备：设置 → 更多设置/系统管理 → 开发者选项 → USB 调试打开
  （部分机型提示滑动确认即可）；执行 adb 命令无需"USB 调试（安全设置）"。
- 控制中心/下拉磁贴的 WLAN 快捷开关在 OriginOS 上可能不走系统限制通道，
  请实机验证；若仍可关，可用 `adb shell svc wifi enable` 兜底。
- 若之前装过旧版本 WifiLock 并授权过 Dhizuku，请先在 Dhizuku 内（或直接重装
  Dhizuku 前后对比）重新点一次「申请 Dhizuku 授权」刷新授权记录，再上锁。

## 重要提醒

1. 上锁会同时禁止"添加/忘记网络"，请先连好常用 WiFi 再上锁；
2. ColorOS 11.1 的快捷中心可能未完全遵守该限制，若仍可关，
   可临时用 adb 兜底：`adb shell svc wifi enable`（循环守护脚本见交付对话）；
3. 仅限自有或已获合法授权的设备使用；"关闭时弹验证窗"在非 root 下无系统拦截点，无法实现。
