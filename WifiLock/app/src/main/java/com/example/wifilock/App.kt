package com.example.wifilock

import android.app.Application
import android.os.Build
import com.rosan.dhizuku.api.Dhizuku
import org.lsposed.hiddenapibypass.HiddenApiBypass

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // Android 9+ 的隐藏 API 限制会拦住「反射 DevicePolicyManager.mService」，
        // Dhizuku 代理 DPM 依赖该反射，这里整类豁免（未受支持机型静默降级）。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                HiddenApiBypass.addHiddenApiExemptions("L")
            } catch (_: Throwable) {
            }
        }
        // Dhizuku API：进程级初始化（未安装 Dhizuku 时静默失败，不影响直连模式）
        try {
            Dhizuku.init(this)
        } catch (_: Throwable) {
        }
    }
}
