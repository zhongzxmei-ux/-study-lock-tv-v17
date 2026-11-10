package com.studylock.tv.config

import android.content.Context
import android.content.SharedPreferences
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class PraiseMode { OFF, SFX_ONLY, SFX_AND_VOICE }

class LockConfig(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("xueban_time_prefs", Context.MODE_PRIVATE)

    var pinInitialized: Boolean
        get() = prefs.getBoolean("pin_initialized", false)
        set(value) = prefs.edit().putBoolean("pin_initialized", value).apply()

    var parentalPin: String
        get() = prefs.getString("parental_pin", "") ?: ""
        set(value) = prefs.edit().putString("parental_pin", value).apply()

    var guardEnabled: Boolean
        get() = prefs.getBoolean("guard_enabled", false)
        set(value) = prefs.edit().putBoolean("guard_enabled", value).apply()

    var autoStartOnBoot: Boolean
        get() = prefs.getBoolean("auto_start_boot", true)
        set(value) = prefs.edit().putBoolean("auto_start_boot", value).apply()

    var watchMinutes: Int
        get() = prefs.getInt("watch_minutes", 15).coerceIn(1, 600)
        set(value) = prefs.edit().putInt("watch_minutes", value.coerceIn(1, 600)).apply()

    var questionsPerRound: Int
        get() = prefs.getInt("questions_per_round", 2).coerceIn(1, 10)
        set(value) = prefs.edit().putInt("questions_per_round", value.coerceIn(1, 10)).apply()

    var wrongWaitSeconds: Int
        get() = prefs.getInt("wrong_wait_seconds", 10).coerceIn(0, 300)
        set(value) = prefs.edit().putInt("wrong_wait_seconds", value.coerceIn(0, 300)).apply()

    var bonusRewardMinutes: Int
        get() = prefs.getInt("bonus_reward_min", 5).coerceIn(1, 120)
        set(value) = prefs.edit().putInt("bonus_reward_min", value.coerceIn(1, 120)).apply()

    var bonusPenaltyMinutes: Int
        get() = prefs.getInt("bonus_penalty_min", 5).coerceIn(1, 120)
        set(value) = prefs.edit().putInt("bonus_penalty_min", value.coerceIn(1, 120)).apply()

    var praiseMode: PraiseMode
        get() = PraiseMode.values().getOrElse(prefs.getInt("praise_mode", 1)) { PraiseMode.SFX_ONLY }
        set(value) = prefs.edit().putInt("praise_mode", value.ordinal).apply()

    var pinyinEnabled: Boolean
        get() = prefs.getBoolean("pinyin_enabled", true)
        set(value) = prefs.edit().putBoolean("pinyin_enabled", value).apply()

    var pauseMediaOnQuiz: Boolean
        get() = prefs.getBoolean("pause_media_on_quiz", true)
        set(value) = prefs.edit().putBoolean("pause_media_on_quiz", value).apply()

    var readAloudGrade12: Boolean
        get() = prefs.getBoolean("read_aloud_grade12", true)
        set(value) = prefs.edit().putBoolean("read_aloud_grade12", value).apply()

    var explainAloudOnWrong: Boolean
        get() = prefs.getBoolean("explain_aloud_on_wrong", true)
        set(value) = prefs.edit().putBoolean("explain_aloud_on_wrong", value).apply()

    var strictMode: Boolean
        get() = prefs.getBoolean("strict_mode", true)
        set(value) = prefs.edit().putBoolean("strict_mode", value).apply()

    var scheduleAllDay: Boolean
        get() = prefs.getBoolean("schedule_all_day", true)
        set(value) = prefs.edit().putBoolean("schedule_all_day", value).apply()

    var scheduleStartMinutes: Int
        get() = prefs.getInt("schedule_start_min", 18 * 60)
        set(value) = prefs.edit().putInt("schedule_start_min", ((value % 1440) + 1440) % 1440).apply()

    var scheduleEndMinutes: Int
        get() = prefs.getInt("schedule_end_min", 21 * 60)
        set(value) = prefs.edit().putInt("schedule_end_min", ((value % 1440) + 1440) % 1440).apply()

    var tempPauseUntilMillis: Long
        get() = prefs.getLong("temp_pause_until", 0L)
        set(value) = prefs.edit().putLong("temp_pause_until", value).apply()

    var enabledSubjects: Set<String>
        get() = prefs.getStringSet("enabled_subjects", setOf("语文", "数学")) ?: setOf("语文", "数学")
        set(value) = prefs.edit().putStringSet("enabled_subjects", value).apply()

    var enabledGrades: Set<String>
        get() = prefs.getStringSet("enabled_grades", setOf("1", "2", "3", "4", "5", "6")) ?: setOf("1", "2", "3", "4", "5", "6")
        set(value) = prefs.edit().putStringSet("enabled_grades", value).apply()

    var enabledVolumes: Set<String>
        get() = prefs.getStringSet("enabled_volumes", setOf("上册", "下册", "通用", "全册")) ?: setOf("上册", "下册", "通用", "全册")
        set(value) = prefs.edit().putStringSet("enabled_volumes", value).apply()

    fun addLog(msg: String) {
        val ts = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val existing = prefs.getString("run_logs", "") ?: ""
        val lines = (listOf("[$ts] $msg") + existing.lines().filter { it.isNotBlank() }).take(50)
        prefs.edit().putString("run_logs", lines.joinToString("\n")).apply()
    }

    fun getLogs(): List<String> {
        val raw = prefs.getString("run_logs", "") ?: ""
        return raw.lines().filter { it.isNotBlank() }
    }

    fun saveAsDefaultGuardSettings() {
        prefs.edit()
            .putBoolean("def_saved", true)
            .putInt("def_watch_minutes", watchMinutes)
            .putInt("def_questions_per_round", questionsPerRound)
            .putInt("def_wrong_wait_seconds", wrongWaitSeconds)
            .putInt("def_bonus_reward_min", bonusRewardMinutes)
            .putInt("def_bonus_penalty_min", bonusPenaltyMinutes)
            .putBoolean("def_schedule_all_day", scheduleAllDay)
            .putInt("def_schedule_start_min", scheduleStartMinutes)
            .putInt("def_schedule_end_min", scheduleEndMinutes)
            .putStringSet("def_enabled_subjects", enabledSubjects)
            .apply()
    }

    fun applyDefaultGuardSettings() {
        if (!prefs.getBoolean("def_saved", false)) return
        watchMinutes = prefs.getInt("def_watch_minutes", 15)
        questionsPerRound = prefs.getInt("def_questions_per_round", 2)
        wrongWaitSeconds = prefs.getInt("def_wrong_wait_seconds", 10)
        bonusRewardMinutes = prefs.getInt("def_bonus_reward_min", 5)
        bonusPenaltyMinutes = prefs.getInt("def_bonus_penalty_min", 5)
        scheduleAllDay = prefs.getBoolean("def_schedule_all_day", true)
        scheduleStartMinutes = prefs.getInt("def_schedule_start_min", 18 * 60)
        scheduleEndMinutes = prefs.getInt("def_schedule_end_min", 21 * 60)
        enabledSubjects = prefs.getStringSet("def_enabled_subjects", setOf("语文", "数学")) ?: setOf("语文", "数学")
    }

}
