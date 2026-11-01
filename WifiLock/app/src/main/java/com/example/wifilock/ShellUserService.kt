package com.example.wifilock

import com.example.wifilock.IShellUserService

/**
 * Shizuku UserService：由 Shizuku server 从本 APK 的 ClassLoader 实例化，
 * 运行身份为 shell（uid 2000，adb 启动）或 root（uid 0，root 启动）。
 *
 * 官方契约备注（rikka.shizuku.Shizuku javadoc）：
 * - user service 进程【无隐藏 API 限制】（本文件只用公开 JVM/Process API，未触碰隐藏 API）；
 * - 它不是合法 Android 应用进程，不要用 registerReceiver / getContentResolver 等
 *   依赖应用生命周期的 API；java.lang.Process 属于纯 JVM 层，可安全使用；
 * - daemon(true) 模式下本服务在调用方进程死亡后继续运行，
 *   直到 destroy（App 主动调）或 Shizuku 退出（框架会杀掉全部 user service）。
 *
 * 本类承载两种身份（ShizukuClient 用不同 UserServiceArgs 绑定同一类）：
 * 1) exec 通道（daemon=false，后缀 shizuku）：跑 dpm/诊断命令，短命即焚；
 * 2) watchdog 通道（daemon=true，后缀 watchdog）：WiFi 看门狗，常驻。
 */
class ShellUserService : IShellUserService.Stub() {

    private val watchLock = Any()
    private var watchRunning = false
    private var watchThread: Thread? = null
    private var watchTicks = 0L
    private var watchLast = "尚未启动"

    /** 一次"把 WiFi 开回"的候选命令（按序尝试，exit=0 即算成功）：
     *  cmd 形式为 Android 10+ 的官方 shell 命令；svc 覆盖更老/定制系统。 */
    private val wifiOnCommands = arrayOf(
        arrayOf("cmd", "wifi", "set-wifi-enabled", "enabled"),
        arrayOf("svc", "wifi", "enable")
    )

    override fun exec(cmd: Array<String>): String = try {
        val proc = ProcessBuilder(*cmd)
            .redirectErrorStream(true) // 合并 stderr，便于把报错一并带回 UI
            .start()
        val output = proc.inputStream.bufferedReader().use { it.readText() }
        "exit=${proc.waitFor()}\n$output".trimEnd()
    } catch (t: Throwable) {
        "exit=-1\n${t.javaClass.simpleName}: ${t.message}"
    }

    override fun watchLoop(intervalMs: Long): String {
        synchronized(watchLock) {
            if (watchRunning) return "already-running"
            val interval = if (intervalMs < 500 || intervalMs > 600000) 3000L else intervalMs
            watchRunning = true
            watchTicks = 0L
            watchLast = "启动成功(interval=${interval}ms)"
            val t = Thread {
                try {
                    while (true) {
                        if (synchronized(watchLock) { !watchRunning }) break
                        val ok = tickOnce()
                        synchronized(watchLock) {
                            watchTicks++
                            watchLast = if (ok) "正常开回(tick=$watchTicks)" else "执行失败(tick=$watchTicks)"
                        }
                        try {
                            Thread.sleep(interval)
                        } catch (_: InterruptedException) {
                            break
                        }
                    }
                    synchronized(watchLock) {
                        watchRunning = false
                        watchThread = null
                        watchLast = "已停止"
                    }
                } catch (_: Throwable) {
                    synchronized(watchLock) { watchRunning = false }
                }
            }
            watchThread = t
            t.name = "wifi-watch"
            t.isDaemon = true
            t.start()
        }
        return "running"
    }

    override fun watchStop(): String {
        synchronized(watchLock) {
            watchRunning = false
            watchThread?.interrupt()
        }
        // 清理可能的残留 watchdog 进程（Shizuku 重启边界等产生的孤儿实例）。
        // 本进程名 <pkg>:watchdog 同样命中；但先把停止标记置位、让 binder 回复先出去。
        val pkg = javaClass.`package`?.name ?: return "stopping"
        Thread {
            try {
                Thread.sleep(300)
            } catch (_: InterruptedException) {
            }
            try {
                ProcessBuilder("pkill", "-f", "$pkg:watchdog")
                    .start()
                    .waitFor()
            } catch (_: Throwable) {
                // toybox pkill 不可用时忽略：watchRunning=false 已让循环自然停
            }
        }.start()
        return "stopping"
    }

    override fun watchStatus(): String = synchronized(watchLock) {
        val state = if (watchRunning) "LOCKING" else "IDLE"
        "$state ticks=$watchTicks last=$watchLast"
    }

    override fun destroy() {
        // Shizuku 约定：调用方 unbind 后框架按事务码 16777115 调到本方法；
        // 进程级退出会连带结束 watchdog 线程，无需额外清理。
        System.exit(0)
    }

    /** 单次 tick：依次尝试候选命令，任一 exit=0 即成功 */
    private fun tickOnce(): Boolean {
        for (cmd in wifiOnCommands) {
            try {
                val proc = ProcessBuilder(*cmd)
                    .redirectErrorStream(true)
                    .start()
                val code = proc.waitFor()
                if (code == 0) return true
            } catch (_: Throwable) {
            }
        }
        return false
    }
}
