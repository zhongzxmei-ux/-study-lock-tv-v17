package com.studylock.tv.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.studylock.tv.config.LockConfig
import com.studylock.tv.model.Question
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class QuestionRepository(private val context: Context) {
    private var cachedQuestions: List<Question>? = null
    private val uploadedFile = File(context.filesDir, "custom_uploaded_questions.json")

    fun loadAll(): List<Question> {
        cachedQuestions?.let { return it }
        val list = mutableListOf<Question>()
        val gson = Gson()
        val type = object : TypeToken<List<Question>>() {}.type

        try {
            val json = context.assets.open("questions_sample.json")
                .bufferedReader().use { it.readText() }
            val builtIn: List<Question>? = gson.fromJson(json, type)
            if (builtIn != null) list.addAll(builtIn)
        } catch (_: Exception) {
        }

        try {
            if (uploadedFile.exists()) {
                val upJson = uploadedFile.readText(Charsets.UTF_8)
                val upList: List<Question>? = gson.fromJson(upJson, type)
                if (upList != null) list.addAll(upList)
            }
        } catch (_: Exception) {
        }

        try {
            val externalFile = File("/sdcard/Download/study_lock_questions.json")
            if (externalFile.exists() && externalFile.canRead()) {
                val extJson = externalFile.readText(Charsets.UTF_8)
                val extList: List<Question>? = gson.fromJson(extJson, type)
                if (extList != null) list.addAll(extList)
            }
        } catch (_: Exception) {
        }

        cachedQuestions = list
        return list
    }

    fun reload() {
        cachedQuestions = null
    }

    fun getTotalCount(): Int = loadAll().size

    fun getMatchingCount(config: LockConfig): Int = filterQuestions(config).size

    fun saveCustomJson(jsonBody: String): Result<Int> {
        return try {
            val gson = Gson()
            val type = object : TypeToken<List<Question>>() {}.type
            val parsed: List<Question> = gson.fromJson(jsonBody, type)
                ?: return Result.failure(IllegalArgumentException("JSON 格式无效"))

            val existing = mutableListOf<Question>()
            if (uploadedFile.exists()) {
                try {
                    val prev: List<Question>? = gson.fromJson(uploadedFile.readText(Charsets.UTF_8), type)
                    if (prev != null) existing.addAll(prev)
                } catch (_: Exception) {
                }
            }

            val seenPrompts = (loadAll().map { it.prompt } + existing.map { it.prompt }).toMutableSet()
            var addedCount = 0
            for (q in parsed) {
                if (q.prompt.isNotBlank() && q.prompt !in seenPrompts) {
                    existing.add(q)
                    seenPrompts.add(q.prompt)
                    addedCount++
                }
            }

            uploadedFile.writeText(gson.toJson(existing), Charsets.UTF_8)
            reload()
            Result.success(addedCount)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun importFromUrl(urlString: String): Result<Int> {
        return try {
            val conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10000
                readTimeout = 15000
                requestMethod = "GET"
            }
            val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            saveCustomJson(body)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun filterQuestions(config: LockConfig): List<Question> {
        val all = loadAll()
        val subjects = config.enabledSubjects
        val grades = config.enabledGrades.mapNotNull { it.toIntOrNull() }.toSet()
        val volumes = config.enabledVolumes

        return all.filter { q ->
            (subjects.isEmpty() || q.subject in subjects || (q.subject == "拼音题" && "语文" in subjects)) &&
                (grades.isEmpty() || q.grade in grades) &&
                (volumes.isEmpty() || q.volume in volumes || q.volume == "通用" || q.volume == "全册")
        }
    }

    fun pickRandomQuestion(config: LockConfig, excludeIds: Set<String> = emptySet()): Question? {
        val filtered = filterQuestions(config).filter { it.id !in excludeIds }
        val pool = filtered.ifEmpty {
            filterQuestions(config).ifEmpty {
                loadAll().filter { it.id !in excludeIds }.ifEmpty { loadAll() }
            }
        }
        return pool.randomOrNull()
    }
}
