package app.tideline

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.*
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build

/** Infrequent system network/passive fixes. No foreground service, GPS polling or history. */
object BackgroundLocation {
    private const val ACTION = "app.tideline.APPROXIMATE_LOCATION"
    fun permitted(context: Context): Boolean = context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
        (Build.VERSION.SDK_INT < 29 || context.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED)
    private fun activeIds(context: Context): IntArray {
        val store = SettingsStore(context)
        return WidgetUpdates.ids(context).filter { store.get(it).let { value -> value.followCurrent && value.backgroundFollow } }.toIntArray()
    }

    private fun callback(context: Context): PendingIntent = PendingIntent.getBroadcast(context, 401,
        Intent(context, BackgroundLocationReceiver::class.java).setAction(ACTION),
        PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)

    @Synchronized
    @SuppressLint("MissingPermission") // Permission and per-widget opt-in checked before registration/read.
    fun sync(context: Context, force: Boolean = false) {
        val manager = context.getSystemService(LocationManager::class.java)
        val enabled = permitted(context) && activeIds(context).isNotEmpty()
        val providers = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .filter { try { manager.isProviderEnabled(it) } catch (_: Exception) { false } }
        val signature = if (enabled) providers.joinToString(",") else ""
        val preferences = context.getSharedPreferences("background-registration", Context.MODE_PRIVATE)
        if (!force && preferences.getString("providers", null) == signature) return
        val intent = callback(context)
        try { manager.removeUpdates(intent) } catch (_: Exception) {}
        var registered = false
        if (enabled) for (provider in providers) {
            try {
                manager.requestLocationUpdates(provider, 15 * 60_000L, 1000f, intent)
                registered = true
            } catch (_: Exception) { /* Location disabled or permission revoked while configuring. */ }
        }
        preferences.edit().putString("providers", if (registered) signature else "").apply()
    }

    @SuppressLint("MissingPermission") // Both permissions checked; only opt-in widgets accept a fix.
    fun refreshCached(context: Context) {
        if (!permitted(context)) return
        val ids = activeIds(context)
        if (ids.isEmpty()) return
        val manager = context.getSystemService(LocationManager::class.java)
        val latest = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER).mapNotNull {
            try { manager.getLastKnownLocation(it) } catch (_: Exception) { null }
        }.maxByOrNull { it.time } ?: return
        storeFix(context, intArrayOf(0, *ids), latest)
    }

    internal fun storeFix(context: Context, ids: IntArray, location: Location) {
        val age = System.currentTimeMillis() - location.time
        if (age !in -60_000L..86_400_000L || !location.latitude.isFinite() || !location.longitude.isFinite() ||
            location.latitude !in -90.0..90.0 || location.longitude !in -180.0..180.0) return
        val store = SettingsStore(context)
        for (id in ids) if (store.contains(id)) store.update(id) { value ->
            if (value.followCurrent && value.backgroundFollow && (location.time > value.locationAt || value.locationAt > System.currentTimeMillis() + 60_000)) {
                value.latitude = location.latitude; value.longitude = location.longitude; value.place = ""
                value.locationAt = location.time; value.accuracy = location.accuracy
            }
        }
    }

    fun receive(context: Context, intent: Intent) {
        if (intent.action != ACTION || !permitted(context)) return
        val ids = activeIds(context)
        if (ids.isEmpty()) return
        @Suppress("DEPRECATION")
        val location = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(LocationManager.KEY_LOCATION_CHANGED, Location::class.java)
            else intent.getParcelableExtra<Location>(LocationManager.KEY_LOCATION_CHANGED)
        if (location != null) {
            storeFix(context, intArrayOf(0, *ids), location)
            WidgetUpdates.enqueue(context)
        }
    }
}

class BackgroundLocationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = BackgroundLocation.receive(context, intent)
}
