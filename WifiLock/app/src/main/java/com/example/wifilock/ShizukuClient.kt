package com.example.wifilock

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import rikka.shizuku.Shizuku

/**
 * Shizuku 客户端封装。全部 API 均与 dev.rikka.shizuku:api 13.1.5 的
 * rikka.shizuku.Shizuku 源码逐一核对（pingBinder/getUid/checkSelfPermission/
 * requestPermission/OnRequestPermissionResultListener/bindUserService/UserServiceArgs）。
 *
 * 两个绑定身份（同一 ShellUserService 类、不同 UserServiceArgs → 两个独立服务进程）：
 * 1) exec 通道：daemon(false) + 后缀 "shizuku"——跑 dpm set-device-owner / 诊断命令，短命即焚；
 * 2) watchdog 通道：daemon(true) + 后缀 "watchdog"——WiFi 看门狗。
 *    官方 javadoc：daemon 模式服务在调用方进程死亡后继续运行，直到 unbind 或 destroy；
 *    若后端是 Shizuku，Shizuku 重启会杀掉全部 user service，重新 bind 即可恢复。
 *    因此 App 侧观测到 Shizuku 重新上线时，需要重新 watchStart（MainActivity 已做自动续接）。
 *
 * 版本约定：USER_SERVICE_VERSION 在服务代码变更时递增，server 会重建服务进程。
 * watchLoop/watchStop/watchStatus 幂等可重复调用（代码版本一致时 server 会复用常驻实例）。
 */
object ShizukuClient {

    /** 申请权限的 requestCode（App 与 ShizukuManager 的匹配码，无系统约束） */
    const val REQ_PERMISSION = 30001

    /** 看门狗默认轮询间隔（毫秒）。短间隔=拦截及时但更耗电；可在 UI 提示。 */
    const val WATCH_INTERVAL_MS = 3000L

    /** Shizuku 管理器包名：新版（v13 时代，见 rikka.shizuku.ShizukuProvider 常量） */
    private const val MANAGER_NEW = "moe.shizuku.privileged.api"
    /** Shizuku 管理器包名：旧版 */
    private const val MANAGER_OLD = "moe.shizuku.manager"

    /** UserService 版本号：契约/服务代码更新时递增，server 会重建进程 */
    private const val USER_SERVICE_VERSION = 2

    private const val EX_MARK = "\u0000EX\u0000"

    fun installed(context: Context): Boolean {
        val pm = context.packageManager
        return listOf(MANAGER_NEW, MANAGER_OLD).any { pkg ->
            try {
                pm.getPackageInfo(pkg, 0)
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    fun ping(): Boolean = try {
        Shizuku.pingBinder()
    } catch (_: Throwable) {
        false
    }

    fun granted(): Boolean {
        if (!ping()) return false
        return try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Throwable) {
            false
        }
    }

    /** -1 = 未知 / pre-v11 未授权；2000 = adb 启动；0 = root 启动 */
    fun uid(): Int = try {
        Shizuku.getUid()
    } catch (_: Throwable) {
        -1
    }

    /** 状态描述，直接用于 UI 文本 */
    fun statusText(context: Context): String = when {
        !installed(context) -> "未安装（应用商店获取 Shizuku）"
        !ping() -> "已安装，但 Shizuku 未启动（手机上打开 Shizuku App，无线调试或 Root 启动）"
        !granted() -> "已启动，尚未授权"
        else -> when (uid()) {
            0 -> "已授权（root 模式，uid=0）"
            2000 -> "已授权（adb 模式，uid=2000）"
            else -> "已授权（uid=${uid()}）"
        }
    }

    // ---------------------------------------------------------------------
    // 会话式调用：bindUserService → (worker 线程)执行操作 → 主线程回调 → unbind
    // 错误路径全部走 onDone 回调文本（exit=-1 / EX 标记），不出 App 崩溃。
    // ---------------------------------------------------------------------
    private fun execArgs(context: Context) = Shizuku.UserServiceArgs(
        ComponentName(context, ShellUserService::class.java)
    )
        .daemon(false)
        .version(USER_SERVICE_VERSION)
        .debuggable(false)
        .processNameSuffix("shizuku") // 必须设置，否则框架 NPE
        .tag("wifilock-exec")

    private fun watchArgs(context: Context) = Shizuku.UserServiceArgs(
        ComponentName(context, ShellUserService::class.java)
    )
        .daemon(true) // 常驻：App 退出后看门狗继续；Shizuku 退出则会停，由 App 自动续接
        .version(USER_SERVICE_VERSION)
        .debuggable(false)
        .processNameSuffix("watchdog")
        .tag("wifilock-watch")

    /** 把异常包装成输出文本（UI 显示原语一致） */
    private fun fmtError(t: Throwable): String =
        "$EX_MARK${t.javaClass.simpleName}: ${t.message ?: "(无消息)"}"

    /** 统一的“拿一次 UserService 然后干活”；onDone 在主线程回调且必达一次。 */
    private fun withService(
        args: Shizuku.UserServiceArgs,
        task: (IShellUserService) -> String,
        onDone: (result: String) -> Unit
    ) {
        val main = Handler(Looper.getMainLooper())
        var settled = false
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                if (settled) return
                settled = true
                val service =
                    if (binder != null) IShellUserService.Stub.asInterface(binder) else null
                if (service == null) {
                    main.post { onDone("${EX_MARK}UserService binder 为空") }
                    return
                }
                val svc = service
                Thread {
                    val output = try {
                        task(svc)
                    } catch (t: Throwable) {
                        fmtError(t)
                    }
                    main.post {
                        try {
                            Shizuku.unbindUserService(args, this, false)
                        } catch (_: Throwable) {
                        }
                        onDone(output)
                    }
                }.start()
            }

            override fun onServiceDisconnected(name: ComponentName?) = Unit
        }
        try {
            Shizuku.bindUserService(args, conn)
        } catch (t: Throwable) {
            main.post {
                // bindUserService 可能已部分注册连接；尽力解绑
                try {
                    Shizuku.unbindUserService(args, conn, false)
                } catch (_: Throwable) {
                }
                onDone(fmtError(IllegalStateException("无法启动 Shizuku UserService", t)))
            }
        }
    }

