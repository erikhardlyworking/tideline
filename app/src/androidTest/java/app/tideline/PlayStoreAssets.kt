package app.tideline

import android.app.Activity
import android.app.Instrumentation
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.time.Instant
import java.util.TimeZone

/** Test APK only: native vector/Canvas artwork and unaltered app-content captures. */
class PlayStoreAssets(private val runner: Instrumentation) {
    private val context = runner.targetContext
    private val output = File(context.filesDir, "play-assets")
    private fun onUi(action: () -> Unit) {
        var failure: Throwable? = null
        runner.runOnMainSync { try { action() } catch (error: Throwable) { failure = error } }
        failure?.let { throw it }
    }
    private fun save(name: String, image: Bitmap, alpha: Boolean = false) {
        image.setHasAlpha(alpha)
        File(output, name).apply { parentFile!!.mkdirs() }.outputStream().use {
            check(image.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }
    private fun texts(view: View): List<TextView> =
        (if (view is TextView) listOf(view) else emptyList()) +
        (if (view is ViewGroup) (0 until view.childCount).flatMap { texts(view.getChildAt(it)) } else emptyList())

    private fun showcase(at: Long) {
        val image = Bitmap.createBitmap(1080, 1920, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(image)
        canvas.drawColor(Color.rgb(221, 229, 230))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        data class Example(val name: String, val country: String, val continent: String, val countryName: String, val theme: Int)
        val locations = listOf(
            Example("Oslo", "NO", "EUROPE", "Norway", 1),
            Example("San Diego", "US", "NORTH AMERICA", "USA", 0),
            Example("Rio de Janeiro", "BR", "SOUTH AMERICA", "Brazil", 1),
            Example("Cape Town", "ZA", "AFRICA", "South Africa", 2),
            Example("Singapore", "SG", "ASIA", "Singapore", 1),
            Example("Perth", "AU", "OCEANIA", "Australia", 2))
        val evidence = StringBuilder("Native ChartRenderer widgets, real EOT20 forecasts at $at UTC milliseconds. One example per inhabited continent; Antarctica is outside tide coverage.\n")
        for ((index, example) in locations.withIndex()) {
            val x = if (index % 2 == 0) 48f else 560f
            val top = 136f + (index / 2) * 600f
            val box = RectF(x, top, x + 472f, top + 472f)
            val place = CoastalPlaces.search(context, example.name).first { it.country == example.country }
            val settings = WidgetSettings(followCurrent = false, latitude = place.latitude, longitude = place.longitude,
                place = example.name, showLocationName = true, labelScale = 1.2f).apply { theme(example.theme) }
            if (index == 2) settings.background = 0xff164c58.toInt()
            if (index == 3) { settings.foreground = 0xff203b42.toInt(); settings.curve = settings.foreground }
            if (index == 4) settings.background = 0xff443756.toInt()
            if (index == 5) { settings.background = 0xffe5dcc5.toInt(); settings.foreground = 0xff344d46.toInt(); settings.curve = settings.foreground }
            val tide = TideRepository.snapshot(context, settings, at)
            check(tide.signal != null) { "Store example unavailable: ${example.name}, ${tide.error}" }
            canvas.save()
            canvas.clipPath(android.graphics.Path().apply { addRoundRect(box, 26f, 26f, android.graphics.Path.Direction.CW) })
            val graph = ChartRenderer.bitmap(context, 180, 180, settings, tide)
            canvas.drawBitmap(graph, null, box, null)
            canvas.restore()
            evidence.append("${example.continent}: ${example.name} (${example.country}): ${place.latitude},${place.longitude}; theme=${example.theme}; ${settings.hours}h; current=${tide.currentMetres}; native ${graph.width}x${graph.height}.\n")
        }
        save("phoneScreenshots/00-widget-styles.png", image)
        File(output, "showcase-provenance.txt").writeText(evidence.toString() +
            "1080x1920 plain home-screen-style arrangement of unmodified native widget renders on a single plain background. Only in-widget time/height/location labels; no marketing text, external captions, status bar or invented launcher UI. No personal device or launcher content. Europe/Oslo process clock for all examples; forecast times follow the phone setting, not the place's time zone.\n")
    }

    fun export() {
        check(context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
        output.mkdirs()
        val settings = WidgetSettings(followCurrent = false, latitude = 58.006377, longitude = 7.554759, place = "Tregde, Norway", guides = true)
        val store = SettingsStore(context)
        val demoId = 99993
        check(!store.contains(demoId) ||
            (AppWidgetManager.getInstance(context).getAppWidgetInfo(demoId) == null && store.get(demoId) == settings)) {
            "Reserved screenshot ID already exists with other settings; no settings overwritten"
        }
        val previousZone = TimeZone.getDefault()
        var activity: Activity? = null
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Oslo")) // App process only; never changes phone settings.
        try {
            val at = Instant.parse("2026-10-30T12:00:00Z").toEpochMilli()
            showcase(Instant.parse("2026-11-26T11:00:00Z").toEpochMilli())
            val snapshot = TideRepository.snapshot(context, settings, at)
            check(snapshot.signal != null) { "Real model data is required for store artwork" }
            val icon = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
            val drawable = context.getDrawable(R.drawable.ic_tide)!!
            drawable.bounds = Rect(0, 0, 512, 512)
            drawable.draw(Canvas(icon))
            save("icon.png", icon, alpha = true)

            val graphic = Bitmap.createBitmap(1024, 500, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(graphic)
            canvas.drawColor(Color.rgb(16, 63, 76))
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); textSize = 66f }
            canvas.drawText("TideLine", 78f, 130f, paint)
            paint.typeface = Typeface.create("sans-serif", Typeface.NORMAL); paint.textSize = 28f; paint.color = Color.rgb(182, 225, 229)
            canvas.drawText("The tide ahead. On your home screen.", 80f, 180f, paint)
            val chart = ChartRenderer.bitmap(context, 360, 92, settings.copy(hours = 12, background = Color.rgb(16, 63, 76)), snapshot)
            val chartHeight = 864f * chart.height / chart.width
            canvas.drawBitmap(chart, null, RectF(80f, 226f, 944f, 226f + chartHeight), null)
            save("featureGraphic.png", graphic)

            store.put(demoId, settings)
            activity = runner.startActivitySync(Intent(context, MainActivity::class.java)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, demoId).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            val screen = activity
            onUi { screen.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            val deadline = System.currentTimeMillis() + 20_000
            var ready = false
            while (System.currentTimeMillis() < deadline) {
                runner.waitForIdleSync()
                onUi {
                    val content = screen.findViewById<ViewGroup>(android.R.id.content)
                    ready = content.isShown && texts(content).any { it.text.contains("Distance to nearest tide cell:") }
                }
                if (ready) break
                Thread.sleep(100)
            }
            check(ready) { "Unlock/wake the phone and retry; the real forecast screen must finish drawing" }
            fun capture(name: String, heading: Int? = null, targetId: Int? = null) {
                onUi {
                    val content = screen.findViewById<ViewGroup>(android.R.id.content)
                    val scroll = content.getChildAt(0) as ScrollView
                    val column = scroll.getChildAt(0) as ViewGroup
                    val target = heading?.let { resource -> texts(column).first { it.text.toString() == screen.getString(resource) } }
                    val top = if (targetId != null) {
                        val view = screen.findViewById<View>(targetId)
                        val bounds = Rect(); view.getDrawingRect(bounds)
                        column.offsetDescendantRectToMyCoords(view, bounds); bounds.top
                    } else target?.parent?.let { (it as View).top } ?: 0
                    scroll.scrollTo(0, top.coerceAtLeast(0))
                }
                runner.waitForIdleSync()
                onUi {
                    val content = screen.findViewById<ViewGroup>(android.R.id.content)
                    // Clip the excess tall-phone viewport, preserving pixels and layout.
                    // No launcher, notifications, status bar, overlays or personal location.
                    val width = content.width
                    val height = width * 16 / 9
                    check(height <= content.height && width >= 1080)
                    val image = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    content.draw(Canvas(image))
                    save("phoneScreenshots/$name.png", image)
                }
            }
            capture("01-widget-and-tide")
            capture("02-day-forecast", R.string.forecast_details)
            capture("03-fixed-location", R.string.location)
            capture("04-appearance", R.string.appearance)
            capture("05-forecast-window", R.string.forecast_window)

            // A public demonstration fix keeps Current-mode captures private.
            // All controls/messages are the real MainActivity UI; no phone
            // position, provider or location-permission setting is changed.
            onUi {
                val demoAccess = object : LocationAccess(screen) {
                    override fun permitted() = true
                    override fun request(callback: (android.location.Location?) -> Unit) {
                        callback(android.location.Location("network").apply {
                            latitude = settings.latitude; longitude = settings.longitude
                            time = System.currentTimeMillis(); accuracy = 2000f
                        })
                    }
                }
                MainActivity::class.java.getDeclaredField("access").apply { isAccessible = true }.set(screen, demoAccess)
                screen.findViewById<android.widget.RadioButton>(R.id.location_mode_current).isChecked = true
            }
            runner.waitForIdleSync()
            capture("06-current-location", targetId = R.id.location_mode_current)
            onUi { screen.findViewById<android.widget.Switch>(R.id.background_follow_toggle).performClick() }
            runner.waitForIdleSync()
            fun clickDialog(id: Int) {
                onUi {
                    android.view.inspector.WindowInspector.getGlobalWindowViews()
                        .mapNotNull { it.findViewById<android.widget.Button>(id) }.last().performClick()
                }
                runner.waitForIdleSync()
            }
            clickDialog(android.R.id.button1) // Accept the real disclosure.
            clickDialog(android.R.id.button2) // Decline the Android-settings route.
            onUi {
                check(!screen.findViewById<android.widget.Switch>(R.id.background_follow_toggle).isChecked)
                val message = screen.findViewById<TextView>(R.id.background_follow_status).text as android.text.Spanned
                check(message.getSpans(0, message.length, android.text.style.StyleSpan::class.java).any { it.style == Typeface.BOLD })
            }
            capture("07-background-permission", targetId = R.id.background_follow_toggle)
            File(output, "provenance.txt").writeText(
                "TideLine ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n" +
                "Icon: unmodified ic_tide Android vector, rendered at 512 px.\n" +
                "Feature: native Canvas and real 12-hour ChartRenderer/EOT20 forecast, Tregde 58.006377,7.554759; $at UTC milliseconds.\n" +
                "Screenshots: native MainActivity content, fixed Tregde demo widget $demoId, current calculation time, Europe/Oslo process time zone.\n" +
                "Settings: fixed location/globe, appearance, forecast window, Current mode and the real bold background-permission failure. Current mode uses an activity-scoped public Tregde location callback; no real phone fix is read. Background permission remains denied; the real disclosure/settings explanation is declined to show its native failure state.\n" +
                "Only bottom clipping to 9:16; no rescaling, UI replacement or text overlays. No user widget preferences or permissions changed.\n" +
                "Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, API ${android.os.Build.VERSION.SDK_INT}.\n")
        } finally {
            activity?.let { onUi { it.finish() } }
            runner.waitForIdleSync()
            // finish() can end the instrumented process before an apply() flush.
            check(context.getSharedPreferences("tideline", 0).edit().remove("widget/$demoId").commit())
            TimeZone.setDefault(previousZone)
        }
    }
}
