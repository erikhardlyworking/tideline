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
    private var exportStoreAssets = false
    private fun onUi(action: () -> Unit) {
        var error: Throwable? = null
        runOnMainSync { try { action() } catch (failure: Throwable) { error = failure } }
        error?.let { throw it }
    }
    override fun onCreate(arguments: Bundle?) {
        exportStoreAssets = arguments?.getString("storeAssets") == "true"
        super.onCreate(arguments); start()
    }
    override fun onStart() {
        if (exportStoreAssets) {
            val result = Bundle()
            try {
                PlayStoreAssets(this).export()
                result.putString("result", "Play assets captured from the real app at a fixed demonstration location; user widget settings and location permissions retained")
                finish(Activity.RESULT_OK, result)
            } catch (error: Throwable) {
                result.putString("failure", error.stackTraceToString())
                finish(Activity.RESULT_CANCELED, result)
            }
            return
        }
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
            check(store.get(99991).hours == 4 && store.get(99992).hours == 24)
            check(WidgetSettings.fromJson(null).hours == 4 && WidgetSettings.fromJson("{\"hours\":2}").hours == 2)
            check(!settings.backgroundFollow)
            check(settings.radius == 0)
            val defaultsContext = object : android.content.ContextWrapper(context) {
                override fun getApplicationContext(): android.content.Context = this
                override fun getSharedPreferences(name: String, mode: Int): android.content.SharedPreferences =
                    baseContext.getSharedPreferences("test-new-widget-defaults", mode)
            }
            val oldDefaults = defaultsContext.getSharedPreferences("tideline", 0)
            oldDefaults.edit().clear().putString("widget/0", "{\"hours\":2}").putString("widget/123", "{\"hours\":2}").commit()
            val upgraded = SettingsStore(defaultsContext)
            check(upgraded.get(0).hours == 4 && upgraded.get(123).hours == 2)
            upgraded.put(0, WidgetSettings(hours = 6))
            check(SettingsStore(defaultsContext).get(0).hours == 6) { "Default migration repeated over a later choice" }
            oldDefaults.edit().clear().commit()
            check(WidgetSettings.fromJson(settings.copy(radius = 16, lineWidth = 1.6f).toJson()).radius == 16)
            check(WidgetSettings.fromJson(settings.copy(radius = 16, lineWidth = 1.6f).toJson()).lineWidth == 1.6f)
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
            val auditLog = StringBuilder()
            fun checkedRender(style: WidgetSettings, width: Int, height: Int, tide: TideSnapshot): Bitmap {
                val audit = ChartAudit()
                val bitmap = ChartRenderer.bitmap(context, width, height, style, tide, audit = audit)
                val update = audit.labels.filter { it.kind == "asof" }
                check(update.size == 1 && update[0].text.startsWith(java.time.format.DateTimeFormatter.ofPattern("HH:mm").withZone(java.time.ZoneId.systemDefault()).format(java.time.Instant.ofEpochMilli(tide.startMillis)))) { "Update timestamp missing at ${width}x$height, scale ${style.labelScale}" }
                for (i in audit.labels.indices) for (j in 0 until i) {
                    check(!android.graphics.RectF.intersects(audit.labels[i].bounds, audit.labels[j].bounds)) {
                        "Overlapping ${audit.labels[i]} and ${audit.labels[j]} at ${width}x$height, scale ${style.labelScale}"
                    }
                }
                for (guide in audit.guides) for (label in audit.labels.filter { it.kind in listOf("maximum", "minimum") }) {
                    if (guide.y >= label.bounds.top && guide.y <= label.bounds.bottom)
                        check(guide.right <= label.bounds.left || guide.left >= label.bounds.right) { "Guide crosses height value" }
                }
                auditLog.append("${width}x$height · ${style.labelScale} · ${tide.startMillis}: ${audit.labels}\n")
                return bitmap
            }
            for (theme in 0..2) {
                val style = settings.copy().apply { theme(theme) }
                for ((index, size) in listOf(130 to 64, 320 to 142, 400 to 100, 180 to 240).withIndex()) {
                    val bitmap = checkedRender(style, size.first, size.second, snapshot)
                    check(bitmap.width > 0 && bitmap.height > 0 && bitmap.allocationByteCount <= 3_000_000)
                    if (theme == 2) check(Color.alpha(bitmap.getPixel(0, 0)) == 0)
                    FileOutputStream(File(outputDirectory, "widget-$theme-$index.png")).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    val views = TideWidgetProvider.views(context, 99991, size.first, size.second, style, snapshot)
                    onUi {
                        val inflated = views.apply(context, null)
                        check(inflated.findViewById<ImageView>(R.id.chart) != null)
                    }
                }
            }
            for ((index, size) in listOf(130 to 64, 320 to 142).withIndex()) {
                val large = checkedRender(settings.copy(labelScale = 2.5f), size.first, size.second, snapshot)
                FileOutputStream(File(outputDirectory, "large-labels-$index.png")).use { large.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            for (minute in listOf(17, 53, 59)) {
                val shifted = TideRepository.snapshot(context, settings, now + minute * 60_000L)
                for ((w,h) in listOf(110 to 48, 130 to 64, 180 to 100, 320 to 142)) {
                    for (scale in listOf(1f, 1.5f, 2.5f)) {
                        val image = checkedRender(settings.copy(labelScale = scale), w, h, shifted)
                        if (minute == 53 && w == 320 && scale == 1.5f)
                            FileOutputStream(File(outputDirectory, "timeline-near-hour.png")).use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    }
                }
            }
            File(outputDirectory, "layout-audit.txt").writeText(auditLog.toString())
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
                onUi { ready = activity.findViewById<View>(android.R.id.content).isShown }
                if (ready) break
                Thread.sleep(100)
            }
            Thread.sleep(700)
            waitForIdleSync()
            onUi {
                val view = activity.findViewById<View>(android.R.id.content)
                check(view.width > 0 && view.height > 0)
                fun texts(node: View): List<String> = (if (node is TextView) listOf(node.text.toString()) else emptyList()) +
                    (if (node is ViewGroup) (0 until node.childCount).flatMap { texts(node.getChildAt(it)) } else emptyList())
                val labels = texts(view)
                check(activity.findViewById<TextView>(R.id.accuracy_note).text.contains("5.3%"))
                fun buttons(node: View): List<Button> = (if (node is Button) listOf(node) else emptyList()) +
                    (if (node is ViewGroup) (0 until node.childCount).flatMap { buttons(node.getChildAt(it)) } else emptyList())
                val controls = buttons(view).filterNot { it is CompoundButton }.filter { it.isShown }
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
            onUi {
                activity.findViewById<Button>(R.id.location_shortcut).performClick()
                activity.findViewById<android.widget.RadioButton>(R.id.location_mode_fixed).isChecked = true
                activity.findViewById<android.widget.EditText>(R.id.fixed_latitude).setText("58.006377")
                activity.findViewById<android.widget.EditText>(R.id.fixed_longitude).setText("7.554759")
                activity.findViewById<android.widget.EditText>(R.id.fixed_location_name).setText("Fixed beach")
                activity.findViewById<Button>(R.id.save_fixed_location).performClick()
            }
            check(!store.get(99991).followCurrent && store.get(99991).place == "Fixed beach")
            check(kotlin.math.abs(store.get(99991).latitude - 58.006377) < 1e-9)
            check(kotlin.math.abs(store.get(99991).longitude - 7.554759) < 1e-9)
            val tapMonitor = addMonitor(MainActivity::class.java.name, null, false)
            val tappable = TideWidgetProvider.views(context, 99991, 320, 142, settings, snapshot)
            onUi { check(tappable.apply(context, null).findViewById<ImageView>(R.id.chart).performClick()) }
            val tapped = waitForMonitorWithTimeout(tapMonitor, 5000)
            check(tapped != null && tapped.intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 0) == 99991) { "Widget tap did not open its own detail screen" }
            onUi { tapped.finish() }; removeMonitor(tapMonitor)
            output.putString("result", "Real offline EOT20 predictions, inland detection, opt-in location fix filtering and edit races, 4-hour defaults, persisted appearance, 50 audited real-data renders with non-overlapping timestamps and height labels, unobstructed level guides, fixed-location saving, accuracy note, single-line buttons, RemoteViews and widget tap passed")
        } catch (error: Throwable) {
            output.putString("failure", error.stackTraceToString())
            outcome = Activity.RESULT_CANCELED
        } finally {
            activity?.let { onUi { it.finish() } }
            store.delete(99991); store.delete(99992)
        }
        finish(outcome, output)
    }
}
