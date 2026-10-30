package app.tideline

import android.content.Context
import org.json.JSONObject
import app.tideline.core.GeoPoint

data class WidgetSettings(
    var followCurrent: Boolean = true,
    var backgroundFollow: Boolean = false,
    var latitude: Double = Double.NaN,
    var longitude: Double = Double.NaN,
    var place: String = "",
    var locationAt: Long = 0,
    var accuracy: Float = 0f,
    var hours: Int = 3,
    var background: Int = 0xff202833.toInt(),
    var foreground: Int = -1,
    var curve: Int = -1,
    var radius: Int = 16,
    var padding: Int = 12,
    var verticalPadding: Int = 0,
    var lineWidth: Float = 1.6f,
    var labelScale: Float = 1.5f,
    var guides: Boolean = false
) {
    fun point(): GeoPoint? = if (latitude.isFinite() && longitude.isFinite() && latitude in -90.0..90.0 && longitude in -180.0..180.0) GeoPoint(latitude, longitude) else null
    fun label(): String = place.ifBlank { point()?.let { String.format(java.util.Locale.getDefault(), "%.3f°, %.3f°", it.latitude, it.longitude) } ?: "" }
    fun theme(index: Int) {
        when (index) {
            1 -> { background = 0xff202833.toInt(); foreground = -1; curve = -1; radius = 16; padding = 12; lineWidth = 1.6f }
            2 -> { background = 0; foreground = -1; curve = -1; radius = 0; padding = 8; lineWidth = 1.05f }
            else -> { background = 0xfff5f7fa.toInt(); foreground = 0xff263547.toInt(); curve = foreground; radius = 16; padding = 12; lineWidth = 1.6f }
        }
        verticalPadding = 0
    }
    fun toJson(): String = JSONObject().apply {
        put("follow", followCurrent); put("backgroundFollow", backgroundFollow); if (point() != null) { put("lat", latitude); put("lon", longitude) }
        put("place", place); put("locationAt", locationAt); put("accuracy", accuracy); put("hours", hours)
        put("background", background); put("foreground", foreground); put("curve", curve)
        put("radius", radius); put("padding", padding); put("verticalPadding", verticalPadding); put("lineWidth", lineWidth); put("labelScale", labelScale); put("guides", guides)
    }.toString()
    companion object {
        val DURATIONS = intArrayOf(2, 3, 4, 6, 12, 24)
        fun fromJson(text: String?): WidgetSettings {
            val s = WidgetSettings()
            if (text == null) return s
            try {
                val o = JSONObject(text)
                s.followCurrent = o.optBoolean("follow", true); s.latitude = o.optDouble("lat", Double.NaN); s.longitude = o.optDouble("lon", Double.NaN)
                s.backgroundFollow = o.optBoolean("backgroundFollow", false)
                s.place = o.optString("place", ""); s.locationAt = o.optLong("locationAt", 0); s.accuracy = o.optDouble("accuracy", 0.0).toFloat().coerceAtLeast(0f)
                s.hours = o.optInt("hours", 3).takeIf { it in DURATIONS } ?: 3
                s.background = o.optInt("background", s.background); s.foreground = o.optInt("foreground", s.foreground); s.curve = o.optInt("curve", s.curve)
                // Upgrade the old palette colours while preserving custom colours/backgrounds.
                if (s.background == 0xff202833.toInt()) {
                    if (s.curve == 0xff82c8ff.toInt()) s.curve = -1
                    if (s.foreground == 0xffe3eaf2.toInt()) s.foreground = -1
                } else if (s.background == 0xfff5f7fa.toInt() && s.curve == 0xff1565c0.toInt()) s.curve = s.foreground
                s.radius = o.optInt("radius", 16).coerceIn(0, 32); s.padding = o.optInt("padding", 12).coerceIn(0, 32)
                s.verticalPadding = o.optInt("verticalPadding", 0).coerceIn(0, 32)
                s.lineWidth = o.optDouble("lineWidth", 1.6).toFloat().takeIf { it.isFinite() }?.coerceIn(.7f, 4f) ?: 1.6f
                s.labelScale = o.optDouble("labelScale", 1.5).toFloat().takeIf { it.isFinite() }?.coerceIn(1f, 2.5f) ?: 1.5f
                s.guides = o.optBoolean("guides", false)
            } catch (_: Exception) { /* Corrupt preferences return honest setup defaults. */ }
            return s
        }
    }
}

class SettingsStore(context: Context) {
    companion object { private val lock = Any() }
    private val preferences = context.applicationContext.getSharedPreferences("tideline", Context.MODE_PRIVATE)
    fun get(id: Int): WidgetSettings = synchronized(lock) { WidgetSettings.fromJson(preferences.getString("widget/$id", null) ?: if (id != 0) preferences.getString("widget/0", null) else null) }
    fun contains(id: Int) = preferences.contains("widget/$id")
    var locationPermissionAsked: Boolean
        get() = preferences.getBoolean("locationPermissionAsked", false)
        set(value) { preferences.edit().putBoolean("locationPermissionAsked", value).apply() }
    fun put(id: Int, value: WidgetSettings) { synchronized(lock) {
        val latest = get(id)
        if (value.followCurrent && latest.followCurrent && latest.locationAt > value.locationAt && latest.locationAt <= System.currentTimeMillis() + 60_000) {
            value.latitude = latest.latitude; value.longitude = latest.longitude; value.locationAt = latest.locationAt; value.accuracy = latest.accuracy; value.place = latest.place
        }
        check(preferences.edit().putString("widget/$id", value.toJson()).commit())
    } }
    fun update(id: Int, change: (WidgetSettings) -> Unit) { synchronized(lock) { val value = get(id); change(value); put(id, value) } }
    fun delete(id: Int) { synchronized(lock) { preferences.edit().remove("widget/$id").apply() } }
}
