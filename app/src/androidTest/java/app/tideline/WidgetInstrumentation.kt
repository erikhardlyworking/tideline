package app.tideline

import android.app.Activity
import android.app.Instrumentation
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Button
import android.widget.CompoundButton
import android.view.ViewGroup
import java.io.File
import java.io.FileOutputStream
import java.time.Instant

/** Native device checks: installed data, real predictions, renderer and detail activity. */
class WidgetInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        val output = Bundle()
        val store = SettingsStore(targetContext)
        var activity: Activity? = null
        var outcome = Activity.RESULT_OK
        try {
            val context = targetContext
            @Suppress("DEPRECATION") val permissions = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions ?: emptyArray()
            check("android.permission.INTERNET" !in permissions) { "Offline app unexpectedly requests Internet access" }
            check("android.permission.ACCESS_FINE_LOCATION" !in permissions) { "App should only request approximate location" }
            val settings = WidgetSettings(followCurrent = false, latitude = 58.006377, longitude = 7.554759, place = "Tregde")
            store.put(99991, settings)
            store.put(99992, settings.copy(hours = 24, place = "Independent widget"))
            check(store.get(99991).hours == 3 && store.get(99992).hours == 24)
            check(WidgetSettings.fromJson(null).hours == 3 && WidgetSettings.fromJson("{\"hours\":2}").hours == 2)
            check(!settings.backgroundFollow)
            val old = settings.copy(followCurrent = true, backgroundFollow = true)
            store.put(99992, old)
            val fix = android.location.Location("network").apply { latitude = 59.0; longitude = 8.0; time = System.currentTimeMillis(); accuracy = 2500f }
            BackgroundLocation.storeFix(context, intArrayOf(99991,99992), fix)
            check(store.get(99991).latitude == settings.latitude && store.get(99992).latitude == 59.0)
            store.put(99992, old.copy(hours = 24))
            check(store.get(99992).latitude == 59.0) { "An appearance edit replaced the newer background fix" }
            store.update(99992) { it.backgroundFollow = false }
            BackgroundLocation.storeFix(context, intArrayOf(99992), android.location.Location(fix).apply { latitude = 60.0; time += 1000 })
            check(store.get(99992).latitude == 59.0) { "Disabled following accepted a new fix" }
            check(store.get(99991).labelScale == 1.5f)
            check(WidgetSettings.fromJson(settings.copy(labelScale = 2.25f).toJson()).labelScale == 2.25f)
            check(settings.verticalPadding == 0 && settings.curve == Color.WHITE && settings.foreground == Color.WHITE)
            check(WidgetSettings.fromJson("{\"padding\":12}").verticalPadding == 0)
            check(WidgetSettings.fromJson(settings.copy(verticalPadding = 7).toJson()).verticalPadding == 7)
            val now = Instant.parse("2026-10-30T12:00:00Z").toEpochMilli()
            val snapshot = TideRepository.snapshot(context, settings, now)
            check(snapshot.signal != null) { "Real-data prediction failed: ${snapshot.error}" }
            check(snapshot.nextHigh != null && snapshot.nextLow != null)
            check(snapshot.maximum24 > snapshot.minimum24)
            check(snapshot.modelLocation!!.distanceKm <= 20)
            val inland = TideRepository.snapshot(context, settings.copy(latitude = 52.52, longitude = 13.405), now)
            check(inland.signal == null && inland.coverageProblem == app.tideline.core.LocationProblem.TOO_FAR_FROM_COAST)
            val outputDirectory = File(context.filesDir, "device-checks").apply { mkdirs() }
            val inlandImage = ChartRenderer.bitmap(context, 320, 142, settings, inland)
            FileOutputStream(File(outputDirectory, "inland.png")).use { inlandImage.compress(Bitmap.CompressFormat.PNG, 100, it) }
            for (theme in 0..2) {
                val style = settings.copy().apply { theme(theme) }
                for ((index, size) in listOf(130 to 64, 320 to 142, 400 to 100, 180 to 240).withIndex()) {
                    val bitmap = ChartRenderer.bitmap(context, size.first, size.second, style, snapshot)
                    check(bitmap.width > 0 && bitmap.height > 0 && bitmap.allocationByteCount <= 3_000_000)
                    if (theme == 2) check(Color.alpha(bitmap.getPixel(0, 0)) == 0)
                    FileOutputStream(File(outputDirectory, "widget-$theme-$index.png")).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    val views = TideWidgetProvider.views(context, 99991, size.first, size.second, style, snapshot)
                    runOnMainSync {
                        val inflated = views.apply(context, null)
                        check(inflated.findViewById<ImageView>(R.id.chart) != null)
                    }
                }
            }
            for ((index, size) in listOf(130 to 64, 320 to 142).withIndex()) {
                val large = ChartRenderer.bitmap(context, size.first, size.second, settings.copy(labelScale = 2.5f), snapshot)
                FileOutputStream(File(outputDirectory, "large-labels-$index.png")).use { large.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            val unavailable = TideSnapshot(now, "Missing coverage", error = "No supported cell")
            val noData = ChartRenderer.bitmap(context, 320, 142, settings, unavailable)
            FileOutputStream(File(outputDirectory, "unavailable.png")).use { noData.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val activityIntent = Intent(context, MainActivity::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 99991).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            activity = startActivitySync(activityIntent)
            waitForIdleSync()
            // The engine is synchronous in the device checks; the UI uses a
            // background executor. Allow it to publish its real chart.
            val deadline = System.currentTimeMillis() + 15_000
            while (System.currentTimeMillis() < deadline) {
                var ready = false
                runOnMainSync { ready = activity.findViewById<View>(android.R.id.content).isShown }
                if (ready) break
                Thread.sleep(100)
            }
            Thread.sleep(700)
            waitForIdleSync()
            runOnMainSync {
                val view = activity.findViewById<View>(android.R.id.content)
                check(view.width > 0 && view.height > 0)
                fun texts(node: View): List<String> = (if (node is TextView) listOf(node.text.toString()) else emptyList()) +
                    (if (node is ViewGroup) (0 until node.childCount).flatMap { texts(node.getChildAt(it)) } else emptyList())
                val labels = texts(view)
                fun buttons(node: View): List<Button> = (if (node is Button) listOf(node) else emptyList()) +
                    (if (node is ViewGroup) (0 until node.childCount).flatMap { buttons(node.getChildAt(it)) } else emptyList())
                val controls = buttons(view).filterNot { it is CompoundButton }
                check(controls.size >= 10)
                for (button in controls) {
                    check(button.maxLines == 1 && button.layout.lineCount == 1) { "Button wraps: ${button.text}" }
                    check(button.layout.getEllipsisCount(0) == 0) { "Button text does not fit: ${button.text}" }
                }
                check(labels.any { it.contains("km from requested location") })
                check(labels.any { it.contains("°") && it.contains(" · ") }) { "Represented model coordinates missing from details" }
                File(outputDirectory, "detail-text.txt").writeText(labels.joinToString("\n---\n"))
                val image = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                view.draw(Canvas(image))
                FileOutputStream(File(outputDirectory, "detail-screen.png")).use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            val tapMonitor = addMonitor(MainActivity::class.java.name, null, false)
            val tappable = TideWidgetProvider.views(context, 99991, 320, 142, settings, snapshot)
            runOnMainSync { check(tappable.apply(context, null).findViewById<ImageView>(R.id.chart).performClick()) }
            val tapped = waitForMonitorWithTimeout(tapMonitor, 5000)
            check(tapped != null && tapped.intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 0) == 99991) { "Widget tap did not open its own detail screen" }
            runOnMainSync { tapped.finish() }; removeMonitor(tapMonitor)
            output.putString("result", "Real offline EOT20 predictions, inland detection, opt-in location fix filtering and edit races, 3-hour defaults, persisted appearance, 14 themed/resized renders, single-line buttons, RemoteViews, detail activity and widget tap passed")
        } catch (error: Throwable) {
            output.putString("failure", error.stackTraceToString())
            outcome = Activity.RESULT_CANCELED
        } finally {
            activity?.let { runOnMainSync { it.finish() } }
            store.delete(99991); store.delete(99992)
        }
        finish(outcome, output)
    }
}
