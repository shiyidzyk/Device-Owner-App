package com.example.wifilock

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.rosan.dhizuku.api.Dhizuku
import com.rosan.dhizuku.api.DhizukuRequestPermissionListener
import rikka.shizuku.Shizuku

class MainActivity : Activity() {

    private lateinit var controller: PolicyController
    private lateinit var modeText: TextView
    private lateinit var stateText: TextView
    private lateinit var lockSwitch: Switch
    private lateinit var watchSwitch: Switch
    private lateinit var watchInfoText: TextView
    private lateinit var grantBtn: Button
    private lateinit var shizukuText: TextView
    private lateinit var shizukuGrantBtn: Button
    private lateinit var shizukuSelfBtn: Button
    private lateinit var shizukuDhizukuBtn: Button
    private lateinit var shizukuDiagBtn: Button
    private lateinit var shizukuAccountsBtn: Button
    private lateinit var shizukuOut: TextView
    private var uiRefreshed = false
    private var watchStatusText = "尚未查询"
    /** 诊断链回读的最新 dumpsys 结果（判断阻断条件用） */
    private var lastOwnerDump = ""
    private var lastAccountDump = ""
    private var lastUsersDump = ""

    private val prefs by lazy { getSharedPreferences("wifilock", MODE_PRIVATE) }

    private val directCmd =
        "adb shell dpm set-device-owner com.example.wifilock/.AdminReceiver"
    private val dhizukuCmd =
        "adb shell dpm set-device-owner com.rosan.dhizuku/.server.DhizukuDAReceiver"

