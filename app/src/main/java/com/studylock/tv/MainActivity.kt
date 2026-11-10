package com.studylock.tv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.app.NotificationManager
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.studylock.tv.audio.TtsAndSoundManager
import com.studylock.tv.config.LockConfig
import com.studylock.tv.config.PraiseMode
import com.studylock.tv.data.LanUploadServer
import com.studylock.tv.data.QuestionRepository
import com.studylock.tv.service.GuardForegroundService
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {
    private lateinit var config: LockConfig
    private lateinit var repo: QuestionRepository
    private lateinit var ttsManager: TtsAndSoundManager
    private var lanServer: LanUploadServer? = null

    private val tickReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val sec = intent?.getIntExtra(GuardForegroundService.EXTRA_REMAINING_SEC, 0) ?: return
            val mode = intent.getStringExtra(GuardForegroundService.EXTRA_STATUS_MODE) ?: "RUNNING"
            val min = sec / 60
            val rem = sec % 60
            val txt = when (mode) {
                "TEMP_PAUSE" -> "临时停用中 · 到点自动恢复"
                "OUTSIDE_SCHEDULE" -> "当前不在设定守护时段内"
                else -> String.format("%02d:%02d 后将弹出答题", min, rem)
            }
            findViewById<TextView>(R.id.tvStatusHeadline).text = txt
            findViewById<TextView>(R.id.tvHomeCountdown).text = txt
            refreshStatusPills()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        config = LockConfig(this)
        repo = QuestionRepository(this)
        ttsManager = TtsAndSoundManager(this)

        bindActions()
        refreshUi()
        refreshPermissionWizard()
        findViewById<Button>(R.id.btnHomeToggleGuard)?.text = if (config.guardEnabled) "暂停守护" else "开启守护模式"
        findViewById<TextView>(R.id.tvHomeSummary)?.text = "每轮 ${config.watchMinutes} 分钟 · 每次 ${config.questionsPerRound} 题 · 答错等待 ${config.wrongWaitSeconds} 秒"

        startHomeClockTicker()
        if (!config.pinInitialized) {
            promptMandatoryFirstInstallPin()
        } else if (!hasAllCorePermissions()) {
            showPermissionWizard()
        }
    }

    private var autoPermSequenceActive = false
    private var batteryStepAttempted = false

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter(GuardForegroundService.BROADCAST_TICK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(tickReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(tickReceiver, filter)
        }
        refreshUi()
        if (findViewById<View>(R.id.layoutPermissionWizard).visibility == View.VISIBLE) {
            refreshPermissionWizard()
            if (autoPermSequenceActive) {
                Handler(Looper.getMainLooper()).postDelayed({ advanceAutoPermissionSequence() }, 350L)
            }
        }
    }

    private fun advanceAutoPermissionSequence() {
        val overlayOk = Settings.canDrawOverlays(this)
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val batteryOk = pm.isIgnoringBatteryOptimizations(packageName)
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notifyOk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) nm.areNotificationsEnabled() else true

        when {
            !overlayOk -> {
                Toast.makeText(this, "第 1 步：请开启「学伴时光」悬浮窗权限，开启后按返回键自动继续", Toast.LENGTH_LONG).show()
                findViewById<Button>(R.id.btnPermOverlay).performClick()
            }
            !batteryOk && !batteryStepAttempted -> {
                batteryStepAttempted = true
                Toast.makeText(this, "第 2 步：请允许忽略电池优化（若电视无此项请按返回继续）", Toast.LENGTH_LONG).show()
                findViewById<Button>(R.id.btnPermBattery).performClick()
            }
            !notifyOk -> {
                Toast.makeText(this, "第 3 步：请允许通知权限，开启后按返回键自动继续", Toast.LENGTH_LONG).show()
                findViewById<Button>(R.id.btnPermNotify).performClick()
            }
            else -> {
                autoPermSequenceActive = false
                Toast.makeText(this, "🎉 基础权限已全部开启！若为小米/海信电视，请在下方第4项确认自启动", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        try { unregisterReceiver(tickReceiver) } catch (_: Exception) {}
    }

    private fun showFourDigitPinDialog(
        title: String,
        stepDesc: String,
        cancelable: Boolean,
        onPinEntered: (pin: String, errorSetter: (String) -> Unit, dismiss: () -> Unit) -> Unit
    ) {
        val view = layoutInflater.inflate(R.layout.dialog_pin_pad, null)
        val tvTitle = view.findViewById<TextView>(R.id.tvPinDialogTitle)
        val tvDesc = view.findViewById<TextView>(R.id.tvPinStepDesc)
        val tvErr = view.findViewById<TextView>(R.id.tvPinError)
        val btnConfirm = view.findViewById<Button>(R.id.btnPinConfirm)
        val btnCancel = view.findViewById<Button>(R.id.btnPinCancel)
        val slots = listOf(
            view.findViewById<View>(R.id.pinSlot0),
            view.findViewById<View>(R.id.pinSlot1),
            view.findViewById<View>(R.id.pinSlot2),
            view.findViewById<View>(R.id.pinSlot3)
        )
        val dots = listOf(
            view.findViewById<View>(R.id.pinDot0),
            view.findViewById<View>(R.id.pinDot1),
            view.findViewById<View>(R.id.pinDot2),
            view.findViewById<View>(R.id.pinDot3)
        )

        tvTitle.text = "● $title"
        tvDesc.text = stepDesc
        btnCancel.visibility = if (cancelable) View.VISIBLE else View.GONE

        var buffer = ""
        fun refreshSlots() {
            for (i in 0..3) {
                dots[i].visibility = if (i < buffer.length) View.VISIBLE else View.GONE
                slots[i].setBackgroundResource(
                    if (i == buffer.length.coerceAtMost(3)) R.drawable.bg_pin_slot_active else R.drawable.bg_pin_slot
                )
            }
            btnConfirm.isEnabled = buffer.length == 4
            btnConfirm.alpha = if (buffer.length == 4) 1.0f else 0.5f
        }
        refreshSlots()

        val dialog = AlertDialog.Builder(this)
            .setView(view)
            .setCancelable(cancelable)
            .create()

        val numIds = listOf(
            R.id.btnPin0 to "0", R.id.btnPin1 to "1", R.id.btnPin2 to "2",
            R.id.btnPin3 to "3", R.id.btnPin4 to "4", R.id.btnPin5 to "5",
            R.id.btnPin6 to "6", R.id.btnPin7 to "7", R.id.btnPin8 to "8", R.id.btnPin9 to "9"
        )
        numIds.forEach { (id, digit) ->
            view.findViewById<Button>(id).setOnClickListener {
                if (buffer.length < 4) {
                    tvErr.text = ""
                    buffer += digit
                    refreshSlots()
                }
            }
        }
        view.findViewById<Button>(R.id.btnPinClear).setOnClickListener {
            tvErr.text = ""
            buffer = ""
            refreshSlots()
        }
        view.findViewById<Button>(R.id.btnPinBack).setOnClickListener {
            tvErr.text = ""
            if (buffer.isNotEmpty()) buffer = buffer.dropLast(1)
            refreshSlots()
        }
        btnCancel.setOnClickListener { dialog.dismiss() }
        btnConfirm.setOnClickListener {
            if (buffer.length == 4) {
                onPinEntered(buffer, { errMsg ->
                    tvErr.text = errMsg
                    buffer = ""
                    refreshSlots()
                }, { dialog.dismiss() })
            }
        }
        dialog.show()
    }

    private fun promptMandatoryFirstInstallPin() {
        showFourDigitPinDialog(
            title = "首次安装 · 设置 4 位家长数字密码",
            stepDesc = "第 1 步 / 共 2 步：请设置 4 位家长密码，防止孩子自行退出守护",
            cancelable = false
        ) { firstPin, _, dismiss1 ->
            dismiss1()
            showFourDigitPinDialog(
                title = "首次安装 · 确认 4 位家长数字密码",
                stepDesc = "第 2 步 / 共 2 步：请再次输入刚才设置的 4 位密码",
                cancelable = false
            ) { confirmPin, _, dismiss2 ->
                if (firstPin != confirmPin) {
                    dismiss2()
                    Toast.makeText(this, "两次输入的密码不一致，请重新设置", Toast.LENGTH_SHORT).show()
                    promptMandatoryFirstInstallPin()
                } else {
                    config.parentalPin = firstPin
                    config.pinInitialized = true
                    config.addLog("完成首次安装 4 位家长密码设置")
                    dismiss2()
                    Toast.makeText(this, "4 位家长密码设置成功", Toast.LENGTH_SHORT).show()
                    showPermissionWizard()
                }
            }
        }
    }

    private fun promptPinIfGuardEnabled() {
        if (!config.guardEnabled) return
        val input = EditText(this).apply { hint = "输入家长数字密码"; inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD }
        AlertDialog.Builder(this)
            .setTitle("家长验证")
            .setMessage("守护运行中，请输入家长密码进入控制台")
            .setView(input)
            .setCancelable(false)
            .setPositiveButton("确认") { _, _ ->
                if (input.text.toString().trim() != config.parentalPin) {
                    Toast.makeText(this, "密码错误，已退出", Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
            .setNegativeButton("退出") { _, _ -> finish() }
            .show()
    }

    private fun hasAllCorePermissions(): Boolean {
        val overlayOk = Settings.canDrawOverlays(this)
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val batteryOk = pm.isIgnoringBatteryOptimizations(packageName)
        return overlayOk && batteryOk
    }

    private fun startHomeClockTicker() {
        val h = Handler(Looper.getMainLooper())
        val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
        val r = object : Runnable {
            override fun run() {
                findViewById<TextView>(R.id.tvHomeClock)?.text = fmt.format(Date())
                h.postDelayed(this, 10000L)
            }
        }
        r.run()
    }

    private fun showHomeScreen() {
        findViewById<View>(R.id.layoutHomeScreen).visibility = View.VISIBLE
        findViewById<View>(R.id.layoutSettingsScreen).visibility = View.GONE
        findViewById<View>(R.id.layoutPermissionWizard).visibility = View.GONE
        findViewById<Button>(R.id.btnHomeToggleGuard).requestFocus()
    }

    private fun showSettingsScreenWithPin() {
        if (!config.pinInitialized) {
            findViewById<View>(R.id.layoutHomeScreen).visibility = View.GONE
            findViewById<View>(R.id.layoutSettingsScreen).visibility = View.VISIBLE
            findViewById<Button>(R.id.btnBackToHome).requestFocus()
            return
        }
        showFourDigitPinDialog(
            title = "家长密码验证",
            stepDesc = "请输入 4 位家长数字密码进入控制台",
            cancelable = true
        ) { enteredPin, setErr, dismiss ->
            if (enteredPin == config.parentalPin) {
                dismiss()
                findViewById<View>(R.id.layoutHomeScreen).visibility = View.GONE
                findViewById<View>(R.id.layoutSettingsScreen).visibility = View.VISIBLE
                findViewById<Button>(R.id.btnBackToHome).requestFocus()
            } else {
                setErr("密码错误，请重新输入")
            }
        }
    }

    private fun showPermissionWizard() {
        findViewById<View>(R.id.layoutPermissionWizard).visibility = View.VISIBLE
        refreshPermissionWizard()
        findViewById<Button>(R.id.btnPermOverlay).requestFocus()
    }

    private fun refreshPermissionWizard() {
        val overlayOk = Settings.canDrawOverlays(this)
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val batteryOk = pm.isIgnoringBatteryOptimizations(packageName)
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notifyOk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) nm.areNotificationsEnabled() else true

        findViewById<TextView>(R.id.tvPermOverlayStatus).text =
            if (overlayOk) "✅ 已开启 · 允许在其他应用上层全屏弹题" else "❌ 未开启 · 必须开启，否则在桌面/看电视时无法弹题"
        findViewById<Button>(R.id.btnPermOverlay).text = if (overlayOk) "✅ 已开启" else "去开启"

        findViewById<TextView>(R.id.tvPermBatteryStatus).text =
            if (batteryOk) "✅ 已开启 · 已加入电池优化白名单" else "❌ 未开启 · 必须开启，防止回到桌面后被系统杀后台"
        findViewById<Button>(R.id.btnPermBattery).text = if (batteryOk) "✅ 已开启" else "去开启"

        findViewById<TextView>(R.id.tvPermNotifyStatus).text =
            if (notifyOk) "✅ 已开启 · 前台守护服务稳定保活" else "❌ 未开启 · 建议开启以保持后台服务存活"
        findViewById<Button>(R.id.btnPermNotify).text = if (notifyOk) "✅ 已开启" else "去开启"

        val readyCount = listOf(overlayOk, batteryOk, notifyOk).count { it }
        findViewById<TextView>(R.id.tvPermWizardSummary).text =
            "基础权限检测：$readyCount / 3 项已开启（另请在下方第4项确认电视「后台弹出界面」与「自启动」）"
    }

    private fun bindActions() {
        findViewById<Button>(R.id.btnHomeToggleGuard).setOnClickListener {
            if (!config.guardEnabled) {
                findViewById<Button>(R.id.btnToggleGuard).performClick()
            } else {
                showFourDigitPinDialog(
                    title = "家长密码验证",
                    stepDesc = "守护运行中，请输入 4 位家长密码以暂停守护",
                    cancelable = true
                ) { enteredPin, setErr, dismiss ->
                    if (enteredPin == config.parentalPin) {
                        dismiss()
                        findViewById<Button>(R.id.btnToggleGuard).performClick()
                    } else {
                        setErr("密码错误，无法暂停守护")
                    }
                }
            }
        }
        findViewById<Button>(R.id.btnHomeTestQuiz).setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "请先开启悬浮窗权限", Toast.LENGTH_SHORT).show()
                showPermissionWizard()
            } else {
                val svc = Intent(this, GuardForegroundService::class.java).apply {
                    action = GuardForegroundService.ACTION_TEST_NOW
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(svc) else startService(svc)
            }
        }
        findViewById<Button>(R.id.btnHomePerms).setOnClickListener { showPermissionWizard() }
        findViewById<Button>(R.id.btnPermOneClickAuto)?.setOnClickListener {
            batteryStepAttempted = false
            autoPermSequenceActive = true
            advanceAutoPermissionSequence()
        }
        findViewById<Button>(R.id.btnHomeOpenSettings).setOnClickListener { showSettingsScreenWithPin() }
        findViewById<Button>(R.id.btnBackToHome).setOnClickListener { showHomeScreen() }
        findViewById<Button>(R.id.btnSaveAsDefault)?.setOnClickListener {
            config.saveAsDefaultGuardSettings()
            config.addLog("已将当前守护设置保存为开机默认配置")
            Toast.makeText(this, "💾 已保存为开机默认守护设置", Toast.LENGTH_SHORT).show()
        }


        findViewById<Button>(R.id.btnPermOverlay).setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }
        }
        findViewById<Button>(R.id.btnPermBattery).setOnClickListener {
            val reqIntent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            val listIntent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            val appInfoIntent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
            try {
                when {
                    reqIntent.resolveActivity(packageManager) != null -> startActivity(reqIntent)
                    listIntent.resolveActivity(packageManager) != null -> startActivity(listIntent)
                    else -> {
                        Toast.makeText(this, "请在「应用详情 → 省电策略」中设为无限制", Toast.LENGTH_LONG).show()
                        startActivity(appInfoIntent)
                    }
                }
            } catch (_: Exception) {
                try {
                    Toast.makeText(this, "请在「应用详情 → 省电策略」中设为无限制", Toast.LENGTH_LONG).show()
                    startActivity(appInfoIntent)
                } catch (_: Exception) {
                    Toast.makeText(this, "当前电视系统未开放电池白名单页面，可跳过此项", Toast.LENGTH_SHORT).show()
                }
            }
        }
        findViewById<Button>(R.id.btnPermNotify).setOnClickListener {
            try {
                val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                } else {
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
                }
                startActivity(intent)
            } catch (_: Exception) {}
        }
        findViewById<Button>(R.id.btnPermAppInfo).setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            } catch (_: Exception) {}
        }
        findViewById<Button>(R.id.btnPermTestTts).setOnClickListener {
            val ok = ttsManager.isAvailable()
            ttsManager.speakText("学伴时光语音检测正常，小朋友准备好答题了吗？")
            Toast.makeText(
                this,
                if (ok) "正在播报中文测试语音" else "未检测到系统中文TTS引擎，已触发提示音兜底，请在电视设置中检查文字转语音",
                Toast.LENGTH_LONG
            ).show()
        }
        findViewById<Button>(R.id.btnPermDone).setOnClickListener {
            showHomeScreen()
        }
        findViewById<Button>(R.id.btnToggleGuard).setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "请先授予「显示在其他应用上层」权限", Toast.LENGTH_LONG).show()
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                return@setOnClickListener
            }
            val next = !config.guardEnabled
            config.guardEnabled = next
            val svc = Intent(this, GuardForegroundService::class.java).apply {
                action = if (next) GuardForegroundService.ACTION_START else GuardForegroundService.ACTION_STOP
            }
            if (next) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(svc) else startService(svc)
            } else {
                startService(svc)
            }
            refreshUi()
        }

        mapOf(R.id.btnDur15 to 15, R.id.btnDur20 to 20, R.id.btnDur30 to 30, R.id.btnDur45 to 45, R.id.btnDur60 to 60).forEach { (id, mins) ->
            findViewById<Button>(id).setOnClickListener { config.watchMinutes = mins; refreshUi() }
        }
        findViewById<Button>(R.id.btnDurMinus).setOnClickListener { config.watchMinutes -= 1; refreshUi() }
        findViewById<Button>(R.id.btnDurPlus).setOnClickListener { config.watchMinutes += 1; refreshUi() }

        mapOf(R.id.btnQ1 to 1, R.id.btnQ2 to 2, R.id.btnQ3 to 3, R.id.btnQ5 to 5).forEach { (id, cnt) ->
            findViewById<Button>(id).setOnClickListener { config.questionsPerRound = cnt; refreshUi() }
        }
        findViewById<Button>(R.id.btnQMinus).setOnClickListener { config.questionsPerRound -= 1; refreshUi() }
        findViewById<Button>(R.id.btnQPlus).setOnClickListener { config.questionsPerRound += 1; refreshUi() }

        mapOf(R.id.btnWait0 to 0, R.id.btnWait30 to 30, R.id.btnWait60 to 60, R.id.btnWait90 to 90, R.id.btnWait120 to 120).forEach { (id, sec) ->
            findViewById<Button>(id).setOnClickListener { config.wrongWaitSeconds = sec; refreshUi() }
        }
        findViewById<Button>(R.id.btnWaitMinus).setOnClickListener { config.wrongWaitSeconds -= 5; refreshUi() }
        findViewById<Button>(R.id.btnWaitPlus).setOnClickListener { config.wrongWaitSeconds += 5; refreshUi() }

        findViewById<Button>(R.id.btnBonusRewardMinus).setOnClickListener { config.bonusRewardMinutes -= 1; refreshUi() }
        findViewById<Button>(R.id.btnBonusRewardPlus).setOnClickListener { config.bonusRewardMinutes += 1; refreshUi() }
        findViewById<Button>(R.id.btnBonusPenaltyMinus).setOnClickListener { config.bonusPenaltyMinutes -= 1; refreshUi() }
        findViewById<Button>(R.id.btnBonusPenaltyPlus).setOnClickListener { config.bonusPenaltyMinutes += 1; refreshUi() }

        findViewById<Button>(R.id.btnPraiseOff).setOnClickListener { config.praiseMode = PraiseMode.OFF; refreshUi() }
        findViewById<Button>(R.id.btnPraiseSfx).setOnClickListener { config.praiseMode = PraiseMode.SFX_ONLY; refreshUi() }
        findViewById<Button>(R.id.btnPraiseVoice).setOnClickListener { config.praiseMode = PraiseMode.SFX_AND_VOICE; refreshUi() }
        findViewById<Button>(R.id.btnTestPraise).setOnClickListener { ttsManager.playPraise(PraiseMode.SFX_AND_VOICE) }

        findViewById<Button>(R.id.btnToggleBoot).setOnClickListener { config.autoStartOnBoot = !config.autoStartOnBoot; refreshUi() }
        findViewById<Button>(R.id.btnTogglePauseMedia).setOnClickListener { config.pauseMediaOnQuiz = !config.pauseMediaOnQuiz; refreshUi() }
        findViewById<Button>(R.id.btnToggleStrict).setOnClickListener { config.strictMode = !config.strictMode; refreshUi() }
        findViewById<Button>(R.id.btnToggleExplainAloud).setOnClickListener { config.explainAloudOnWrong = !config.explainAloudOnWrong; refreshUi() }
        findViewById<Button>(R.id.btnTestExplain).setOnClickListener { ttsManager.speakText("差一点点就对啦，没关系，我们一起来看看：正确答案是 C，下。上和下是一对反义词。") }

        findViewById<Button>(R.id.btnScheduleToggle).setOnClickListener { config.scheduleAllDay = !config.scheduleAllDay; refreshUi() }
        findViewById<Button>(R.id.btnStartMinus).setOnClickListener { config.scheduleStartMinutes -= 30; refreshUi() }
        findViewById<Button>(R.id.btnStartPlus).setOnClickListener { config.scheduleStartMinutes += 30; refreshUi() }
        findViewById<Button>(R.id.btnEndMinus).setOnClickListener { config.scheduleEndMinutes -= 30; refreshUi() }
        findViewById<Button>(R.id.btnEndPlus).setOnClickListener { config.scheduleEndMinutes += 30; refreshUi() }

        mapOf(R.id.btnTemp15 to 15, R.id.btnTemp30 to 30, R.id.btnTemp60 to 60).forEach { (id, mins) ->
            findViewById<Button>(id).setOnClickListener {
                config.tempPauseUntilMillis = System.currentTimeMillis() + mins * 60_000L
                config.addLog("临时停用 $mins 分钟")
                refreshUi()
            }
        }
        findViewById<Button>(R.id.btnTempResume).setOnClickListener { config.tempPauseUntilMillis = 0L; refreshUi() }

        mapOf(
            R.id.btnSubjPinyin to "拼音题", R.id.btnSubjCn to "语文", R.id.btnSubjMath to "数学",
            R.id.btnSubjEn to "英语", R.id.btnSubjPhy to "物理", R.id.btnSubjGeo to "地理",
            R.id.btnSubjHis to "历史", R.id.btnSubjBio to "生物"
        ).forEach { (id, subj) ->
            findViewById<Button>(id).setOnClickListener {
                val cur = config.enabledSubjects.toMutableSet()
                if (subj in cur) {
                    if (cur.size <= 1) { Toast.makeText(this, "至少保留一门科目", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
                    cur.remove(subj)
                } else cur.add(subj)
                config.enabledSubjects = cur
                refreshUi()
            }
        }

        findViewById<Button>(R.id.btnTestQuizNow).setOnClickListener {
            if (!Settings.canDrawOverlays(this)) { Toast.makeText(this, "请先授予悬浮窗权限", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            val svc = Intent(this, GuardForegroundService::class.java).apply { action = GuardForegroundService.ACTION_TEST_NOW }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(svc) else startService(svc)
        }

        findViewById<Button>(R.id.btnResetTimer).setOnClickListener {
            startService(Intent(this, GuardForegroundService::class.java).apply { action = GuardForegroundService.ACTION_RESET_TIMER })
            Toast.makeText(this, "计时已重置为 ${config.watchMinutes} 分钟", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btnQuestionBank).setOnClickListener { openQuestionBankDialog() }
        findViewById<Button>(R.id.btnLanUpload).setOnClickListener { openLanUploadDialog() }
        findViewById<Button>(R.id.btnChangePin).setOnClickListener { openChangePinDialog() }
        findViewById<Button>(R.id.btnBatterySetup).setOnClickListener { requestBatteryWhitelist() }
        findViewById<Button>(R.id.btnViewLogs).setOnClickListener { openLogsDialog() }
        findViewById<Button>(R.id.btnUsageGuide).setOnClickListener { openGuideDialog() }
    }

    private fun openQuestionBankDialog() {
        val grades = (1..9).map { "${it}年级" }.toTypedArray()
        val curGrades = config.enabledGrades
        val checked = BooleanArray(9) { (it + 1).toString() in curGrades }

        AlertDialog.Builder(this)
            .setTitle("人教版题库管理（共 ${repo.getTotalCount()} 题 · 勾选年级）")
            .setMultiChoiceItems(grades, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton("保存") { _, _ ->
                val selected = (0 until 9).filter { checked[it] }.map { (it + 1).toString() }.toSet()
                if (selected.isNotEmpty()) config.enabledGrades = selected
                refreshUi()
            }
            .setNeutralButton("册别 / URL导入") { _, _ -> openVolumeAndUrlDialog() }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun openVolumeAndUrlDialog() {
        val volumes = arrayOf("上册", "下册", "通用", "全册")
        val curVol = config.enabledVolumes
        val checked = BooleanArray(volumes.size) { volumes[it] in curVol }

        AlertDialog.Builder(this)
            .setTitle("选择册别或在线导入")
            .setMultiChoiceItems(volumes, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton("保存册别") { _, _ ->
                val sel = volumes.indices.filter { checked[it] }.map { volumes[it] }.toSet()
                if (sel.isNotEmpty()) config.enabledVolumes = sel
                refreshUi()
            }
            .setNeutralButton("URL / U盘导入") { _, _ -> openUrlImportDialog() }
            .show()
    }

    private fun openUrlImportDialog() {
        val input = EditText(this).apply { hint = "输入 JSON 题库直链（留空则扫描 U 盘 Download）" }
        AlertDialog.Builder(this)
            .setTitle("导入人教版扩展题库")
            .setView(input)
            .setPositiveButton("确定") { _, _ ->
                val url = input.text.toString().trim()
                if (url.isEmpty()) {
                    repo.reload(); refreshUi()
                    Toast.makeText(this, "已扫描本地题库，共 ${repo.getTotalCount()} 题", Toast.LENGTH_SHORT).show()
                } else {
                    thread {
                        val res = repo.importFromUrl(url)
                        runOnUiThread {
                            res.onSuccess { cnt -> refreshUi(); Toast.makeText(this, "成功导入 $cnt 道题", Toast.LENGTH_LONG).show() }
                                .onFailure { err -> Toast.makeText(this, "导入失败：${err.message}", Toast.LENGTH_LONG).show() }
                        }
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun openLanUploadDialog() {
        lanServer = LanUploadServer(repo) { cnt ->
            runOnUiThread {
                refreshUi()
                Toast.makeText(this, "收到局域网上传的 $cnt 道题！", Toast.LENGTH_LONG).show()
            }
        }
        val url = lanServer?.start(8899) ?: "http://127.0.0.1:8899"
        AlertDialog.Builder(this)
            .setTitle("手机/电脑局域网上传题库")
            .setMessage("请用手机或电脑浏览器（需与小米电视在同一 Wi-Fi 下）打开：\n\n$url\n\n选择 convert_pep_questions.py 生成的 JSON 文件即可一键发送到电视。")
            .setPositiveButton("完成") { _, _ -> lanServer?.stop(); lanServer = null }
            .setOnDismissListener { lanServer?.stop(); lanServer = null }
            .show()
    }

    private fun openChangePinDialog() {
        showFourDigitPinDialog(
            title = "修改 4 位家长数字密码",
            stepDesc = "请输入新的 4 位数字密码",
            cancelable = true
        ) { newPin, _, dismiss1 ->
            dismiss1()
            showFourDigitPinDialog(
                title = "确认新 4 位家长数字密码",
                stepDesc = "请再次输入新的 4 位密码以完成修改",
                cancelable = true
            ) { confirmPin, _, dismiss2 ->
                if (newPin == confirmPin) {
                    config.parentalPin = newPin
                    config.pinInitialized = true
                    config.addLog("已更新 4 位家长数字密码")
                    dismiss2()
                    Toast.makeText(this, "4 位家长密码已更新", Toast.LENGTH_SHORT).show()
                } else {
                    dismiss2()
                    Toast.makeText(this, "两次新密码不一致", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun requestBatteryWhitelist() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply { data = Uri.parse("package:$packageName") })
            }
        } catch (_: Exception) {
            Toast.makeText(this, "请在小米电视「电视管家 → 应用管理」中开启自启动与省电无限制", Toast.LENGTH_LONG).show()
        }
    }

    private fun openLogsDialog() {
        val logs = config.getLogs()
        AlertDialog.Builder(this)
            .setTitle("运行记录")
            .setMessage(if (logs.isEmpty()) "暂无记录" else logs.joinToString("\n"))
            .setPositiveButton("关闭", null)
            .show()
    }

    private fun openGuideDialog() {
        AlertDialog.Builder(this)
            .setTitle("学伴时光 · 小米电视使用须知")
            .setMessage(
                "1. 请授予「悬浮窗权限」并在小米电视「电视管家」开启自启动与省电无限制。\n" +
                    "2. 开机自动启动开启后，每次电视开机都会按关机前规则自动恢复守护。\n" +
                    "3. 答题页面连续按遥控器方向键 ↑ 5次可弹出密码框紧急关闭守护。"
            )
            .setPositiveButton("知道了", null)
            .show()
    }

    private fun formatMin(totalMin: Int): String = String.format("%02d:%02d", (totalMin / 60) % 24, totalMin % 60)

    private fun refreshStatusPills() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val inWhitelist = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || pm.isIgnoringBatteryOptimizations(packageName)
        val gText = if (config.guardEnabled) "守护 已开启" else "守护 已关闭"
        val sText = if (GuardForegroundService.isRunning) "后台服务 运行中" else "后台服务 未运行"
        val bText = if (inWhitelist) "电池白名单 已加入" else "未加电池白名单"
        findViewById<TextView>(R.id.tvStatusPills).text = "$gText  ·  $sText  ·  $bText"
    }

    private fun refreshUi() {
        findViewById<TextView>(R.id.tvHeaderBadge).text = "可用 ${repo.getMatchingCount(config)}/${repo.getTotalCount()} 题 · 学伴时光 TV"
        val enabled = config.guardEnabled
        findViewById<Button>(R.id.btnToggleGuard).text = if (enabled) "关闭守护" else "开启守护"
        findViewById<TextView>(R.id.tvGuardSub).text = if (enabled) "守护已开启 · 到点自动全屏弹题" else "守护已关闭 · 点击右侧按钮开启"
        if (!enabled) findViewById<TextView>(R.id.tvStatusHeadline).text = "守护未开启"
        refreshStatusPills()

        findViewById<TextView>(R.id.tvCustomDuration).text = "${config.watchMinutes} 分钟"
        findViewById<TextView>(R.id.tvCustomQ).text = "${config.questionsPerRound} 道"
        findViewById<TextView>(R.id.tvCustomWait).text = "${config.wrongWaitSeconds} 秒"
        findViewById<TextView>(R.id.tvBonusReward).text = "+${config.bonusRewardMinutes} 分钟"
        findViewById<TextView>(R.id.tvBonusPenalty).text = "-${config.bonusPenaltyMinutes} 分钟"
        findViewById<TextView>(R.id.tvCurrentSummary).text = "每轮 ${config.watchMinutes} 分钟 · 每次 ${config.questionsPerRound} 题 · 答错等待 ${config.wrongWaitSeconds} 秒"

        findViewById<Button>(R.id.btnToggleBoot).text = "开机自启：" + if (config.autoStartOnBoot) "开启" else "关闭"
        findViewById<Button>(R.id.btnTogglePauseMedia).text = "答题暂停播放：" + if (config.pauseMediaOnQuiz) "开启" else "关闭"
        findViewById<Button>(R.id.btnToggleStrict).text = "防闪烁严格锁：" + if (config.strictMode) "开启" else "关闭"
        findViewById<Button>(R.id.btnToggleExplainAloud).text = "答错语音讲解：" + if (config.explainAloudOnWrong) "开启" else "关闭"

        findViewById<Button>(R.id.btnScheduleToggle).text = if (config.scheduleAllDay) "当前：全天生效" else "当前：按时段生效"
        findViewById<TextView>(R.id.tvScheduleWindow).text = "生效时段：${formatMin(config.scheduleStartMinutes)} ~ ${formatMin(config.scheduleEndMinutes)}"

        val tempMin = ((config.tempPauseUntilMillis - System.currentTimeMillis()) / 60_000L).toInt()
        findViewById<TextView>(R.id.tvTempPauseStatus).text = if (tempMin > 0) "正在临时停用，约 ${tempMin + 1} 分钟后恢复" else "当前未临时停用"

        val subjs = config.enabledSubjects
        findViewById<Button>(R.id.btnSubjPinyin).text = (if ("拼音题" in subjs) "✓ " else "") + "拼音题"
        findViewById<Button>(R.id.btnSubjCn).text = (if ("语文" in subjs) "✓ " else "") + "语文"
        findViewById<Button>(R.id.btnSubjMath).text = (if ("数学" in subjs) "✓ " else "") + "数学"
        findViewById<Button>(R.id.btnSubjEn).text = (if ("英语" in subjs) "✓ " else "") + "英语"
        findViewById<Button>(R.id.btnSubjPhy).text = (if ("物理" in subjs) "✓ " else "") + "物理"
        findViewById<Button>(R.id.btnSubjGeo).text = (if ("地理" in subjs) "✓ " else "") + "地理"
        findViewById<Button>(R.id.btnSubjHis).text = (if ("历史" in subjs) "✓ " else "") + "历史"
        findViewById<Button>(R.id.btnSubjBio).text = (if ("生物" in subjs) "✓ " else "") + "生物"
    }

    override fun onDestroy() {
        lanServer?.stop()
        ttsManager.release()
        super.onDestroy()
    }

    private val upKeyTimestamps = mutableListOf<Long>()

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
            val now = System.currentTimeMillis()
            upKeyTimestamps.removeAll { now - it > 2000L }
            upKeyTimestamps.add(now)
            if (upKeyTimestamps.size >= 5) {
                upKeyTimestamps.clear()
                showSettingsScreenWithPin()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

}
