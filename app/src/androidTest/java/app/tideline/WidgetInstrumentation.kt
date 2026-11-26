package app.tideline

import android.app.Activity
import android.app.Instrumentation
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.content.ComponentName
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
    private fun buttons(node: View): List<Button> = (if (node is Button) listOf(node) else emptyList()) +
        (if (node is ViewGroup) (0 until node.childCount).flatMap { buttons(node.getChildAt(it)) } else emptyList())
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
            val provider = AppWidgetManager.getInstance(context).installedProviders.first {
                it.provider == ComponentName(context, TideWidgetProvider::class.java)
            }
            if (android.os.Build.VERSION.SDK_INT >= 31) check(provider.targetCellWidth == 2 && provider.targetCellHeight == 2) { "New widgets must default to 2×2" }
            val legacyDefault = (110 * context.resources.displayMetrics.density).toInt()
            check(provider.minWidth >= legacyDefault && provider.minHeight >= legacyDefault) { "Older launchers need a two-cell default in both directions" }
            val settings = WidgetSettings(followCurrent = false, latitude = 58.006377, longitude = 7.554759, place = "Tregde")
            store.put(99991, settings)
            store.put(99992, settings.copy(hours = 24, place = "Independent widget"))
            check(store.get(99991).hours == 4 && store.get(99992).hours == 24)
            check(WidgetSettings.fromJson(null).hours == 4 && WidgetSettings.fromJson("{\"hours\":2}").hours == 2)
            check(!settings.backgroundFollow)
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
            check(WidgetSettings.fromJson(settings.copy(lineWidth = 1.6f).toJson()).lineWidth == 1.6f)
            val old = settings.copy(followCurrent = true, backgroundFollow = true, currentPlace = "My coast", showLocationName = true)
            store.put(99992, old)
            val fix = android.location.Location("network").apply { latitude = 59.0; longitude = 8.0; time = System.currentTimeMillis(); accuracy = 2500f }
            BackgroundLocation.storeFix(context, intArrayOf(99991,99992), fix)
            check(store.get(99991).latitude == settings.latitude && store.get(99992).latitude == 59.0)
            check(store.get(99992).currentPlace == "My coast" && store.get(99992).place == "Tregde") { "A location fix erased a chosen name" }
            store.put(99992, old.copy(hours = 24))
            check(store.get(99992).latitude == 59.0) { "An appearance edit replaced the newer background fix" }
            store.put(99992, old.copy(currentPlace = "Renamed coast"))
            check(store.get(99992).currentPlace == "Renamed coast" && store.get(99992).latitude == 59.0) { "Merging a newer fix erased the edited name" }
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
            check(snapshot.modelLocation!!.distanceKm <= 30)
            val inland = TideRepository.snapshot(context, settings.copy(latitude = 52.52, longitude = 13.405), now)
            check(inland.signal == null && inland.coverageProblem == app.tideline.core.LocationProblem.TOO_FAR_FROM_COAST)
            val outputDirectory = File(context.filesDir, "device-checks").apply { mkdirs() }
            val oslo = CoastalPlaces.search(context, "Oslo").first()
            check(oslo.name == "Oslo" && oslo.country == "NO" && oslo.region.isNotBlank())
            check(TideRepository.snapshot(context, settings.copy(latitude = oslo.latitude, longitude = oslo.longitude), now).signal != null) { "Oslo search result has no usable tide support" }
            for ((name, lat, lon) in listOf(Triple("Hammerfest", 70.6634, 23.6821), Triple("Nordkapp", 71.1725, 25.7844), Triple("Cape Horn", -55.98, -67.29))) {
                val retained = TideRepository.snapshot(context, settings.copy(latitude = lat, longitude = lon), now)
                check(retained.signal != null) { "Polar trim removed $name: ${retained.error}" }
            }
            for ((lat, lon) in listOf(78.223 to 15.646, -67.0 to -66.0)) {
                val polar = TideRepository.snapshot(context, settings.copy(latitude = lat, longitude = lon), now)
                check(polar.signal == null && polar.coverageProblem == app.tideline.core.LocationProblem.NO_COVERAGE)
            }
            for ((name, point) in listOf("oslo" to app.tideline.core.GeoPoint(oslo.latitude, oslo.longitude), "horn" to app.tideline.core.GeoPoint(-55.98, -67.29), "dateline" to app.tideline.core.GeoPoint(0.0, 180.0), "polar" to app.tideline.core.GeoPoint(90.0, 0.0))) {
                val globe = GlobeRenderer.bitmap(context, point)
                check(Color.alpha(globe.getPixel(0, 0)) == 0)
                check(globe.getPixel(globe.width / 2, globe.height / 2) == 0xffc8483b.toInt())
                FileOutputStream(File(outputDirectory, "globe-$name.png")).use { globe.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            check(CoastalPlaces.search(context, "Ålesund").first().id == CoastalPlaces.search(context, "Alesund").first().id)
            check(CoastalPlaces.search(context, "Brest").map { it.country }.distinct().isNotEmpty())
            check(CoastalPlaces.search(context, "zznonexistentcoastzz").isEmpty())
            check(CoastalPlaces.search(context, "[").isEmpty())
            check(CoastalPlaces.search(context, "Os").size <= 20)
            val migrated = WidgetSettings.fromJson("{\"follow\":false,\"lat\":59.9,\"lon\":10.7,\"place\":\"Oslo\"}")
            check(!migrated.showLocationName && migrated.currentPlace == "Current" && migrated.fixedLatitude == 59.9 && migrated.fixedLongitude == 10.7)
            check(WidgetSettings.fromJson(migrated.copy(currentPlace = "Home coast", showLocationName = true).toJson()).currentPlace == "Home coast")
            val inlandImage = ChartRenderer.bitmap(context, 320, 142, settings, inland)
            FileOutputStream(File(outputDirectory, "inland.png")).use { inlandImage.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val auditLog = StringBuilder()
            fun checkedRender(style: WidgetSettings, width: Int, height: Int, tide: TideSnapshot): Bitmap {
                val audit = ChartAudit()
                val bitmap = ChartRenderer.bitmap(context, width, height, style, tide, audit = audit)
                val update = audit.labels.filter { it.kind == "asof" }
                check(update.size == 1 && update[0].text.startsWith(PhoneFormats.time(context, tide.startMillis))) { "Update timestamp missing at ${width}x$height, scale ${style.labelScale}" }
                check(kotlin.math.abs(update[0].bounds.centerX() - bitmap.width / 2f) < .2f) { "Timestamp is not centred" }
                val visible = tide.samples(style.hours, (width * 2).coerceIn(100, 1000))!!
                check(audit.textHaloAlpha in 100..180) { "Text outlines must remain translucent" }
                if (audit.heightMarks.size == 1) for (mark in audit.heightMarks) check(kotlin.math.abs(mark.y - mark.labelSignY) < .2f) { "One-sided height sign is not aligned with its guide at ${width}x$height" }
                for (label in audit.labels) check(label.bounds.top >= -.2f && label.bounds.bottom <= bitmap.height + .2f && label.bounds.left >= -.2f && label.bounds.right <= bitmap.width + .2f) { "Label outside image: $label" }
                check(audit.labels.any { it.kind == "minimum" } == visible.any { it < 0 })
                check(audit.labels.any { it.kind == "maximum" } == (visible.any { it > 0 } || visible.all { it == 0.0 }))
                val quarter = audit.ticks.filter { it.minute == 15 || it.minute == 45 }
                val half = audit.ticks.filter { it.minute == 30 }
                val whole = audit.ticks.filter { it.minute == 0 }
                if (quarter.isNotEmpty() && half.isNotEmpty()) check(quarter.maxOf { it.length } < half.minOf { it.length })
                if (half.isNotEmpty() && whole.isNotEmpty()) check(half.maxOf { it.length } < whole.minOf { it.length })
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
                for ((index, size) in listOf(130 to 64, 320 to 142, 400 to 100, 180 to 240, 180 to 180).withIndex()) {
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
            for (hours in listOf(2, 3, 6, 12, 24))
                checkedRender(settings.copy(hours = hours), 320, 142, snapshot)
            val dayWindows = (0..23).map { TideRepository.snapshot(context, settings, now + it * 3_600_000L) }
            for ((name, predicate) in listOf<Pair<String, (Double) -> Boolean>>("above" to { it > 0 }, "below" to { it < 0 })) {
                val oneSided = dayWindows.first { it.samples(2, 640)!!.all(predicate) }
                val image = checkedRender(settings.copy(hours = 2), 320, 142, oneSided)
                FileOutputStream(File(outputDirectory, "$name.png")).use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
                for ((w,h) in listOf(130 to 64, 180 to 180, 320 to 142)) {
                    val named = checkedRender(settings.copy(hours = 2, showLocationName = true, place = "A very long coastal location name that must fit"), w, h, oneSided)
                    FileOutputStream(File(outputDirectory, "$name-named-$w.png")).use { named.compress(Bitmap.CompressFormat.PNG, 100, it) }
                }
            }
            val namedAudit = ChartAudit()
            ChartRenderer.bitmap(context, 320, 142, settings.copy(showLocationName = true, place = "Oslo"), snapshot, audit = namedAudit)
            check(namedAudit.labels.single { it.kind == "asof" }.text.endsWith("Oslo"))
            val offset = TideRepository.snapshot(context, settings.copy(latitude = 58.15, longitude = 7.55), now)
            check(offset.signal != null && offset.nearestCellDistanceKm!! in 0.0..30.0) { "30 km location policy failed: ${offset.error}, ${offset.nearestCellDistanceKm}" }
            check(inland.nearestCellDistanceKm!! > 30.0 && inland.error == "Too far from coast")
            check(PhoneFormats.time(context, now) == android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date(now)))
            check(PhoneFormats.dateTime(context, now).startsWith(android.text.format.DateFormat.getDateFormat(context).format(java.util.Date(now))))
            File(outputDirectory, "layout-audit.txt").writeText(auditLog.toString())
            val unavailable = TideSnapshot(now, "Missing coverage", error = "No supported cell")
            val noData = ChartRenderer.bitmap(context, 320, 142, settings, unavailable)
            FileOutputStream(File(outputDirectory, "unavailable.png")).use { noData.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val activityIntent = Intent(context, MainActivity::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 99991).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            activity = startActivitySync(activityIntent)
            onUi { activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
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
                val controls = buttons(view).filterNot { it is CompoundButton }.filter { it.isShown }
                check(controls.size >= 9)
                for (button in controls) {
                    check(button.maxLines == 1 && button.layout.lineCount == 1) { "Button wraps: ${button.text}" }
                    check(button.layout.getEllipsisCount(0) == 0) { "Button text does not fit: ${button.text}" }
                }
                check(labels.any { it.contains("Distance to nearest tide cell:") })
                check(labels.any { it.contains("°") && it.contains(" · ") }) { "Represented model coordinates missing from details" }
                File(outputDirectory, "detail-text.txt").writeText(labels.joinToString("\n---\n"))
                val image = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                view.draw(Canvas(image))
                FileOutputStream(File(outputDirectory, "detail-screen.png")).use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            onUi {
                check(activity.findViewById<View>(R.id.widget_selector) == null)
                check(activity.findViewById<View>(R.id.widget_preview) == null)
                val content = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as android.widget.ScrollView
                val field = activity.findViewById<View>(R.id.fixed_latitude)
                val rectangle = android.graphics.Rect(); field.getDrawingRect(rectangle)
                content.offsetDescendantRectToMyCoords(field, rectangle); content.scrollTo(0, rectangle.top)
                activity.findViewById<android.widget.RadioButton>(R.id.location_mode_fixed).isChecked = true
                activity.findViewById<Button>(R.id.coordinate_toggle).performClick()
                activity.findViewById<android.widget.EditText>(R.id.fixed_latitude).setText("58.006377")
                activity.findViewById<android.widget.EditText>(R.id.fixed_longitude).setText("7.554759")
                activity.findViewById<android.widget.EditText>(R.id.fixed_location_name).setText("Fixed beach")
                activity.findViewById<Button>(R.id.save_fixed_location).performClick()
            }
            check(!store.get(99991).followCurrent && store.get(99991).place == "Fixed beach")
            check(kotlin.math.abs(store.get(99991).latitude - 58.006377) < 1e-9)
            check(kotlin.math.abs(store.get(99991).longitude - 7.554759) < 1e-9)
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                onUi { activity.findViewById<Button>(R.id.search_location).performClick() }
                waitForIdleSync()
                onUi {
                    android.view.inspector.WindowInspector.getGlobalWindowViews().mapNotNull { it.findViewById<android.widget.EditText>(R.id.place_search_query) }.single().setText("Oslo")
                }
                val searchDeadline = System.currentTimeMillis() + 10_000
                var loaded = false
                while (System.currentTimeMillis() < searchDeadline && !loaded) {
                    onUi { loaded = android.view.inspector.WindowInspector.getGlobalWindowViews().mapNotNull { it.findViewById<android.widget.ListView>(R.id.place_search_results) }.single().adapter?.count?.let { it > 0 } == true }
                    if (!loaded) Thread.sleep(100)
                }
                check(loaded) { "Offline search dialog did not show Oslo" }
                onUi {
                    val list = android.view.inspector.WindowInspector.getGlobalWindowViews().mapNotNull { it.findViewById<android.widget.ListView>(R.id.place_search_results) }.single()
                    list.performItemClick(list.getChildAt(0), 0, list.adapter.getItemId(0))
                    activity.findViewById<android.widget.Switch>(R.id.show_location_name).isChecked = true
                }
                check(store.get(99991).place == "Oslo" && store.get(99991).showLocationName)
                check(kotlin.math.abs(store.get(99991).latitude - oslo.latitude) < 1e-9)
                onUi {
                    activity.findViewById<android.widget.EditText>(R.id.fixed_location_name).setText("Oslo home")
                    activity.findViewById<android.widget.RadioButton>(R.id.location_mode_current).isChecked = true
                }
                // Cancel any permission prompt immediately; this test never grants
                // or supplies the phone's real location to either app copy.
                uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                waitForIdleSync()
                onUi {
                    activity.findViewById<android.widget.EditText>(R.id.fixed_location_name).setText("Travelling")
                    activity.findViewById<android.widget.RadioButton>(R.id.location_mode_fixed).isChecked = true
                }
                check(store.get(99991).place == "Oslo home" && store.get(99991).currentPlace == "Travelling")
                check(kotlin.math.abs(store.get(99991).latitude - oslo.latitude) < 1e-9) { "Switching modes lost the fixed location" }
            }
            val tapMonitor = addMonitor(MainActivity::class.java.name, null, false)
            val idsBeforeEditing = WidgetUpdates.ids(context).sorted()
            val otherWidgetBeforeEditing = store.get(99992).toJson()
            val tappable = TideWidgetProvider.views(context, 99991, 320, 142, settings, snapshot)
            onUi { check(tappable.apply(context, null).findViewById<ImageView>(R.id.chart).performClick()) }
            val tapped = waitForMonitorWithTimeout(tapMonitor, 5000)
            check(tapped != null && tapped.intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 0) == 99991) { "Widget tap did not open its own detail screen" }
            onUi {
                check(tapped.findViewById<View>(R.id.widget_selector) == null && tapped.findViewById<View>(R.id.widget_preview) == null)
                val actions = buttons(tapped.findViewById(android.R.id.content))
                check(actions.none { it.text.toString() == context.getString(R.string.add_widget) }) { "An existing widget tap offers to add another widget" }
                tapped.findViewById<android.widget.EditText>(R.id.fixed_location_name).setText("Saved from widget tap")
                tapped.findViewById<Button>(R.id.save_fixed_location).performClick()
                actions.single { it.text.toString() == context.getString(R.string.finish_setup) }.performClick()
                check(tapped.isFinishing) { "Save widget did not finish editing" }
            }
            check(store.get(99991).place == "Saved from widget tap") { "The tapped widget's edits were not saved" }
            check(store.get(99992).toJson() == otherWidgetBeforeEditing) { "Editing one widget changed another" }
            check(WidgetUpdates.ids(context).sorted() == idsBeforeEditing) { "Editing created another widget" }
            removeMonitor(tapMonitor)
            for (mode in listOf("configure", "launcher")) {
                val intent = Intent(context, MainActivity::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 99991).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                intent.action = if (mode == "launcher") Intent.ACTION_MAIN else AppWidgetManager.ACTION_APPWIDGET_CONFIGURE
                val modeActivity = startActivitySync(intent); waitForIdleSync()
                onUi {
                    check((modeActivity.findViewById<View>(R.id.widget_preview) != null) == (mode == "launcher"))
                    val expectedSelector = mode == "launcher" && WidgetUpdates.ids(context).isNotEmpty()
                    check((modeActivity.findViewById<View>(R.id.widget_selector) != null) == expectedSelector)
                    val actions = buttons(modeActivity.findViewById(android.R.id.content)).map { it.text.toString() }
                    check((context.getString(R.string.add_widget) in actions) == (mode == "launcher"))
                    check((context.getString(R.string.finish_setup) in actions) == (mode == "configure"))
                    modeActivity.finish()
                }
                waitForIdleSync()
            }
            output.putString("result", "Offline coastal search, Oslo model coverage, accent aliases, search dialog selection, named current/fixed modes, name/fix merge races, optional ellipsized footer names, aligned minimum/maximum signs, translucent halos and continuous curves, 66 audited native renders, offline globe projections at Oslo/Cape Horn/dateline/pole, polar exclusion and Hammerfest/Nordkapp/Cape Horn retention; existing coverage, tick, activity, widget editing and 2×2 default regressions passed")
        } catch (error: Throwable) {
            output.putString("failure", error.stackTraceToString())
            outcome = Activity.RESULT_CANCELED
        } finally {
            activity?.let { onUi { it.finish() } }
            waitForIdleSync()
            check(targetContext.getSharedPreferences("tideline", 0).edit().remove("widget/99991").remove("widget/99992").commit())
        }
        finish(outcome, output)
    }
}
