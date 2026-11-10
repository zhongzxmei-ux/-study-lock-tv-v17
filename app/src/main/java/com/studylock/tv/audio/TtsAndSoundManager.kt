package com.studylock.tv.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import com.studylock.tv.R
import com.studylock.tv.config.PraiseMode
import com.studylock.tv.model.Question
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.Executors

class TtsAndSoundManager(context: Context) : TextToSpeech.OnInitListener {
    private val appContext = context.applicationContext
    private var tts: TextToSpeech? = TextToSpeech(appContext, this)
    private var ttsReady = false
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val ioExecutor = Executors.newSingleThreadExecutor()
    private val cacheDir = File(appContext.cacheDir, "tts_cache").apply { mkdirs() }
    private var mediaPlayer: MediaPlayer? = null

    private val singlePraiseLines = listOf(
        "哇，答对啦！你的小脑瓜转得真快呀！",
        "太厉害了，一下子就选对啦！",
        "完全正确，这题可难不倒你呢！",
        "真棒呀，思路特别清楚，给你点个赞！",
        "答对咯！我就知道你肯定可以的！"
    )

    private val roundCompletePraiseLines = listOf(
        "闯关成功！刚才特别认真，现在可以开心看电视啦！",
        "太棒了，全部答对！快去接着看吧，记得坐端正哦！",
        "挑战完成！你真是个爱动脑筋的小朋友！"
    )

    private val bonusPraiseLines = listOf(
        "太强啦！成功赢取额外观看时间，真是个小机灵鬼！",
        "哇塞，加时挑战也难不倒你，太厉害了！"
    )

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            val candidates = listOf(Locale.CHINA, Locale.SIMPLIFIED_CHINESE, Locale.CHINESE, Locale.getDefault())
            var ok = false
            for (loc in candidates) {
                val res = tts?.setLanguage(loc)
                if (res != null && res != TextToSpeech.LANG_MISSING_DATA && res != TextToSpeech.LANG_NOT_SUPPORTED) {
                    ok = true
                    break
                }
            }
            ttsReady = ok
            tts?.setPitch(1.06f)
            tts?.setSpeechRate(0.96f)
        } else {
            ttsReady = false
        }
    }

    fun isAvailable(): Boolean = true

    fun speakText(text: String, fallbackRawRes: Int = R.raw.voice_question) {
        if (ttsReady) {
            val params = Bundle().apply {
                putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC)
                putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
            }
            val ret = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, "tts_${System.currentTimeMillis()}")
            if (ret == TextToSpeech.SUCCESS) return
        }
        // 电视缺少系统中文 TTS 时，自动走程序内置缓存播报 + 离线音频兜底
        speakViaBuiltInCache(text, fallbackRawRes)
    }

    private fun speakViaBuiltInCache(text: String, fallbackRawRes: Int) {
        val key = md5(text)
        val cachedFile = File(cacheDir, "$key.mp3")
        if (cachedFile.exists() && cachedFile.length() > 512L) {
            playAudioFile(cachedFile)
            return
        }
        ioExecutor.execute {
            val downloaded = downloadChineseTtsToFile(text, cachedFile)
            if (downloaded) {
                playAudioFile(cachedFile)
            } else {
                playRawAsset(fallbackRawRes)
            }
        }
    }

    private fun downloadChineseTtsToFile(text: String, target: File): Boolean {
        return try {
            val encoded = URLEncoder.encode(text.take(180), "UTF-8")
            val url = URL("https://translate.google.com/translate_tts?ie=UTF-8&tl=zh-CN&client=tw-ob&q=$encoded")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 4500
                readTimeout = 4500
                setRequestProperty("User-Agent", "Mozilla/5.0")
            }
            if (conn.responseCode == 200) {
                conn.inputStream.use { input ->
                    FileOutputStream(target).use { output ->
                        input.copyTo(output)
                    }
                }
                target.length() > 512L
            } else {
                false
            }
        } catch (_: Exception) {
            false
        }
    }

    @Synchronized
    private fun playAudioFile(file: File) {
        try {
            mediaPlayer?.release()
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                setDataSource(file.absolutePath)
                setOnCompletionListener { mp -> mp.release() }
                prepare()
                start()
            }
        } catch (_: Exception) {
            playRawAsset(R.raw.voice_question)
        }
    }

    @Synchronized
    private fun playRawAsset(rawResId: Int) {
        try {
            mediaPlayer?.release()
            mediaPlayer = MediaPlayer.create(appContext, rawResId)?.apply {
                setOnCompletionListener { mp -> mp.release() }
                start()
            }
        } catch (_: Exception) {
        }
    }

    private fun md5(input: String): String {
        val bytes = MessageDigest.getInstance("MD5").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun speakQuestion(q: Question) {
        val labels = listOf("A", "B", "C", "D")
        val optionsText = q.options.mapIndexed { idx, opt ->
            "${labels.getOrElse(idx) { "" }}，$opt"
        }.joinToString("。")
        speakText("请听题。${q.prompt}。选项是：$optionsText", R.raw.voice_question)
    }

    fun speakExplanation(q: Question) {
        val labels = listOf("A", "B", "C", "D")
        val ansLabel = labels.getOrElse(q.correctIndex) { "" }
        val ansText = q.options.getOrElse(q.correctIndex) { "" }
        speakText("差一点点就对啦，正确答案是 $ansLabel，$ansText。${q.explanation}", R.raw.voice_wrong)
    }

    private fun playTvNativeSuccessSound() {
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val ringtone = if (uri != null) RingtoneManager.getRingtone(appContext, uri) else null
            if (ringtone != null) {
                ringtone.play()
                return
            }
        } catch (_: Exception) {
        }
        playRawAsset(R.raw.voice_praise)
    }

    fun playPraise(mode: PraiseMode, isRoundComplete: Boolean = false, isBonus: Boolean = false) {
        when (mode) {
            PraiseMode.OFF -> Unit
            PraiseMode.SFX_ONLY -> playRawAsset(R.raw.voice_praise)
            PraiseMode.SFX_AND_VOICE -> {
                playRawAsset(R.raw.voice_praise)
                val line = when {
                    isBonus -> bonusPraiseLines.random()
                    isRoundComplete -> roundCompletePraiseLines.random()
                    else -> singlePraiseLines.random()
                }
                speakText(line, R.raw.voice_praise)
            }
        }
    }

    fun stopSpeaking() {
        tts?.stop()
        try {
            mediaPlayer?.stop()
        } catch (_: Exception) {
        }
    }

    fun release() {
        tts?.stop()
        tts?.shutdown()
        mediaPlayer?.release()
        mediaPlayer = null
        ioExecutor.shutdown()
    }
}