    private val shizukuPermListener = object : Shizuku.OnRequestPermissionResultListener {
        override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
            if (requestCode != ShizukuClient.REQ_PERMISSION) return
            val granted = grantResult == PackageManager.PERMISSION_GRANTED
            Handler(Looper.getMainLooper()).post {
                toast(if (granted) "Shizuku 授权成功" else "Shizuku 授权被拒绝")
                if (granted) tryWatchHeal()
                refresh()
            }
        }
    }

    private val shizukuBinderListener = Shizuku.OnBinderReceivedListener {
        // Shizuku 刚刚上线（或恢复）→ 若锁定意图为开，自动续接看门狗
        tryWatchHeal()
        refresh()
    }
    private val shizukuDeadListener = Shizuku.OnBinderDeadListener { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        controller = PolicyController(this)
        setContentView(buildUi())
        Shizuku.addRequestPermissionResultListener(shizukuPermListener)
        // sticky：若 binder 已就绪会立即回调
        Shizuku.addBinderReceivedListenerSticky(shizukuBinderListener)
        Shizuku.addBinderDeadListener(shizukuDeadListener)
        refresh()
    }

    override fun onResume() {
        super.onResume()
        if (uiRefreshed) refresh()
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            Shizuku.removeRequestPermissionResultListener(shizukuPermListener)
            Shizuku.removeBinderReceivedListener(shizukuBinderListener)
            Shizuku.removeBinderDeadListener(shizukuDeadListener)
        } catch (_: Throwable) {
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun buildUi(): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(20))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        box.addView(TextView(this).apply {
            text = "🔒 WiFi 锁定工具"
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(8))
        })

        modeText = TextView(this).apply {
            textSize = 15f
            setPadding(0, 0, 0, dp(4))
        }
        box.addView(modeText)

        stateText = TextView(this).apply {
            textSize = 15f
            setPadding(0, 0, 0, dp(10))
        }
        box.addView(stateText)

        lockSwitch = Switch(this).apply {
            text = "  禁止修改 WiFi（设备管理员限制·持久）"
            textSize = 16f
        }
        lockSwitch.setOnCheckedChangeListener { _, checked ->
            if (!uiRefreshed) return@setOnCheckedChangeListener
            try {
                controller.setLock(checked)
                toast(if (checked) "已上锁：WiFi 开关已被禁用" else "已解锁")
            } catch (t: Throwable) {
                toast("操作失败：${t.message}")
            }
            refresh()
        }
        box.addView(lockSwitch)

        watchSwitch = Switch(this).apply {
            text = "  Shizuku 直接锁定（免管理员·已用过的设备推荐）"
            textSize = 16f
        }
        watchSwitch.setOnCheckedChangeListener { _, checked ->
            if (!uiRefreshed) return@setOnCheckedChangeListener
            if (checked) {
                watchOn()
            } else {
                watchOff()
            }
        }
        box.addView(watchSwitch)

        watchInfoText = TextView(this).apply {
            textSize = 12f
            setTextColor(0xFF556655.toInt())
            setPadding(0, dp(2), 0, dp(8))
        }
        box.addView(watchInfoText)

        grantBtn = Button(this).apply {
            text = "申请 Dhizuku 授权"
            setOnClickListener { requestDhizuku() }
        }
        box.addView(grantBtn)

        // ===== Shizuku 区块 =====
        box.addView(TextView(this).apply {
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            text = "Shizuku（直接锁定 / 诊断 / 可选一键管理员）"
            setPadding(0, dp(10), 0, dp(4))
        })
        shizukuText = TextView(this).apply {
            textSize = 13f
            setPadding(0, 0, 0, dp(6))
        }
        box.addView(shizukuText)

        shizukuGrantBtn = Button(this).apply {
            text = "申请 Shizuku 授权"
            setOnClickListener { requestShizukuPermission() }
        }
        box.addView(shizukuGrantBtn)

        shizukuSelfBtn = Button(this).apply {
            text = "Shizuku 一键：本 App 设为 Device Owner（自动先诊断）"
            setOnClickListener { activateOwner(directCmd.substringAfter("dpm set-device-owner ")) }
        }
        box.addView(shizukuSelfBtn)

        shizukuDhizukuBtn = Button(this).apply {
            text = "Shizuku 一键：Dhizuku 设为 Device Owner（自动先诊断）"
            setOnClickListener { activateOwner(dhizukuCmd.substringAfter("dpm set-device-owner ")) }
        }
        box.addView(shizukuDhizukuBtn)

        shizukuDiagBtn = Button(this).apply {
            text = "一键诊断（检查激活障碍：已有 Owner / 账户 / 多用户）"
            setOnClickListener {
                shizukuOut.text = "诊断中…"
                diagnoseChain()
            }
        }
        box.addView(shizukuDiagBtn)

        shizukuAccountsBtn = Button(this).apply {
            text = "打开系统账户设置（临时删除 Google 等账户后可重试一键）"
            setOnClickListener {
                try {
                    startActivity(Intent(android.provider.Settings.ACTION_SYNC_SETTINGS))
                } catch (t: Throwable) {
                    toast("跳转失败：${t.message}")
                }
            }
        }
        box.addView(shizukuAccountsBtn)

        shizukuOut = TextView(this).apply {
            textSize = 12f
            setTextColor(0xFF555555.toInt())
            setTextIsSelectable(true)
            setPadding(0, dp(4), 0, dp(4))
        }
        box.addView(shizukuOut)

        box.addView(TextView(this).apply {
            textSize = 13f
            setPadding(0, dp(14), 0, dp(8))
            text = buildString {
                appendLine("首次使用（按设备状态三选一）：")
                appendLine()
                appendLine("方案 0 · Shizuku 直接锁定（推荐·已使用过的设备，无需任何激活）")
                appendLine("    启动并授权 Shizuku（无线调试/Root），打开上方“Shizuku 直接锁定”")
                appendLine("    原理：由 Shizuku（adb 身份）每 3 秒把 WiFi 强制开回；Shizuku 断线则保护失效，")
                appendLine("    重新上线时 App 会自动续接。被关闭的 WiFi 最长约 3 秒内恢复，期间有短暂断网。")
                appendLine()
                appendLine("方案 1 · Shizuku 一键设为 Device Owner（灰化开关，最持久）：")
                appendLine("    点击上面的“一键”（内置自动诊断）：若无障碍直接设置成功；")
                appendLine("    若被“账户”阻断（已用过的设备最常见），先到系统账户设置临时删除，")
                appendLine("    点“重试”激活成功后再把账户加回来即可，无需恢复出厂。")
                appendLine()
                appendLine("方案 2 · 电脑 adb（Dhizuku 或本 App 直连）——效果同方案 1：")
                append("    ").appendLine(dhizukuCmd)
                append("    ").appendLine(directCmd)
                append("    ").appendLine("（Dhizuku 命令以 Dhizuku App 内授权页展示的为准）")
                appendLine()
                appendLine("效果：方案 1/2 为系统级禁改 WiFi（设置页开关变灰；各系统控制中心/磁贴请实机验证）；")
                appendLine("方案 0 能覆盖所有关闭途径（含厂商磁贴），但依赖 Shizuku 在线。")
                appendLine("注意：锁定期间会同时禁止添加/忘记网络（方案 1/2），请先连好常用 WiFi。")
                appendLine("免责声明：仅限自有/已授权设备的合法管理用途。")
            }
        })

        box.addView(Button(this).apply {
            text = "复制 Dhizuku 授权命令"
            setOnClickListener { copy(dhizukuCmd) }
        })
        box.addView(Button(this).apply {
            text = "复制直连授权命令"
            setOnClickListener { copy(directCmd) }
        })

        return box
    }

    private fun requestDhizuku() {
        try {
            if (!Dhizuku.init(this)) {
                toast("Dhizuku 初始化失败：请确认 Dhizuku 已成为 Device Owner")
                return
            }
            Dhizuku.requestPermission(object : DhizukuRequestPermissionListener() {
                override fun onRequestPermission(grantResult: Int) {
                    val granted = grantResult == PackageManager.PERMISSION_GRANTED
                    Handler(Looper.getMainLooper()).post {
                        toast(if (granted) "Dhizuku 授权成功" else "授权被拒绝")
                        refresh()
                    }
                }
            })
        } catch (t: Throwable) {
            toast("无法连接 Dhizuku：${t.message}")
        }
    }

    private fun requestShizukuPermission() {
        try {
            if (!ShizukuClient.installed(this)) {
                toast("未安装 Shizuku：应用商店或官网获取")
                return
            }
            if (!ShizukuClient.ping()) {
                toast("Shizuku 未启动：请先在手机上启动 Shizuku")
                return
            }
            Shizuku.requestPermission(ShizukuClient.REQ_PERMISSION)
        } catch (t: Throwable) {
            toast("无法连接 Shizuku：${t.message}")
        }
    }

    // ------------------------------------------------------------------
    // Shizuku 直接锁定（看门狗）
    // ------------------------------------------------------------------

    private fun watchOn() {
        if (!ShizukuClient.granted()) {
            toast("请先申请 Shizuku 授权")
            uiRefreshed = false
            watchSwitch.isChecked = false
            uiRefreshed = true
            return
        }
        shizukuOut.text = "看门狗启动中…"
        ShizukuClient.watchStart(this) { output ->
            val ok = !output.contains("\u0000EX\u0000")
            if (ok) {
                prefs.edit().putBoolean(WATCH_KEY, true).apply()
                toast("看门狗已开启：WiFi 被关闭后约 ${
                    ShizukuClient.WATCH_INTERVAL_MS / 1000
                } 秒自动恢复")
                queryWatchStatus()
            } else {
                uiRefreshed = false
                watchSwitch.isChecked = false
                uiRefreshed = true
            }
            shizukuOut.text = "watchLoop →\n$output"
            refresh()
        }
    }

    private fun watchOff() {
        if (!ShizukuClient.granted()) {
            // Shizuku 已死：daemon 服务也与它共存亡；直接清意图即可
            prefs.edit().putBoolean(WATCH_KEY, false).apply()
            return
        }
        shizukuOut.text = "看门狗停止中…"
        ShizukuClient.watchStop(this) { output ->
            prefs.edit().putBoolean(WATCH_KEY, false).apply()
            shizukuOut.text = "watchStop →\n$output"
            watchStatusText = "已停止"
            refresh()
        }
    }

    private fun queryWatchStatus() {
        if (!ShizukuClient.granted()) {
            watchStatusText = "Shizuku 离线（看门狗随之停止）"
            return
        }
        ShizukuClient.watchStatus(this) { output ->
            watchStatusText = if (output.contains("\u0000EX\u0000")) output else "服务端：$output"
            refresh()
        }
    }

    /** Shizuku 恰好上线时的自动续接（服务端 daemon 服务会随 Shizuku 死亡） */
    private fun tryWatchHeal() {
        if (prefs.getBoolean(WATCH_KEY, false) && ShizukuClient.granted()) {
            ShizukuClient.watchStart(this) { output ->
                watchStatusText = "自动续接 → $output"
                refresh()
            }
        }
    }

    // ------------------------------------------------------------------
    // 诊断链：owner / 账户 / 多用户
    // ------------------------------------------------------------------

    private fun diagnoseChain(done: (() -> Unit)? = null) {
        appendOut("\$ 诊断开始")
        ShizukuClient.execCmd(
            this,
            arrayOf("sh", "-c", "dumpsys device_policy | grep -iE 'owner|admin' | head -30")
        ) { ownerOut ->
            lastOwnerDump = ownerOut
            appendOut("— 已有管理角色 —\n$ownerOut")
            ShizukuClient.execCmd(
                this,
                arrayOf("sh", "-c", "dumpsys account | grep 'Account {' | head -15; echo ----; dumpsys account | grep -c 'Account {'")
            ) { accountOut ->
                lastAccountDump = accountOut
                appendOut("— 现存账户（临时删除后可重试一键）—\n$accountOut")
                ShizukuClient.execCmd(
                    this,
                    arrayOf("sh", "-c", "pm list users")
                ) { usersOut ->
                    lastUsersDump = usersOut
                    appendOut("— 用户情况 —\n$usersOut")
                    appendOut(conclusion())
                    done?.invoke()
                }
            }
        }
    }

    /** 由三探针结果给出建议结论 */
    private fun conclusion(): String {
        val ownerLine = Regex("Device owner\\s*:\\s*\\S+").find(lastOwnerDump)?.value
        val existingOwner = ownerLine != null && !ownerLine.endsWith(":")
        val accounts = lastAccountDump.substringAfterLast("----")
            .trim()
            .toIntOrNull() ?: -1
        val extraUsers = Regex("UserInfo\\s*\\{\\s*\\d+").findAll(lastUsersDump).count() > 1
        return buildString {
            appendLine("→ 结论：")
            if (existingOwner) {
                appendLine("已存在 Device Owner（$ownerLine）：一键必失败；需先移除该 Owner（详他们应用的说明/设备管理设置）。")
            } else if (accounts > 0) {
                appendLine("设备上有 $accounts 个账户：dpm set-device-owner 将报 “already some accounts”。")
                appendLine("处理：临时删除（记住密码），一键成功后再加回来；或直接用“Shizuku 直接锁定”。")
            } else if (accounts == 0) {
                appendLine("无账户：一键激活应可成功（若设备曾预装/管理类 App 或存在多用户仍可能被阻）。")
            } else {
                appendLine("账户信息读取失败（Shizuku 离线或系统无账户服务），请检查授权后重试。")
            }
            if (extraUsers) {
                appendLine("注意：设备存在附加用户，激活可能报 “several users”。")
            }
            appendLine("提示：一键失败时的系统报错原文会显示在输出里。")
        }
    }

    // ------------------------------------------------------------------
    // 一键激活（先诊断，无阻断则执行 dpm）
    // ------------------------------------------------------------------

    private fun activateOwner(adminFlatten: String) {
        if (!ShizukuClient.granted()) {
            toast("请先申请 Shizuku 授权")
            return
        }
        shizukuOut.text = "激活前自动诊断中…"
        diagnoseChain {
            val ownerLine = Regex("Device owner\\s*:\\s*\\S+").find(lastOwnerDump)?.value
            val existingOwner = ownerLine != null && !ownerLine.endsWith(":")
            val accounts = lastAccountDump.substringAfterLast("----")
                .trim()
                .toIntOrNull() ?: 0
            when {
                existingOwner -> {
                    appendOut("→ 阻断：已存在 Device Owner（$ownerLine）。请先移除它再重试。")
                    toast("被“已有 Device Owner”阻断")
                }
                accounts > 0 -> {
                    appendOut("→ 阻断：设备有 $accounts 个账户（激活会失败）。")
                    appendOut("   请到系统账户设置临时删除（记住密码，激活后可再加回），然后点重试。")
                    toast("被“已有账户”阻断")
                }
                else -> doOwner(adminFlatten)
            }
        }
    }

    private fun doOwner(adminFlatten: String) {
        try {
            shizukuOut.text = "正在通过 Shizuku（uid=${ShizukuClient.uid()}）执行…"
            ShizukuClient.setDeviceOwner(this, adminFlatten) { output, success ->
                shizukuOut.text = "$ dpm set-device-owner $adminFlatten\n$output"
                if (success) {
                    toast("Device Owner 设置成功")
                } else {
                    toast("设置失败，详见下方输出")
                }
                uiRefreshed = false
                refresh()
            }
        } catch (t: Throwable) {
            shizukuOut.text = "执行异常：\n${t.javaClass.simpleName}: ${t.message}"
            toast("执行失败：${t.message}")
        }
    }

    private fun appendOut(text: String) {
        shizukuOut.text = "${shizukuOut.text}\n$text".trimStart('\n')
    }

    private fun refresh() {
        val mode = controller.detectMode()
        modeText.text = "权限模式：" + when (mode) {
            PolicyController.Mode.DIRECT -> "直连 Device Owner（本应用）"
            PolicyController.Mode.DHIZUKU -> "Dhizuku 代理"
            PolicyController.Mode.NONE ->
                if (controller.dhizukuInstalled()) "未授权（已检测到 Dhizuku，可点下方按钮申请）"
                else "未授权（无管理员：可用下方 Shizuku 直接锁定，或一键激活）"
        }
        val locked = try {
            controller.isLocked()
        } catch (_: Throwable) {
            false
        }
        val watchIntent = prefs.getBoolean(WATCH_KEY, false)
        stateText.text = buildString {
            append("当前状态：")
            if (mode == PolicyController.Mode.NONE) {
                if (watchIntent) {
                    append("WiFi 强制开启（Shizuku 看门狗，依赖 Shizuku 在线）")
                } else {
                    append("无管理权限，锁定功能不可用")
                }
            } else {
                append("WiFi ${if (locked) "🔒 已锁定（开关禁用）" else "未锁定"}")
            }
        }

        // 更新开关前先把监听静默，避免触发 setLock 造成循环
        uiRefreshed = false
        lockSwitch.isEnabled = mode != PolicyController.Mode.NONE
        lockSwitch.isChecked = locked
        val szGranted = ShizukuClient.granted()
        watchSwitch.isEnabled = szGranted
        if (!szGranted) {
            // Shizuku 离线时保持显示意图，但提示其已然停效
            watchInfoText.text =
                "Shizuku 直接锁定依赖 Shizuku 在线；离线期间保护停止。\n$watchStatusText"
        } else {
            watchInfoText.text =
                "轮询间隔约 ${ShizukuClient.WATCH_INTERVAL_MS / 1000} 秒。\n$watchStatusText"
        }
        watchSwitch.isChecked = prefs.getBoolean(WATCH_KEY, false)
        uiRefreshed = true

        grantBtn.visibility = if (mode == PolicyController.Mode.NONE &&
            controller.dhizukuInstalled()
        ) Button.VISIBLE else Button.GONE

        // Shizuku 区块状态
        shizukuText.text = "Shizuku：${ShizukuClient.statusText(this)}"
        val szInstalled = ShizukuClient.installed(this)
        val szPing = ShizukuClient.ping()
        shizukuGrantBtn.visibility =
            if (szInstalled && szPing && !szGranted) Button.VISIBLE else Button.GONE
        val showOwnerBtns = szGranted
        shizukuSelfBtn.visibility = if (showOwnerBtns) Button.VISIBLE else Button.GONE
        shizukuDhizukuBtn.visibility =
            if (showOwnerBtns && controller.dhizukuInstalled()) Button.VISIBLE else Button.GONE
        shizukuDiagBtn.visibility =
            if (showOwnerBtns) Button.VISIBLE else Button.GONE
        shizukuAccountsBtn.visibility =
            if (showOwnerBtns) Button.VISIBLE else Button.GONE
    }

    private fun copy(text: String) {
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("cmd", text))
            toast("已复制，电脑端执行即可")
        } catch (t: Throwable) {
            toast("复制失败：${t.message}")
        }
    }

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        private const val WATCH_KEY = "watch_on"
    }
}
