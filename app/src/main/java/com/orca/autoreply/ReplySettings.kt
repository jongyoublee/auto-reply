package com.orca.autoreply

import android.content.Context
import java.io.File

enum class CallStatus(
    val key: String,
    val label: String,
    val description: String,
    /** Recommended messages; the first one is pre-filled when a status is registered. */
    val templates: List<String>,
) {
    OUT_ANSWERED(
        "out_answered", "발신 · 통화 완료",
        "내가 전화를 걸어 상대와 통화한 뒤",
        listOf(
            "통화해 주셔서 감사합니다. 안내 자료를 보내드립니다.",
            "오늘 통화 감사했습니다. 말씀 나눈 내용은 확인 후 정리해서 다시 안내드리겠습니다.",
            "통화 감사합니다. 추가로 궁금하신 점은 이 번호로 편하게 문자 주세요.",
        ),
    ),
    OUT_UNANSWERED(
        "out_unanswered", "발신 · 부재중",
        "내가 전화를 걸었지만 상대가 받지 않았을 때 (상대가 거절한 경우 포함)",
        listOf(
            "전화드렸는데 받지 않으셔서 문자 남깁니다. 편하실 때 연락 부탁드립니다.",
            "안녕하세요, 방금 전화드렸습니다. 통화 가능하실 때 회신 부탁드립니다.",
            "연락드렸으나 닿지 않아 문자 남깁니다. 용건은 문자로 남겨 주셔도 됩니다.",
        ),
    ),
    IN_ANSWERED(
        "in_answered", "수신 · 통화 완료",
        "상대가 건 전화를 받아 통화한 뒤",
        listOf(
            "통화 감사합니다. 말씀하신 내용 정리해서 보내드릴게요.",
            "방금 통화한 내용은 확인 후 빠르게 처리하겠습니다. 감사합니다.",
            "통화해 주셔서 감사합니다. 추가 문의는 이 번호로 문자 주시면 확인하겠습니다.",
        ),
    ),
    IN_MISSED(
        "in_missed", "수신 · 부재중",
        "상대가 건 전화를 받지 못했을 때 (내가 거절한 경우 포함)",
        listOf(
            "전화 주셨는데 받지 못했습니다. 확인 후 바로 연락드리겠습니다.",
            "지금은 통화가 어렵습니다. 용건을 문자로 남겨 주시면 확인 후 회신드리겠습니다.",
            "부재중 전화 확인했습니다. 잠시 후 다시 연락드리겠습니다.",
        ),
    ),
    ;

    val defaultMessage: String get() = templates.first()
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

    var lastHandledLogId: Long
        get() = prefs.getLong("last_log_id", 0L)
        set(v) = prefs.edit().putLong("last_log_id", v).apply()

    /** A status is registered only once the user saved a message for it from the + flow. */
    fun registered(s: CallStatus) = prefs.getBoolean("${s.key}_registered", false)

    fun setRegistered(s: CallStatus, v: Boolean) = prefs.edit().putBoolean("${s.key}_registered", v).apply()

    /** Removes a registration together with its message and image. */
    fun unregister(s: CallStatus) {
        prefs.edit()
            .putBoolean("${s.key}_registered", false)
            .putBoolean("${s.key}_enabled", false)
            .remove("${s.key}_message")
            .apply()
        imageFile(s).delete()
    }

    fun enabled(s: CallStatus) = prefs.getBoolean("${s.key}_enabled", false)
    fun setEnabled(s: CallStatus, v: Boolean) = prefs.edit().putBoolean("${s.key}_enabled", v).apply()

    /** What the call receiver uses: registered and switched on. */
    fun active(s: CallStatus) = registered(s) && enabled(s)

    fun message(s: CallStatus) = prefs.getString("${s.key}_message", "") ?: ""
    fun setMessage(s: CallStatus, v: String) = prefs.edit().putString("${s.key}_message", v).apply()

    fun imageFile(s: CallStatus): File = File(appContext.filesDir, "images/${s.key}.jpg")
}
