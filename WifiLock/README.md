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

## 界面结构（v1.1 起）

- **主页**：权限模式/状态 + 两个锁定开关（路线 1 设备管理员 / 路线 2 Shizuku 直接锁定）
  + 两个入口按钮（⚙️ 设置与授权、📖 使用说明）。开关联动状态实时显示。
- **设置与授权页**：Shizuku 状态与授权、一键激活 Device Owner（自动诊断）、
  Dhizuku 授权、执行输出窗。
- **使用说明页**：三方案怎么选 + 授权命令复制。
- 系统返回键或页内「← 返回」回到主页。

## 首次使用（三选一）

### 方案 0 · Shizuku 直接锁定（已用过的设备推荐，无需任何激活）

1. 安装并启动 Shizuku（无线调试配对，或 Root 一键启动）；
2. 本 App「设置与授权」页点「申请 Shizuku 授权」；
3. 回主页打开「路线 2 · Shizuku 直接锁定」开关 → App 每 3 秒由 Shizuku 把 WiFi 开回
   （`cmd wifi set-wifi-enabled enabled`，失败退回 `svc wifi enable`）。

特性与边界：
- **免管理员**：有 Google 账户、已走过开机引导的“旧设备”直接可用；
- 看门狗以 daemon 模式跑在 Shizuku 的 UserService 进程里：**关掉本 App 仍然生效**；
- Shizuku 停止（如关闭无线调试）时保护随之失效；Shizuku 重新上线后本 App 会自动续接；
- 关闭 WiFi 会在约 ≤3 秒内被强制恢复，期间有短暂断网，属正常现象；
- 想要"系统级灰化开关 + 不依赖 Shizuku"，仍需路线 1（设备管理员）。

### 方案 1 · Shizuku 一键设为 Device Owner（灰化开关，最持久）

点「设置与授权」页的「Shizuku 一键：本 App 设为 Device Owner」。**内置自动诊断**：
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

## 限制管理（v1.2 新增，企业 MDM 常用清单）

主页「🧰 限制管理」进入：网络 / 应用 / 系统与安全 / 外设与数据 / 硬件与媒体
五组共 22 项系统级管控（蓝牙配置、热点、飞行模式、装/卸应用、未知来源、
USB 调试、安全模式、恢复出厂、添加用户、日期时间、USB 文件传输、外部介质
挂载、跨资料剪贴板、打印、相机、麦克风开关锁定、多媒体、截屏等），全部经
`addUserRestriction`（截屏为 `setScreenCaptureDisabled`）下发，三种授权通道复用，
开关状态实时读取自系统，解除即回本页关闭。

- 需要**路线 1**（本 App 或 Dhizuku 为 Device Owner）授权；路线 2（Shizuku 看门狗）
  仅覆盖 WiFi 强制开回，与本页无关。
- **Dhizuku + Android 15+**：用户限制读写可用（AIDL 契约已适配），“禁截屏”未适配
  （该 API 事务码不在契约内），页内会置灰并提示。
- 条目合法性已逐条对照 AOSP `UserRestrictionsUtils`：Device Owner 可设除
  `DISALLOW_RECORD_AUDIO / DISALLOW_WALLPAPER / DISALLOW_OEM_UNLOCK` 外的全部限制，
  本清单不含这三条；`DISALLOW_OUTGOING_CALLS / DISALLOW_SMS` 仅对托管资料生效，亦未收录。
- “禁相机”使用的 `no_camera` 是隐藏常量（SDK 不导出）；Android 12 起“禁用麦克风”
  API 已移除，本页提供的是 `DISALLOW_MICROPHONE_TOGGLE`（麦克风隐私开关锁定）。

## 应用锁（v1.3 新增，v1.4 改严格模式：PIN 码 + 离线重置）

主页「🔐 应用锁」启用后，进入应用需输入 PIN（数字键盘门禁页，返回键只会退到桌面）：

- **每次离开即锁（v1.4）**：切后台、划掉后台、跳转其他应用、进程被杀——重新进入
  一律要求重新输入 PIN（旋转屏幕不算离开）；
- 门禁页禁止截屏与最近任务预览（FLAG_SECURE），防止 PIN 位被偷看；
- **防爆破**：连续错 5 次进入指数锁定（30 秒起，最长 30 分钟）；
- PIN 以「16B 随机盐 + 5 万次迭代 SHA-256」存储，不存明文；
- **修改 PIN** 复用同一重置私钥（已保存的私钥继续有效）；**关闭应用锁**会连私钥记录一并清除。

### 忘记 PIN：纯离线挑战-应答重置

1. **启用应用锁时会显示一串“重置私钥”（只显示这一次！）**——立即复制保存到电脑
   `tools/pin_reset.py` 同目录，文件名 `wifilock_reset_private.key`（纯文本 Base64）。
   本机只存公钥，**拿到手机的人无法自行生成重置码**；
2. 锁定页点「忘记 PIN」→ 得到申请码 `WFL1-<keyid>-<挑战hex>`（10 分钟有效、一次性）；
3. 电脑运行（需 `pip install cryptography`）：
   ```
   python tools/pin_reset.py reset WFL1-xxxxxxxx-...
   ```
4. 把输出的 `WFR1-...` 重置码粘贴回 App → 验签通过即删除 PIN（回到未启用状态）。

协议：App 生成 16 字节随机挑战 → PC 用私钥做 ECDSA-SHA256(P-256) 签名（DER）→
App 用存留公钥验签。签名不可由公钥推出，挑战一次性且限时。
**丢失 PIN 且丢失私钥 = 只能恢复出厂**（Device Owner 下也无法卸载重装），私钥请妥善保管。

### Windows 助手工具（双击即用，免记命令）

- `tools\pin_reset.bat` —— 命令行版：自动找 Python（含常见安装路径探测，绕过微软商店
  占位符）、缺 `cryptography` 自动安装，粘贴申请码回车即出重置码；
- `tools\pin_reset_gui.vbs` —— 图形版：双击 → 对话框粘贴申请码 → 弹窗显示重置码
  （Ctrl+C 复制）；也支持命令行参数调用（`cscript //nologo pin_reset_gui.vbs <申请码>`，
  结果走 stdout，便于自动化）；
- 两者都要求私钥文件 `wifilock_reset_private.key` 与脚本同目录
  （内容来自 App 启用应用锁时显示的那串“重置私钥”）。

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

## 许可与免责

- 本项目以 **MIT License** 开源（见仓库根目录 `LICENSE`）；
- **免责声明**：本工具仅限用于自有或已获合法授权的设备管理；使用者需遵守当地法律
  法规，因使用本工具产生的任何后果由使用者自行承担；请勿用于未经授权管控他人设备；
- `tools/wifilock_reset_private.key` 为作者示例私钥（公开仓库中的演示用途），
  使用者请替换为自己的私钥且不要提交真钥，见 `tools/README.md`。
