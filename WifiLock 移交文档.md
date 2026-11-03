# WifiLock 项目移交文档

> 文档目的：完整交接本项目的设计思路、实现细节、修复历史与踩坑记录。
> 本文档只覆盖 WifiLock（WiFi 锁定工具）本项目内容。
> 交付日期：2026-11-01　构建产物版本：APK 890 KB（versionCode 2，含二阶段「Shizuku 直接锁定」，见 §12）

---

## 1. 项目概述

**一句话**：设备管理小工具，通过 Device Owner 用户限制
`DISALLOW_CONFIG_WIFI`（即 `no_config_wifi`）把系统 WiFi 开关禁用，
让设置页 WLAN 无法关闭、无法添加/忘记网络。

**核心约束（架构设计的出发点）**：
- 无 root、面向用户自己的设备合法管理。
- 设置用户限制需要 Device Owner / Profile Owner 权限，获取 owner 权限是本项目的主要复杂度。
- 「关闭 WiFi 时弹验证窗拦截」经调研为不可行（非 root 下无系统拦截点），已排除，勿再走弯路。

**授权模式演进**：最初两种（直连 HomeOfDevice Owner / Dhizuku 代理），后应需求增加第三种（Shizuku 一键授权），共三种并存：

| 模式 | 获权方式 | 锁定时的调用通道 | 适用场景 |
|---|---|---|---|
| DIRECT | App 自身就是 Device Owner（adb 或 Shizuku 设置） | platform DPM 公共 API | 最简单稳定 |
| DHIZUKU | Dhizuku App 是 owner，授权本 App 调用 | 反射 `mService` 热替换 + Dhizuku binder 转发 | 不想让工具类 App 长期占 owner |
| SHIZUKU | App 内经 Shizuku UserService 执行 `dpm set-device-owner`（等效 adb） | 成功后即转入 DIRECT | 手机上免电脑完成授权 |

---

## 2. 交付物清单（云盘位置）

```
Device Owner App\
├── WifiLock-debug.apk      最新调试 APK（1617 KB，包含全部修复：Android 15/16 契约适配 + Shizuku）
├── WifiLock.zip            最新源码包（22.2 KB）
├── WifiLock 移交文档.md     本文档
└── WifiLock\               源码树（与 zip 内容一致）
    ├── build.gradle        根构建脚本（插件 8.7.3 / Kotlin 1.9.22）
    ├── settings.gradle     仓库镜像配置（阿里云镜像置于官方仓库前）
    ├── gradle.properties   JDK 路径、androidX、内存
    ├── local.properties    本机 Android SDK 位置（接手后按实际改）
    └── app\
        ├── build.gradle    依赖与 AIDL 开关（详见 §3、§8）
        └── src\main\
            ├── AndroidManifest.xml      （Dhizuku meta、Shizuku provider/service、queries、Device Admin）
            ├── aidl\android\app\admin\IDevicePolicyManager.aidl   ★ 事务码对齐的精简契约（Android 15/16 修复核心）
            ├── aidl\com\example\wifilock\IShellUserService.aidl   ★ Shizuku UserService 契约
            ├── java\com\example\wifilock\
            │   ├── App.kt             （HiddenApiBypass 豁免注册）
            │   ├── AdminReceiver.kt   （Device Admin 壳，DIRECT 模式 owner 组件）
            │   ├── MainActivity.kt    （程序化 UI，无 XML 布局无 viewBinding）
            │   ├── PolicyController.kt★ （模式检测 + 三个通道的锁定实现，全项目的核心逻辑）
            │   ├── ShizukuClient.kt   （Shizuku 状态检测 + bindUserService 执行 dpm）
            │   └── ShellUserService.kt（运行在 Shizuku 进程里的 UserService）
            ├── res\values\strings.xml
            └── res\xml\device_admin.xml
```

---

## 3. 构建环境与命令

| 组件 | 版本 | 说明 |
|---|---|---|
| AGP (com.android.application) | 8.7.3 | 升级连带 Gradle 升级，见 E3 |
| Kotlin Android plugin | 1.9.22 | |
| Gradle | 8.9 | |
| compileSdk | 36 | 平台目录 `android-36` |
| targetSdk | **34** | 刻意压低以规避新系统行为变化，改动前先想清楚 |
| minSdk | 24 | |
| Java | 17（javac/kotlin target） | 编译器用 JDK 21 运行 |
| JDK 运行 Gradle | 21.0.2 | 由 `gradle.properties` 的 `org.gradle.java.home` 指定 |

**构建命令**（在**纯 ASCII 路径**下执行，见 E1）：

```powershell
cd <纯ASCII路径>\WifiLock
& <gradle-8.9>\bin\gradle.bat assembleDebug --console=plain
```

产物：`app\build\outputs\apk\debug\app-debug.apk`，构建完成后拷回云盘。

**给接手者的环境重建要点**：
- 原始开发用的 JDK（`...\Temp\opencode\build\tools\jdk21`）与 Gradle（`...\build\tools\gradle-8.9`）放在**临时目录**，可能已被清理。请重新下载置于稳定路径并改 `gradle.properties` 的 `org.gradle.java.home`。
  - JDK 21.0.2 下载源参考：华为云镜像（原构建即如此解决访问问题）。
  - Gradle 8.9 下载源参考：腾讯镜像。
- `settings.gradle` 原本用阿里云镜像（google/gradle-plugin/central 前置）解决网络问题；海外环境可改回官方仓库。
- `local.properties` 的 `sdk.dir` 指向本机 Android SDK，接手后按实际修改；需要 platform `android-36` 与 build-tools。
- **PowerShell 特有误报**：gradle.bat 的 stderr 常被 PowerShell 显示为 `NativeCommandError`，`Exited with code 1` 也可能是误报 —— **一律以输出中是否出现 `BUILD SUCCESSFUL` 为准**。
- debug 签名由 AGP 默认生成，无签名配置文件依赖。

---

## 4. 授权模式实现细节

### 4.1 DIRECT（直连）

- owner 设置：`adb shell dpm set-device-owner com.example.wifilock/.AdminReceiver`
- 锁定：platform `DevicePolicyManager.addUserRestriction(admin, DISALLOW_CONFIG_WIFI)` / `clearUserRestriction`，此时调用者就是 owner，系统识别 caller 与 admin 组件归属，直接通过。
- 检测：`dpm.isDeviceOwnerApp(applicationId)`（注意该 API 在 Android 15+ 也带 callerPackage 校验，但参数是本应用包名、由本进程发起，天然通过）。

### 4.2 DHIZUKU（代理 DPM）

**机制**（与 Dhizuku 官方 App 自身方法一致，参考其源码 `ActivateViewModel`，不要用任何"省事 API"）：
1. `Dhizuku.init(context)` → 通过 ContentProvider（authority `com.rosan.dhizuku.server.provider`，method "client"）取得 Dhizuku server 的 binder；
2. 反射拿到 platform `DevicePolicyManager` 内部的 **`mService`**（hidden 字段，Android 9+ 受限，需 HiddenApiBypass 豁免）——这是系统真实 `IDevicePolicyManager` binder 的唯一获取点；
3. `IDevicePolicyManager.Stub.asInterface(Dhizuku.binderWrapper(原binder))` 得到代理 stub，**临时**把该 stub 写回 `mService`，`finally` 恢复原始 binder；
4. 之后所有 platform DPM 公共 API 的调用由 Dhizuku server（owner 身份）转发给系统。

**务必注意**：
- App 端锁定的 admin 参数必须是 `Dhizuku.getOwnerComponent()`（= `com.rosan.dhizuku/.server.DhizukuDAReceiver`，v2.13.0 的组件名），**绝不能**传本应用 `AdminReceiver`；
- 客户端库：MavenCentral `io.github.iamr0s:Dhizuku-API:2.7.0`（API v3 meta 保留无害）；**Dhizuku 服务端（管理器 App）必须 v2.13.0**（v8 协议，SERVER_VERSION_CODE=8；旧服务端无法对接新版客户端 API）；
- `DhizukuRequestPermissionListener.onRequestPermission(int grantResult)` 是 **int**，与 `PackageManager.PERMISSION_GRANTED` 比较；
- JitPack 不提供该库（404）；**唯一渠道是 MavenCentral**。

**Android 15/16 契约适配（本项目的关键修复，详见 §7）**：
Android 15 起 Binder 层改约：`addUserRestriction(admin,key)` 实际发送
`setUserRestriction(admin, callerPackage, key, enable, parent)`，其中 callerPackage 由 platform 客户端自动填为本应用包名；系统端校验"callerPackage 必须属于真实调用方 uid"。Dhizuku 转发后系统看到的调用方是 Dhizuku server，包归属不匹配即抛
`Caller with uid X is not com.example.wifilock`。

