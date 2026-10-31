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
            fun capture(name: String, heading: Int? = null, widgetAtTop: Boolean = false) {
                onUi {
                    val content = screen.findViewById<ViewGroup>(android.R.id.content)
                    val scroll = content.getChildAt(0) as ScrollView
                    val column = scroll.getChildAt(0) as ViewGroup
                    val target = heading?.let { resource -> texts(column).first { it.text.toString() == screen.getString(resource) } }
                    val top = if (widgetAtTop) {
                        (0 until column.childCount).map { column.getChildAt(it) }.filterIsInstance<android.widget.ImageView>().first().top
                    } else target?.parent?.let { (it as View).top } ?: 0
                    scroll.scrollTo(0, (top - (24 * screen.resources.displayMetrics.density).toInt()).coerceAtLeast(0))
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
            File(output, "provenance.txt").writeText(
                "TideLine ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n" +
                "Icon: unmodified ic_tide Android vector, rendered at 512 px.\n" +
                "Feature: native Canvas and real 12-hour ChartRenderer/EOT20 forecast, Tregde 58.006377,7.554759; $at UTC milliseconds.\n" +
                "Screenshots: native MainActivity content, fixed Tregde demo widget $demoId, current calculation time, Europe/Oslo process time zone.\n" +
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
