package com.duck.worldcup

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

// Background poller for GMS-less devices: every ~15 min it fetches notifications newer than
// the last seen id and posts them as local system-tray notifications. No push service needed.
class NotifyPollWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {

    override fun doWork(): Result {
        return try {
            val prefs = applicationContext.getSharedPreferences("duck_notify", Context.MODE_PRIVATE)
            val lastId = prefs.getInt("last_id", -1)
            val since = if (lastId < 0) 0 else lastId

            val conn = URL("https://duck.gobet365.win/api/notifications?since=$since").openConnection() as HttpURLConnection
            conn.connectTimeout = 12000
            conn.readTimeout = 12000
            if (conn.responseCode != 200) {
                conn.disconnect()
                return Result.retry()
            }
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()

            val json = JSONObject(text)
            val latestId = json.optInt("latestId", since)

            // First ever run: just record the baseline, don't replay history.
            if (lastId < 0) {
                prefs.edit().putInt("last_id", latestId).apply()
                return Result.success()
            }

            val items = json.optJSONArray("items")
            if (items != null) {
                for (i in 0 until items.length()) {
                    val item = items.getJSONObject(i)
                    val id = item.optInt("id")
                    if (id <= lastId) continue
                    DuckMessagingService.postNotification(
                        applicationContext,
                        id,
                        item.optString("title", "大黄鸭世界杯预测"),
                        item.optString("body", ""),
                        item.optString("url", "/")
                    )
                }
            }
            prefs.edit().putInt("last_id", latestId).apply()
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }
}
