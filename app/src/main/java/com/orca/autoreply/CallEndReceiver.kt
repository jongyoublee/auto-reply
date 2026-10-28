package com.orca.autoreply

import android.content.BroadcastReceiver
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.CallLog
import android.telephony.TelephonyManager
import kotlin.concurrent.thread

class CallEndReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
        val settings = ReplySettings(context)

        when (state) {
            TelephonyManager.EXTRA_STATE_RINGING,
            TelephonyManager.EXTRA_STATE_OFFHOOK -> settings.hadCall = true

            TelephonyManager.EXTRA_STATE_IDLE -> {
                if (!settings.hadCall) return
                settings.hadCall = false
                if (!settings.masterEnabled) return
                val pending = goAsync()
                thread {
                    try {
                        handleCallEnded(context.applicationContext, settings)
                    } finally {
                        pending.finish()
                    }
                }
            }
        }
    }

    private class LogEntry(val id: Long, val number: String, val type: Int, val endMillis: Long, val duration: Long)

    private fun handleCallEnded(context: Context, settings: ReplySettings) {
        // The call log row is written shortly after IDLE, so poll for it briefly.
        repeat(6) {
            val e = latestLogEntry(context)
            if (e != null && e.id != settings.lastHandledLogId &&
                e.endMillis >= System.currentTimeMillis() - 2 * 60_000
            ) {
                process(context, settings, e)
                return
            }
            Thread.sleep(1000)
        }
        AppLog.d(context, "call ended but no new call log entry found (READ_CALL_LOG granted?)")
    }

    private fun process(context: Context, settings: ReplySettings, e: LogEntry) {
        settings.lastHandledLogId = e.id
        val status = when (e.type) {
            CallLog.Calls.OUTGOING_TYPE ->
                if (e.duration > 0) CallStatus.OUT_ANSWERED else CallStatus.OUT_UNANSWERED
            CallLog.Calls.INCOMING_TYPE -> CallStatus.IN_ANSWERED
            CallLog.Calls.MISSED_TYPE, CallLog.Calls.REJECTED_TYPE -> CallStatus.IN_MISSED
            else -> {
                AppLog.d(context, "call ended: ignored log type=${e.type}")
                return
            }
        }
        AppLog.d(context, "call ended: id=${e.id} number=${AppLog.mask(e.number)} type=${e.type} duration=${e.duration}s -> ${status.key}")
        if (!settings.active(status)) {
            AppLog.d(context, "skip: ${status.key} is disabled")
            return
        }
        if (e.number.count { it.isDigit() } < 3) { // private/unknown numbers
            AppLog.d(context, "skip: no usable number")
            return
        }
        Sender.send(context, e.number, status)
    }

    private fun latestLogEntry(context: Context): LogEntry? {
        val args = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, "${CallLog.Calls.DATE} DESC")
            putInt(ContentResolver.QUERY_ARG_LIMIT, 1)
        }
        val projection = arrayOf(
            CallLog.Calls._ID, CallLog.Calls.NUMBER, CallLog.Calls.TYPE,
            CallLog.Calls.DATE, CallLog.Calls.DURATION,
        )
        return try {
            context.contentResolver.query(CallLog.Calls.CONTENT_URI, projection, args, null)?.use { c ->
                if (!c.moveToFirst()) return null
                val duration = c.getLong(4)
                LogEntry(
                    id = c.getLong(0),
                    number = c.getString(1) ?: "",
                    type = c.getInt(2),
                    endMillis = c.getLong(3) + duration * 1000,
                    duration = duration,
                )
            }
        } catch (_: SecurityException) {
            null // READ_CALL_LOG not granted
        }
    }
}
