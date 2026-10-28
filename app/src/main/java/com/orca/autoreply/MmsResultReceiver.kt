package com.orca.autoreply

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class MmsResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AppLog.d(context, "MMS result code=$resultCode (RESULT_OK=${Activity.RESULT_OK})")
        if (resultCode == Activity.RESULT_OK) return
        val number = intent.getStringExtra("number") ?: return
        val status = CallStatus.entries.firstOrNull { it.key == intent.getStringExtra("status") } ?: return
        Sender.notifyManualSend(context, number, status)
    }
}
