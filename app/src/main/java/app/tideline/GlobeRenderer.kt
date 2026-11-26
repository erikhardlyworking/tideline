package app.tideline

import android.content.Context
import android.graphics.*
import app.tideline.core.GeoPoint
import kotlin.math.*

/** Orthographic globe, centred on the selected point; no map service or tiles. */
object GlobeRenderer {
    private data class MapMask(val width: Int, val height: Int, val pixels: IntArray)
    @Volatile private var cached: MapMask? = null
    @Synchronized private fun mask(context: Context): MapMask = cached ?: context.assets.open("globe-ocean.png").use {
        val bitmap = BitmapFactory.decodeStream(it) ?: error("Globe shoreline unavailable")
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        MapMask(bitmap.width, bitmap.height, pixels).also { cached = it; bitmap.recycle() }
    }
    fun bitmap(context: Context, selected: GeoPoint?, size: Int = 360): Bitmap {
        require(size in 64..720)
        val map = mask(context)
        val centre = selected ?: GeoPoint(20.0, 0.0)
        val phi = Math.toRadians(centre.latitude); val lambda = Math.toRadians(centre.longitude)
        val sinPhi = sin(phi); val cosPhi = cos(phi); val sinLambda = sin(lambda); val cosLambda = cos(lambda)
        val radius = size * .46; val middle = size / 2.0
        val pixels = IntArray(size * size)
        for (py in 0 until size) for (px in 0 until size) {
            val east = (px + .5 - middle) / radius; val north = (middle - py - .5) / radius
            val squared = east * east + north * north
            if (squared > 1.0) continue
            val front = sqrt(1 - squared)
            val wx = front * cosPhi * cosLambda - east * sinLambda - north * sinPhi * cosLambda
            val wy = front * cosPhi * sinLambda + east * cosLambda - north * sinPhi * sinLambda
            val wz = front * sinPhi + north * cosPhi
            val lon = Math.toDegrees(atan2(wy, wx)); val lat = Math.toDegrees(asin(wz.coerceIn(-1.0, 1.0)))
            val x = floor((lon + 180) / 360 * map.width).toInt().coerceIn(0, map.width - 1)
            val y = floor((90 - lat) / 180 * map.height).toInt().coerceIn(0, map.height - 1)
            val sea = Color.red(map.pixels[y * map.width + x]) > 128
            val base = if (sea) 0xff669ca8.toInt() else 0xffe2e5d6.toInt()
            val shade = .78 + .22 * front
            val grid = if (front > .15 && (abs(lon / 30 - round(lon / 30)) < .015 || abs(lat / 30 - round(lat / 30)) < .015)) .9 else 1.0
            val edge = ((1 - sqrt(squared)) * radius).coerceIn(0.0, 1.0)
            pixels[py * size + px] = Color.argb((edge * 255).roundToInt(),
                (Color.red(base) * shade * grid).roundToInt(), (Color.green(base) * shade * grid).roundToInt(), (Color.blue(base) * shade * grid).roundToInt())
        }
        return Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { bitmap ->
            bitmap.setPixels(pixels, 0, size, 0, 0, size, size)
            val canvas = Canvas(bitmap); val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            paint.color = 0xff315f70.toInt(); paint.style = Paint.Style.STROKE; paint.strokeWidth = size * .003f
            canvas.drawCircle(middle.toFloat(), middle.toFloat(), radius.toFloat(), paint)
            if (selected != null) {
                paint.style = Paint.Style.FILL; paint.color = Color.WHITE
                canvas.drawCircle(middle.toFloat(), middle.toFloat(), size * .023f, paint)
                paint.color = 0xffc8483b.toInt()
                canvas.drawCircle(middle.toFloat(), middle.toFloat(), size * .014f, paint)
            }
        }
    }
}
