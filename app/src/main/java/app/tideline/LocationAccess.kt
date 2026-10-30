package app.tideline

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper

/** Bounded foreground approximate-location request; background following is a separate opt-in. */
class LocationAccess(private val activity: Activity) {
    private val manager = activity.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val handler = Handler(Looper.getMainLooper())
    private var listener: LocationListener? = null
    private var timeout: Runnable? = null
    fun permitted() = activity.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    fun cancel() {
        listener?.let { try { manager.removeUpdates(it) } catch (_: SecurityException) {} }
        timeout?.let { handler.removeCallbacks(it) }; listener = null; timeout = null
    }
    @android.annotation.SuppressLint("MissingPermission") // Checked by permitted() before every request.
    fun request(callback: (Location?) -> Unit) {
        cancel()
        if (!permitted()) { callback(null); return }
        val providers = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
        val cached = providers.mapNotNull { provider -> try { manager.getLastKnownLocation(provider) } catch (_: Exception) { null } }
            .filter { it.time <= System.currentTimeMillis() + 60_000 && System.currentTimeMillis() - it.time < 24 * 3_600_000L }
            .maxByOrNull { it.time }
        if (cached != null && System.currentTimeMillis() - cached.time < 30 * 60_000) { callback(cached); return }
        var finished = false
        fun finish(location: Location?) {
            if (!finished) { finished = true; cancel(); callback(location ?: cached) }
        }
        val current = object : LocationListener {
            override fun onLocationChanged(location: Location) { finish(location) }
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
            @Deprecated("Deprecated in Android") override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        }
        listener = current
        var requested = false
        for (provider in providers.take(1)) {
            try { if (manager.isProviderEnabled(provider)) { manager.requestSingleUpdate(provider, current, Looper.getMainLooper()); requested = true } }
            catch (_: Exception) { /* A provider may be unavailable or permission may have changed. */ }
        }
        if (!requested) finish(cached)
        else { timeout = Runnable { finish(cached) }.also { handler.postDelayed(it, 25_000) } }
    }
}
