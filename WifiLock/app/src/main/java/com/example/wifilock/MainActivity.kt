package com.example.wifilock

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.rosan.dhizuku.api.Dhizuku
import com.rosan.dhizuku.api.DhizukuRequestPermissionListener
import rikka.shizuku.Shizuku

/**
 * 页面结构（单 Activity 程序化 UI，无 XML 布局）：
 * - 主页：模式/状态 + 两个锁定开关（设备管理员 / Shizuku 直接锁定）+ 两个入口按钮；
 * - 设置与授权页：Shizuku 状态与授权、一键激活（自动诊断）、Dhizuku 授权、执行输出；
 * - 使用说明页：三方案说明 + 命令复制。
 * 所有页面套 ScrollView；系统返回键与页内「← 返回」均回到主页。
 * 控件引用按页面存在（导航时置空重建），refresh() 对空引用安全，可被任意回调触发。
 */
class MainActivity : Activity() {

    private lateinit var controller: PolicyController
    private lateinit var root: ScrollView
    private var currentPage = PAGE_HOME

    // —— 主页控件 ——
    private var modeText: TextView? = null
    private var stateText: TextView? = null
    private var lockSwitch: Switch? = null
    private var watchSwitch: Switch? = null
    private var watchInfoText: TextView? = null
    private var setupEntryBtn: Button? = null

    // —— 设置与授权页控件 ——
    private var shizukuText: TextView? = null
    private var shizukuGrantBtn: Button? = null
    private var shizukuSelfBtn: Button? = null
    private var shizukuDhizukuBtn: Button? = null
    private var shizukuDiagBtn: Button? = null
    private var shizukuAccountsBtn: Button? = null
    private var shizukuOut: TextView? = null

    // —— 限制管理页控件 ——
    private var restrictStatusText: TextView? = null
    private val restrictionSwitches = mutableListOf<Pair<RestrictionCatalog.Item, Switch>>()

    // —— 应用锁（PIN）门禁与设置页 ——
    private var gateDots: TextView? = null
    private var gateMsg: TextView? = null
    private var gateForgotBtn: Button? = null
    private var gateCopyChallengeBtn: Button? = null
    private var gateChallengeText: TextView? = null
    private var gateResetInput: EditText? = null
    private var gateResetBtn: Button? = null
    private var gateMode = "unlock"
    private val gatePin = StringBuilder()
    private var securityStatusText: TextView? = null
    private var securityOnceKey: TextView? = null