修复 = 绕开 platform 客户端封装，直接发 AIDL 调用：
- `app/src/main/aidl/android/app/admin/IDevicePolicyManager.aidl` 是**事务码对齐**的精简契约：`setUserRestriction` 与 `getUserRestrictions` 两个真实方法实体放在与 AOSP main 的 **第 134、第 138 号**事务槽位，其余槽位用无参占位方法 `void fN();` 填充以维持事务码顺序 —— binder 事务码按 AIDL 方法声明顺序递增，**删除/增减任何"没用到"的方法都会导致后续调用错位**，这是精心设计而非臃肿代码；
- 调用时 `callerPackage` 显式传 `"com.rosan.dhizuku"`（Dhizuku 包名）→ 转发后与系统看到的 server uid 归属一致，`getCallerIdentity` 的两项校验（包名归属 + admin 组件归属）同时通过；
- **SDK 分代**：`Build.VERSION.SDK_INT >= 35` 走上述新契约；< 35（含 ColorOS 11.1 = Android 11）走原有 mService 热替换 + platform 公共 API 老路径，老契约下无 callerPackage 校验。
- 读取锁定状态的 `getUserRestrictions(who, callerPackage, parent)` 同样带包名参数，一并走新契约。

### 4.3 SHIZUKU

**用途**：手机上指尖完成 owner 授权，无需电脑 adb（原理是 Shizuku 进程持 shell uid，与 adb 等效）。

**组件与契约**（全部对 `dev.rikka.shizuku:api/provider 13.1.5` 源码逐一核实过）：
- 依赖：`dev.rikka.shizuku:api:13.1.5` + `dev.rikka.shizuku:provider:13.1.5`（provider 提供 `rikka.shizuku.ShizukuProvider`，binder 接收载体）。
- Manifest：provider 声明（authority `${applicationId}.shizuku`、`exported="true"`、`permission="android.permission.INTERACT_ACROSS_USERS_FULL"`、`multiprocess="false"`）——写法照 rikka.shizuku 源码 javadoc，缺一不可；`<queries>` 声明 `moe.shizuku.privileged.api`（新版管理器包名，见库内常量 MANAGER_APPLICATION_ID）与 `moe.shizuku.manager`（旧版）；service 声明 `.ShellUserService`（exported=false）。
- **不要用 `Shizuku.newProcess`**：它在 api 13.1.5 里是 `private`（且官方文档标 deprecated）。
- 采用 **UserService** 路线：`Shizuku.bindUserService(args, conn)` 把 `ShellUserService` 装进 Shizuku 进程执行 `dpm set-device-owner ...`（同 adb），输出原样回显到 UI（`exit=` 行 + stdout/stderr 合并流错生成）。
- `UserServiceArgs` 坑点：`processNameSuffix(...)` **必须设置**（内部 `Objects.requireNonNull`，漏了 NPE）；`daemon(false)` 让调用方进程死亡时服务跟随退出；`version(...)` 在服务代码更新时递增以触发重建；`tag(...)` 防混淆冲突。
- **destroy() 特殊事务码**：Shizuku 框架约定 destroy 的事务码为 16777115，AIDL 按 `void destroy() = 16777114;` 声明（Shizuku javadoc 原文如此）。**且该 AIDL 一旦有一个方法带显式编号，所有方法都必须编号** —— 所以 `exec` 显式标 `= 1`（见 E13）。
- 权限流：`Shizuku.pingBinder()` / `checkSelfPermission()` / `requestPermission(code)` + `OnRequestPermissionResultListener.onRequestPermissionResult(requestCode, grantResult)`（int 语义，同 Dhizuku 教训）；binder 到位监听用 `addBinderReceivedListenerSticky`（已就绪立即回调），死亡监听 `OnBinderDeadListener`。
- **dpm set-device-owner 的系统前置**（与 adb 相同）：设备上不能已有 owner；非 test-only owner 时要求设备无账户（新机/恢复出厂后成功率最高）。失败时系统原文错误会展示在 App 输出窗。

---

## 5. 版本演变时间线

1. **初版设计**：双授权（DIRECT + Dhizuku）整体架构敲定；第一次生成完整工程。
2. **构建打通（ColorOS 11.1 验证机）**：JDK/Gradle 镜像下载、中文路径迁移 ASCII 目录、Dhizuku 依赖坐标修正等（E1~E4），产出 809 KB APK。
3. **Dhizuku API 适配**：删除幻觉 API、改为官方 mService 热替换姿势、监听器签名修正（E9~E11）。
4. **OriginOS 6 适配第一轮**：HiddenApiBypass 升 6.2、AarMetadata 检查禁用、Dhizuku owner 组件名按 v2.13.0 更新（E5、E12），产出 1590 KB APK。
5. **实机报错根因修复**：`Caller with uid 10003 is not com.example.wifilock` → 定位 Android 15/16 DPM 契约变化 → AIDL 精简契约 + callerPackage 方案（E14，全部过程见 §7），重建 1590 KB APK。
6. **Shizuku 适配**：UserService + shell 命令方案（E13~E15），产出 1617 KB APK（当前交付）。

后续版本号管理提醒：交付期间 versionCode 一直是 1，**接手后正式迭代记得递增**。

---

## 6. 错误与教训总结

### 6.1 构建/工具链类

| # | 现象 | 原因 | 解决 | 防再犯 |
|---|---|---|---|---|
| E1 | AGP 构建在中文路径（云盘挂载目录）直接失败 | AGP 拒绝非 ASCII 工程路径 | 工程复制到纯 ASCII 临时目录构建，产物拷回 | 长期方案：把源码树放在英文路径，或 CI 内统一 ASCII 工作区 |
| E2 | `android.disableAarMetadataCheck=true` 无视 | 该 gradle 属性实测无效（AGP 8.7.3） | 在 `app/build.gradle` 里用 `tasks.matching { it.name.endsWith('AarMetadata') }.configureEach { enabled = false }` | 属性没生效先怀疑任务级实现；gradle.properties 里那行已删除（不报错但无效） |
| E3 | HiddenApiBypass 6.2 的 AAR 元数据要求 compileSdk 37，本机失败 | AGP 8.7.3 不认新平台目录命名 `android-37.0`（只认 `android-37`）；且升级 AGP 会连带升级 Gradle，连锁变更风险大 | 不升 AGP，按 E2 禁用检查任务；compileSdk 保持 36（该库运行时不依赖 API 37 符号） | 引入新版本库第一时间读其 AAR 元数据要求 |
| E4 | AIDL 文件编译被静默忽略，Kotlin 报 unresolved IShellUserService | **AGP 8 默认关闭 AIDL build feature** | `buildFeatures { aidl = true }` | AGP 5+ 把 aidl 改为 opt-in，新工程加 AIDL 必显式开启 |
| E5 | `aidl.exe` 报 `syntax error, unexpected unrecognized character`（第 1.1-2 字节） | PowerShell `[System.IO.File]::WriteAllText(..., UTF8)` 默认写入 UTF-8 **BOM**，aidl 不认 BOM | 用 `UTF8Encoding($false)`（无 BOM）重写文件 | 脚本向源码树写文件统一无 BOM |
| E6 | Kotlin 编译找不到 aidl 生成的类（当时 aidl feature 未开） | 同 E4，未生成自然不存在 | 开 aidl feature 后 AGP 自动生成并进编译classpath | —— |
| E7 | AIDL 编译报 `You must either assign id's to all methods or to none of them` | `IShellUserService` 里 destroy 带显式事务码而 exec 未带 | 给 exec 也显式标 `= 1` | An AIDL interface 的显式编号要么全覆盖要么全无 |
| E8 | Kotlin 报 unresolved `textIsSelectable` | TextView 只有 setter（`setTextIsSelectable`）无对应 getter 属性对，Kotlin 属性语法失效 | 改 `setTextIsSelectable(true)` | 属性赋值语法要求 get/set 成对；不行就退回方法调用 |

### 6.2 依赖与 API 类

| # | 现象 | 原因 | 解决 | 教训 |
|---|---|---|---|---|
| E9 | 依赖坐标 `com.rosan.dhizuku:api` 解析失败 | **该坐标是幻觉（不存在）** | 正确：MavenCentral `io.github.iamr0s:Dhizuku-API:2.7.0` | 依赖坐标必须从仓库元数据核对，不凭印象 |
| E10 | `Dhizuku.getDhizukuDevicePolicyManager()` 在任何历史版本都不存在 | 幻觉 API | 正确姿势见 §4.2：反射 mService + `Dhizuku.binderWrapper`（Dhizuku 官方 App 同法，参考其 ActivateViewModel `@requireBinderWrapperDevicePolicyManager`） | API 调用前拉 sources jar / 官方仓库核对，杜绝"看起来合理"的假 API |
| E11 | 授权监听器签名错误 | DhizukuRequestPermissionListener 是 `onRequestPermission(int grantResult)` 不是 boolean | 比较 `PackageManager.PERMISSION_GRANTED` | 同上；两类库此坑同源，Shizuku 同样 int |
| E12 | JitPack 上 Dhizuku-API 404 | 该库不上 JitPack | 只用 MavenCentral | —— |
| E13 | Shizuku 执行 shell 找不到公开 API | api 13.1.5 的 `newProcess` 是 private（且官方建议弃用） | 用 `bindUserService` + 自定义 UserService 契约 | 阅读 API 源码（sources jar）确认每个方法的可见性 |

### 6.3 核心根因类（最有价值）

**E14：`Caller with uid 10003 is not com.example.wifilock`（Android 15/16 契约变化）** —— 全过程见 §7。

