package org.fossify.messages.receivers

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import org.fossify.commons.extensions.toast
import org.fossify.messages.R
import org.fossify.messages.helpers.NOTIFICATION_ID
import org.fossify.messages.helpers.OTP_VALUE

class CopyOtpReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val otp = intent.getStringExtra(OTP_VALUE).orEmpty()
        if (otp.isBlank()) {
            return
        }

        val clipboard = context.getSystemService(ClipboardManager::class.java)
        clipboard?.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.otp_copied), otp))

        val notificationId = intent.getIntExtra(NOTIFICATION_ID, 0)
        context.getSystemService(NotificationManager::class.java)?.cancel(notificationId)

        context.toast(R.string.otp_copied)
    }
}
