package com.studylock.tv.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.studylock.tv.R
import com.studylock.tv.audio.TtsAndSoundManager
import com.studylock.tv.config.LockConfig
import com.studylock.tv.data.QuestionRepository
import com.studylock.tv.model.Question

class QuizOverlayManager(
    private val context: Context,
    private val config: LockConfig,
    private val repo: QuestionRepository,
    private val ttsManager: TtsAndSoundManager,
    private val onUnlockWithSeconds: (Int) -> Unit,
    private val onEmergencyDisableGuard: () -> Unit
) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var overlayView: View? = null
    private var currentQuestion: Question? = null
    private var solvedCount = 0
    private val usedIds = mutableSetOf<String>()
    private var waitTimer: CountDownTimer? = null
    private var isLockedByWrongWait = false

    // Continue challenge state
    private var isBonusRound = false
    private var accumulatedSeconds = 0

    // ↑ x 5 emergency unlock tracker
    private var upStreakCount = 0
    private var lastUpTimeMs = 0L

    val isShowing: Boolean
        get() = overlayView != null

    fun showQuiz() {
        mainHandler.post {
            if (overlayView != null) return@post
            isBonusRound = false
            solvedCount = 0
            accumulatedSeconds = config.watchMinutes * 60
            usedIds.clear()
            upStreakCount = 0
            config.addLog("到达观看时长，弹出全屏答题锁")
            if (config.pauseMediaOnQuiz) {
                pauseBackgroundMedia()
            }
            attachOverlay()
            loadNextQuestion()
        }
    }

    // 修复闪烁：仅当悬浮窗意外被系统移除时才重新挂载，不再每2秒 remove+add
    fun ensureAttachedWithoutFlicker() {
        val view = overlayView ?: return
        mainHandler.post {
            if (!view.isAttachedToWindow) {
                try {
                    windowManager.addView(view, createLayoutParams())
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun createLayoutParams(needKeyboard: Boolean = false): WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val flags = if (needKeyboard) {
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_FULLSCREEN or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        } else {
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_FULLSCREEN or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        }
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            flags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }
    }

    private fun attachOverlay() {
        val inflater = LayoutInflater.from(context)
        val view = inflater.inflate(R.layout.overlay_quiz, null)
        view.isFocusableInTouchMode = true
        view.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN) {
                if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                    val now = SystemClock.uptimeMillis()
                    upStreakCount = if (now - lastUpTimeMs < 3000L) upStreakCount + 1 else 1
                    lastUpTimeMs = now
                    if (upStreakCount >= 5) {
                        upStreakCount = 0
                        showEmergencyPinPanel(view)
                        return@setOnKeyListener true
                    }
                } else if (keyCode != KeyEvent.KEYCODE_BACK && keyCode != KeyEvent.KEYCODE_ESCAPE) {
                    upStreakCount = 0
                }
                if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE) {
                    return@setOnKeyListener true
                }
            }
            false
        }
        windowManager.addView(view, createLayoutParams())
        overlayView = view
    }

    private fun showEmergencyPinPanel(view: View) {
        val pinBox = view.findViewById<LinearLayout>(R.id.layoutEmergencyPin)
        pinBox.visibility = View.VISIBLE
        val etPin = view.findViewById<EditText>(R.id.etEmergencyPin)
        etPin.setText("")
        etPin.requestFocus()

        view.findViewById<Button>(R.id.btnConfirmEmergencyPin).setOnClickListener {
            if (etPin.text.toString().trim() == config.parentalPin) {
                config.addLog("通过答题页连续按 ↑ 5次输入密码关闭守护")
                dismissAndClose(disableGuard = true)
            } else {
                Toast.makeText(context, "密码错误", Toast.LENGTH_SHORT).show()
            }
        }
        view.findViewById<Button>(R.id.btnCancelEmergencyPin).setOnClickListener {
            pinBox.visibility = View.GONE
            view.findViewById<Button>(R.id.btnOptA).requestFocus()
        }
    }

    private fun loadNextQuestion() {
        val view = overlayView ?: return
        waitTimer?.cancel()
        isLockedByWrongWait = false

        view.findViewById<LinearLayout>(R.id.layoutQuizCard).visibility = View.VISIBLE
        view.findViewById<LinearLayout>(R.id.layoutContinueChallenge).visibility = View.GONE
        view.findViewById<LinearLayout>(R.id.layoutEmergencyPin).visibility = View.GONE

        val q = repo.pickRandomQuestion(config, usedIds) ?: return
        currentQuestion = q
        usedIds.add(q.id)

        val total = if (isBonusRound) 1 else config.questionsPerRound
        view.findViewById<TextView>(R.id.tvQuizSubtitle).text =
            if (isBonusRound) {
                "加时挑战：答对奖励 +${config.bonusRewardMinutes} 分钟，答错扣除 ${config.bonusPenaltyMinutes} 分钟"
            } else {
                "本轮要答对 $total 道题，才能继续观看 ${config.watchMinutes} 分钟（按 ↑ 5次可输密码解锁）"
            }
        view.findViewById<TextView>(R.id.tvQuestionMeta).text =
            if (isBonusRound) {
                "加时挑战 · ${q.gradeLabel} · ${q.subject}"
            } else {
                "${q.gradeLabel} · ${q.subject} · ${q.volume} · 第 ${solvedCount + 1}/$total 题"
            }

        view.findViewById<TextView>(R.id.tvQuestionPrompt).text = q.prompt

        view.findViewById<TextView>(R.id.tvBannerStatus).visibility = View.GONE
        view.findViewById<LinearLayout>(R.id.layoutExplanation).visibility = View.GONE

        val btnIds = listOf(R.id.btnOptA, R.id.btnOptB, R.id.btnOptC, R.id.btnOptD)
        val labels = listOf("A", "B", "C", "D")
        btnIds.forEachIndexed { index, id ->
            val btn = view.findViewById<Button>(id)
            val optText = q.options.getOrElse(index) { "" }
            btn.text = "${labels[index]}. $optText"
            btn.setBackgroundResource(R.drawable.bg_option_button)
            btn.isEnabled = true
            btn.setOnClickListener { handleAnswer(index) }
        }

        view.findViewById<Button>(R.id.btnReplayQuestion).setOnClickListener {
            ttsManager.speakQuestion(q)
        }
        view.findViewById<Button>(R.id.btnReplayExplain).setOnClickListener {
            ttsManager.speakExplanation(q)
        }

        view.findViewById<Button>(R.id.btnOptA).requestFocus()
        mainHandler.postDelayed({ ttsManager.speakQuestion(q) }, 250)
    }

    private fun handleAnswer(selectedIndex: Int) {
        if (isLockedByWrongWait) return
        val q = currentQuestion ?: return
        val view = overlayView ?: return
        ttsManager.stopSpeaking()

        if (selectedIndex == q.correctIndex) {
            solvedCount++
            val total = if (isBonusRound) 1 else config.questionsPerRound
            ttsManager.playPraise(config.praiseMode, isRoundComplete = solvedCount >= total, isBonus = isBonusRound)
            val banner = view.findViewById<TextView>(R.id.tvBannerStatus)
            banner.visibility = View.VISIBLE
            banner.setBackgroundColor(Color.parseColor("#1B8A4B"))
            if (solvedCount >= total) {
                if (isBonusRound) {
                    accumulatedSeconds += config.bonusRewardMinutes * 60
                    banner.text = "🎉 挑战成功！奖励 +${config.bonusRewardMinutes} 分钟"
                    config.addLog("继续挑战成功，奖励 +${config.bonusRewardMinutes} 分钟")
                    mainHandler.postDelayed({ showContinueChallengeDialog("挑战成功！奖励 +${config.bonusRewardMinutes} 分钟") }, 1200)
                } else {
                    accumulatedSeconds = config.watchMinutes * 60
                    banner.text = "🎉 全部答对，真棒！"
                    config.addLog("本轮 $total 题全部答对，获得 ${config.watchMinutes} 分钟")
                    mainHandler.postDelayed({ showContinueChallengeDialog("本轮答题完成！") }, 1200)
                }
            } else {
                banner.text = "答对啦！还需答对 ${total - solvedCount} 道题"
                mainHandler.postDelayed({ loadNextQuestion() }, 1100)
            }
        } else {
            if (isBonusRound) {
                isLockedByWrongWait = true
                accumulatedSeconds -= config.bonusPenaltyMinutes * 60
                config.addLog("继续挑战失败，扣除 ${config.bonusPenaltyMinutes} 分钟")
                val btnIds = listOf(R.id.btnOptA, R.id.btnOptB, R.id.btnOptC, R.id.btnOptD)
                btnIds.forEachIndexed { idx, id ->
                    val btn = view.findViewById<Button>(id)
                    btn.isEnabled = false
                    if (idx == q.correctIndex) btn.setBackgroundColor(Color.parseColor("#1B8A4B"))
                    else if (idx == selectedIndex) btn.setBackgroundColor(Color.parseColor("#B83232"))
                }
                val explainBox = view.findViewById<LinearLayout>(R.id.layoutExplanation)
                explainBox.visibility = View.VISIBLE
                val labels = listOf("A", "B", "C", "D")
                view.findViewById<TextView>(R.id.tvCorrectAnswer).text =
                    "正确答案：${labels[q.correctIndex]}. ${q.options[q.correctIndex]}"
                view.findViewById<TextView>(R.id.tvExplainBody).text = "解题思路：${q.explanation}"
                if (config.explainAloudOnWrong) ttsManager.speakExplanation(q)
                mainHandler.postDelayed({
                    if (accumulatedSeconds <= 0) {
                        accumulatedSeconds = 0
                        isBonusRound = false
                        solvedCount = 0
                        Toast.makeText(context, "剩余时间已归零，已直接锁定！", Toast.LENGTH_LONG).show()
                        loadNextQuestion()
                    } else {
                        showContinueChallengeDialog("挑战失败，已扣除 ${config.bonusPenaltyMinutes} 分钟")
                    }
                }, 1400)
                return
            }

            isLockedByWrongWait = true
            config.addLog("答错题目：${q.prompt}")
            val btnIds = listOf(R.id.btnOptA, R.id.btnOptB, R.id.btnOptC, R.id.btnOptD)
            btnIds.forEachIndexed { idx, id ->
                val btn = view.findViewById<Button>(id)
                btn.isEnabled = false
                if (idx == q.correctIndex) btn.setBackgroundColor(Color.parseColor("#1B8A4B"))
                else if (idx == selectedIndex) btn.setBackgroundColor(Color.parseColor("#B83232"))
            }

            val explainBox = view.findViewById<LinearLayout>(R.id.layoutExplanation)
            explainBox.visibility = View.VISIBLE
            val labels = listOf("A", "B", "C", "D")
            view.findViewById<TextView>(R.id.tvCorrectAnswer).text =
                "正确答案：${labels[q.correctIndex]}. ${q.options[q.correctIndex]}"
            view.findViewById<TextView>(R.id.tvExplainBody).text = "解题思路：${q.explanation}"

            if (config.explainAloudOnWrong) ttsManager.speakExplanation(q)

            val waitMs = config.wrongWaitSeconds * 1000L
            val tvWait = view.findViewById<TextView>(R.id.tvWaitCountdown)
            if (waitMs <= 0L) {
                tvWait.text = "即将切换下一题..."
                mainHandler.postDelayed({ loadNextQuestion() }, 900)
            } else {
                waitTimer = object : CountDownTimer(waitMs, 1000) {
                    override fun onTick(millisUntilFinished: Long) {
                        val sec = (millisUntilFinished / 1000L) + 1
                        tvWait.text = "⏳ 认真看解析，${sec} 秒后自动换新题"
                    }
                    override fun onFinish() {
                        loadNextQuestion()
                    }
                }.start()
            }
        }
    }

    private fun showContinueChallengeDialog(headline: String) {
        val view = overlayView ?: return
        view.findViewById<LinearLayout>(R.id.layoutQuizCard).visibility = View.GONE
        view.findViewById<LinearLayout>(R.id.layoutExplanation).visibility = View.GONE
        val box = view.findViewById<LinearLayout>(R.id.layoutContinueChallenge)
        box.visibility = View.VISIBLE

        val mins = (accumulatedSeconds / 60).coerceAtLeast(1)
        view.findViewById<TextView>(R.id.tvContinueTitle).text = "$headline  是否继续挑战？"
        view.findViewById<TextView>(R.id.tvContinueSub).text =
            "当前累计可观看：$mins 分钟 ｜ 挑战成功可奖励 ${config.bonusRewardMinutes} 分钟，答题失败扣除 ${config.bonusPenaltyMinutes} 分钟"

        val btnNo = view.findViewById<Button>(R.id.btnContinueNo)
        val btnYes = view.findViewById<Button>(R.id.btnContinueYes)

        btnNo.setOnClickListener {
            dismissAndClose(disableGuard = false)
        }
        btnYes.setOnClickListener {
            isBonusRound = true
            solvedCount = 0
            loadNextQuestion()
        }
        btnNo.requestFocus()
    }

    private fun dismissAndClose(disableGuard: Boolean) {
        waitTimer?.cancel()
        ttsManager.stopSpeaking()
        overlayView?.let {
            try {
                windowManager.removeViewImmediate(it)
            } catch (_: Exception) {
            }
        }
        overlayView = null
        if (disableGuard) {
            onEmergencyDisableGuard()
        } else {
            onUnlockWithSeconds(accumulatedSeconds.coerceAtLeast(60))
        }
    }

    private fun pauseBackgroundMedia() {
        try {
            val now = SystemClock.uptimeMillis()
            val down = KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE, 0)
            val up = KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE, 0)
            audioManager.dispatchMediaKeyEvent(down)
            audioManager.dispatchMediaKeyEvent(up)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    ).build()
                audioManager.requestAudioFocus(req)
            }
        } catch (_: Exception) {
        }
    }
}