---

## 7. 「Caller with uid」根因排查全记录（案例教学）

### 7.1 现象
Dhizuku 模式点"上锁"，App 提示：`操作失败：Caller with uid 10003 is not com.example.wifilock`。
（10003 为 Dhizuku server 在该机上的 uid；报错文本完整保留原样，是第一手证据。）

### 7.2 排查走线（可以照抄方法）
1. **Dhizuku 主仓库（已克隆）grep 该文案** → 无。排除 server 端自定义校验。
2. **猜测依赖库 Dhizuku-SERVER_API 有独立 repo** → GitHub 404（repo 名猜错）。**教训：先列作者全部仓库再猜**（`api.github.com/users/{author}/repos`）。
3. 发现 `SERVER_API` 是 **Dhizuku-API 仓库的 `dhizuku-server_api` 子模块**；同时从 **MavenCentral 拉 0.2.0 的 sources jar**（注意 jar 不是 zip，需改名后再解压）逐字搜索 → 仍无该文案，且确认服务端只是裸转发 binder（`onRemoteTransact → binder.transact`）。
4. **关键翻转：该文案是标准 AOSP 格式**。下载 AOSP main 的 `DevicePolicyManagerService.java`（raw.githubusercontent.com），grep `Caller with uid` → **精确命中两处**（约 L2431 / L7257）：
   ```java
   if (!isCallingFromPackage(callerPackage, callerUid)) {
       throw new SecurityException(String.format("Caller with uid %d is not %s", callerUid, callerPackage));
   }
   ```
5. 读上下文找准触发面：这条校验只在**带 callerPackage 参数**的 DPM API 里发生。随后下载 platform 客户端 `DevicePolicyManager.java`（AOSP main），发现 Android 15/16 已经把 `addUserRestriction` 改约：
   ```java
   mService.setUserRestriction(admin, mContext.getPackageName(), key, true, mParentInstance);
   ```
   **callerPackage 由平台客户端自动填入本应用包名** —— Dhizuku 转发后，系统看到的调用方 uid 是 Dhizuku server（10003），却拿着 `com.example.wifilock` 这个包名参数，`isCallingFromPackage` 必然失败。

### 7.3 被推翻过的假设（记录防止重复走弯路）
- 初步假设"Dhizuku 授权记录（按 uid 存 entity）与新装 uid 失配" —— 可以解释报错字面，但**与本事件的量化特征不吻合**：该假设的字面解释里 10003 应为 WifiLock 自己的 uid，证据链上 10003 是 Dhizuku 的 uid；且 Dhizuku 的 AppRepo/entity 模型（读源码确认：`findByUID` + signature 校验）根本不产生 `String.format("Caller with uid %d is not %s", ...)` 格式的文案。
- 另一度怀疑"framework 校验 provider 调用者的 admin 归属"，read 源码确认 admin 归属报错有独立文案（`Admin %s does not exist or is not owned by uid %d`），与本次不同，排除。
- **方法论结论**：错误文案是强指纹，先在权威源码（AOSP / 库 sources jar）里 grep 原文，而不是靠模型自洽性推测 —— 本次最快命中的动作就是第 4 步的 AOSP 原文 grep。

### 7.4 修复设计（为什么这样修）
- 目标：让系统端 `getCallerIdentity(admin, callerPackage)` 的两项校验同时通过：
  1. **包名归属**：callerPackage 必须等于系统看到的调用方包 → 系统看到的调用方就是 Dhizuku server → callerPackage 传 `com.rosan.dhizuku`；
  2. **admin 归属**：adminComponent 必须是 active admin 且属于调用方 → 本来就传 `Dhizuku.getOwnerComponent()`（server 自己的 admin），天然通过。
- 实现难点：platform 客户端封装会"写死" callerPackage=本应用包，无法注入 → **直接发 AIDL 调用**，但完整 AOSP main 的 IDPM.aidl imports 32 个隐藏 parcelable（PasswordMetrics/ParcelableResource 等），全量移植代价大。
- **精简方案（事务码对齐）**：binder 事务码 = FIRST_CALL_TRANSACTION + 方法声明序号；只保留真实调用的两个方法实体，其余槽位用**无参占位方法**顶住序号 —— 保留声明序列即可保证事务码与平台 Stub 完全一致，生成的 marshal 代码也只会为我们调用的方法生成（占位方法永不调用）。
- **事务码定位（必须严格）**：第一次扫描报 401 个方法、setUserRestriction=128，**被证明是错的**（多行声明与注释行混入）。写严格解析器（跳过 `//` 与 `*` 开头注释行、处理跨行声明、从 `interface` 开行后计数）重扫得 412 个，**setUserRestriction=#134、getUserRestrictions=#138**。教训：**对事务码这类"差一就全错"的数字，必须用能处理目标格式细节的解析器；粗扫数字只能当参考**；。
- **兼容性切分**：`SDK_INT >= 35` 走新契约；`< 35` 走原 mService 热替换 + platform API 老路径。正向依据：Android 11 客户端封装还是老契约 `addUserRestriction(admin,key)`（ColorOS 11.1 已实测可用）。vivo 对 Binder 接口顺序的修改风险极低（改契约即破坏系统自身兼容性），据此默认事务码对齐成立。
- **未来防御**：若实机再报同款错误，优先怀疑 vivo 对 IDPM.aidl 方法序做过微调 —— 验证法：用 HiddenApiBypass 反射读真机 `android.app.admin.IDevicePolicyManager$Stub` 的 `TRANSACTION_setUserRestriction` 静态字段值与 134 对比（运行时真值说话）。

### 7.5 相关 API 语义备忘（AOSP main 核实）
| 系统端方法 | 签名 / 要点 |
|---|---|
| setUserRestriction | `(ComponentName who, String callerPackage, String key, boolean enabledFromThisOwner, boolean parent)`；第一行即 `getCallerIdentity(who, callerPackage)` 触发 §7.1 校验 |
| getUserRestrictions | `(ComponentName who, String callerPackage, boolean parent)`；同样带包名参数 |
| clearUserRestriction | **不存在了**；"清除"就是同方法 callerPackage+key+`enable=false` |
| setDeviceOwner | `(ComponentName admin, int userId, boolean setProfileOwnerOnCurrentUserIfNecessary)`；要求 caller 为 adb/root/system 身份（Shizuku UserService 的 shell 命令路径不受此影响，因为走的是 `dpm` 命令行，由 fw 命令实现自行适配各版本）|
| setActiveAdmin | main 为 4 参（多 `provisioningContext`）——**各版本签名不同**，这也是 Shizuku 一键走 shell `dpm` 命令而非直调 binder 的原因之一 |

---

## 8. 外部依赖与版本锁定

```gradle
implementation 'io.github.iamr0s:Dhizuku-API:2.7.0'          // MavenCentral 唯一渠道；JitPack 404
implementation 'org.lsposed.hiddenapibypass:hiddenapibypass:6.2'  // Android 16 文档认可版本；AAR 需要 compileSdk 37 → 见 E2/E3
implementation 'dev.rikka.shizuku:api:13.1.5'                 // MavenCentral
implementation 'dev.rikka.shizuku:provider:13.1.5'           // 提供 ShizukuProvider（binder 接收载体）
```

配套要求：
- Dhizuku 服务端 App 必须 **v2.13.0**（发布信息：2026-10-30，v8 协议 / SERVER_VERSION_CODE=8）。旧服务端无法对接 2.7.0 客户端（连接期特性差异）。
- Dhizuku owner 组件：`com.rosan.dhizuku/.server.DhizukuDAReceiver`（v2.13.0；老版本组件名不同，若对方产品 line 仍用旧版客户端/服务端组合需按 README 提示以 Dhizuku App 授权页文案为准）。
- 升级任何依赖前：拉 MavenCentral metadata 确认坐标存在 → 拉 sources jar 核 API 面 —— 不要凭记忆升级。

---

## 9. 已知限制与未验证事项

**既有事实（设计层面已接受）**：
- 非 root 下**无拦截点**实现"关闭时弹验证窗"，已排除，不要重提。
- `no_config_wifi` 的 UI 生效面各家族不同：设置页 WLAN 开关一定会变灰；**控制中心/下拉磁贴**在 ColorOS 快捷中心、OriginOS 6 磁贴上有可能不走系统限制通道 —— 需实机验证；兜底方案 `adb shell svc wifi enable`（往回开）。
- App 无法自毁 owner 时无法被卸载（owner 保护）；如需撤销，先在系统里停用设备管理（设置 → 安全 → 设备管理器 / 或 Dhizuku 里解绑），为本 app 的运维常识，README 已述。

**待实机确认（接手者第一优先）**：
1. OriginOS 6 上 Dhizuku 模式 AIDL 修复后的锁定是否通过（源码已含修复，等真机回归）；
2. OriginOS 6 控制中心磁贴是否遵守 no_config_wifi；
3. Shizuku 一键 owner 在 OriginOS 6 全流程（无线调试配对 → 授权 → 一键 → 上锁）。

