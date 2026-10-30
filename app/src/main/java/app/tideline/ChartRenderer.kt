package app.tideline

import android.content.Context
import android.graphics.*
import app.tideline.core.ForecastTimeline
import app.tideline.core.LocationProblem
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.*

object ChartRenderer {
    private val time = DateTimeFormatter.ofPattern("HH:mm")
    fun bitmap(context: Context, widthDp: Int, heightDp: Int, settings: WidgetSettings, snapshot: TideSnapshot, hours: Int = settings.hours): Bitmap {
        val density = context.resources.displayMetrics.density
        val metrics = context.resources.displayMetrics
        val pixelBudget = min(600_000.0, metrics.widthPixels.toDouble() * metrics.heightPixels * .65)
        val scale = min(density, min(min(1100f / widthDp, 650f / heightDp), sqrt(pixelBudget / (widthDp.toDouble() * heightDp)).toFloat()))
        val width = max(1, (widthDp * scale).roundToInt()); val height = max(1, (heightDp * scale).roundToInt())
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        draw(context, Canvas(bitmap), width, height, scale, settings, snapshot, hours)
        return bitmap
    }
    private fun draw(context: Context, canvas: Canvas, width: Int, height: Int, dp: Float, s: WidgetSettings, tide: TideSnapshot, hours: Int) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = s.background
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), min(s.radius * dp, height / 2f), min(s.radius * dp, height / 2f), paint)
        val fontScale = context.resources.configuration.fontScale.coerceIn(1f, 1.6f)
        val compact = height / dp < 100
        val padding = min(s.padding * dp, min(width / 8f, height / (if (compact) 12f else 7f)))
        val verticalPadding = min(s.verticalPadding * dp, height / 4f)
        // Bound very large labels on the smallest launcher sizes.
        val textSize = min((if (compact) 9.5f else 11f) * dp * fontScale * s.labelScale, (height - 2 * verticalPadding) * .28f)
        val tickScale = min(dp * s.labelScale, height / 40f)
        val axis = height * .5f
        paint.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        paint.textSize = textSize
        val left = padding
        val right = width - padding
        if (tide.signal == null) {
            paint.textAlign = Paint.Align.CENTER
            paint.color = s.foreground
            paint.textSize = min(13 * dp * fontScale, width / 16f)
            val message = context.getString(if (tide.needsLocation) R.string.choose_location else if (tide.coverageProblem == LocationProblem.TOO_FAR_FROM_COAST) R.string.too_far_from_coast else R.string.unavailable)
            paint.textSize *= min(1f, (right - left) / paint.measureText(message))
            canvas.drawText(message, width / 2f, axis - (paint.ascent() + paint.descent()) / 2, paint)
            return
        }
        val markLeft = right - 8 * dp
        val labelRight = markLeft - 4 * dp
        val labelHalf = (paint.descent() - paint.ascent()) / 2
        // Glyph bounds remain inside the bitmap even with zero added padding.
        val half = max(dp, axis - verticalPadding - labelHalf)
        fun heightY(metres: Double) = axis - (metres / tide.scaleMetres).toFloat().coerceIn(-1f, 1f) * half
        val maximumY = heightY(tide.maximum24)
        val minimumY = heightY(tide.minimum24)
        val separation = labelHalf * 2 + 2 * dp
        val middle = ((maximumY + minimumY) / 2).coerceIn(verticalPadding + labelHalf + separation / 2, height - verticalPadding - labelHalf - separation / 2)
        val maxLabelY = if (minimumY - maximumY < separation) middle - separation / 2 else maximumY
        val minLabelY = if (minimumY - maximumY < separation) middle + separation / 2 else minimumY
        val levelTexts = listOf(cm(tide.maximum24), cm(tide.minimum24))
        val levelYs = listOf(maxLabelY, minLabelY)
        val levelRects = if (tide.signal == null) emptyList() else levelTexts.indices.map { i ->
            RectF(labelRight - paint.measureText(levelTexts[i]) - 2 * dp, levelYs[i] - labelHalf - dp, right, levelYs[i] + labelHalf + dp)
        }
        paint.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = .6f * dp
        paint.color = s.foreground
        canvas.drawLine(left, axis, right, axis, paint)
        if (s.guides) {
            paint.color = withAlpha(s.foreground, 28)
            canvas.drawLine(left, axis - half * .5f, right, axis - half * .5f, paint)
            canvas.drawLine(left, axis + half * .5f, right, axis + half * .5f, paint)
        }
        paint.textSize = textSize * .87f
        val zone = ZoneId.systemDefault()
        val ticks = ForecastTimeline.ticks(tide.startMillis, hours, zone)
        fun tickX(at: Long) = left + (right - left) * ((at - tide.startMillis).toDouble() / (hours * 3_600_000L)).toFloat()
        paint.color = s.foreground
        paint.strokeWidth = .6f * tickScale
        for (tick in ticks) {
            val x = tickX(tick.epochMillis)
            canvas.drawLine(x, axis - (if (tick.wholeHour) 4 else 2) * tickScale, x, axis + (if (tick.wholeHour) 8 else 4) * tickScale, paint)
        }
        val halo = if (Color.alpha(s.background) >= 200) s.background else if (Color.luminance(s.foreground) > .5) 0xc0000000.toInt() else 0xc0ffffff.toInt()
        fun outlinedText(text: String, x: Float, y: Float, colour: Int = s.foreground) {
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 2.4f * dp; paint.color = halo
            canvas.drawText(text, x, y, paint)
            paint.style = Paint.Style.FILL; paint.color = colour
            canvas.drawText(text, x, y, paint)
        }
        fun drawTimeLabels() {
            paint.textSize = textSize * .87f
            paint.textAlign = Paint.Align.LEFT
            val above = axis - 5 * tickScale - 2 * dp - paint.descent()
            val below = axis + 8 * tickScale + 2 * dp - paint.ascent()
            val centred = axis - (paint.ascent() + paint.descent()) / 2
            fun bounds(text: String, x: Float, y: Float): RectF {
                val w = paint.measureText(text)
                val start = (x - w / 2).coerceIn(left, max(left, right - w))
                return RectF(start, y + paint.ascent(), start + w, y + paint.descent())
            }
            fun collides(rect: RectF) = rect.top < 0 || rect.bottom > height || levelRects.any { RectF.intersects(rect, it) }
            val majors = ticks.filter { it.wholeHour }
            val baseline = (if (compact) listOf(centred, above, below) else listOf(above, below, centred))
                .minBy { y -> majors.count { collides(bounds(time.format(Instant.ofEpochMilli(it.epochMillis).atZone(zone)), tickX(it.epochMillis), y)) } }
            val occupied = mutableListOf<RectF>()
            // Prioritise whole hours; minute labels appear only where space permits.
            for (tick in majors + ticks.filterNot { it.wholeHour }) {
                paint.typeface = Typeface.create(if (tick.wholeHour) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
                paint.textSize = textSize * if (tick.wholeHour) .87f else .63f
                val text = if (tick.wholeHour) time.format(Instant.ofEpochMilli(tick.epochMillis).atZone(zone)) else String.format(java.util.Locale.getDefault(), ":%02d", tick.minute)
                val rect = bounds(text, tickX(tick.epochMillis), baseline)
                if (collides(rect) || occupied.any { RectF.intersects(rect, it) }) continue
                outlinedText(text, rect.left, baseline)
                occupied += RectF(rect).apply { inset(-3 * dp, 0f) }
            }
            paint.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        }
        val samples = tide.samples(hours, (width / dp * 2).roundToInt().coerceIn(100, 1000)) ?: return
        val ys = FloatArray(samples.size) { heightY(samples[it]) }
        val dx = (right - left) / (samples.size - 1)
        // Smooth adjacent samples without adding overshoot at turning points.
        val tangents = FloatArray(samples.size) { i ->
            when (i) {
                0 -> ys[1] - ys[0]
                samples.lastIndex -> ys[i] - ys[i - 1]
                else -> {
                    val before = ys[i] - ys[i - 1]; val after = ys[i + 1] - ys[i]
                    if (before * after <= 0) 0f else sign(before) * min(abs((before + after) / 2), 3 * min(abs(before), abs(after)))
                }
            }
        }
        val path = Path()
        path.moveTo(left, ys[0])
        for (i in samples.indices) {
            if (i == 0) continue
            val x = left + dx * i
            path.cubicTo(x - dx * 2 / 3, ys[i - 1] + tangents[i - 1] / 3, x - dx / 3, ys[i] - tangents[i] / 3, x, ys[i])
        }
        paint.color = s.curve
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = s.lineWidth * dp
        canvas.drawPath(path, paint)
        paint.style = Paint.Style.FILL
        canvas.drawCircle(left, ys[0], 1.8f * dp, paint)
        drawTimeLabels()
        paint.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        val secondary = subdued(s.foreground)
        paint.textSize = textSize
        paint.textAlign = Paint.Align.RIGHT
        for (i in levelTexts.indices) {
            outlinedText(levelTexts[i], labelRight, levelYs[i] - (paint.ascent() + paint.descent()) / 2, secondary)
            paint.color = secondary; paint.strokeWidth = 1.2f * dp
            val at = if (i == 0) maximumY else minimumY
            canvas.drawLine(markLeft, at, right, at, paint)
            if (abs(at - levelYs[i]) > dp) canvas.drawLine(labelRight + dp, levelYs[i], markLeft, at, paint)
        }
        paint.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        paint.textSize = textSize * .8f
        paint.textAlign = Paint.Align.LEFT
        val now = context.getString(R.string.current_height, cm(tide.currentMetres))
        var nowBaseline = height - verticalPadding - paint.descent()
        val available = levelRects.filter { it.top < nowBaseline + paint.descent() && it.bottom > nowBaseline + paint.ascent() }
            .minOfOrNull { it.left - left - 4 * dp } ?: (right - left)
        paint.textSize *= min(1f, max(dp, available) / paint.measureText(now))
        nowBaseline = height - verticalPadding - paint.descent()
        outlinedText(now, left, nowBaseline, secondary)
    }
    fun cm(metres: Double) = String.format(java.util.Locale.getDefault(), "%+d cm", (metres * 100).roundToInt())
    private fun withAlpha(color: Int, alpha: Int) = (color and 0x00ffffff) or (alpha shl 24)
    private fun subdued(colour: Int): Int {
        val target = if (Color.luminance(colour) > .5) 0 else 255
        fun channel(value: Int) = (value * .75f + target * .25f).roundToInt()
        return Color.rgb(channel(Color.red(colour)), channel(Color.green(colour)), channel(Color.blue(colour)))
    }
}
