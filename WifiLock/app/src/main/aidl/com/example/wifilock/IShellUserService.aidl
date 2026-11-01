package com.example.wifilock;

// 在 Shizuku（shell/root）进程中运行的用户服务契约。
// exec() 由 App 侧调用，在 Shizuku server 里执行 shell 命令并收集输出。
// destroy() 是 Shizuku 框架约定的清理方法：
// 事务码必须固定为 16777115（aidl 按 Shizuku 文档写 16777114，编译会后移一位），
// 见 rikka.shizuku.Shizuku#bindUserService 的 javadoc。
interface IShellUserService {
    String exec(in String[] cmd) = 1;
    void destroy() = 16777114;
}
