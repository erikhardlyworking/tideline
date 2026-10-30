package app.tideline

import android.app.Activity
import android.app.AlertDialog
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import android.widget.*

object ColourDialog {
    fun show(activity: Activity, title: String, initial: Int, allowAlpha: Boolean, apply: (Int) -> Unit) {
        val density = activity.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val hsv = FloatArray(3); Color.colorToHSV(initial, hsv)
        var alpha = if (allowAlpha) Color.alpha(initial) else 255
        val panel = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(22), dp(8), dp(22), dp(8)) }
        val hex = EditText(activity).apply { hint = activity.getString(R.string.hex_colour); setSingleLine(true); textSize = 16f }
        val square = object : View(activity) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            override fun onDraw(canvas: Canvas) {
                p.shader = LinearGradient(0f, 0f, width.toFloat(), 0f, Color.WHITE, Color.HSVToColor(floatArrayOf(hsv[0], 1f, 1f)), Shader.TileMode.CLAMP)
                canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), p)
                p.shader = LinearGradient(0f, 0f, 0f, height.toFloat(), Color.TRANSPARENT, Color.BLACK, Shader.TileMode.CLAMP)
                canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), p)
                p.shader = null; p.style = Paint.Style.STROKE; p.strokeWidth = dp(2).toFloat(); p.color = Color.WHITE
                canvas.drawCircle(hsv[1] * width, (1 - hsv[2]) * height, dp(7).toFloat(), p); p.style = Paint.Style.FILL
            }
            override fun onTouchEvent(event: MotionEvent): Boolean {
                if (event.action == MotionEvent.ACTION_DOWN || event.action == MotionEvent.ACTION_MOVE || event.action == MotionEvent.ACTION_UP) {
                    hsv[1] = (event.x / width).coerceIn(0f, 1f); hsv[2] = 1 - (event.y / height).coerceIn(0f, 1f)
                    hex.setText(String.format("#%08X", Color.HSVToColor(alpha, hsv))); invalidate()
                    if (event.action == MotionEvent.ACTION_UP) performClick()
                    return true
                }
                return super.onTouchEvent(event)
            }
            override fun performClick(): Boolean { super.performClick(); return true }
        }
        square.contentDescription = "Colour saturation and brightness"
        panel.addView(square, LinearLayout.LayoutParams(-1, dp(160)))
        fun slider(label: String, maximum: Int, progress: Int, changed: (Int) -> Unit) {
            panel.addView(TextView(activity).apply { text = label; setPadding(0, dp(10), 0, 0) })
            panel.addView(SeekBar(activity).apply {
                max = maximum; this.progress = progress
                contentDescription = label
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) { if (fromUser) { changed(value); hex.setText(String.format("#%08X", Color.HSVToColor(alpha, hsv))); square.invalidate() } }
                    override fun onStartTrackingTouch(bar: SeekBar?) {}
                    override fun onStopTrackingTouch(bar: SeekBar?) {}
                })
            })
        }
        slider(activity.getString(R.string.hue), 360, hsv[0].toInt()) { hsv[0] = it.toFloat() }
        if (allowAlpha) slider(activity.getString(R.string.opacity), 255, alpha) { alpha = it }
        hex.setText(String.format("#%08X", initial)); panel.addView(hex)
        val dialog = AlertDialog.Builder(activity).setTitle(title).setView(panel).setNegativeButton(R.string.cancel, null).setPositiveButton(R.string.save, null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                try {
                    val value = Color.parseColor(hex.text.toString().trim())
                    apply(if (allowAlpha) value else value or Color.BLACK); dialog.dismiss()
                } catch (_: IllegalArgumentException) { hex.error = activity.getString(R.string.invalid_colour) }
            }
        }
        dialog.show()
    }
}
