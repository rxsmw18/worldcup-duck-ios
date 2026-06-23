package com.duck.worldcup

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import java.net.HttpURLConnection
import java.net.URL

class DuckMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        registerToken(applicationContext, token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val title = message.notification?.title ?: message.data["title"] ?: "大黄鸭世界杯预测"
        val body = message.notification?.body ?: message.data["body"] ?: ""
        val url = message.data["url"] ?: "/"
        showNotification(title, body, url)
    }

    private fun showNotification(title: String, body: String, url: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ensureChannel(nm)

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("open_url", url)
        }
        val piFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pi = PendingIntent.getActivity(this, url.hashCode(), intent, piFlags)

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pi)
            .build()
        nm.notify(System.currentTimeMillis().toInt(), notification)
    }

    companion object {
        const val CHANNEL_ID = "duck_default"

        fun ensureChannel(nm: NotificationManager) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(CHANNEL_ID, "比赛提醒", NotificationManager.IMPORTANCE_HIGH)
                channel.description = "比赛开始/比分/结束 与每日预测提醒"
                nm.createNotificationChannel(channel)
            }
        }

        // POST the FCM token to our backend so the server knows where to push.
        fun registerToken(context: Context, token: String) {
            Thread {
                try {
                    val conn = URL("https://duck.gobet365.win/api/push/fcm-register").openConnection() as HttpURLConnection
                    conn.requestMethod = "POST"
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.doOutput = true
                    conn.connectTimeout = 10000
                    conn.readTimeout = 10000
                    conn.outputStream.use { it.write("{\"token\":\"$token\"}".toByteArray()) }
                    conn.responseCode
                    conn.disconnect()
                } catch (e: Exception) {
                    /* offline / server down -> retried on next launch */
                }
            }.start()
        }
    }
}
