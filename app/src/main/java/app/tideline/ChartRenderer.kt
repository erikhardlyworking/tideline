package app.tideline

import android.content.Context
import android.graphics.*
import app.tideline.core.ForecastTimeline
import app.tideline.core.LocationProblem
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.*

/** Optional render evidence used by device checks, recorded from the actual drawing layout. */
class ChartAudit {
    data class Label(val kind: String, val text: String, val bounds: RectF)
    data class Guide(val y: Float, val left: Float, val right: Float)
    val labels = mutableListOf<Label>()
    val guides = mutableListOf<Guide>()
}

object ChartRenderer {
    private val time = DateTimeFormatter.ofPattern("HH:mm")
    private val hour = DateTimeFormatter.ofPattern("H")
    private data class ClockLabel(val text: String, val main: String, val suffix: String, val size: Float,
        val mainWidth: Float, val bounds: RectF, val baseline: Float)

    fun bitmap(context: Context, widthDp: Int, heightDp: Int, settings: WidgetSettings, snapshot: TideSnapshot,
               hours: Int = settings.hours, audit: ChartAudit? = null): Bitmap {
        val metrics = context.resources.displayMetrics
        val pixelBudget = min(600_000.0, metrics.widthPixels.toDouble() * metrics.heightPixels * .65)
        val scale = min(metrics.density, min(min(1100f / widthDp, 650f / heightDp), sqrt(pixelBudget / (widthDp.toDouble() * heightDp)).toFloat()))
        val width = max(1, (widthDp * scale).roundToInt()); val height = max(1, (heightDp * scale).roundToInt())
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
            draw(context, Canvas(it), width, height, scale, settings, snapshot, hours, audit)
        }
    }

    private fun draw(context: Context, canvas: Canvas, width: Int, height: Int, dp: Float, s: WidgetSettings, tide: TideSnapshot, hours: Int, audit: ChartAudit?) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = s.background
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), min(s.radius * dp, height / 2f), min(s.radius * dp, height / 2f), paint)
        val fontScale = context.resources.configuration.fontScale.coerceIn(1f, 1.6f)
        val compact = height / dp < 100
        val padding = min(s.padding * dp, min(width / 8f, height / (if (compact) 12f else 7f)))
        val verticalPadding = min(s.verticalPadding * dp, height / 4f)
        val textSize = min((if (compact) 9.5f else 11f) * dp * fontScale * s.labelScale, (height - 2 * verticalPadding) * .28f)
        val tickScale = min(dp * s.labelScale, height / 40f)
        val axis = height * .5f
        val left = padding; val right = width - padding
        val normal = Typeface.create("sans-serif", Typeface.NORMAL)
        val medium = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        paint.typeface = medium; paint.textSize = textSize
        if (tide.signal == null) {
            paint.textAlign = Paint.Align.CENTER; paint.color = s.foreground
            paint.textSize = min(13 * dp * fontScale, width / 16f)
            val message = context.getString(if (tide.needsLocation) R.string.choose_location else if (tide.coverageProblem == LocationProblem.TOO_FAR_FROM_COAST) R.string.too_far_from_coast else R.string.unavailable)
            paint.textSize *= min(1f, (right - left) / paint.measureText(message))
            canvas.drawText(message, width / 2f, axis - (paint.ascent() + paint.descent()) / 2, paint)
            return
        }
        val secondary = subdued(s.foreground)
        val markLeft = right - 8 * dp; val labelRight = markLeft - 4 * dp
        val labelHalf = (paint.descent() - paint.ascent()) / 2
        val half = max(dp, axis - verticalPadding - labelHalf)
        fun heightY(metres: Double) = axis - (metres / tide.scaleMetres).toFloat().coerceIn(-1f, 1f) * half
        val maximumY = heightY(tide.maximum24); val minimumY = heightY(tide.minimum24)
        val separation = labelHalf * 2 + 2 * dp
        val middle = ((maximumY + minimumY) / 2).coerceIn(verticalPadding + labelHalf + separation / 2, height - verticalPadding - labelHalf - separation / 2)
        val levelYs = listOf(if (minimumY - maximumY < separation) middle - separation / 2 else maximumY,
            if (minimumY - maximumY < separation) middle + separation / 2 else minimumY)
        val levelTexts = listOf(cm(tide.maximum24), cm(tide.minimum24))
        val levelBaselines = levelYs.map { it - (paint.ascent() + paint.descent()) / 2 }
        val levelRects = levelTexts.indices.map { i ->
            RectF(labelRight - paint.measureText(levelTexts[i]), levelBaselines[i] + paint.ascent(), labelRight, levelBaselines[i] + paint.descent())
        }
        paint.typeface = normal; paint.textSize = textSize * .8f
        val zone = ZoneId.systemDefault()
        val updated = time.format(Instant.ofEpochMilli(tide.startMillis).atZone(zone))
        val now = context.getString(R.string.height_at_update, updated, cm(tide.currentMetres))
        val corner = min(s.radius * dp, height / 2f)
        val footerLeft = max(left + 6 * dp, corner)
        val footerRight = min(right, width - corner)
        var nowBaseline = height - verticalPadding - paint.descent()
        val available = levelRects.filter { it.top < nowBaseline + paint.descent() && it.bottom > nowBaseline + paint.ascent() }
            .minOfOrNull { it.left - footerLeft - 6 * dp } ?: (footerRight - footerLeft)
        paint.textSize *= min(1f, max(dp, available) / paint.measureText(now))
        val nowSize = paint.textSize
        nowBaseline = height - verticalPadding - paint.descent()
        val nowRect = RectF(footerLeft, nowBaseline + paint.ascent(), footerLeft + paint.measureText(now), nowBaseline + paint.descent())
        val blocked = (levelRects + nowRect).map { RectF(it).apply { inset(-3 * dp, -2 * dp) } }
        val ticks = ForecastTimeline.ticks(tide.startMillis, hours, zone)
        fun tickX(at: Long) = left + (right - left) * ((at - tide.startMillis).toDouble() / (hours * 3_600_000L)).toFloat()
        val samples = tide.samples(hours, (width / dp * 2).roundToInt().coerceIn(100, 1000)) ?: return
        val ys = FloatArray(samples.size) { heightY(samples[it]) }
        val dx = (right - left) / (samples.size - 1)
        val clocks = mutableListOf<ClockLabel>()
        fun candidate(main: String, suffix: String, x: Float, above: Boolean, size: Float): ClockLabel {
            paint.typeface = medium; paint.textSize = size
            val mainWidth = paint.measureText(main)
            val ascent = paint.ascent(); val descent = paint.descent()
            paint.textSize = size * .68f
            val total = mainWidth + paint.measureText(suffix)
            val start = (x - total / 2).coerceIn(left, max(left, right - total))
            val baseline = if (above) max(verticalPadding - ascent, axis - 5 * tickScale - 3 * dp - descent)
                else min(height - verticalPadding - descent, axis + 8 * tickScale + 3 * dp - ascent)
            return ClockLabel(main + suffix, main, suffix, size, mainWidth,
                RectF(start, baseline + ascent, start + total, baseline + descent), baseline)
        }
        fun fits(label: ClockLabel): Boolean {
            val rect = RectF(label.bounds).apply { inset(-2 * dp, -dp) }
            return label.bounds.left >= left && label.bounds.right <= right + .1f && label.bounds.top >= verticalPadding - .1f && label.bounds.bottom <= height - verticalPadding + .1f &&
                blocked.none { RectF.intersects(rect, it) } && clocks.none { RectF.intersects(rect, RectF(it.bounds).apply { inset(-3 * dp, -dp) }) }
        }
        fun crossesCurve(label: ClockLabel): Boolean {
            val box = RectF(label.bounds).apply { inset(-2 * dp, -2 * dp) }
            val first = floor((box.left - left) / dx).toInt().coerceIn(0, ys.lastIndex - 1)
            val last = ceil((box.right - left) / dx).toInt().coerceIn(first, ys.lastIndex - 1)
            return (first..last).any { i -> max(ys[i], ys[i+1]) >= box.top && min(ys[i], ys[i+1]) <= box.bottom }
        }
        val clockSize = textSize * .87f
        var preferAbove = true
        for (tick in ticks.filter { it.wholeHour }) {
            val at = Instant.ofEpochMilli(tick.epochMillis).atZone(zone)
            val main = hour.format(at); val x = tickX(tick.epochMillis)
            val full = candidate(main, ":00", x, preferAbove, clockSize)
            val wideEnough = (right - left) / hours >= full.bounds.width() + 18 * dp
            placed@ for (avoidCurve in listOf(true, false)) {
                for (suffix in if (wideEnough) listOf(":00", "") else listOf("")) {
                    for (above in listOf(preferAbove, !preferAbove)) {
                        val label = candidate(main, suffix, x, above, clockSize)
                        if (fits(label) && (!avoidCurve || !crossesCurve(label))) { clocks += label; preferAbove = !above; break@placed }
                    }
                }
            }
        }
        val annotations = levelRects + nowRect + clocks.map { it.bounds }
        fun horizontal(y: Float, colour: Int, stroke: Float, guide: Boolean = false) {
            paint.color = colour; paint.strokeWidth = stroke; paint.strokeCap = Paint.Cap.ROUND
            val cuts = annotations.filter { y >= it.top - 2 * dp && y <= it.bottom + 2 * dp }
                .map { max(left, it.left - 3 * dp) to min(right, it.right + 3 * dp) }.sortedBy { it.first }
            var from = left
            fun segment(to: Float) {
                if (to > from) { canvas.drawLine(from, y, to, y, paint); if (guide) audit?.guides?.add(ChartAudit.Guide(y, from, to)) }
            }
            for ((a,b) in cuts) { segment(a); from = max(from, b) }
            segment(right)
        }
        horizontal(maximumY, withAlpha(secondary, 155), .45f * dp, true)
        horizontal(minimumY, withAlpha(secondary, 155), .45f * dp, true)
        if (s.guides) {
            horizontal(axis - half * .5f, withAlpha(s.foreground, 28), .45f * dp)
            horizontal(axis + half * .5f, withAlpha(s.foreground, 28), .45f * dp)
        }
        horizontal(axis, s.foreground, .6f * dp)
        canvas.save()
        annotations.forEach { canvas.clipOutRect(RectF(it).apply { inset(-2 * dp, -dp) }) }
        paint.color = s.foreground; paint.strokeWidth = .6f * tickScale
        for (tick in ticks) {
            val x = tickX(tick.epochMillis)
            if (x - left <= 3 * dp) continue
            canvas.drawLine(x, axis - (if (tick.wholeHour) 4 else 2) * tickScale, x, axis + (if (tick.wholeHour) 8 else 4) * tickScale, paint)
        }
        // The rendered window starts here, even between clock-aligned ticks.
        canvas.drawCircle(left, axis, 2.6f * dp, paint)
        val tangents = FloatArray(samples.size) { i ->
            when (i) {
                0 -> ys[1] - ys[0]
                samples.lastIndex -> ys[i] - ys[i - 1]
                else -> { val before = ys[i] - ys[i-1]; val after = ys[i+1] - ys[i]
                    if (before * after <= 0) 0f else sign(before) * min(abs((before+after)/2), 3 * min(abs(before), abs(after))) }
            }
        }
        val path = Path().apply { moveTo(left, ys[0]) }
        for (i in 1 until samples.size) {
            val x = left + dx * i
            path.cubicTo(x - dx * 2/3, ys[i-1] + tangents[i-1]/3, x - dx/3, ys[i] - tangents[i]/3, x, ys[i])
        }
        paint.color = s.curve; paint.style = Paint.Style.STROKE; paint.strokeWidth = s.lineWidth * dp
        canvas.drawPath(path, paint)
        paint.style = Paint.Style.FILL
        canvas.drawCircle(left, ys[0], 2.6f * dp, paint)
        canvas.restore()
        val halo = if (Color.alpha(s.background) >= 200) s.background else if (Color.luminance(s.foreground) > .5) 0xc0000000.toInt() else 0xc0ffffff.toInt()
        fun text(value: String, x: Float, baseline: Float, colour: Int) {
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 2.4f * dp; paint.color = halo
            canvas.drawText(value, x, baseline, paint)
            paint.style = Paint.Style.FILL; paint.color = colour
            canvas.drawText(value, x, baseline, paint)
        }
        paint.textAlign = Paint.Align.LEFT
        for (label in clocks) {
            paint.typeface = medium; paint.textSize = label.size
            text(label.main, label.bounds.left, label.baseline, s.foreground)
            paint.typeface = normal; paint.textSize = label.size * .68f
            text(label.suffix, label.bounds.left + label.mainWidth, label.baseline, secondary)
            audit?.labels?.add(ChartAudit.Label("hour", label.text, RectF(label.bounds)))
        }
        paint.typeface = medium; paint.textSize = textSize; paint.textAlign = Paint.Align.RIGHT
        for (i in levelTexts.indices) {
            text(levelTexts[i], labelRight, levelBaselines[i], secondary)
            val at = if (i == 0) maximumY else minimumY
            paint.color = secondary; paint.strokeWidth = .45f * dp
            if (abs(at - levelYs[i]) > dp) canvas.drawLine(labelRight + dp, levelYs[i], markLeft, at, paint)
            audit?.labels?.add(ChartAudit.Label(if (i == 0) "maximum" else "minimum", levelTexts[i], RectF(levelRects[i])))
        }
        paint.typeface = normal; paint.textSize = nowSize; paint.textAlign = Paint.Align.LEFT
        text(now, footerLeft, nowBaseline, secondary)
        audit?.labels?.add(ChartAudit.Label("asof", now, RectF(nowRect)))
    }
    fun cm(metres: Double) = String.format(java.util.Locale.getDefault(), "%+d cm", (metres * 100).roundToInt())
    private fun withAlpha(colour: Int, alpha: Int) = (colour and 0x00ffffff) or (alpha shl 24)
    private fun subdued(colour: Int): Int {
        val target = if (Color.luminance(colour) > .5) 0 else 255
        fun channel(value: Int) = (value * .75f + target * .25f).roundToInt()
        return Color.rgb(channel(Color.red(colour)), channel(Color.green(colour)), channel(Color.blue(colour)))
    }
}
