package com.studylock.tv.model

data class Question(
    val id: String,
    val grade: Int,
    val gradeLabel: String,
    val subject: String,
    val volume: String = "通用",
    val prompt: String,
    val promptPinyin: String? = null,
    val options: List<String>,
    val correctIndex: Int,
    val explanation: String
)