**遗留小装备**：
- `PolicyController` 里 `CALLER_PACKAGE = "com.rosan.dhizuku"` 为 Dhizuku 包名硬编码 —— 改 app applicationId 时**不需要**动它（它与 app 包名无关），但 MainActivity 里的两条 adb 命令文案与 README 中的命令必须同步改包名；
- `versionCode` 恒为 1，见 §5 提醒；
- 开发期 Temp 里的工程副本（含构建级 gradle.properties 差算）与云盘源码树可能不完全一致，**以云盘源码树 + 本文档为准**。

---

## 10. 常见报错对照表（运维速查）

| 报错/现象 | 判定 | 处理 |
|---|---|---|
| `Caller with uid X is not <pkg>`（AOSP 格式） | DPM 新契约 callerPackage 归属校验 | 用最新 APK；若自改代码请复核 IDevicePolicyManager.aidl 事务码与 callerPackage 参数（§7） |
| `当前系统框架缺少标准 mService 字段` | 定制框架对 DPM 的 mService 字段做了重排 | 该系统上 Dhizuku 代理不可用 → 改走 DIRECT（adb / Shizuku） |
| `Not active admin` / `Not allowed to set the device owner ...`（dpm 输出） | set-device-owner 前置不满足（已存在 owner / 有账户） | 恢复出厂或清除 owner；test-only 与 test 服务机制留待研究 |
| Dhizuku 初始化失败 / 无法连接 | 服务端版本不对 / 未成为 owner / 权限未授予 | 服务端升 v2.13.0；先 `dpm set-device-owner com.rosan.dhizuku/.server.DhizukuDAReceiver` 激活；再在 App 内申请授权 |
| Shizuku ping 为 false | 管理器未启动 / queries 未声明 / provider 声明缺失 | 手机上启动 Shizuku（无线调试/Root）；核对 manifest 里 queries + provider（§4.3） |
| Shizuku 输出 exit=-1 | UserService 里 Process 启动异常 | 看 App 输出窗第二行异常原文（`NullPointerException` 等） |
| 上锁成功但磁贴仍能关 WiFi | 地区/厂商 UI 不走限制通道 | 用 `adb shell svc wifi enable` 恢复开关，然后按 §9 实测记录 |

**验证.owner 关系的最快命令**：`adb shell dumpsys device_policy | findstr -i owner`（或 `dpm list-owners`）。

---

## 11. 移交注意事项

1. **applicationId 为 `com.example.wifilock`（占位包名）**：接手团队若要改成正式包名，需同步改：MainActivity 里两条 adb 命令文案、README、`AdminReceiver` 引用由 namespace 生成（framework 层无硬编码）、`IShellUserService`（包路径即 aidl 包）以及可预见的引用；Dhizuku/Shizuku 授权流本身不受影响（只是授权记录里的目标包变了，需重新授权）。
2. **两个 AIDL 文件是本项目的"精密件"**：`IDevicePolicyManager.aidl` 事务码按 AOSP main（Android 16）的方法顺序生成、占位方法填充 —— **不要整理格式、不要移除"用不到"的方法**，如需修改请先读 §7.4；`IShellUserService.aidl` 的显式编号（`= 1` / `= 16777114`）是 Shizuku 框架约定，同样不要动。
3. **构建环境要提前重建**（§3）：Temp 目录中的 JDK/Gradle/工程副本随时可能消失；建议接手第一件事就是照 §3 在稳定路径搭好环境并跑一次 `assembleDebug` 出对同尺寸 APK。
4. **targetSdk 34 是刻意选择**：如需上 35+，先在目标设备上回归 Dhizuku/Shizuku 两条链路的新系统行为差异。
5. **免责与合规**：工具用途为设备拥者对自有/已授权设备的画面管理（禁改 WiFi），README 内已声明；接手后对外发布时保留免责声明。

---

## 12. 二阶段迭代记录（2026-11-01，接手后第一次迭代）

### 12.1 需求

上一开发者的 SHIZUKU 模式 = 「用 Shizuku 执行 `dpm set-device-owner`（等效 adb）」，
在**已使用一段时间**的设备上必然失败（需格式化重过 OOBE 才可能成功）。
需求方要求：**只靠 Shizuku 授权即可锁定，不做任何管理员/owner 激活**。

### 12.2 先回答“能不能”：无 owner 的持久化 no_config_wifi 在 shell 身份下不可行（源码证据）

接手后先从 AOSP 源码核实了所有可能的 shell 通道，结论如下（保留证据防走回头路）：

| 通道 | 证据（AOSP 源码） | 结论 |
|---|---|---|
| `IUserManager.setUserRestriction(key,value,userId)` 直接写基础限制 | `core/java/android/os/IUserManager.aidl` L99 存在该方法；`UserManagerService.java` L3642-3643 首行即 `checkManageUsersPermission` | 实现（L4069-4072）：仅放行 SYSTEM_UID / root / 持 `MANAGE_USERS` 者，**无 shell 特例** |
| shell uid 是否持 `MANAGE_USERS` | `packages/Shell/AndroidManifest.xml`（android-11.0.0_r1 → master 全部核实）：shell 持 `CREATE_USERS/QUERY_USERS/MANAGE_DEVICE_ADMINS/MANAGE_PROFILE_AND_DEVICE_OWNERS` 等，**无 MANAGE_USERS** | shell 身份（Shizuku）调该 API 必抛 SecurityException |
| `cmd user` 命令面 | `UserManagerServiceShellCommand.java`：仅 list / 系统用户模式仿真 / 可见性查询 | 无写限制子命令 |
| DPM 公共 API | 需要 admin 组件且归调用方所有（无 owner 即无 admin） | 不适用 |
| 「AppOps 屏蔽开关来源」 | `WifiServiceImpl`（AOSP 16）`enforceChangePermission`：**持 `NETWORK_SETTINGS` 权限者提前 return MODE_ALLOWED，跳过 AppOps 检查**；Settings/SystemUI（uid 1000）正是持权者 | 对 Settings/SystemUI 无效，仅能挡 shell 自身，不足以做锁 |
| DPMS 内部 setup 标志“改回 0”把戏 | `updateUserSetupCompleteAndPaired`：观察器只会 false→true（单调、持久化于 device_policy 状态）；`hasUserSetupCompleted()` 读的是内部状态而非实时 settings | 改 `user_setup_complete/device_provisioned` 为 0 **无效**，勿再尝试 |

### 12.3 一键激活失败的真正门控（同样已核实，指导诊断功能）

`DevicePolicyManagerService.checkDeviceOwnerProvisioningPreConditionLocked`（master 与 android-11.0.0_r45 结构一致）：
- 调用方为 adb/shell（`isAdb(caller) = isShellUid || isRootUid`，master L18784）时，
  **setup 已完成本身不是障碍**；
- 真障碍只有两个：
  1. `STATUS_ACCOUNTS_NOT_EMPTY`——设备**存在账户**（本应用 admin 非 testOnly ⇒ 任何账户都“不兼容”）；
  2. `STATUS_NONSYSTEM_USER_EXISTS`——存在非预创建的额外用户（罕见）；
- 已有 Device Owner / Profile Owner ⇒ `STATUS_HAS_DEVICE_OWNER` / `STATUS_USER_HAS_PROFILE_OWNER`。
  
⇒ “已用过的设备”一键失败的根因 = **账户**。解决路径 = 临时删除账户 → 一键 → 激活成功后再加回
（无需格式化）。testOnly 包标记可绕过账户校验（`isAdminTestOnlyLocked` 分支），
但有发布/卸载语义副作用，**本轮未采用**，留作后手。

### 12.4 本轮落地（versionCode 2 / versionName 1.1）

新增「Shizuku 直接锁定」（免管理员，覆盖需求）+「一键自动诊断」（把 12.3 的门控做成引导）：

| 文件 | 变更 |
|---|---|
| `IShellUserService.aidl` | 追加三个显式编号方法：`watchLoop(long)=2`、`watchStop()=3`、`watchStatus()=4`（`exec=1`/`destroy=16777114` 原样保留；编号规则见 §11.2） |
| `ShellUserService.kt` | 实现看门狗线程：每 tick 依序尝试 `cmd wifi set-wifi-enabled enabled` → `svc wifi enable`（任一 exit=0 即成功）；`watchStop` 置停并尽力 `pkill -f <pkg>:watchdog` 清理孤儿实例；`watchStatus` 供 App 回读真实状态 |
| `ShizukuClient.kt` | 双绑定身份：exec 通道 `daemon(false)`（后缀 shizuku，短命）；watchdog 通道 **`daemon(true)`**（后缀 watchdog）——官方 javadoc：daemon 服务在调用方进程死亡后继续运行；Shizuku 重启会杀掉全部 user service，需 App 重新拉起；新增 `execCmd/watchStart/watchStop/watchStatus`；`USER_SERVICE_VERSION` 升到 2 |
| `MainActivity.kt` | 新增「Shizuku 直接锁定」开关（依赖 Shizuku 在线；关 App 仍生效；Shizuku 重上线自动续接 `tryWatchHeal`）；「一键诊断」按钮（三探针：`dumpsys device_policy` owner 检索 / `dumpsys account` 账户计数 / `pm list users`）+ 结论引导；一键激活改为「先诊断，无阻断才执行」，账户阻断时给出“临时删除→重试→激活后加回”指引并附系统账户设置深链；原两条一键按钮保留 |
| `app/build.gradle` | versionCode 1→2、versionName 1.0→1.1 |

