package app.tideline

import android.content.Context
import android.graphics.*
import app.tideline.core.ForecastTimeline
import app.tideline.core.LocationProblem
import java.time.ZoneId
import kotlin.math.*

/** Optional render evidence used by device checks, recorded from the actual drawing layout. */
class ChartAudit {
    data class Label(val kind: String, val text: String, val bounds: RectF)
    data class Guide(val y: Float, val left: Float, val right: Float)
    val labels = mutableListOf<Label>()
    val guides = mutableListOf<Guide>()
    data class Tick(val minute: Int, val length: Float)
    val ticks = mutableListOf<Tick>()
}

object ChartRenderer {
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
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        val fontScale = context.resources.configuration.fontScale.coerceIn(1f, 1.6f)
        val compact = height / dp < 100
        val padding = min(s.padding * dp, min(width / 8f, height / (if (compact) 12f else 7f)))
        val verticalPadding = min(s.verticalPadding * dp, height / 4f)
        val textSize = min((if (compact) 9.5f else 11f) * dp * fontScale * s.labelScale, (height - 2 * verticalPadding) * .24f)
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
        val samples = tide.samples(hours, (width / dp * 2).roundToInt().coerceIn(100, 1000)) ?: return
        val levelIndices = listOf(0, 1).filter { index ->
            if (index == 0) samples.any { it > 0 } else samples.any { it < 0 }
        }.ifEmpty { listOf(0) }
        val secondary = subdued(s.foreground)
        paint.typeface = normal; paint.textSize = textSize * .8f
        val updated = PhoneFormats.time(context, tide.startMillis)
        val now = context.getString(R.string.height_at_update, updated, cm(tide.currentMetres))
        val footerSpace = max(dp, right - left - 12 * dp)
        paint.textSize *= min(1f, footerSpace / paint.measureText(now))
        val nowSize = paint.textSize
        val nowBaseline = height - verticalPadding - paint.descent()
        val nowLeft = (width - paint.measureText(now)) / 2
        val nowRect = RectF(nowLeft, nowBaseline + paint.ascent(), nowLeft + paint.measureText(now), nowBaseline + paint.descent())

        paint.typeface = medium; paint.textSize = textSize
        val markLeft = right - 8 * dp; val labelRight = markLeft - 4 * dp
        val labelHalf = (paint.descent() - paint.ascent()) / 2
        val half = max(dp, axis - verticalPadding - labelHalf)
        fun heightY(metres: Double) = axis - (metres / tide.scaleMetres).toFloat().coerceIn(-1f, 1f) * half
        val allValues = listOf(tide.maximum24, tide.minimum24)
        val actualYs = levelIndices.map { heightY(allValues[it]) }
        val topCentre = verticalPadding + labelHalf
        val bottomCentre = max(topCentre, nowRect.top - 2 * dp - labelHalf)
        val separation = labelHalf * 2 + 2 * dp
        var levelYs = actualYs.map { it.coerceIn(topCentre, bottomCentre) }
        if (levelYs.size == 2 && levelYs[1] - levelYs[0] < separation) {
            val middle = ((levelYs[0] + levelYs[1]) / 2)
                .coerceIn(topCentre + separation / 2, bottomCentre - separation / 2)
            levelYs = listOf(middle - separation / 2, middle + separation / 2)
        }
        val levelTexts = levelIndices.map { cm(allValues[it]) }
        val levelBaselines = levelYs.map { it - (paint.ascent() + paint.descent()) / 2 }
        val levelRects = levelTexts.indices.map { i ->
            RectF(labelRight - paint.measureText(levelTexts[i]), levelBaselines[i] + paint.ascent(), labelRight, levelBaselines[i] + paint.descent())
        }
        val blocked = (levelRects + nowRect).map { RectF(it).apply { inset(-3 * dp, -2 * dp) } }
        val zone = ZoneId.systemDefault()
        val ticks = ForecastTimeline.ticks(tide.startMillis, hours, zone)
        fun tickX(at: Long) = left + (right - left) * ((at - tide.startMillis).toDouble() / (hours * 3_600_000L)).toFloat()
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
            val x = tickX(tick.epochMillis)
            val fullParts = PhoneFormats.hour(context, tick.epochMillis, true)
            val shortParts = PhoneFormats.hour(context, tick.epochMillis, false)
            val full = candidate(fullParts.main, fullParts.suffix, x, preferAbove, clockSize)
            val wideEnough = (right - left) / hours >= full.bounds.width() + 18 * dp
            placed@ for (avoidCurve in listOf(true, false)) {
                for (parts in if (wideEnough) listOf(fullParts, shortParts).distinct() else listOf(shortParts)) {
                    for (above in listOf(preferAbove, !preferAbove)) {
                        val label = candidate(parts.main, parts.suffix, x, above, clockSize)
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
        for (y in actualYs) horizontal(y, withAlpha(secondary, 155), .45f * dp, true)
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
            val tickLength = when { tick.wholeHour -> 4f; tick.minute == 30 -> 2.8f; else -> 1.6f } * tickScale
            canvas.drawLine(x, axis - tickLength, x, axis + tickLength * 2, paint)
            audit?.ticks?.add(ChartAudit.Tick(tick.minute, tickLength * 3))
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
            val at = actualYs[i]
            paint.color = secondary; paint.strokeWidth = .45f * dp
            if (abs(at - levelYs[i]) > dp) canvas.drawLine(labelRight + dp, levelYs[i], markLeft, at, paint)
            audit?.labels?.add(ChartAudit.Label(if (levelIndices[i] == 0) "maximum" else "minimum", levelTexts[i], RectF(levelRects[i])))
        }
        paint.typeface = normal; paint.textSize = nowSize; paint.textAlign = Paint.Align.LEFT
        text(now, nowLeft, nowBaseline, secondary)
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
