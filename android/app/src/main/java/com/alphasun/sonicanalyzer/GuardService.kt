package com.alphasun.sonicanalyzer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * 值守前台服务
 *
 * 【v0.6.0 新增 —— 补齐真机功能不可用的关键缺口】
 * 此前 Manifest 声明了 FOREGROUND_SERVICE / _MICROPHONE / _CAMERA 三个权限，
 * 但**根本没有对应的 Service 类**。后果：
 *   ① 切后台或息屏后，Android 会限制/中断麦克风采集，
 *      声波警戒值守形同虚设（用户的核心诉求就是"移动设备上长时间值守"）；
 *   ② 声明了前台服务权限却没有服务，Android 14+ 上会被系统/商店判定为权限滥用。
 *
 * 现在：开启警戒值守时启动本服务，持常驻通知 + 可选 WakeLock，
 * 保证息屏/切后台仍持续采集与抓拍。停止值守时自行结束。
 */
class GuardService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        // NotificationChannel 必须在 startForeground 之前建好，否则 Android 8+ 通知不显示
        ensureChannel(this)
        try {
            startForeground(NOTI_ID, buildNotification("值守中 · 持续监听环境声波"))
        } catch (e: Throwable) {
            android.util.Log.e(TAG, "startForeground 失败", e)
            // 通知权限被拒（Android 13+）时降级：不崩，继续跑采集
        }
        acquireWakeLock()
        return START_STICKY
    }

    /** 更新通知文案（事件触发时给用户在状态栏可见的反馈）。 */
    private fun notifyText(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return
        if (!channelReady) ensureChannel(this)
        try {
            nm.notify(NOTI_ID, buildNotification(text))
        } catch (_: Throwable) { }
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            // Android 12+ 必须显式指定 FLAG_IMMUTABLE，否则抛 IllegalArgumentException
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AlphaSun 声波警戒")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pi)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    @Suppress("DEPRECATION")
    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "alphasun:guard")
            wl.acquire(6 * 60 * 60 * 1000L)   // 上限 6 小时，防止常驻耗电
            wakeLock = wl
        } catch (e: Throwable) {
            android.util.Log.w(TAG, "WakeLock 申请失败（不影响采集）", e)
        }
    }

    override fun onDestroy() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Throwable) { }
        wakeLock = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "AlphaSunGuardSvc"
        const val CHANNEL_ID = "alphasun_guard"
        const val NOTI_ID = 20240
        const val ACTION_STOP = "com.alphasun.sonicanalyzer.STOP_GUARD"

        @Volatile private var channelReady = false

        private fun ensureChannel(ctx: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || channelReady) return
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID, "声波警戒值守",
                        NotificationManager.IMPORTANCE_LOW
                    ).apply {
                        description = "值守期间保持麦克风持续采集"
                        setSound(null, null)
                        enableVibration(false)
                    }
                )
            }
            channelReady = true
        }

        /** 启动前台值守服务。无通知权限时静默降级，不抛异常。 */
        fun start(ctx: Context) {
            try {
                val i = Intent(ctx, GuardService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ctx.startForegroundService(i)
                } else {
                    ctx.startService(i)
                }
            } catch (e: Throwable) {
                android.util.Log.w(TAG, "启动前台服务失败，值守将仅在应用前台有效", e)
            }
        }

        fun stop(ctx: Context) {
            try {
                ctx.startService(Intent(ctx, GuardService::class.java).apply {
                    action = ACTION_STOP
                })
            } catch (_: Throwable) { }
        }

        /** 是否有通知权限（Android 13+）。无权限时前台服务通知不显示，但采集仍可继续。 */
        fun hasNotifyPermission(ctx: Context): Boolean =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(
                    ctx, android.Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
            } else true
    }
}