**看门狗的行为语义（重要）**：
- 锁定 = Shizuku 持续把 WiFi 开回，**不改系统持久配置**；Shizuku 停止 ⇒ 保护停止；
- 关闭 WiFi 后最长约 3 秒内被恢复（期间短暂断网，正常）；
- 好处：**厂商磁贴/快捷中心这类可能不走 no_config_wifi 通道的关闭途径也被覆盖**（拦截的是“结果”而非 UI）；
- daemon(true) 服务与 Shizuku server 同生共死；App 侧重开 App / Shizuku 上线会自动重挂（幂等）。

**Dhizuku/直连（owner）通道本轮零改动**；二阶段未触碰 `IDevicePolicyManager.aidl`。

### 12.5 待实机验证（接手者清单）

1. **OriginOS 6**：装 Shizuku（无线调试）→ 授权 → 打开「直接锁定」→ 手动关 WiFi，
   观察约 3 秒内自动恢复；控制中心磁贴同样验证；退出 App 后再关 WiFi，确认仍生效；
   关 Shizuku → 确认保护停止 → 重开 Shizuku → 确认自动续接。
2. **一键诊断**：在有 Google 账户的旧设备上跑诊断，确认给出“账户阻断”结论；
   临时删账户 → 一键 → 成功 → 加回账户 → 路线 1 灰化开关验证（这同时回归验证了 §9.2/9.3 的遗留项）。
3. **ColorOS 11.1**：同 1（顺带验证 `cmd wifi set-wifi-enabled` 在该机是否存在，应能退回 `svc`）。
4. 看门狗耗电：观察整机耗电排行（每 3 秒一次子进程，理论可忽略，实测说话）。

### 12.6 本轮排除但值得记录的后手

- **testOnly 包**：manifest `android:testOnly="true"` 可使账户在位时仍能 set-device-owner
  （`hasIncompatibleAccountsOrNonAdbNoLock` 对 testOnly owner 走账户类型兼容判断）；
  代价：发布渠道受限、部分设备上存在 owner 清理时序问题——仅当“账户引导” UX 不可接受时再评估。
- **shell 移除账户自动化**：`AccountManager` 的 removeAccount 需要认证器交互（Google 账户
  有确认流），shell 直调不可控——维持“引导用户手动删”。
- **AppOps 屏蔽开关来源**：见 12.2——被 `NETWORK_SETTINGS` 提前放行绕过，不作为锁通道。

### 12.7 UI 二级菜单化（同日追加，versionCode 2 持续）

单页布局在功能变多后按钮被挤出屏幕。已改为**单 Activity 三页面**（程序化 UI，
无新依赖、无新 Activity 声明）：

- **主页**：模式/状态 + 两个锁定开关（路线 1 管理员 / 路线 2 Shizuku 直接锁定）
  + 入口按钮（⚙️ 设置与授权 · 📖 使用说明）；
- **设置与授权页**：Shizuku 状态/授权、两条一键激活、一键诊断、系统账户设置深链、
  Dhizuku 授权、执行输出窗；
- **使用说明页**：三方案说明 + 命令复制；
- 全页面套 `ScrollView`；返回键（`onBackPressed`）与页内「← 返回」均回主页。

实现要点（后续改 UI 必读）：
- 控件引用**按页面存在**（nullable 字段），`navigate()` 时统一置空重建；
  `refresh()` 对空引用安全（全部 `?.`/`let`），可被 Shizuku binder 回调在任意页面触发；
- 输出窗内容存在 `lastOutput` 成员里，页面重建（导航/旋转）后自动恢复显示；
- `watchOn/watchOff` 不做页面跳转，主页开关操作仅靠 toast + `watchInfoText` 反馈；
  看门狗启停的命令回显在「设置与授权页」输出窗可随时查看。

## 13. 三阶段迭代（2026-11-01）：限制管理页 + 邮箱远程控制调研

### 13.1 限制管理页（versionCode 3 / versionName 1.2）

主页新增「🧰 限制管理」二级页：五组 22 项企业 MDM 常用用户限制，全部走
`PolicyController.setRestriction(key, enable)`（三通道分发，与 WiFi 同路）。

| 文件 | 变更 |
|---|---|
| `RestrictionCatalog.kt`（新） | 限制清单（key/标题/说明/分组）；条目合法性逐条对照 AOSP `UserRestrictionsUtils`（Device Owner 除 IMMUTABLE_BY_OWNERS 三条外全部可设；未收录那三条，也未收录仅托管资料生效的 OUTGOING_CALLS/SMS） |
| `PolicyController.kt` | 通用化：`setRestriction/isRestrictionOn/getRestrictions`（整包快照，一次 binder 往返刷整页）；`setLock/isLocked` 变为 DISALLOW_CONFIG_WIFI 的薄包装；新增 `setScreenCaptureDisabled/isScreenCaptureDisabled`（DPM API，非用户限制） |
| `MainActivity.kt` | 新页面 PAGE_RESTRICTIONS（分组开关 + 每项说明 + 实时状态读取）；主页加入口按钮 |

**本轮实机核对出的 API 事实（后续开发注意）**：
- 读取截屏禁用态的接口名是 `getScreenCaptureDisabled`（API 36 无 `isScreenCaptureDisabled`；
  用本机 SDK javap 核对）；
- `UserManager.DISALLOW_CAMERA` 是隐藏常量（SDK jar 不导出），AOSP 15 原文 `"no_camera"`；
- **`DISALLOW_MICROPHONE` 已从 Android 15 移除**（连同 `setMicrophoneDisabled` 公开 API），
  麦克风管控只剩 `DISALLOW_MICROPHONE_TOGGLE`（隐私开关锁定，DO 专属）；
- Dhizuku+Android15+ 的契约适配范围仍只有 setUserRestriction/getUserRestrictions 两个事务码；
  其他 DPM API（如 setScreenCaptureDisabled）在该组合下未适配，代码会主动抛提示（不是默默失败）。
  若将来要支持，需按 §7.4 方法把对应方法纳入事务码对齐的 AIDL 契约。

### 13.2 邮箱指令远程控制（调研结论，未实现）

**结论：技术可行，适合作为无 GMS 环境的“穷人版推送”通道；但受三个硬约束**：
1. **网络前提**：设备在线才能收信——断网/飞行模式下“远程开 WiFi”类指令天然收不到
   （指令通道依赖网络可达性，无法自举）；
2. **后台存活**：Android 后台限制下，稳定轮询必须前台服务（常驻通知）；IMAP IDLE
   长连接易被 Doze/厂商省电切断，60~120s 轮询 + 前台服务是最稳形态（WorkManager
   最小 15 分钟周期，只适合低频策略同步）；
3. **安全面**：邮箱即 C2，凭据与指令合法性是全部安全所在——必须：专用指令邮箱、
   只认指定发件人 + 共享密钥 HMAC（或 PGP/S/MIME 签名）、指令带时间戳/单调序号防重放、
   高危指令（wipeData/resetPassword）两步确认（先收命令邮件，再收确认邮件才执行）、
   执行后回执邮件留痕。

**建议形态（若实装）**：`MailCommandChannel`（前台服务）→ IMAPS(993) 轮询专用邮箱 →
解析主题 `WF-<cmd>` + 正文 JSON+HMAC → 白名单指令（lock/unlock/restriction/lockNow/
status/wipe[两步]）→ 调 `PolicyController` 现有通道执行 → 回执邮件。依赖
`com.sun.mail:android-mail`（JavaMail Android 移植，约 2MB）。
**并列替代**：自建 HTTP/MQTT 或 ntfy.sh 轮询更实时更轻（邮箱优势仅是“零服务器”）；
国内无 GMS、FCM 不可用是选邮箱/自建通道的根本原因。

## 14. 四阶段迭代（2026-11-01）：应用锁（PIN）+ 离线重置

### 14.1 功能（versionCode 4 / versionName 1.3）

- **PIN 门禁**：主页「🔐 应用锁」启用；进入应用先过数字键盘门禁页（返回键=退到桌面，
  无法绕过）；**离开应用（onStop，含划后台/进程被杀）后 10 分钟内重进免重验**——
  `lastPause` 持久化在 prefs，进程被杀冷启动也能正确判断；连续错 5 次指数锁定（30s 起，封顶 30min）。
- **PIN 存储**：16B 随机盐 + 5 万次迭代 SHA-256（自实现 KDF，API 24 全兼容，不存明文，
  比对用 `MessageDigest.isEqual` 恒时比较）。
