package com.example.wifilock;

// 【精密件】在 Shizuku（shell/root）进程中运行的用户服务契约。
// 见移交文档 §11：本接口一旦有方法带显式事务编号，所有方法都必须显式编号；
// exec=1、destroy=16777114 是 Shizuku 框架约定（框架按 FIRST_CALL_TRANSACTION+声明码调用
// destroy，即运行时事务码 16777115，见 rikka.shizuku.Shizuku bindUserService javadoc 与
// rikka.shizuku.ShizukuApiConstants.USER_SERVICE_TRANSACTION_destroy）。请勿移动/修改这两个码。
// 新增方法必须使用互不冲突的新编号（当前占用：1、2、3、4、16777114）。
interface IShellUserService {
    // 通用 shell 执行（诊断链、dpm 命令等都在此通道完成）
    String exec(in String[] cmd) = 1;

    // 启动 WiFi 看门狗：每 intervalMs（[500,600000]，越界回落 3000）毫秒尝试一次
    // `cmd wifi set-wifi-enabled enabled`，失败退回 `svc wifi enable`。
    // 幂等：已在运行时直接返回 "running"（由 ShizukuClient 的 daemon(true) 服务承载，
    // 本 App 进程退出后仍继续运行，直至 watchStop/destroy 或 Shizuku 退出）。
    String watchLoop(long intervalMs) = 2;

    // 停止看门狗线程，并尽力清理可能残留的 watchdog 服务进程（pkill -f <pkg>:watchdog）
    String watchStop() = 3;

    // 查询看门狗状态：state=LOCKING/IDLE, 运行信息（供 App 回读真实状态）
    String watchStatus() = 4;

    void destroy() = 16777114;
}
