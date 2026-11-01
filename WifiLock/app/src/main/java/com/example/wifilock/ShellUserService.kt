package com.example.wifilock

import com.example.wifilock.IShellUserService

/**
 * Shizuku UserService：由 Shizuku server 从本 APK 的 ClassLoader 实例化，
 * 运行身份为 shell（uid 2000，adb 启动）或 root（uid 0，root 启动）。
 * 该进程不是有效的 Android 应用进程，不要在这里使用
 * registerReceiver / getContentResolver 等 framework 生命周期 API；
 * java.lang.Runtime / Process 属于纯 JVM 层，可以安全使用。
 */
class ShellUserService : IShellUserService.Stub() {

    override fun exec(cmd: Array<String>): String = try {
        val proc = ProcessBuilder(*cmd)
            .redirectErrorStream(true) // 合并 stderr，便于把报错一并带回 UI
            .start()
        val output = proc.inputStream.bufferedReader().use { it.readText() }
        val code = proc.waitFor()
        "exit=$code\n$output".trimEnd()
    } catch (t: Throwable) {
        "exit=-1\n${t.javaClass.simpleName}: ${t.message}"
    }

    override fun destroy() {
        System.exit(0)
    }
}
