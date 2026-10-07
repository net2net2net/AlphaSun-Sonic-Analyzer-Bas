package com.alphasun.sonicanalyzer

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.getcapacitor.BridgeActivity

/**
 * v0.3.0 web 框架线 · Capacitor 启动入口。
 *
 * 背景（2026-10-05 复盘）：本工程的 android/ 曾被 v0.4.0「全原生重写」改成
 * Kotlin/Compose（`MainActivity : ComponentActivity`），Capacitor 依赖与 web 资产被移除。
 * 但按「以 web 框架为版本基线」的要求，交付物应是 **Capacitor WebView 容器 + index.html**，
 * 即 `dist/AlphaSun-Sonic-Analyzer-0.3.0.apk`（versionCode 7）那种形态。
 * 故新增本类作为启动入口；原生 `MainActivity` **原样保留**在库内，仅不再作为启动页。
 *
 * 为什么需要显式申请权限：Capacitor 的 `BridgeWebChromeClient` 对 `AUDIO_CAPTURE`
 * 要求同时具备 `RECORD_AUDIO` **与** `MODIFY_AUDIO_SETTINGS` 才 grant，
 * 缺任一项都会让 `getUserMedia` 始终失败（表现为"麦克风不可用"）。
 */
class BridgeMainActivity : BridgeActivity() {

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* Web 层通过 getUserMedia 自行处理结果，此处仅负责把权限框拉起来 */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestCriticalPermissions()
    }

    /** 一次性把 WebView 采集链依赖的权限拉起来；已授予则不弹框。 */
    private fun requestCriticalPermissions() {
        val need = REQUIRED.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (need.isNotEmpty()) permLauncher.launch(need.toTypedArray())
    }

    private companion object {
        /**
         * RECORD_AUDIO + MODIFY_AUDIO_SETTINGS：麦克风采集（缺一即 getUserMedia 失败）
         * CAMERA：声波警戒告警时抓拍/录像
         * POST_NOTIFICATIONS：Android 13+ 前台服务通知
         * ACCESS_FINE/COARSE_LOCATION：噪音评估记录 GPS 定位（v0.4.0 需求④；
         *   用户拒绝时 Web 层降级为人工填写位置，不阻塞主流程）
         */
        val REQUIRED = arrayOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.MODIFY_AUDIO_SETTINGS,
            Manifest.permission.CAMERA,
            Manifest.permission.POST_NOTIFICATIONS,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
    }
}
