package com.example.wifilock

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
    private lateinit var grantBtn: Button
    private lateinit var shizukuText: TextView
    private lateinit var shizukuGrantBtn: Button
    private lateinit var shizukuSelfBtn: Button
    private lateinit var shizukuDhizukuBtn: Button
    private lateinit var shizukuOut: TextView
    private var uiRefreshed = false

    private val directCmd =
        "adb shell dpm set-device-owner com.example.wifilock/.AdminReceiver"
    private val dhizukuCmd =
        "adb shell dpm set-device-owner com.rosan.dhizuku/.server.DhizukuDAReceiver"

    private val shizukuPermListener = object : Shizuku.OnRequestPermissionResultListener {
        override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
            if (requestCode != ShizukuClient.REQ_PERMISSION) return
            val granted = grantResult == android.content.pm.PackageManager.PERMISSION_GRANTED
            Handler(Looper.getMainLooper()).post {
                toast(if (granted) "Shizuku 授权成功" else "Shizuku 授权被拒绝")
                refresh()
            }
        }
    }

    private val shizukuBinderListener = Shizuku.OnBinderReceivedListener { refresh() }
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
            setPadding(0, 0, 0, dp(12))
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

        lockSwitch = Switch(this).apply {
            text = "  禁止修改 WiFi（上锁开关）"
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

        grantBtn = Button(this).apply {
            text = "申请 Dhizuku 授权"
            setOnClickListener { requestDhizuku() }
        }
        box.addView(grantBtn)

        // ===== Shizuku 区块 =====
        box.addView(TextView(this).apply {
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            text = "Shizuku（一键授权，免电脑 adb）"
            setPadding(0, dp(8), 0, dp(4))
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
            text = "Shizuku 一键：设本应用为 Device Owner"
            setOnClickListener { doOwner(directCmd.substringAfter("dpm set-device-owner ")) }
        }
        box.addView(shizukuSelfBtn)

        shizukuDhizukuBtn = Button(this).apply {
            text = "Shizuku 一键：设 Dhizuku 为 Device Owner"
            setOnClickListener { doOwner(dhizukuCmd.substringAfter("dpm set-device-owner ")) }
        }
        box.addView(shizukuDhizukuBtn)

        shizukuOut = TextView(this).apply {
            textSize = 12f
            setTextColor(0xFF555555.toInt())
            setTextIsSelectable(true)
            setPadding(0, dp(4), 0, dp(4))
        }
        box.addView(shizukuOut)

        box.addView(TextView(this).apply {
            textSize = 13f
            setPadding(0, dp(16), 0, dp(8))
            text = buildString {
                appendLine("首次使用需授权（设备须无已有 Device Owner；无账户状态时最干净）:")
                appendLine()
                appendLine("方案一 · Shizuku 一键（推荐，手机上完成）：")
                append("    ").appendLine("装好 Shizuku 并启动（无线调试/Root），回来点上面的按钮")
                appendLine()
                appendLine("方案二 · Dhizuku 托管（电脑 adb）：")
                append("    ").appendLine(dhizukuCmd)
                append("    ").appendLine("（命令以 Dhizuku App 内授权页展示的为准）")
                appendLine()
                appendLine("方案三 · 本 App 直连（电脑 adb）：")
                append("    ").appendLine(directCmd)
                appendLine()
                appendLine("效果：设置页 WLAN 开关变灰不可关闭；各系统控制中心/磁贴请实机验证。")
                appendLine("注意：会同时禁止添加/忘记网络，请先连好常用 WiFi 再上锁。")
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
                    val granted = grantResult == android.content.pm.PackageManager.PERMISSION_GRANTED
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

    private fun refresh() {
        val mode = controller.detectMode()
        modeText.text = "权限模式：" + when (mode) {
            PolicyController.Mode.DIRECT -> "直连 Device Owner（本应用）"
            PolicyController.Mode.DHIZUKU -> "Dhizuku 代理"
            PolicyController.Mode.NONE ->
                if (controller.dhizukuInstalled()) "未授权（已检测到 Dhizuku，可点下方按钮申请）"
                else "未授权（未安装 Dhizuku，可走 Shizuku 或直连方案）"
        }
        val locked = try {
            controller.isLocked()
        } catch (_: Throwable) {
            false
        }
        stateText.text = if (mode == PolicyController.Mode.NONE) {
            "当前状态：无管理权限，锁定功能不可用"
        } else {
            "当前状态：WiFi ${if (locked) "🔒 已锁定（开关禁用）" else "未锁定"}"
        }

        // 更新开关前先把监听静默，避免触发 setLock 造成循环
        uiRefreshed = false
        lockSwitch.isEnabled = mode != PolicyController.Mode.NONE
        lockSwitch.isChecked = locked
        uiRefreshed = true

        grantBtn.visibility = if (mode == PolicyController.Mode.NONE &&
            controller.dhizukuInstalled()
        ) Button.VISIBLE else Button.GONE

        // Shizuku 区块状态
        shizukuText.text = "Shizuku：${ShizukuClient.statusText(this)}"
        val szInstalled = ShizukuClient.installed(this)
        val szPing = ShizukuClient.ping()
        val szGranted = ShizukuClient.granted()
        shizukuGrantBtn.visibility =
            if (szInstalled && szPing && !szGranted) Button.VISIBLE else Button.GONE
        val showOwnerBtns = mode == PolicyController.Mode.NONE && szGranted
        shizukuSelfBtn.visibility =
            if (showOwnerBtns) Button.VISIBLE else Button.GONE
        shizukuDhizukuBtn.visibility =
            if (showOwnerBtns && controller.dhizukuInstalled()) Button.VISIBLE else Button.GONE
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
}
