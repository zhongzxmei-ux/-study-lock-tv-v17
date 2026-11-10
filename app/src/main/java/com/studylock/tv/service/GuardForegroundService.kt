package com.studylock.tv.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.app.AlarmManager
import android.app.PendingIntent
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import com.studylock.tv.R
import com.studylock.tv.audio.TtsAndSoundManager
import com.studylock.tv.config.LockConfig
import com.studylock.tv.data.QuestionRepository
import com.studylock.tv.overlay.QuizOverlayManager
import java.util.Calendar

class GuardForegroundService : Service() {
    companion object {
        var isRunning = false
        const val ACTION_START = "com.studylock.tv.START_GUARD"
        const val ACTION_STOP = "com.studylock.tv.STOP_GUARD"
        const val ACTION_TEST_NOW = "com.studylock.tv.TEST_NOW"
        const val ACTION_RESET_TIMER = "com.studylock.tv.RESET_TIMER"
        const val BROADCAST_TICK = "com.studylock.tv.TIMER_TICK"
        const val EXTRA_REMAINING_SEC = "remaining_sec"
        const val EXTRA_STATUS_MODE = "status_mode"
    }

    private lateinit var config: LockConfig
    private lateinit var repo: QuestionRepository
    private lateinit var ttsManager: TtsAndSoundManager
    private lateinit var overlayManager: QuizOverlayManager

    private val handler = Handler(Looper.getMainLooper())
    private var remainingSeconds = 15 * 60
    private var strictTickCounter = 0
    private var wakeLock: PowerManager.WakeLock? = null

    private val tickRunnable = object : Runnable {
        override fun run() {
            if (!config.guardEnabled) return

            val nowMs = System.currentTimeMillis()
            val isTempPaused = nowMs < config.tempPauseUntilMillis
            val inWindow = isWithinActiveSchedule()
            val mode = when {
                isTempPaused -> "TEMP_PAUSE"
                !inWindow -> "OUTSIDE_SCHEDULE"
                else -> "RUNNING"
            }

            if (overlayManager.isShowing) {
                strictTickCounter++
                if (config.strictMode && strictTickCounter % 2 == 0) {
                    overlayManager.ensureAttachedWithoutFlicker()
                }
            } else if (!isTempPaused && inWindow) {
                remainingSeconds--
                if (remainingSeconds <= 0) {
                    overlayManager.showQuiz()
                }
            }

            sendBroadcast(Intent(BROADCAST_TICK).apply {
                setPackage(packageName)
                putExtra(EXTRA_REMAINING_SEC, remainingSeconds.coerceAtLeast(0))
                putExtra(EXTRA_STATUS_MODE, mode)
            })

            handler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        config = LockConfig(this)
        repo = QuestionRepository(this)
        ttsManager = TtsAndSoundManager(this)
        overlayManager = QuizOverlayManager(
            context = this,
            config = config,
            repo = repo,
            ttsManager = ttsManager,
            onUnlockWithSeconds = { sec -> remainingSeconds = sec },
            onEmergencyDisableGuard = {
                config.guardEnabled = false
                handler.removeCallbacks(tickRunnable)
                isRunning = false
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        )
        remainingSeconds = config.watchMinutes * 60
        startForeground(1001, buildNotification())
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "StudyLockTV::GuardWakeLock").apply {
                setReferenceCounted(false)
                acquire(12 * 60 * 60 * 1000L)
            }
        } catch (_: Exception) {}
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                config.guardEnabled = false
                config.addLog("已暂停学伴时光守护")
                handler.removeCallbacks(tickRunnable)
                isRunning = false
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_TEST_NOW -> {
                repo.reload()
                overlayManager.showQuiz()
            }
            ACTION_RESET_TIMER -> {
                remainingSeconds = config.watchMinutes * 60
                config.addLog("重置倒计时为 ${config.watchMinutes} 分钟")
            }
            ACTION_BOOT_LAUNCH -> {
                config.guardEnabled = true
                config.addLog("开机自动拉起学伴时光并开启默认守护")
                handler.removeCallbacks(tickRunnable)
                handler.post(tickRunnable)
                try {
                    val launch = Intent(this, com.studylock.tv.MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    }
                    startActivity(launch)
                } catch (_: Exception) {
                }
            }
            else -> {
                config.guardEnabled = true
                config.addLog("开启学伴时光守护（每轮 ${config.watchMinutes} 分钟）")
                handler.removeCallbacks(tickRunnable)
                handler.post(tickRunnable)
            }
        }
        return START_STICKY
    }

    private fun isWithinActiveSchedule(): Boolean {
        if (config.scheduleAllDay) return true
        val cal = Calendar.getInstance()
        val nowMin = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val start = config.scheduleStartMinutes
        val end = config.scheduleEndMinutes
        return if (start <= end) nowMin in start..end else (nowMin >= start || nowMin <= end)
    }

    private fun buildNotification(): Notification {
        val channelId = "xueban_guard"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(channelId, "学伴时光守护服务", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, channelId)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("学伴时光守护运行中")
            .setContentText("每观看 ${config.watchMinutes} 分钟自动弹出答题")
            .setSmallIcon(R.drawable.ic_study_lock)
            .build()
    }

    override fun onDestroy() {
        isRunning = false
        handler.removeCallbacks(tickRunnable)
        ttsManager.release()
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (config.guardEnabled) {
            try {
                val restartIntent = Intent(applicationContext, GuardForegroundService::class.java).apply {
                    action = ACTION_START
                }
                val pi = PendingIntent.getService(
                    applicationContext, 1002, restartIntent,
                    PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
                )
                val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
                am.setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + 1500L,
                    pi
                )
            } catch (_: Exception) {}
        }
        super.onTaskRemoved(rootIntent)
    }


    override fun onBind(intent: Intent?): IBinder? = null
}
