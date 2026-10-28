package com.orca.autoreply

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Telephony
import android.telephony.SmsManager
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import java.io.File

object Sender {
    private const val CHANNEL_ID = "fallback"

    fun send(context: Context, number: String, status: CallStatus) {
        val settings = ReplySettings(context)
        val text = settings.message(status)
        val image = settings.imageFile(status).takeIf { it.exists() }
        when {
            image != null -> sendMms(context, number, text, image, status)
            text.isNotBlank() -> sendSms(context, number, text)
        }
    }

    private fun smsManager(context: Context): SmsManager =
        if (Build.VERSION.SDK_INT >= 31) context.getSystemService(SmsManager::class.java)
        else @Suppress("DEPRECATION") SmsManager.getDefault()

    private fun sendSms(context: Context, number: String, text: String) {
        val sms = smsManager(context)
        AppLog.d(context, "sendSms to=${AppLog.mask(number)} len=${text.length}")
        sms.sendMultipartTextMessage(number, null, sms.divideMessage(text), null, null)
    }

    private fun sendMms(context: Context, number: String, text: String, image: File, status: CallStatus) {
        try {
            val dir = File(context.cacheDir, "mms").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val pdu = File(dir, "send_${System.currentTimeMillis()}.pdu")
            pdu.writeBytes(MmsBuilder.build(number, text, image.readBytes()))

            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", pdu)
            // The telephony stack reads the PDU on our behalf, so it needs explicit access.
            context.grantUriPermission("com.android.phone", uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)

            val resultIntent = Intent(context, MmsResultReceiver::class.java)
                .putExtra("number", number)
                .putExtra("status", status.key)
            val sent = PendingIntent.getBroadcast(
                context, System.currentTimeMillis().toInt(), resultIntent,
                PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE,
            )
            AppLog.d(context, "sendMms to=${AppLog.mask(number)} status=${status.key} pdu=${pdu.length()}B smil=${MmsBuilder.USE_SMIL}")
            smsManager(context).sendMultimediaMessage(context, uri, null, null, sent)
        } catch (e: Exception) {
            AppLog.w(context, "sendMms failed, falling back to notification", e)
            notifyManualSend(context, number, status)
        }
    }

    /** MMS could not be sent automatically: leave a notification that opens the messaging app. */
    fun notifyManualSend(context: Context, number: String, status: CallStatus) {
        val settings = ReplySettings(context)
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "수동 전송 필요", NotificationManager.IMPORTANCE_DEFAULT)
        )
        val uri = FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", settings.imageFile(status)
        )
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, settings.message(status))
            putExtra("address", number)
            putExtra("sms_body", settings.message(status))
            Telephony.Sms.getDefaultSmsPackage(context)?.let { setPackage(it) }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val tap = PendingIntent.getActivity(
            context, number.hashCode(), share, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle("이미지 문자를 직접 보내주세요")
            .setContentText("$number · ${status.label}")
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build()
        try {
            nm.notify(number.hashCode(), n)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS not granted
        }
    }
}
