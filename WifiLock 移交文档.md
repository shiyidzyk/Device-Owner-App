# WifiLock 项目移交文档

> 文档目的：完整交接本项目的设计思路、实现细节、修复历史与踩坑记录。
> 本文档只覆盖 WifiLock（WiFi 锁定工具）本项目内容。
> 交付日期：2026-11-01　构建产物版本：APK 1617 KB（versionCode 1）

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

*本文档由前期开发过程回溯整理；所有外部库 API 语义均以 sources jar / AOSP 官方源码核对过（核对明细见 §6 各条）。
接手者遇本表未覆盖的报错，优先复现 §7.2 的排查方法（错误文案 → 权威源码 grep → 链逐节点读实现）。*