- **离线重置（挑战-应答）**：
  - 启用 PIN 时生成 EC P-256 密钥对；**私钥（PKCS8 Base64）只显示一次**要求保存到电脑
    （`tools/pin_reset.py` 同目录 `wifilock_reset_private.key`）；本机只存公钥 →
    拿到手机者无法离线伪造重置码；
  - 忘记 PIN：App 出申请码 `WFL1-<keyid8hex>-<challenge32hex>`（一次性、10 分钟有效、
    生成即作废旧挑战）→ PC 端 `pin_reset.py reset <申请码>` 用私钥签名（ECDSA-SHA256，
    DER，输出 `WFR1-<base64>`）→ App 验签（**对挑战原始 16 字节**，注意不是 hex 的 ASCII——
    首版曾写错，端到端测试抓出后修正）→ 通过即删除 PIN（含公钥，回到未启用；下次启用重新生成）；
  - 修改 PIN 复用同一密钥对（已保存私钥继续有效）；手动关闭也清公钥。
- **交付脚本** `tools/pin_reset.py`（依赖 `pip install cryptography`）已做端到端协议测试：
  正例验签通过 / 篡改挑战被拒 / 非法申请码被拒（测试脚本模式见构建对话，ALL PASS）。

### 14.2 安全边界（如实记录）

- 本功能的威胁模型是**防随手窥屏/防他人解锁**，不是防取证：PIN 哈希与公钥存于
  应用私有 prefs（未 root 不可读）；root/取证者可离线攻击 4–8 位数字空间（有指数锁定
  在线侧防护，但离线无意义）——需要更强请走“设备级强密码 + 文件级加密”，本工具不替代。
- 私钥文件是唯一重置路径：**PIN 与私钥双双丢失 = 只能恢复出厂**（owner 下无法卸载重装）。
- 时钟回拨可绕过 10 分钟窗口（离线设备限制，接受；`DISALLOW_CONFIG_DATE_TIME` 限制可缓解）。

### 14.3 纯离线 2FA / 通行密钥讨论（结论，未实装）

- **离线 2FA 可行**：① PIN + TOTP（启用时展示 seed/二维码，用户录入任意离线验证器，
  解锁需 PIN+6 位动态码；注意离线设备的时钟漂移风险，可用 ±2 窗口+HOTP 兜底）；
  ② PIN + 生物识别（Keystore `setUserAuthenticationRequired(true)` + BiometricPrompt，
  硬件绑定、完全离线、体验最好）——两者都是真·双因子；已有的 EC 重置流程天然充当
  “找回因子”。
- **“通行密钥”要分两种说**：真·WebAuthn/FIDO2 passkey 的验证方（RP）必须在线发挑战/验签，
  **纯离线做不到生态互通的 FIDO2**；但通行密钥的内核（挑战-签名-公钥验签 + 硬件绑定密钥）
  可以**离线复刻**：App 自任 RP，Keystore/StrongBox 生成 ES256 密钥（可绑生物识别），
  解锁 = 对本地挑战签名验签——形态即“本地通行密钥”（部分密码管理器的本地解锁即此法），
  与本项目已有的 EC 重置协议同构，实装成本低。
- 建议：若要做，直接做「本地通行密钥」解锁（Keystore ES256 + 生物识别 + PIN 兜底），
  不必引入 FIDO2 依赖。

## 15. Windows 侧助手脚本（同日追加：bat + vbs）

交付 `tools/pin_reset.bat`（命令行版）与 `tools/pin_reset_gui.vbs`（图形版），
包装 pin_reset.py：自动找 Python、缺依赖自动装/提示、粘贴申请码出重置码。

**编码与解析坑（实测，勿重蹈）**：
- 批处理必须是 **ANSI(GBK) + CRLF**：UTF-8 无 BOM 的中文 bat 在中文系统 cmd 里
  会被按 GBK 拆行，出现"把注释碎片当命令执行"的诡异报错；`chcp 65001` 前置的
  民间偏方在本机实测不稳，已弃用（直接 GBK 落盘）。
- VBS 含中文必须 **UTF-16 LE with BOM**（FF FE）：cscript 只认 ANSI 或 UTF-16，
  UTF-8 会乱码。构建脚本写完 UTF-8 后统一转 Unicode。
- **微软商店 python/python3 占位符排在 PATH 最前**：直接 `where python` 找到的是
  假 python（运行报 "Python was not found"、exit 9009）。两个脚本都用
  "试运行 `-c \"print(1)\"` 检查退出码"的方式筛选，并在三个 PATH 候选全灭后
  退回显式路径探测（`%LocalAppData%\Programs\Python\Python*\python.exe`、
  `%LocalAppData%\Python\bin\python3.exe`、`C:\Python*`、`C:\Program Files\Python3`）。
- VBScript 的 **`Sub` 是保留字**，不能当变量名（`For Each sub In ...` 直接语法错误）。
- 测试方式：`set /p` 可从管道读入 → 用管道驱动 bat；vbs 设计了无头模式
  （带参数时结果走 `WScript.Echo`，仅双击无参时才弹窗）。两个脚本产出的重置码
  均通过独立 Python 端验签（端到端 ALL PASS）。

## 16. 五阶段迭代（2026-11-01）：应用锁改"每次离开即锁"

### 16.1 变更（versionCode 5 / versionName 1.4）

用户实测后判定 v1.3 的"10 分钟免重验窗口"不符合预期（切后台马上回来不要求验证），
改为**严格模式**：

- **新语义**：任何离开（切后台 / 划掉后台 / 跳转其他 App / 进程被杀）后重新进入，
  一律要求 PIN；旋转屏幕等配置变化不算离开（`onStop` 里以 `isChangingConfigurations`
  豁免）。
- **实现**：解锁标志改为 `PinManager` 的**内存变量**（`unlocked`）——验证成功
  `markUnlocked()`、`onStop`（非配置变化）`markLeft()`、进程被杀标志自然消失
  （重启即未解锁）。**删除了 v1.3 的 lastPause 持久化**（旧 prefs 字段残留无害，
  `clearPin` 全清）。
- **加固**：门禁页设置 `FLAG_SECURE`（禁止截屏与最近任务预览，防 PIN 位被偷看），
  离开门禁页即清除。
- **行为细节**：Shizuku 授权弹窗等"对话框形 Activity"只触发 onPause（本 Activity
  仍部分可见，无 onStop）→ 不触发重验；跳系统设置等全屏页面会触发（符合"每次离开"）。

### 16.2 已知取舍

- 分屏/多窗口下本 Activity 仍可见时切走焦点不触发 onStop → 不重验（可见即无泄露面，接受）；
- 严格模式下频繁跳系统页（如账户设置）每次回来都要输 PIN——属预期行为；
- 如日后需要"可配置时长"，把内存标志换成 (离开时刻 + N 分钟) 判断即可（v1.3 的
  lastPause 方案可复用，注意时钟回拨）。

## 17. GitHub 发布与同步约定（2026-11-01 起）

- **仓库**：https://github.com/shiyidzyk/Device-Owner-App （public，MIT License）；
  默认分支 `main`（已由 master 切换；旧 master 指针已删除，两分支当时内容一致）。
- **每次迭代的固定动作**：本地 commit → `git push origin main` → 有产物变化时打
  tag `v<主.次>` 并建 Release（附件：WifiLock-debug.apk + WifiLock.zip）。
