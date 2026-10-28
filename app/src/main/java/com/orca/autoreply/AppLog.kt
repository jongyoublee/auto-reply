package com.orca.autoreply

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Logs to logcat and to a file in app storage so test builds can export it. */
object AppLog {
    private const val TAG = "AutoReply"
    private const val MAX_BYTES = 512 * 1024

    fun file(context: Context) = File(context.filesDir, "logs/autoreply.log")

    /** Keeps only the first and last digits so exported logs don't carry full numbers. */
    fun mask(number: String): String =
        if (number.length <= 6) number else number.take(3) + "****" + number.takeLast(3)

    fun d(context: Context, msg: String) {
        Log.d(TAG, msg)
        append(context, msg)
    }

    fun w(context: Context, msg: String, t: Throwable? = null) {
        Log.w(TAG, msg, t)
        append(context, if (t == null) "WARN $msg" else "WARN $msg: ${t::class.java.simpleName} ${t.message}")
    }

    @Synchronized
    private fun append(context: Context, msg: String) {
        try {
            val f = file(context)
            f.parentFile?.mkdirs()
            if (f.length() > MAX_BYTES) f.delete()
            val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            f.appendText("$ts $msg\n")
        } catch (_: Exception) {
            // never let logging break a send
        }
    }
}
