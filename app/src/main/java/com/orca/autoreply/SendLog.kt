package com.orca.autoreply

import android.content.Context
import java.io.File
import java.util.Calendar

/** Send history: which number got which message, and the once-per-day-per-status check. */
object SendLog {
    class Entry(val time: Long, val number: String, val status: CallStatus, val message: String, val hasImage: Boolean)

    private const val MAX = 500

    private fun file(context: Context) = File(context.filesDir, "send_history.tsv")

    private fun esc(s: String) = s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "")
    private fun unesc(s: String) = buildString {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                append(when (s[i + 1]) { 'n' -> '\n'; 't' -> '\t'; else -> s[i + 1] })
                i += 2
            } else {
                append(c); i++
            }
        }
    }

    /** Digits only, with a +82 country code folded into the local leading 0. */
    private fun norm(number: String): String {
        val d = number.filter { it.isDigit() }
        return if (d.startsWith("82") && d.length > 9) "0" + d.substring(2) else d
    }

    private fun startOfToday(): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    @Synchronized
    fun all(context: Context): List<Entry> {
        val f = file(context)
        if (!f.exists()) return emptyList()
        return f.readLines().mapNotNull { line ->
            val p = line.split('\t')
            if (p.size < 5) return@mapNotNull null
            val status = CallStatus.entries.firstOrNull { it.key == p[2] } ?: return@mapNotNull null
            val time = p[0].toLongOrNull() ?: return@mapNotNull null
            Entry(time, p[1], status, unesc(p[3]), p[4] == "1")
        }.sortedByDescending { it.time }
    }

    /**
     * Atomically checks "already sent today?" and records the send.
     * Returns false (and records nothing) if this number already got this status's message today.
     */
    @Synchronized
    fun tryRecord(context: Context, number: String, status: CallStatus, message: String, hasImage: Boolean): Boolean {
        val n = norm(number)
        val start = startOfToday()
        if (all(context).any { it.time >= start && it.status == status && norm(it.number) == n }) return false
        val f = file(context)
        val line = listOf(System.currentTimeMillis(), number, status.key, esc(message), if (hasImage) "1" else "0")
            .joinToString("\t")
        val lines = (if (f.exists()) f.readLines() else emptyList()) + line
        f.writeText(lines.takeLast(MAX).joinToString("\n") + "\n")
        return true
    }

    /** Removes the newest entry, used when the send it recorded failed. */
    @Synchronized
    fun undoLast(context: Context) {
        val f = file(context)
        if (!f.exists()) return
        val lines = f.readLines().dropLast(1)
        f.writeText(if (lines.isEmpty()) "" else lines.joinToString("\n") + "\n")
    }

    @Synchronized
    fun clear(context: Context) {
        file(context).delete()
    }
}
