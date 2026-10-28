package com.orca.autoreply

import android.content.Context
import java.io.File

enum class CallStatus(val key: String, val label: String) {
    OUT_ANSWERED("out_answered", "내가 걸어서 통화함"),
    OUT_UNANSWERED("out_unanswered", "내가 걸었지만 상대가 받지 않음"),
    IN_ANSWERED("in_answered", "걸려온 전화를 받아서 통화함"),
    IN_MISSED("in_missed", "걸려온 전화를 못 받음(부재중)"),
}

class ReplySettings(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var masterEnabled: Boolean
        get() = prefs.getBoolean("master", false)
        set(v) = prefs.edit().putBoolean("master", v).apply()

    // PHONE_STATE receivers are re-created per broadcast, so call tracking lives in prefs.
    var hadCall: Boolean
        get() = prefs.getBoolean("had_call", false)
        set(v) = prefs.edit().putBoolean("had_call", v).apply()

    var testNumber: String
        get() = prefs.getString("test_number", "") ?: ""
        set(v) = prefs.edit().putString("test_number", v).apply()

    var lastHandledLogId: Long
        get() = prefs.getLong("last_log_id", 0L)
        set(v) = prefs.edit().putLong("last_log_id", v).apply()

    fun enabled(s: CallStatus) = prefs.getBoolean("${s.key}_enabled", false)
    fun setEnabled(s: CallStatus, v: Boolean) = prefs.edit().putBoolean("${s.key}_enabled", v).apply()

    fun message(s: CallStatus) = prefs.getString("${s.key}_message", "") ?: ""
    fun setMessage(s: CallStatus, v: String) = prefs.edit().putString("${s.key}_message", v).apply()

    fun imageFile(s: CallStatus): File = File(appContext.filesDir, "images/${s.key}.jpg")
    fun hasImage(s: CallStatus) = imageFile(s).exists()
}
