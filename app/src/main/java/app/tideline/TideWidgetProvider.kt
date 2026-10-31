package app.tideline

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.*
import android.net.Uri
import android.os.Bundle
import android.widget.RemoteViews
import androidx.work.*
import java.util.concurrent.TimeUnit

class TideWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val store = SettingsStore(context)
        for (id in ids) if (!store.contains(id)) store.put(id, store.get(0))
        WidgetUpdates.enqueue(context); WidgetUpdates.schedule(context)
    }
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) { WidgetUpdates.enqueue(context) }
    override fun onDeleted(context: Context, ids: IntArray) { ids.forEach { SettingsStore(context).delete(it) }; WidgetUpdates.schedule(context) }
    override fun onEnabled(context: Context) { WidgetUpdates.schedule(context) }
    override fun onDisabled(context: Context) { WidgetUpdates.schedule(context) }
    override fun onRestored(context: Context, oldIds: IntArray, newIds: IntArray) {
        val store = SettingsStore(context)
        for (i in 0 until minOf(oldIds.size, newIds.size)) { store.put(newIds[i], store.get(oldIds[i])); store.delete(oldIds[i]) }
        WidgetUpdates.enqueue(context); WidgetUpdates.schedule(context)
    }
    companion object {
        fun render(context: Context, id: Int, settings: WidgetSettings, snapshot: TideSnapshot) {
            val manager = AppWidgetManager.getInstance(context)
            if (manager.getAppWidgetInfo(id) == null) return
            val options = manager.getAppWidgetOptions(id)
            val minWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 260).coerceAtLeast(110)
            val maxWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, minWidth).coerceAtLeast(minWidth)
            val minHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 110).coerceAtLeast(48)
            val maxHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, minHeight).coerceAtLeast(minHeight)
            val portrait = views(context, id, minWidth, maxHeight, settings, snapshot)
            val landscape = views(context, id, maxWidth, minHeight, settings, snapshot)
            // A full RemoteViews replacement bounds bitmap memory on the launcher.
            manager.updateAppWidget(id, RemoteViews(landscape, portrait))
        }
        internal fun views(context: Context, id: Int, width: Int, height: Int, settings: WidgetSettings, snapshot: TideSnapshot): RemoteViews {
            val intent = Intent(context, MainActivity::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id).setData(Uri.parse("tideline://widget/$id"))
            val tap = PendingIntent.getActivity(context, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            return RemoteViews(context.packageName, R.layout.tide_widget).apply {
                setImageViewBitmap(R.id.chart, ChartRenderer.bitmap(context, width, height, settings, snapshot))
                setContentDescription(R.id.chart, description(context, settings, snapshot))
                setOnClickPendingIntent(R.id.chart, tap)
            }
        }
        fun description(context: Context, settings: WidgetSettings, tide: TideSnapshot): String {
            if (tide.signal == null) return tide.error ?: context.getString(R.string.unavailable)
            fun event(value: app.tideline.core.TideEvent?) = value?.let { PhoneFormats.dateTime(context, it.epochMillis) + ", " + ChartRenderer.cm(it.metres) } ?: context.getString(R.string.no_extremum)
            return context.getString(R.string.widget_accessibility, tide.label,
                PhoneFormats.time(context, tide.startMillis) + "–" + PhoneFormats.time(context, tide.startMillis + settings.hours * 3_600_000L),
                ChartRenderer.cm(tide.currentMetres), context.getString(if (tide.slope > .01) R.string.rising else if (tide.slope < -.01) R.string.falling else R.string.turning),
                event(tide.nextHigh), event(tide.nextLow))
        }
    }
}

object WidgetUpdates {
    fun ids(context: Context): IntArray = AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, TideWidgetProvider::class.java))
    fun enqueue(context: Context) { WorkManager.getInstance(context).enqueueUniqueWork("tideline-render", ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<TideWorker>().build()) }
    fun schedule(context: Context) {
        BackgroundLocation.sync(context)
        val manager = WorkManager.getInstance(context)
        if (ids(context).isEmpty()) manager.cancelUniqueWork("tideline-periodic")
        else manager.enqueueUniquePeriodicWork("tideline-periodic", ExistingPeriodicWorkPolicy.KEEP, PeriodicWorkRequestBuilder<TideWorker>(15, TimeUnit.MINUTES).build())
    }
}

class TideWorker(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {
    override fun doWork(): Result {
        BackgroundLocation.sync(applicationContext)
        BackgroundLocation.refreshCached(applicationContext)
        val store = SettingsStore(applicationContext)
        for (id in WidgetUpdates.ids(applicationContext)) {
            if (isStopped) return Result.success()
            val settings = store.get(id)
            val snapshot = TideRepository.snapshot(applicationContext, settings)
            // Do not publish a calculation started before a location/settings edit.
            if (!isStopped && store.get(id).toJson() == settings.toJson()) TideWidgetProvider.render(applicationContext, id, settings, snapshot)
        }
        return Result.success()
    }
}

class LifecycleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED,
                Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_LOCALE_CHANGED,
                android.location.LocationManager.PROVIDERS_CHANGED_ACTION)) return
        BackgroundLocation.sync(context, force = true)
        WidgetUpdates.enqueue(context); WidgetUpdates.schedule(context)
    }
}

class PinReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        if (id > 0) SettingsStore(context).put(id, intent.getStringExtra("settings")?.let { WidgetSettings.fromJson(it) } ?: SettingsStore(context).get(0))
        WidgetUpdates.enqueue(context); WidgetUpdates.schedule(context)
    }
}
