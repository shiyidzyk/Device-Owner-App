# WifiLock — WiFi 锁定 / 轻量 MDM 工具

![license](https://img.shields.io/badge/license-MIT-green) ![release](https://img.shields.io/badge/release-v1.4-blue)

把设备的 WiFi 锁定为"始终开启"，并附带企业 MDM 常用的系统级管控与应用锁（PIN）。
免 root，面向自有/已授权设备的合法管理场景。

> 📖 完整图文教程见 **[使用说明.md](使用说明.md)**；开发/维护视角的全部技术证据链见
> **[WifiLock 移交文档.md](WifiLock%20移交文档.md)**。

## 功能总览

| 能力 | 说明 |
|---|---|
| WiFi 锁定 · 路线 1 | App（或 Dhizuku）成为 Device Owner，经 `no_config_wifi` 用户限制灰化 WLAN 开关（系统级、持久） |
| WiFi 锁定 · 路线 2 | Shizuku 免管理员：以 adb 身份每 3 秒把 WiFi 强制开回，**已用过的设备（有账户/已过引导）直接可用**，且覆盖厂商磁贴 |
| 一键激活 + 自动诊断 | 内置诊断（已有 Owner / 账户 / 多用户），账户阻断时给出"临时删除→重试→加回"引导，**无需恢复出厂** |
| 限制管理 | 24 项企业 MDM 常用管控（禁调试/侧载/恢复出厂/相机/热点/飞行模式等），三通道复用，状态实时读取 |
| 蓝牙锁定（服务级冻结） | `no_bluetooth` 限制由蓝牙系统服务直接拒绝开/关——**控制中心磁贴切换也无效**；注意双向冻结（先关好蓝牙再启用） |
| 防卸载（仅本应用） | DPM 单应用 API `setUninstallBlocked`：只锁本应用（Dhizuku 模式连同代理本身），**其他应用照常可卸载**；设置/商店/adb 的卸载入口均被系统拒绝 |
| 防卸载（全部应用） | `no_uninstall_apps` 在包管理器层面统一拒绝（含本应用与 Dhizuku），**连 `adb uninstall` 都会失败**（仅 root 可绕过）；影响所有应用，慎用 |
| 应用锁 | PIN 门禁：每次离开（含切后台/进程被杀）重进即验；防爆破指数锁定；门禁页 FLAG_SECURE |
| 离线找回 | 忘记 PIN：挑战-应答（EC P-256 签名）纯离线重置，配 Windows 双击工具（bat/vbs）；重置私钥由设备标识派生，重装/清数据/反复开关均不变 |

## 下载

到 [Releases 页面](https://github.com/shiyidzyk/Device-Owner-App/releases) 下载：

- `WifiLock-debug.apk` —— 直接安装使用
- `WifiLock.zip` —— 完整源码包

## 快速开始

1. **已用过的设备（最常见）**：装 Shizuku → 无线调试启动 → App 内授权 →
   主页打开「路线 2 · Shizuku 直接锁定」；
2. **新设备 / 想要最持久的系统级灰化**：设置与授权页 →「Shizuku 一键：本 App 设为
   Device Owner」（自动诊断，被账户阻断时按引导临时删除账户再重试）；
3. 详细步骤、撤销方法、FAQ：见 [使用说明.md](使用说明.md)。

## 仓库结构

```
├── WifiLock-debug.apk / WifiLock.zip   # 产物（见 Releases，不入库）
├── 使用说明.md                          # 面向使用者的操作手册
├── WifiLock 移交文档.md                 # 开发/维护文档（含源码级证据链）
├── LICENSE                             # MIT
└── WifiLock/                           # 源码（Android Studio 工程根）
    ├── app/src/main/...                # 主逻辑：PolicyController / ShizukuClient /
    │                                   #   ShellUserService / PinManager / MainActivity 等
    ├── app/src/main/aidl/...           # 精密件：IDevicePolicyManager.aidl（事务码对齐）
    └── tools/                          # PC 端配套：pin_reset.py / .bat / .vbs / 演示示例私钥
```

## 构建

```powershell
cd WifiLock
gradle assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

- JDK 21 + Gradle 8.9 + AGP 8.7.3；`local.properties` 按本机 SDK 修改；
- 工程路径须为纯 ASCII（AGP 限制）；
- 注意 `app/src/main/aidl/android/app/admin/IDevicePolicyManager.aidl` 是事务码对齐的
  精简契约，**不要整理格式或删减"用不到"的方法**（详见移交文档 §7）。

## 应用锁重置私钥

仓库中的 `WifiLock/tools/wifilock_reset_private.key` 是**演示示例私钥**（有意保留，
用于展示离线重置流程）。私钥与设备绑定、由设备标识确定性派生，保存一次长期有效；
获取方式、使用与替换为你自己私钥的步骤见
[WifiLock/tools/README.md](WifiLock/tools/README.md)。

## 免责声明

本工具仅限用于**自有或已获合法授权**的设备管理；使用者需遵守当地法律法规，
因使用本工具产生的任何后果由使用者自行承担。请勿用于未经授权管控他人设备。

## 开发说明

本项目（自 2026-11 迭代起）的全部开发工作——包括功能设计、AOSP 源码考证、
编码实现、构建与测试——由智谱（[z.ai](https://z.ai) / [zchat.z.ai](https://zchat.z.ai)）
的 **GLM-5.3-Flash** 模型完成。

## 许可

本项目基于 [MIT License](LICENSE) 开源。