    private var uiRefreshed = false
    private var watchStatusText = "尚未查询"
    private var lastOutput = ""
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
        root = ScrollView(this)
        setContentView(root)
        Shizuku.addRequestPermissionResultListener(shizukuPermListener)
        // sticky：若 binder 已就绪会立即回调
        Shizuku.addBinderReceivedListenerSticky(shizukuBinderListener)
        Shizuku.addBinderDeadListener(shizukuDeadListener)
        if (PinManager.isEnabled(this) && !PinManager.isUnlocked()) {
            navigate(PAGE_GATE)
        } else {
            navigate(PAGE_HOME)
        }
    }

    override fun onResume() {
        super.onResume()
        // 严格模式：任何离开（切后台/划掉/进程被杀）后重新进入都要求验证
        if (PinManager.isEnabled(this) && !PinManager.isUnlocked() &&
            currentPage != PAGE_GATE
        ) {
            navigate(PAGE_GATE)
            return
        }
        refresh()
    }

    override fun onStop() {
        super.onStop()
        // 每次真正离开应用都锁回门禁；旋转屏幕等配置变化不算离开
        if (!isChangingConfigurations) {
            PinManager.markLeft()
        }
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

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (currentPage == PAGE_GATE) {
            // 门禁页不允许返回绕过；退到桌面
            moveTaskToBack(true)
            return
        }
        if (currentPage != PAGE_HOME) {
            navigate(PAGE_HOME)
        } else {
            super.onBackPressed()
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // =====================================================================
    // 页面容器与导航
    // =====================================================================

    private fun navigate(page: String) {
        currentPage = page
        // 门禁页禁止截屏/最近任务预览（防止 PIN 位被偷拍）
        if (page == PAGE_GATE) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        modeText = null; stateText = null; lockSwitch = null; watchSwitch = null
        watchInfoText = null; setupEntryBtn = null
        shizukuText = null; shizukuGrantBtn = null; shizukuSelfBtn = null
        shizukuDhizukuBtn = null; shizukuDiagBtn = null; shizukuAccountsBtn = null
        shizukuOut = null
        restrictStatusText = null
        restrictionSwitches.clear()
        gateDots = null; gateMsg = null; gateForgotBtn = null; gateCopyChallengeBtn = null
        gateChallengeText = null; gateResetInput = null; gateResetBtn = null
        gateMode = "unlock"
        gatePin.clear()
        securityStatusText = null
        root.removeAllViews()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(20))
        }
        root.addView(
            box,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        when (page) {
            PAGE_SETUP -> buildSetupPage(box)
            PAGE_HELP -> buildHelpPage(box)
            PAGE_RESTRICTIONS -> buildRestrictionsPage(box)
            PAGE_GATE -> buildGatePage(box)
            PAGE_SECURITY -> buildSecurityPage(box)
            else -> buildHomePage(box)
        }
        refresh()
    }

    private fun sectionTitle(parent: LinearLayout, text: String) {
        parent.addView(TextView(this).apply {
            this.text = text
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(14), 0, dp(6))
        })
    }

    private fun backBtn(parent: LinearLayout) {
        parent.addView(Button(this).apply {
            text = "← 返回"
            setOnClickListener { navigate(PAGE_HOME) }
        })
    }

    // =====================================================================
    // 主页
    // =====================================================================

    private fun buildHomePage(box: LinearLayout) {
        box.addView(TextView(this).apply {
            text = "🔒 WiFi 锁定工具"
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(10))
        })

        modeText = TextView(this).apply {
            textSize = 15f
            setPadding(0, 0, 0, dp(4))
        }
        box.addView(modeText)

        stateText = TextView(this).apply {
            textSize = 15f
            setPadding(0, 0, 0, dp(12))
        }
        box.addView(stateText)

        box.addView(TextView(this).apply {
            text = "两条锁定路线（按设备情况选择，详见“使用说明”）："
            textSize = 13f
            setPadding(0, 0, 0, dp(6))
        })

        lockSwitch = Switch(this).apply {
            text = "  路线 1 · 设备管理员（系统级灰化，持久）"
            textSize = 16f
        }
        lockSwitch!!.setOnCheckedChangeListener { _, checked ->
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
            text = "  路线 2 · Shizuku 直接锁定（免管理员）"
            textSize = 16f
        }
        watchSwitch!!.setOnCheckedChangeListener { _, checked ->
            if (!uiRefreshed) return@setOnCheckedChangeListener
            if (checked) watchOn() else watchOff()
        }
        box.addView(watchSwitch)

        watchInfoText = TextView(this).apply {
            textSize = 12f
            setTextColor(0xFF556655.toInt())
            setPadding(0, dp(2), 0, dp(10))
        }
        box.addView(watchInfoText)

        setupEntryBtn = Button(this).apply {
            text = "⚙️ 设置与授权"
            setOnClickListener { navigate(PAGE_SETUP) }
        }
        box.addView(setupEntryBtn)

        box.addView(Button(this).apply {
            text = "🧰 限制管理（企业 MDM 常用清单）"
            setOnClickListener { navigate(PAGE_RESTRICTIONS) }
        })

        box.addView(Button(this).apply {
            text = "🔐 应用锁（PIN 码）"
            setOnClickListener { navigate(PAGE_SECURITY) }
        })

        box.addView(Button(this).apply {
            text = "📖 使用说明（三选一怎么选）"
            setOnClickListener { navigate(PAGE_HELP) }
        })

        box.addView(TextView(this).apply {
            textSize = 12f
            setTextColor(0xFF888888.toInt())
            setPadding(0, dp(12), 0, 0)
            text = "免责声明：仅限自有/已授权设备的合法管理用途。"
        })
    }

    // =====================================================================
    // 设置与授权页
    // =====================================================================

    private fun buildSetupPage(box: LinearLayout) {
        backBtn(box)

        sectionTitle(box, "Shizuku（路线 2 的授权来源，也是一键激活的执行者）")
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

        sectionTitle(box, "一键激活 Device Owner（路线 1，先自动诊断再执行）")
        shizukuSelfBtn = Button(this).apply {
            text = "Shizuku 一键：本 App 设为 Device Owner"
            setOnClickListener { activateOwner(directCmd.substringAfter("dpm set-device-owner ")) }
        }
        box.addView(shizukuSelfBtn)
        shizukuDhizukuBtn = Button(this).apply {
            text = "Shizuku 一键：Dhizuku 设为 Device Owner"
            setOnClickListener { activateOwner(dhizukuCmd.substringAfter("dpm set-device-owner ")) }
        }
        box.addView(shizukuDhizukuBtn)
        shizukuDiagBtn = Button(this).apply {
            text = "一键诊断（已有 Owner / 账户 / 多用户）"
            setOnClickListener {
                lastOutput = ""
                appendOut("诊断中…")
                diagnoseChain()
            }
        }
        box.addView(shizukuDiagBtn)
        shizukuAccountsBtn = Button(this).apply {
            text = "打开系统账户设置（临时删除账户后可重试一键）"
            setOnClickListener {
                try {
                    startActivity(Intent(android.provider.Settings.ACTION_SYNC_SETTINGS))
                } catch (t: Throwable) {
                    toast("跳转失败：${t.message}")
                }
            }
        }
        box.addView(shizukuAccountsBtn)

        sectionTitle(box, "Dhizuku（已由 Dhizuku 托管的设备走这里）")
        box.addView(Button(this).apply {
            text = "申请 Dhizuku 授权"
            setOnClickListener { requestDhizuku() }
        })

        sectionTitle(box, "执行输出")
        shizukuOut = TextView(this).apply {
            textSize = 12f
            setTextColor(0xFF555555.toInt())
            setTextIsSelectable(true)
            setPadding(0, dp(4), 0, dp(4))
        }
        box.addView(shizukuOut)
    }

    // =====================================================================
    // 使用说明页
    // =====================================================================

    private fun buildHelpPage(box: LinearLayout) {
        backBtn(box)

        box.addView(TextView(this).apply {
            textSize = 13f
            setPadding(0, dp(4), 0, dp(8))
            text = buildString {
                appendLine("首次使用（按设备状态三选一）：")
                appendLine()
                appendLine("方案 0 · Shizuku 直接锁定（推荐·已使用过的设备，无需任何激活）")
                appendLine("    启动并授权 Shizuku（无线调试/Root），回主页打开“路线 2”")
                appendLine("    原理：由 Shizuku（adb 身份）每 3 秒把 WiFi 强制开回；Shizuku 断线则保护失效，")
                appendLine("    重新上线时 App 会自动续接。被关闭的 WiFi 最长约 3 秒内恢复，期间有短暂断网。")
                appendLine()
                appendLine("方案 1 · Shizuku 一键设为 Device Owner（灰化开关，最持久）：")
                appendLine("    “设置与授权”页点一键（内置自动诊断）：若无障碍直接设置成功；")
                appendLine("    若被“账户”阻断（已用过的设备最常见），先到系统账户设置临时删除，")
                appendLine("    一键成功后再把账户加回来即可，无需恢复出厂。")
                appendLine()
                appendLine("方案 2 · 电脑 adb（与方案 1 同效）：")
                append("    ").appendLine(dhizukuCmd)
                append("    ").appendLine(directCmd)
                append("    ").appendLine("（Dhizuku 命令以 Dhizuku App 内授权页展示的为准）")
                appendLine()
                appendLine("效果：方案 1/2 为系统级禁改 WiFi（设置页开关变灰；各系统控制中心/磁贴请实机验证）；")
                appendLine("方案 2 的路线 2（Shizuku 直接锁定）能覆盖所有关闭途径（含厂商磁贴），但依赖 Shizuku 在线。")
                appendLine("注意：路线 1 上锁会同时禁止添加/忘记网络，请先连好常用 WiFi。")
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
    }

    // =====================================================================
    // 限制管理页（企业 MDM 常用用户限制清单，路线 1 授权后可用）
    // =====================================================================

    private fun buildRestrictionsPage(box: LinearLayout) {
        backBtn(box)

        restrictStatusText = TextView(this).apply {
            textSize = 13f
            setPadding(0, dp(4), 0, dp(8))
        }
        box.addView(restrictStatusText)

        for (section in RestrictionCatalog.sections) {
            sectionTitle(box, section.title)
            for (item in section.items) {
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                }
                val sw = Switch(this).apply {
                    text = "  ${item.title}"
                    textSize = 15f
                    setOnCheckedChangeListener { _, checked ->
                        if (!uiRefreshed) return@setOnCheckedChangeListener
                        try {
                            if (item.special == RestrictionCatalog.SPECIAL_SCREEN_CAPTURE) {
                                controller.setScreenCaptureDisabled(checked)
                            } else {
                                controller.setRestriction(item.key!!, checked)
                            }
                            toast(if (checked) "已启用：${item.title}" else "已解除：${item.title}")
                        } catch (t: Throwable) {
                            toast("操作失败：${t.message}")
                        }
                        refresh()
                    }
                }
                row.addView(sw)
                row.addView(TextView(this).apply {
                    text = "    ${item.desc}"
                    textSize = 11f
                    setTextColor(0xFF888888.toInt())
                    setPadding(dp(28), 0, 0, dp(6))
                })
                restrictionSwitches.add(item to sw)
                box.addView(row)
            }
        }

        box.addView(TextView(this).apply {
            textSize = 12f
            setTextColor(0xFF888888.toInt())
            setPadding(0, dp(10), 0, 0)
            text = "说明：以上均为系统级管控，路线 1 授权（本 App 或 Dhizuku 为 Device Owner）后可用；" +
                "开关状态实时读取自系统，解除时回本页关闭对应开关即可。\n" +
                "注意：禁恢复出厂/禁相机/禁调试等会显著影响设备可用性，请按需启用。"
        })
    }

    // =====================================================================
    // 应用锁门禁页（PIN 键盘 / 忘记 PIN 离线重置）
    // =====================================================================

    private fun buildGatePage(box: LinearLayout) {
        box.addView(TextView(this).apply {
            text = "🔒 应用已锁定"
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(48), 0, dp(10))
        })

        gateMsg = TextView(this).apply {
            textSize = 13f
            setTextColor(0xFFB00020.toInt())
            setPadding(0, 0, 0, dp(8))
        }
        box.addView(gateMsg)

        gateDots = TextView(this).apply {
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            text = dotsText()
            setPadding(0, 0, 0, dp(14))
        }
        box.addView(gateDots)

        val rows = arrayOf(
            arrayOf("1", "2", "3"),
            arrayOf("4", "5", "6"),
            arrayOf("7", "8", "9"),
            arrayOf("⌫", "0", "✓")
        )
        for (row in rows) {
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (label in row) {
                r.addView(Button(this).apply {
                    text = label
                    textSize = 18f
                    setOnClickListener { onGateKey(label) }
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
            box.addView(r)
        }

        gateForgotBtn = Button(this).apply {
            text = "忘记 PIN？（离线重置）"
            setOnClickListener { onGateForgot() }
        }
        box.addView(gateForgotBtn)

        gateChallengeText = TextView(this).apply {
            textSize = 13f
            setTextIsSelectable(true)
            setPadding(0, dp(8), 0, dp(4))
            visibility = TextView.GONE
        }
        box.addView(gateChallengeText)

        gateCopyChallengeBtn = Button(this).apply {
            text = "复制申请码"
            visibility = Button.GONE
            setOnClickListener { gateChallengeText?.text?.toString()?.let { copy(extractCode(it)) } }
        }
        box.addView(gateCopyChallengeBtn)

        gateResetInput = EditText(this).apply {
            hint = "粘贴 PC 生成的重置码 WFR1-…"
            inputType = InputType.TYPE_CLASS_TEXT
            visibility = EditText.GONE
        }
        box.addView(gateResetInput)

        gateResetBtn = Button(this).apply {
            text = "验证重置码并删除 PIN"
            visibility = Button.GONE
            setOnClickListener { onGateReset() }
        }
        box.addView(gateResetBtn)

        box.addView(TextView(this).apply {
            textSize = 12f
            setTextColor(0xFF888888.toInt())
            setPadding(0, dp(14), 0, 0)
            text = "重置流程：① 点“忘记 PIN”得到申请码 → ② 电脑上运行 " +
                "tools/pin_reset.py（需当初启用时保存的私钥）→ ③ 把得到的重置码粘贴到这里。"
        })
    }

    private fun extractCode(text: String): String =
        Regex("WFL1-[0-9a-fA-F]{8}-[0-9a-fA-F]{32}").find(text)?.value ?: text

    private fun dotsText(): String =
        if (gatePin.isEmpty()) "输入 PIN 码" else "● ".repeat(gatePin.length)

    private fun onGateKey(label: String) {
        when (label) {
            "⌫" -> if (gatePin.isNotEmpty()) gatePin.deleteCharAt(gatePin.length - 1)
            "✓" -> {
                val rest = PinManager.lockoutRemaining(this)
                if (rest > 0) {
                    gateMsg?.text = "错误次数过多，请 ${rest / 1000} 秒后再试"
                } else if (PinManager.verifyPin(this, gatePin.toString())) {
                    PinManager.markUnlocked()
                    toast("已解锁")
                    navigate(PAGE_HOME)
                    return
                } else {
                    val r = PinManager.lockoutRemaining(this)
                    gateMsg?.text = if (r > 0) "错误次数过多，请 ${r / 1000} 秒后再试" else "PIN 不正确"
                }
                gatePin.clear()
            }
            else -> if (gatePin.length < 8) gatePin.append(label)
        }
        gateDots?.text = dotsText()
    }

    private fun onGateForgot() {
        gateMode = "reset"
        gateForgotBtn?.visibility = Button.GONE
        val code = PinManager.newChallenge(this)
        gateChallengeText?.visibility = TextView.VISIBLE
        gateChallengeText?.text = "申请码（10 分钟内有效，一次性）：\n$code"
        gateCopyChallengeBtn?.visibility = Button.VISIBLE
        gateResetInput?.visibility = EditText.VISIBLE
        gateResetBtn?.visibility = Button.VISIBLE
    }

    private fun onGateReset() {
        val input = gateResetInput?.text?.toString() ?: ""
        if (PinManager.verifyResetCode(this, input)) {
            toast("验证成功：PIN 已删除，请重新设置")
            navigate(PAGE_SECURITY)
        } else {
            toast("重置码无效或已过期（申请码 10 分钟有效且一次性）")
        }
    }

    // =====================================================================
    // 应用锁设置页（启用 / 修改 / 关闭）
    // =====================================================================

    private fun buildSecurityPage(box: LinearLayout) {
        backBtn(box)

        securityStatusText = TextView(this).apply {
            textSize = 14f
            setPadding(0, dp(4), 0, dp(10))
        }
        box.addView(securityStatusText)

        if (!PinManager.isEnabled(this)) {
            sectionTitle(box, "设置 PIN 码（4–8 位数字）")
            val et1 = numberField("新 PIN")
            val et2 = numberField("确认新 PIN")
            box.addView(et1)
            box.addView(et2)
            box.addView(Button(this).apply {
                text = "启用应用锁"
                setOnClickListener {
                    val a = et1.text.toString()
                    if (!a.matches(Regex("\\d{4,8}"))) {
                        toast("PIN 须为 4–8 位数字")
                        return@setOnClickListener
                    }
                    if (a != et2.text.toString()) {
                        toast("两次输入不一致")
                        return@setOnClickListener
                    }
                    val once = PinManager.setPin(this@MainActivity, a)
                    if (securityOnceKey == null) {
                        securityOnceKey = TextView(this@MainActivity).apply {
                            textSize = 12f
                            setTextIsSelectable(true)
                            setPadding(0, dp(10), 0, 0)
                        }
                        box.addView(securityOnceKey)
                    }
                    securityOnceKey?.text = if (once.isEmpty()) {
                        "已启用（沿用既有重置私钥）"
                    } else {
                        "⚠️ 重置私钥（设备绑定，只显示这一次！）：\n\n$once\n\n" +
                            "请立即复制保存到电脑 tools/pin_reset.py 同目录的 " +
                            "wifilock_reset_private.key（纯文本）。\n" +
                            "它由设备标识派生：之后反复开关应用锁、重装应用、清除数据都不会变；" +
                            "仅恢复出厂会随系统更换。\n" +
                            "用途：忘记 PIN 时离线重置。丢失 PIN 且丢失私钥 = 只能恢复出厂。"
                    }
                    PinManager.markUnlocked()
                    refresh()
                    toast("应用锁已启用")
                }
            })
        } else {
            sectionTitle(box, "修改 PIN")
            val etCur = numberField("当前 PIN")
            val etNew = numberField("新 PIN（4–8 位数字）")
            val etNew2 = numberField("确认新 PIN")
            box.addView(etCur)
            box.addView(etNew)
            box.addView(etNew2)
            box.addView(Button(this).apply {
                text = "确认修改"
                setOnClickListener {
                    if (!PinManager.verifyPin(this@MainActivity, etCur.text.toString())) {
                        toast("当前 PIN 不正确")
                        return@setOnClickListener
                    }
                    val n = etNew.text.toString()
                    if (!n.matches(Regex("\\d{4,8}"))) {
                        toast("新 PIN 须为 4–8 位数字")
                        return@setOnClickListener
                    }
                    if (n != etNew2.text.toString()) {
                        toast("两次输入不一致")
                        return@setOnClickListener
                    }
                    PinManager.setPin(this@MainActivity, n)
                    PinManager.markUnlocked()
                    toast("PIN 已修改（重置私钥不变）")
                    navigate(PAGE_HOME)
                }
            })

            sectionTitle(box, "关闭应用锁")
            val etOff = numberField("当前 PIN")
            box.addView(etOff)
            box.addView(Button(this).apply {
                text = "关闭应用锁"
                setOnClickListener {
                    if (!PinManager.verifyPin(this@MainActivity, etOff.text.toString())) {
                        toast("PIN 不正确")
                        return@setOnClickListener
                    }
                    PinManager.clearPin(this@MainActivity)
                    toast("应用锁已关闭（重置私钥已一并清除）")
                    navigate(PAGE_HOME)
                }
            })

            box.addView(TextView(this).apply {
                textSize = 12f
                setTextColor(0xFF666666.toInt())
                setPadding(0, dp(12), 0, 0)
                text = "规则：离开应用（划后台/进程被杀）后 10 分钟内重进免验证，超时重验；" +
                    "连续错 5 次进入指数锁定（30s 起）。\n" +
                    "忘记 PIN：锁定页“忘记 PIN”→ 申请码 → 电脑 tools/pin_reset.py 生成重置码 → " +
                    "回 App 验证，通过即删除 PIN（全程离线）。"
            })
        }
    }

    private fun numberField(hint: String): EditText = EditText(this).apply {
        this.hint = hint
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
    }

    // =====================================================================
    // 授权流程
    // =====================================================================

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

    // =====================================================================
    // Shizuku 直接锁定（看门狗）
    // =====================================================================

    private fun watchOn() {
        if (!ShizukuClient.granted()) {
            toast("请先在“设置与授权”页申请 Shizuku 授权")
            uiRefreshed = false
            watchSwitch?.isChecked = false
            uiRefreshed = true
            return
        }
        appendOut("看门狗启动中…")
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
                watchSwitch?.isChecked = false
                uiRefreshed = true
            }
            appendOut("watchLoop →\n$output")
            refresh()
        }
    }

    private fun watchOff() {
        if (!ShizukuClient.granted()) {
            // Shizuku 已死：daemon 服务也与它共存亡；直接清意图即可
            prefs.edit().putBoolean(WATCH_KEY, false).apply()
            return
        }
        appendOut("看门狗停止中…")
        ShizukuClient.watchStop(this) { output ->
            prefs.edit().putBoolean(WATCH_KEY, false).apply()
            appendOut("watchStop →\n$output")
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

    // =====================================================================
    // 诊断链：owner / 账户 / 多用户
    // =====================================================================

    private fun diagnoseChain(done: (() -> Unit)? = null) {
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
                appendLine("已存在 Device Owner（$ownerLine）：一键必失败；需先移除该 Owner。")
            } else if (accounts > 0) {
                appendLine("设备上有 $accounts 个账户：dpm set-device-owner 将报 “already some accounts”。")
                appendLine("处理：临时删除（记住密码），一键成功后再加回来；或直接用“路线 2”看门狗。")
            } else if (accounts == 0) {
                appendLine("无账户：一键激活应可成功（若存在多用户仍可能被阻）。")
            } else {
                appendLine("账户信息读取失败（Shizuku 离线或系统无账户服务），请检查授权后重试。")
            }
            if (extraUsers) {
                appendLine("注意：设备存在附加用户，激活可能报 “several users”。")
            }
        }
    }

    // =====================================================================
    // 一键激活（先诊断，无阻断则执行 dpm）
    // =====================================================================

    private fun activateOwner(adminFlatten: String) {
        if (!ShizukuClient.granted()) {
            toast("请先申请 Shizuku 授权")
            return
        }
        lastOutput = ""
        appendOut("激活前自动诊断中…")
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
            appendOut("正在通过 Shizuku（uid=${ShizukuClient.uid()}）执行…")
            ShizukuClient.setDeviceOwner(this, adminFlatten) { output, success ->
                lastOutput = "$ dpm set-device-owner $adminFlatten\n$output"
                appendOut("")
                if (success) {
                    toast("Device Owner 设置成功")
                } else {
                    toast("设置失败，详见输出窗")
                }
                refresh()
            }
        } catch (t: Throwable) {
            appendOut("执行异常：\n${t.javaClass.simpleName}: ${t.message}")
            toast("执行失败：${t.message}")
        }
    }

    // =====================================================================
    // 公共
    // =====================================================================

    private fun appendOut(text: String) {
        lastOutput = if (text.isEmpty()) lastOutput else "${lastOutput}\n$text".trimStart('\n')
        shizukuOut?.text = lastOutput
    }

    private fun refresh() {
        val mode = controller.detectMode()
        val watchIntent = prefs.getBoolean(WATCH_KEY, false)
        val szInstalled = ShizukuClient.installed(this)
        val szPing = ShizukuClient.ping()
        val szGranted = ShizukuClient.granted()

        // —— 主页 ——
        modeText?.text = "权限模式：" + when (mode) {
            PolicyController.Mode.DIRECT -> "直连 Device Owner（本应用）"
            PolicyController.Mode.DHIZUKU -> "Dhizuku 代理"
            PolicyController.Mode.NONE ->
                if (controller.dhizukuInstalled()) "未授权（已检测到 Dhizuku）"
                else "未授权（无管理员：可用路线 2，或一键激活）"
        }
        val locked = try {
            controller.isLocked()
        } catch (_: Throwable) {
            false
        }
        stateText?.text = buildString {
            append("当前状态：")
            if (mode == PolicyController.Mode.NONE) {
                if (watchIntent) {
                    append("WiFi 强制开启（Shizuku 看门狗，依赖 Shizuku 在线）")
                } else {
                    append("无管理权限，路线 1 不可用")
                }
            } else {
                append("WiFi ${if (locked) "🔒 已锁定（开关禁用）" else "未锁定"}")
            }
        }
        lockSwitch?.let { sw ->
            uiRefreshed = false
            sw.isEnabled = mode != PolicyController.Mode.NONE
            sw.isChecked = locked
            uiRefreshed = true
        }
        watchSwitch?.let { sw ->
            uiRefreshed = false
            sw.isEnabled = szGranted
            sw.isChecked = watchIntent
            uiRefreshed = true
        }
        watchInfoText?.text =
            if (szGranted) {
                "轮询间隔约 ${ShizukuClient.WATCH_INTERVAL_MS / 1000} 秒。\n$watchStatusText"
            } else {
                "路线 2 依赖 Shizuku 在线；离线期间保护停止。\n$watchStatusText"
            }
        setupEntryBtn?.text = "⚙️ 设置与授权（Shizuku：" + when {
            !szInstalled -> "未安装"
            !szPing -> "未启动"
            !szGranted -> "待授权"
            else -> "已授权"
        } + "）"

        // —— 设置与授权页 ——
        shizukuText?.text = "Shizuku：${ShizukuClient.statusText(this)}"
        shizukuGrantBtn?.visibility =
            if (szInstalled && szPing && !szGranted) Button.VISIBLE else Button.GONE
        shizukuSelfBtn?.visibility = if (szGranted) Button.VISIBLE else Button.GONE
        shizukuDhizukuBtn?.visibility =
            if (szGranted && controller.dhizukuInstalled()) Button.VISIBLE else Button.GONE
        shizukuDiagBtn?.visibility = if (szGranted) Button.VISIBLE else Button.GONE
        shizukuAccountsBtn?.visibility = if (szGranted) Button.VISIBLE else Button.GONE
        shizukuOut?.text = lastOutput.ifBlank { "（暂无输出）" }

        // —— 限制管理页 ——
        restrictStatusText?.text = buildString {
            append("当前授权：")
            when (mode) {
                PolicyController.Mode.DIRECT ->
                    append("直连 Device Owner（全部可用）")
                PolicyController.Mode.DHIZUKU ->
                    if (Build.VERSION.SDK_INT >= 35) {
                        append("Dhizuku 代理（Android 15+：用户限制可用；“禁截屏”契约未适配）")
                    } else {
                        append("Dhizuku 代理（全部可用）")
                    }
                PolicyController.Mode.NONE ->
                    append("无管理权限——开关不可用。请先在“设置与授权”完成路线 1 激活")
            }
        }
        if (restrictionSwitches.isNotEmpty()) {
            val snapshot = controller.getRestrictions()
            for ((item, sw) in restrictionSwitches) {
                uiRefreshed = false
                sw.isEnabled = mode != PolicyController.Mode.NONE &&
                    !(item.special == RestrictionCatalog.SPECIAL_SCREEN_CAPTURE &&
                        mode == PolicyController.Mode.DHIZUKU && Build.VERSION.SDK_INT >= 35)
                sw.isChecked =
                    if (item.special == RestrictionCatalog.SPECIAL_SCREEN_CAPTURE) {
                        controller.isScreenCaptureDisabled()
                    } else {
                        snapshot?.getBoolean(item.key!!) ?: false
                    }
                uiRefreshed = true
            }
        }

        // —— 应用锁设置页 ——
        securityStatusText?.text = if (PinManager.isEnabled(this)) {
            "状态：已启用（离开应用 10 分钟内重进免验证）"
        } else {
            "状态：未启用。启用后进入应用（或超时重进）需输入 PIN；" +
                "忘记 PIN 可用电脑脚本 tools/pin_reset.py 离线重置。"
        }
    }

    private fun copy(text: String) {
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("cmd", text))
            toast("已复制")
        } catch (t: Throwable) {
            toast("复制失败：${t.message}")
        }
    }

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        private const val WATCH_KEY = "watch_on"
        private const val PAGE_HOME = "home"
        private const val PAGE_SETUP = "setup"
        private const val PAGE_HELP = "help"
        private const val PAGE_RESTRICTIONS = "restrictions"
        private const val PAGE_GATE = "gate"
        private const val PAGE_SECURITY = "security"
    }
}