    /** 前置权限检查 + withService 包装 */
    private fun withServiceChecked(
        args: Shizuku.UserServiceArgs,
        label: String,
        task: (IShellUserService) -> String,
        onDone: (result: String) -> Unit
    ) {
        if (!granted()) {
            onDone("$EX_MARK[label=$label] 未取得 Shizuku 授权（请先点“申请 Shizuku 授权”）")
            return
        }
        withService(args, task, onDone)
    }

    // ---------------------------------------------------------------------
    // exec 通道 API
    // ---------------------------------------------------------------------

    /** 执行任一 shell 命令（诊断链主体）。输出带 exit= 行。 */
    fun execCmd(context: Context, cmd: Array<String>, onResult: (output: String) -> Unit) {
        withServiceChecked(
            execArgs(context), "exec ${cmd.joinToString(" ")}",
            { it.exec(cmd) },
            onResult
        )
    }

    /**
     * 通过 Shizuku UserService 执行 dpm set-device-owner（等效 adb，同一 shell 身份豁免）。
     * @param adminFlatten 例如 "com.example.wifilock/.AdminReceiver"
     * 成功与否以 output 文本判定（exit=0）。
     */
    fun setDeviceOwner(
        context: Context,
        adminFlatten: String,
        onResult: (output: String, success: Boolean) -> Unit
    ) {
        execCmd(context, arrayOf("dpm", "set-device-owner", adminFlatten)) { output ->
            onResult(output, output.startsWith("exit=0"))
        }
    }

    // ---------------------------------------------------------------------
    // watchdog 通道 API
    // ---------------------------------------------------------------------

    /** 启动 WiFi 看门狗（幂等）。服务侧 daemon(true)，App 退出后仍生效。 */
    fun watchStart(context: Context, onResult: (output: String) -> Unit) {
        withServiceChecked(
            watchArgs(context), "watchStart",
            { it.watchLoop(WATCH_INTERVAL_MS) },
            onResult
        )
    }

    /** 停止看门狗线程，并尽力清理残留的 watchdog 服务进程。 */
    fun watchStop(context: Context, onResult: (output: String) -> Unit) {
        withServiceChecked(
            watchArgs(context), "watchStop",
            { it.watchStop() },
            onResult
        )
    }

    /** 查询看门狗真实状态：state=LOCKING/IDLE ticks=.. last=.. */
    fun watchStatus(context: Context, onResult: (output: String) -> Unit) {
        withServiceChecked(
            watchArgs(context), "watchStatus",
            { it.watchStatus() },
            onResult
        )
    }
}