- **v1.4（versionCode 5）= Release v1.4**，含 APK 与源码 zip。
- **重置私钥政策（2026-11-01 最终版）**：演示示例私钥**保留在仓库中**（需求方确认：
  有意随仓库发布，用于演示重置流程）；同时用 `git update-index --skip-worktree` 锁定
  ——后续本 key 的本地内容变更**不再被同步提交**（仓库恒定保留当前演示版本）。
  .gitignore 中 `*.key` 全忽略 + 对该文件的显式豁免（两者并存）。
  文件本体也已手工同步到备份路径 `D:\移动云盘挂载\18725885126\Device Owner App\WifiLock\tools\`。
  使用者应替换为自己设备的私钥且不要提交真钥（见 tools/README.md）。
  注意：历史提交 6663f7a→59f2645 之间该文件曾被短暂移除又恢复，当前 HEAD 与后续均保留。
- **免责声明落位**：根 README、WifiLock/README.md、使用说明.md、App 主页文案四处均有。
- **开发说明（2026-11-01 追加）**：本项目（2026-11 迭代起）全程由智谱
  （z.ai / zchat.z.ai）的 **GLM-5.3-Flash** 模型完成开发（已在根 README 与
  WifiLock/README 声明）。
- 凭证：Windows Git Credential Manager 缓存的 GitHub token（`git credential fill`
  可取，用于 Release 的 REST API 调用）；gh CLI 未安装。

## 18. 六阶段迭代（2026-11-01）：重置私钥改设备绑定（确定性派生）

### 18.1 需求与结论

需求：避免"每次关闭密码重新设置后私钥变更"（用户希望私钥文件长期有效）。
用户提议用 SN/IMEI 派生——**核实后不可行**：
- `Build.getSerial()`：API 29+ 需要 `READ_PRIVILEGED_PHONE_STATE`（系统权限），普通应用
  拿到 "unknown"；
- `TelephonyManager.getImei()`：API 29+ 起对第三方应用一律拒绝（同样需要系统级权限）；
- 目标设备（ColorOS 11.1 = Android 11 / OriginOS 6 = Android 16）全部落在限制范围内。

**落地替代**：`Settings.Secure.ANDROID_ID`（SSAID）——应用签名级隔离的设备标识：
- 对同一应用（同签名、同用户）跨重启/重装/清数据**恒定**；
- 仅恢复出厂变更——而恢复出厂本身清掉应用全部数据（PIN 哈希也不存在了），
  届时重新启用生成新钥，语义自洽；
- **adb 拿不到本应用的 SSAID**：`settings get secure android_id` 返回的是调用方自己
  （shell）的 SSAID——SSAID 按调用包+签名隔离存储（settings_ssaid.xml，root 才可读），
  故派生输入对外部攻击者（非 root）不可知。

### 18.2 实现（versionCode 6 / versionName 1.5）

- 派生：`d = HMAC-SHA256(公开常量盐, ANDROID_ID || "WifiLock-reset-key-v1" || counter)`，
  while 保证 `1 ≤ d < n`；每进程缓存（点乘+自检约几十毫秒）。
- 公钥点 Q = d·G：**纯 Kotlin P-256 仿射点乘**（BigInteger modP，双倍-加法）；
  运行时内置 **NIST 2G 自检向量**（2G = 0x7cf27b18..., 0x07775510...，
  已用 python cryptography 独立核对）——不符直接抛错，绝不给出坏钥。
  算法另以 python 镜像与 cryptography 交叉验证 5/5 随机标量一致。
- 公钥不再持久化（spkiDer 按固定模板 + Q 手工拼 X.509 SPKI DER → KeyFactory 生成
  PublicKey → SHA256withECDSA 验签）——清数据/重装后依然能验签（密钥随设备）。
- **导出格式 v2**：`WFLKEY2-<64 位 hex 标量>`（App 显示；`clearPin` 后重新设置显示
  同一把钥）；`pin_reset.py` 同时兼容 v2（hex 标量，`ec.derive_private_key`）与 v1
  （Base64 PKCS8，老文件）。
- **迁移（v1.4 → v1.5）**：旧随机公钥记录在下次 `setPin` 时清除并切换为设备派生钥
  ——老用户需重新保存一次私钥文件（一次性），此后永不再变。
- 测试：镜像算法 vs cryptography 5/5；2G 向量 ✓；同一 ANDROID_ID 重复生成 =
  同一标量/同一 key 文件 ✓；v2 格式 e2e（申请码→签名→验签）ALL PASS ✓。

### 18.3 安全边界（如实记录）

- 算法与常量盐随仓库公开，安全性取决于"ANDROID_ID 对外部不可知"（root 读取
  settings_ssaid.xml 可复现钥——root 攻击者本就可绕过应用锁，不构成额外降级）；
- 非爆破对抗 4–8 位 PIN 的在线侧仍由指数锁定承担；
- 离线侧攻击需 2^64 ANDROID_ID 空间的 HMAC 穷举——对本工具威胁模型足够。

## 19. 七阶段迭代（2026-11-01）：脚本健壮性 + 路线互斥（versionCode 7 / versionName 1.6）

### 19.1 重置脚本对 v2 私钥的兼容性修复

需求方实测报“私钥文件格式不对（应为 Base64 文本）: Only base64 data is allowed”
——**该文案是 v1.4 时代旧脚本的报错文本**（新版应为“应为 WFLKEY2-<64位hex> 或 Base64”），
即需求方电脑上运行的是旧拷贝。借机把脚本做得更健壮（新版三形态实测全过）：
- `utf-8-sig` 读文件：自动吞掉记事本"UTF-8 with BOM"的 BOM（`\s+` 不匹配 \ufeff，
  旧解析会被 BOM 破坏）；
- v2 识别改 `re.search(r"WFLKEY2-([0-9a-fA-F]{64})")`：兼容文件里误粘贴了旧钥/
  说明文字的混合内容（新旧并存时优先取新钥）；
- 报错文案提示"也请确认本脚本是仓库最新版"。
- 用需求方真实 key（cd94b308...）实测：normal / BOM / 新旧混合 三形态 ALL PASS。

### 19.2 路线 1 / 路线 2 互斥（需求方要求：只能开一个）

- **判定基准**：路线 1 生效中 = 有管理权（DIRECT/DHIZUKU）&& no_config_wifi 已设置；
  路线 2 生效意图 = 应用内开关持久化标记（WATCH_KEY）。
- 开路线 1（主开关 ON）时：若路线 2 意图开启 && Shizuku 在线 → 拒绝并提示
  “路线需二选一：若需更改，请先关闭已开启的路线 2”；若意图开启但 Shizuku 离线
  （保护已死）→ **自动清除路线 2 意图并提示**，避免“Shizuku 离线时路线 2 开关
  置灰关不掉 → 路线 1 被卡死”的死锁。
- 开路线 2（watchOn）时：若路线 1 生效中 → 拒绝并提示“…请先关闭已开启的路线 1”。
- 自动续接（tryWatchHeal）同样尊重互斥（路线 1 生效中不续接；覆盖经 adb 外部
  设置限制的边界）。

---

## 20. 第八阶段迭代（2026-11-01）：蓝牙服务级冻结 + 防卸载语义升级（versionCode 8 / versionName 1.7）

### 20.1 背景（需求方实测：realme UI 2.0 / Android 11，Dhizuku 经 AOSP 测试签名激活成功）

需求方用「Dhizuku + AOSP 测试签名」方式完成 Device Owner 激活，限制经 Dhizuku 代理
生效；随后发现：WiFi/蓝牙等仍可在**下拉控制中心**直接开关，询问能否解决；同时要求
加「防卸载开关」（MDM 内）。

### 20.2 控制中心绕过限制——AOSP R 源码结论（aosp-mirror platform_frameworks_base @ android-11.0.0_r1）

- **SystemUI 快速磁贴从不检查用户限制**：`packages/SystemUI/.../qs/tiles/WifiTile.java`
  handleClick（115–121 行）直接 `mController.setWifiEnabled(!wifiEnabled)`；
  `BluetoothTile.java` handleClick（85–90 行）直接 `mController.setBluetoothEnabled(!isEnabled)`；
  两处均无 DISALLOW/hasUserRestriction 检查。→ 原生 AOSP 亦绕过；ColorOS/realmeUI
  深度定制面板同理。设置 App 的灰化是「设置自己读限制」的行为。
- **WiFi 服务层无限制拦截**：`packages/modules/Wifi/.../WifiServiceImpl`
  `setWifiEnabled`（890–915 行）只查调用方权限（uid/权限/飞行模式/SoftAp），无任何
  用户限制检查；`no_config_wifi` 在 WiFi 服务内的唯一落点是 `factoryReset`
  （若以 Android 12 基线 lineage-19.0 �SearchTree核对，R 同构），**不管开关状态改动**。
  → **WiFi 无法在服务层锁死，“保持状态”只能靠看门狗改回**（本工具路线 2 已有）。
- **蓝牙例外：`no_bluetooth` 是服务级强制**：`services/core/java/com/android/server/
  BluetoothManagerService.java` 的 `isBluetoothDisallowed()`（2546–2550 行）检查
  `UserManager.DISALLOW_BLUETOOTH, UserHandle.SYSTEM`；`enable()`（1054 行）、
  `disable()`（1088 行）、`enableNoAutoConnect`（1027 行）、BLE（857/883/913 行）与
  重启路径（1320 行）全部拒绝。**与调用方/界面无关**——控制中心切了等于没切。
  - 合法性：`UserRestrictionsUtils`（R）`PRIMARY_USER_ONLY_RESTRICTIONS` 含
    `DISALLOW_BLUETOOTH`（153–154 行）——Device Owner 可设，设后应用于本用户；
    Dhizuku（DO）经 setUserRestriction 代理可设（既有契约 134 事务码）。
  - 公开性：`UserManager.DISALLOW_BLUETOOTH` 为 AOSP 公开常量（"no_bluetooth"，
    API 21+；Android 14 起仅标记弃用，R/S/T/U 服务层仍在用）；SDK 头文件存在，可编译。
  - **双向冻结语义**（重要）：`disable()` 同样被 1088 行拦——开启限制时蓝牙若开着
    → 冻在"开"；想保持"关"必须先关蓝牙再上锁。UI 以红字描述提示。
- 附：R 的 svc 存在 `svc bluetooth enable|disable`（`cmds/svc/.../BluetoothCommand.java`
  经 BluetoothAdapter → BluetoothManagerService，同样被 no_bluetooth 拦）；
  `cmd bluetooth_manager` 在 R **不存在**（服务未实现 ShellCommand，S 才有
  BluetoothShellCommand）——仅供后续版本升级时参考。
- **互斥决策**：需求方明确选择「**维持互斥不变**」（v1.6 规则原样保留）与「蓝牙
  **只要限制冻结**」（不做 `svc bluetooth` 看门狗扩展）。WiFi 控制中心绕过如实写进
  文档（平台现状），解决途径=路线 2 看门狗（需先关路线 1）。

### 20.3 防卸载——人工智能核实链（R PackageManagerService）

- `deletePackageAsUser`（18169 行）：`isUserRestricted(userId, DISALLOW_UNINSTALL_APPS)`
  → 直接返回 `DELETE_FAILED_USER_RESTRICTED`；`isUserRestricted`（13304–13311 行）
  经 `UserManager.getUserRestrictions(userId)` 查**目标用户**的限制，与调用方身份无关
  → **`adb shell pm uninstall`（shell uid）同样被拒**，仅 root 可绕过（root 可改限制）。
- `isSuspendAllowedForUser`（13661–13665 行）：无 DISALLOW_APPS_CONTROL 而有
  DISALLOW_UNINSTALL_APPS 时，普通调用方的 **停用/挂起应用** 同样被拒
  （DO 调用方豁免）。
- 结论：目录里的 `no_uninstall_apps` 一项就是最强防卸载手段（系统级、全应用生效），
  v1.7 将其改名「防卸载（禁止卸载应用）」并升级描述；不采用 `setUninstallBlocked`
  ≤（单应用 API）：Dhizuku 契约未覆盖该事务码，且语义弱于本限制（无法保护 Dhizuku 自身）。

### 20.4 实现（versionCode 8 / versionName 1.7）与收尾

- `RestrictionCatalog.kt`：
  - 新增 `DISALLOW_BLUETOOTH` 行「蓝牙锁定（服务级冻结）」（网络组，紧邻
    “禁蓝牙配置”，后者说明文案标注“界面级”并与前者分工）；
  - 「禁卸载应用」→「防卸载（禁止卸载应用）」，描述含 adb 拦截与“先关闭再卸载”提示；
  - 类注释补充 no_bluetooth 服务级证据行号。
- `MainActivity.kt.buildRestrictionsPage`：页首加两条提示（控制中心磁贴不查限制的
  根因 + 蓝牙/WiFi 的不同拦截路径与互斥提示）。
- 顺手修正 v1.4 遗留的过期文案两处：
  - 应用锁状态行「离开应用 10 分钟内重进免验证」→「严格模式：每次离开后重进都需验证」
    （v1.4 改严格模式时此行漏改）；
  - 使用说明能力表「10 分钟免重验」同因。
- 使用说明 §6/§9（FAQ 3 行）、根 README 特性表、WifiLock/README 限制管理节
  （22→23 项）同步。
- 仓库描述同步改「23 项」。发布：Release v1.7（tag），附 APK + zip。
- 决策记录（需求方拍板）：互斥维持不变；蓝牙只做限制冻结（不做看门狗扩展）。

### 20.5 验证边界（未做实机回归，需需求方在 realme UI 2.0 验证）

- 「蓝牙锁定」开启后控制中心切蓝牙应无效（卡在原状态，磁贴可能有短暂"正在打开"残影
  ——SystemUI 乐观更新所致，服务拒绝后状态回读会复位）；
- 「防卸载」开启后：设置内卸载/停用应被拒；`adb shell pm uninstall <本应用或任意应用>`
  预期报 "DELETE_FAILED_USER_RESTRICTED"（failure 码）；
- 解锁路径：回限制管理页关闭对应开关即可（Dhizuku 通道解除实时生效）。

---

## 21. 第九阶段迭代（2026-11-03）：单应用防卸载「仅本应用」（versionCode 9 / versionName 1.8）

### 21.1 需求变更与方案反转记录

- 需求方澄清（拍板）："防卸载"指**在不影响其他软件正常卸载的前提下，让本软件自身不被
  手动卸载**——即 v1.7 的「全应用统一拒绝」语义不符合预期（它会拦下所有应用的卸载）。
- 因此反转 §20.3 的判定「不采用 `setUninstallBlocked`」：该判定给出的两条理由
  （① Dhizuku 契约未覆盖该事务码；② 单应用语义弱于全量限制、无法保护 Dhizuku 自身）
  在新约束下均不成立——新约束恰好要求"其他应用可正常卸载"，全量限制才是违反者；
  ② 通过把 Dhizuku 代理加进保护列表解决。
- 条目布局：新增「防卸载（仅本应用）」（**推荐日常选项**）；v1.7 的全量项改名
  「防卸载（全部应用）」，描述标注"⚠️ 影响所有应用的正常卸载，慎用"，仅在需要
  彻底锁死整台设备时使用。两者并存于"应用"组。

### 21.2 技术路线（DPM 公开 API，不动 AIDL 精密件）

- 走 `DevicePolicyManager.setUninstallBlocked(admin, packageName, enable)`
  / `isUninstallBlocked(admin, packageName)`（API 21+，Device Owner / Profile Owner
  专属，公开 SDK API）。不动任何用户限制；与 DISALLOW_UNINSTALL_APPS 互不干扰。
- 三通道分发（`PolicyController.setUninstallSelfBlocked` / `isUninstallSelfBlocked`）：
  - **DIRECT**：直调本应用 AdminReceiver 的 DPM。
  - **DHIZUKU（Android ≤14，含 realme UI 2.0 / ColorOS 11.1）**：复用 v1.2 的
    `withDhizukuDpm` 老契约重放机制（mService 热替换 + 平台公共 API）——该机制按
    方法逐个经 Dhizuku 转发，天然覆盖本 API，**无需修改 IDevicePolicyManager.aidl
    精密件**（仍只有 setUserRestriction=134 / getUserRestrictions=138 两个真实声明）。
  - **DHIZUKU（Android 15+）**：Dhizuku 新契约只适配了用户限制读写两事务码，
    该 API 事务码不在契约内 → 主动抛 `IllegalStateException` 提示改用直连 owner
    或全量限制（与 v1.7 禁截屏同策略）。
- 保护范围 `uninstallProtectedPackages()`：本应用包名恒在；DHIZUKU 模式下连同
  `com.rosan.dhizuku` 一起锁定——**否则用户先卸掉 Dhizuku 代理防线即刻失效**
  （把 §20.3"无法保护 Dhizuku 自身"的缺陷正向补齐）。设置时逐包下发，解除即逐包放开。

### 21.3 源码取证（AOSP R PackageManagerService，aosp-mirror android-11.0.0_r1）

- `deletePackageAsUser` **18180 行**：`getBlockUninstallForUser` 单应用 block 检查
  → 返回 `DELETE_FAILED_OWNER_BLOCKED`。该检查**与调用方无关**（设置/应用商店/
  `adb pm uninstall` 一律被拒），仅 root 可绕过。
- 对照 v1.7 取证：全量限制在 **18169 行**（`DELETE_FAILED_USER_RESTRICTED`）；
  `isSuspendAllowedForUser` **13661 行**（全量限制连带拒"停用/挂起应用"）。
- 结论：单应用 block 的拦截点在包管理器删除流程统一入口，防护强度与全量限制
  相同（连 adb 都进不来），只是作用域精确到被锁的包——正是"仅本应用"所需语义。

### 21.4 实现（RestrictionCatalog / MainActivity）

- `RestrictionCatalog.kt`：新增 `SPECIAL_UNINSTALL_SELF = "uninstall_self"` 常量与
  「防卸载（仅本应用）」条目（特殊型，`key = null`，第二个 special 条目）；全量项
  改名「防卸载（全部应用）」并重写描述；类注释补充两条 special 条目的语义边界说明。
- `MainActivity.kt`：限制页开关**下发**由 `if (special == SCREEN_CAPTURE)` 改为
  `when (item.special)` 三分支（SCREEN_CAPTURE / UNINSTALL_SELF / 其余为用户限制）；
  **状态读取**同构三分支；置灰判定从"仅禁截屏"推广为
  `special != null && DHIZUKU && SDK_INT >= 35`（两个单应用级 API 都在 15+ 契约外）。
- `PolicyController.kt`：如 21.2 所述新增一对读写方法；`isUninstallSelfBlocked`
  仅读本应用包的状态。
- `app/build.gradle`：versionCode 9 / versionName "1.8"。

### 21.5 文档四处同步与验证边界

- 根 README 特性表 23→24 项并拆两条防卸载行；WifiLock/README 限制管理节标题计
  24 项 + 新增 v1.8 bullet（含 PMS 18180 行证据）；使用说明 §6 表格行、"两条重点限制"
  扩为"三条重点限制"、§9 FAQ 防卸载行重写；本节即移交记录。
- 验证清单（需求方在 realme UI 2.0 实测）：
  1. 开「防卸载（仅本应用）」后，设置内对**本应用**卸载应被拒（提示"已被管理员/组织
     停用"类文案）；`adb shell pm uninstall com.example.wifilock` 预期失败；
  2. **其他应用**（任选系统应用或第三方）应照常可卸载——这是本版本的核心验收点；
  3. Dhizuku 模式下 `com.rosan.dhizuku` 也应无法卸载（走设置卸载入口验证）；
  4. 解除路径：回限制管理页关闭开关，随后本应用应可正常卸载；
  5. 状态读取：App 内开关与系统实测一致（关掉 App 重进不回跳）。
- 决策记录（需求方拍板）：路线 1/2 互斥维持不变（§19 结论）；蓝牙仍只做限制冻结。

---

*本文档由前期开发过程回溯整理；所有外部库 API 语义均以 sources jar / AOSP 官方源码核对过（核对明细见 §6 各条）。
接手者遇本表未覆盖的报错，优先复现 §7.2 的排查方法（错误文案 → 权威源码 grep → 链逐节点读实现）。*
