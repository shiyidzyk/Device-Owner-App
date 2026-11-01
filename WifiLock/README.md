# WiFi 锁（WifiLock）

Android 设备管理小工具：把系统的 WiFi 开关锁定为“始终开启”。
提供两条技术路线：

1. **设备管理员路线**（系统级持久）：App（或 Dhizuku）成为 Device Owner，
   通过用户限制 `DISALLOW_CONFIG_WIFI`（no_config_wifi）让设置页 WLAN 开关
   灰化、无法关闭/添加/忘记网络。持久生效，不依赖任何后台进程。
2. **Shizuku 直接锁定路线**（免管理员）：App 向 Shizuku 申请授权后，由
   Shizuku（adb 身份）周期性把 WiFi 强制开回（默认 3 秒轮询）。**已使用过的
   设备、不想激活任何管理员时用这条**；保护依赖 Shizuku 在线。

| 模式 | 获权方式 | 适用场景 |
|---|---|---|
| DIRECT 直连 | `adb dpm set-device-owner` 指向本应用 | 单工具长期占用 owner |
| DHIZUKU 代理 | Dhizuku 是 owner，本应用向其申请授权 | 多个小工具共用 owner |
| SHIZUKU 一键 | App 内经 Shizuku 执行 `dpm set-device-owner`（免电脑） | 想要路线 1 但手边没有电脑 |
| **SHIZUKU 直接锁定** | 仅向 Shizuku 申请授权，不做任何激活 | **已用过的设备（有账户/已过引导）** |

## 构建

- 推荐：Android Studio 打开本项目根目录；或命令行
  （本机实测 Gradle 8.9 + AGP 8.7.3 + JDK 21，详见 `gradle.properties`）：

```
gradle assembleDebug
产物：app/build/outputs/apk/debug/app-debug.apk
```

> AGP 8 默认关闭 AIDL，本项目已在 `app/build.gradle` 打开 `buildFeatures { aidl = true }`。

### 依赖说明（Maven Central）

- `io.github.iamr0s:Dhizuku-API:2.7.0` —— Dhizuku 官方客户端 API
- `org.lsposed.hiddenapibypass:6.2` —— Dhizuku 代理需要反射 `DevicePolicyManager.mService`
- `dev.rikka.shizuku:api` / `dev.rikka.shizuku:provider` 13.1.5 —— Shizuku 客户端与 binder 载体
- `app/src/main/aidl/…/IDevicePolicyManager.aidl` —— Android 15/16 DPM 契约适配（事务码对齐，
  **不要整理/删改**，见移交文档 §7）

## 首次使用（三选一）

### 方案 0 · Shizuku 直接锁定（已用过的设备推荐，无需任何激活）

1. 安装并启动 Shizuku（无线调试配对，或 Root 一键启动）；
2. 本 App 内点「申请 Shizuku 授权」；
3. 打开「Shizuku 直接锁定」开关 → App 每 3 秒由 Shizuku 把 WiFi 开回
   （`cmd wifi set-wifi-enabled enabled`，失败退回 `svc wifi enable`）。

特性与边界：
- **免管理员**：有 Google 账户、已走过开机引导的“旧设备”直接可用；
- 看门狗以 daemon 模式跑在 Shizuku 的 UserService 进程里：**关掉本 App 仍然生效**；
- Shizuku 停止（如关闭无线调试）时保护随之失效；Shizuku 重新上线后本 App 会自动续接；
- 关闭 WiFi 会在约 ≤3 秒内被强制恢复，期间有短暂断网，属正常现象；
- 想要"系统级灰化开关 + 不依赖 Shizuku"，仍需路线 1（设备管理员）。

### 方案 1 · Shizuku 一键设为 Device Owner（灰化开关，最持久）

点「Shizuku 一键：本 App 设为 Device Owner」。**内置自动诊断**：
- 先用 shell 检查：已有 Device Owner / 账户数 / 多用户；
- 无障碍 → 直接执行 `dpm set-device-owner`；
- 有账户 → 提示先到系统账户设置临时删除（记住密码，激活成功后再加回来），
  无需恢复出厂。这是“已用过的设备”上激活失败的真正原因（系统校验
  `already some accounts`），诊断结论会显示在输出窗。

### 方案 2 · 电脑 adb（与方案 1 同效）

```
adb shell dpm set-device-owner com.example.wifilock/.AdminReceiver     # 本应用直连
adb shell dpm set-device-owner com.rosan.dhizuku/.server.DhizukuDAReceiver  # Dhizuku（以 Dhizuku App 授权页命令为准）
```

- 一台设备只能有一个 Device Owner；Dhizuku 需 v2.13.0+（v8 协议）。
- Dhizuku 模式要求先向 Dhizuku App 申请本应用授权。

## 验证与撤销

- 验证：设置 → WLAN 开关应灰化（路线 1）；路线 0 请关一次 WiFi 观察约 3 秒内自动恢复。
- 解锁：App 内关闭对应开关（路线 0 关开关即停止看门狗并清理进程）。
- 彻底解除：Device Owner 使 App 受系统保护、无法被普通卸载；
  恢复出厂，或由 owner 应用自身解除（如 Dhizuku 的解绑入口）。

## OriginOS 6 / ColorOS 11 实测注意

- 控制中心/下拉磁贴可能不走 `no_config_wifi` 限制通道（厂商实现差异），
  请实机验证；路线 0 的看门狗可覆盖这类磁贴（它拦截的是“结果”而非 UI）。
- Android 15/16 的 DPM 契约变化已在本包内适配（`Caller with uid … is not …`
  报错的修复），详见移交文档 §7。

## 重要提醒

1. 路线 1 上锁会同时禁止“添加/忘记网络”，请先连好常用 WiFi；
2. 仅限自有或已获合法授权的设备使用；“关闭时弹验证窗”在非 root 下无系统拦截点；
3. Shizuku 直接锁定不改系统持久配置，本质是“自动把 WiFi 开回”，请按需选择路线。
