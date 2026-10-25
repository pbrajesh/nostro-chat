package app.mobilechat.data

import android.content.Context
import app.mobilechat.core.Retention

class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var retention: Retention
        get() = Retention.fromName(prefs.getString(KEY_RETENTION, null))
        set(value) = prefs.edit().putString(KEY_RETENTION, value.name).apply()

    private companion object {
        const val KEY_RETENTION = "message_retention"
    }
}
