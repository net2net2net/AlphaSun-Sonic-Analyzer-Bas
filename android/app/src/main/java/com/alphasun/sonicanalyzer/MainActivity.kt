package com.alphasun.sonicanalyzer

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.alphasun.sonicanalyzer.ui.AlphaSunApp

/**
 * v0.6.0 启动链彻底修复。
 *
 * 上一版存在三处会导致真机「功能不可用」的硬伤：
 *
 * 1. `requestPermissions` 传入的是**空实现** `startActivitySafely()`，
 *    Compose 侧首次启动调用它什么也不会发生 → 系统权限框永不弹出。
 * 2. `MainViewModel` 声明为 `ViewModel(Context)`，却用
 *    `AndroidViewModelFactory` 构造 —— 工厂按 `AndroidViewModel` 反射查找，
 *    找不到匹配构造器直接抛 `IllegalArgumentException`，
 *    `as? MainViewModel ?: MainViewModel(...)` 的兜底在 Activity 构造期就炸，
 *    结果整页 Compose 根本渲染不出来。
 * 3. 权限回调与 ViewModel 就绪存在竞态：回调先到时 vmRef 还是 null，
 *    旧代码只置一个标志位，但 Compose 侧组合并不会因为标志位变化而重组，
 *    于是 `retryStart()` 永远不会被调用。
 *
 * 现在：
 * · 权限请求直接调 `permLauncher.launch()`，无任何空实现；
 * · ViewModel 用显式 Factory 构造，绕过反射猜测；
 * · 「是否需要拉起权限框」由 Activity 自己在 onCreate 决定并串行化，
 *   Compose 侧只负责显示状态与提供按钮，不再承担启动时序。
 */
class MainActivity : ComponentActivity() {

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val mic = result[Manifest.permission.RECORD_AUDIO]
            ?: hasMicPermission()
        handlePermissionResult(mic)
    }

    private var vmRef: MainViewModel? = null
    private var permissionRequested = false

    private fun hasMicPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
    }

    /**
     * 供 Compose「去授权」按钮调用。
     *
     * 【v0.6.0】一并申请 CAMERA 与 POST_NOTIFICATIONS：
     *   · CAMERA 缺了 → 声波警戒抓拍必定失败（前/后摄都拿不到画面），
     *     而这是用户明确要求的核心功能；
     *   · POST_NOTIFICATIONS 缺了（Android 13+）→ 前台服务通知不显示。
     * 只申请麦克风是不够的。
     */
    fun launchPermissions() {
        if (hasMicPermission()) {
            handlePermissionResult(true)
            return
        }
        permissionRequested = true
        val want = ArrayList<String>().apply {
            add(Manifest.permission.RECORD_AUDIO)
            add(Manifest.permission.MODIFY_AUDIO_SETTINGS)
            add(Manifest.permission.CAMERA)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        permLauncher.launch(want.toTypedArray())
    }

    private fun handlePermissionResult(micGranted: Boolean) {
        val vm = vmRef
        if (vm == null) {
            // ViewModel 还没创建（极少见）：先记下，等 setContent 注入时消费
            pendingMic = micGranted
            return
        }
        if (micGranted) vm.retryStart() else vm.onPermissionDenied()
    }

    private var pendingMic: Boolean? = null

    private val vmFactory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
                return MainViewModel(application) as T
            }
            throw IllegalArgumentException("未知 ViewModel: $modelClass")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)

        // 显式构造，保证类型正确、时机确定
        val vm = ViewModelProvider(this, vmFactory)[MainViewModel::class.java]
        vmRef = vm

        // 首启动且未授权 → 立即拉起系统权限框（这里才是唯一权威的启动决策点）
        if (!hasMicPermission()) {
            launchPermissions()
        } else {
            vm.retryStart()
        }

        setContent {
            AlphaSunApp(
                viewModel = vm,
                onRequestMicPermission = { launchPermissions() }
            )
        }

        pendingMic?.let { granted ->
            pendingMic = null
            handlePermissionResult(granted)
        }
    }

    override fun onDestroy() {
        vmRef = null
        super.onDestroy()
    }
}
