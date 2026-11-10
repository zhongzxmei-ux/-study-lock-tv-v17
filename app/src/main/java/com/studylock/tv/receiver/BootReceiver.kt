package com.studylock.tv.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.studylock.tv.MainActivity
import com.studylock.tv.config.LockConfig
import com.studylock.tv.service.GuardForegroundService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_LOCKED_BOOT_COMPLETED ||
            action == "android.intent.action.QUICKBOOT_POWERON"
        ) {
            val config = LockConfig(context)
            if (config.autoStartOnBoot) {
                config.applyDefaultGuardSettings()
                config.guardEnabled = true
                config.addLog("电视开机自启：打开学伴时光并按默认守护设置开启守护")
                val svcIntent = Intent(context, GuardForegroundService::class.java).apply {
                    this.action = GuardForegroundService.ACTION_BOOT_LAUNCH
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(svcIntent)
                } else {
                    context.startService(svcIntent)
                }
                val launchIntent = Intent(context, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                context.startActivity(launchIntent)
            }
        }
    }
}
