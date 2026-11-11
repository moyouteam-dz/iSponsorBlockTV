package com.moyou.isponsorblocktv.data

import android.content.Context
import com.moyou.isponsorblocktv.domain.MonitorConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

@Singleton
class SettingsRepository @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("app_config", Context.MODE_PRIVATE)
    private val editable = MutableStateFlow(read())
    val settings: StateFlow<MonitorConfig> = editable

    @Synchronized
    fun update(transform: (MonitorConfig) -> MonitorConfig) {
        val changed = transform(editable.value)
        prefs.edit()
            .putString("screen_id", changed.screenId)
            .putString("screen_name", changed.screenName)
            .putStringSet("categories", changed.categories.toSet())
            .putBoolean("mute_ads", changed.muteAds)
            .putBoolean("skip_ads", changed.skipAds)
            .putBoolean("track_skips", changed.trackSkips)
            .putBoolean("auto_play", changed.autoPlay)
            .putInt("min_skip_seconds", changed.minSkipSeconds.coerceIn(0, 30))
            .putInt("offset_millis", changed.offsetMillis.coerceIn(-1000, 1000))
            .apply()
        editable.value = changed
    }

    private fun read() = MonitorConfig(
        screenId = prefs.getString("screen_id", "").orEmpty(),
        screenName = prefs.getString("screen_name", "").orEmpty(),
        categories = prefs.getStringSet("categories", setOf("sponsor"))?.toSet() ?: setOf("sponsor"),
        muteAds = prefs.getBoolean("mute_ads", true),
        skipAds = prefs.getBoolean("skip_ads", true),
        trackSkips = prefs.getBoolean("track_skips", true),
        autoPlay = prefs.getBoolean("auto_play", true),
        minSkipSeconds = prefs.getInt("min_skip_seconds", 1),
        offsetMillis = prefs.getInt("offset_millis", 0),
    )
}
