package app.tideline

import android.Manifest
import android.app.*
import android.appwidget.AppWidgetManager
import android.content.*
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.location.Location
import android.os.*
import android.text.InputType
import android.text.TextUtils
import android.util.TypedValue
import android.view.*
import android.widget.*
import app.tideline.core.TideEvent
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors
import kotlin.math.*

class MainActivity : Activity() {
    private lateinit var store: SettingsStore
    private lateinit var settings: WidgetSettings
    private lateinit var access: LocationAccess
    private lateinit var root: LinearLayout
    private lateinit var preview: ImageView
    private lateinit var detailChart: ImageView
    private lateinit var current: TextView
    private lateinit var direction: TextView
    private lateinit var high: TextView
    private lateinit var low: TextView
    private lateinit var source: TextView
    private lateinit var locationStatus: TextView
    private var widgetId = 0
    private var configuring = false
    private var stopped = false
    private var locating = false
    private var continueBackgroundPermission = false
    private lateinit var backgroundStatus: TextView
    private var requestGeneration = 0
    private val executor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private val minuteTick = object : Runnable {
        override fun run() { if (!stopped) { recalculate(); handler.postDelayed(this, 60_000) } }
    }
    private var snapshot: TideSnapshot? = null
    private val ink = Color.rgb(29, 53, 65)
    private val muted = Color.rgb(94, 114, 124)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 0).coerceAtLeast(0)
        if (!intent.hasExtra(AppWidgetManager.EXTRA_APPWIDGET_ID)) widgetId = WidgetUpdates.ids(this).firstOrNull() ?: 0
        configuring = intent.action == AppWidgetManager.ACTION_APPWIDGET_CONFIGURE && widgetId > 0
        if (configuring) setResult(RESULT_CANCELED)
        store = SettingsStore(this); settings = store.get(widgetId); access = LocationAccess(this)
        buildScreen()
        recalculate()
    }
    override fun onStart() {
        super.onStart(); stopped = false
        settings = store.get(widgetId)
        BackgroundLocation.sync(this, force = true)
        updateBackgroundStatus()
        if (snapshot != null) recalculate()
        if (settings.followCurrent) {
            if (access.permitted() || !store.locationPermissionAsked) handler.post { requestLocation() }
            else locationStatus.text = getString(R.string.no_location_permission)
        }
        handler.removeCallbacks(minuteTick); handler.postDelayed(minuteTick, 60_000)
    }
    override fun onStop() { stopped = true; locating = false; handler.removeCallbacks(minuteTick); access.cancel(); super.onStop() }
    override fun onDestroy() { executor.shutdownNow(); super.onDestroy() }

    private fun text(value: String, size: Float = 15f, colour: Int = ink, bold: Boolean = false): TextView = TextView(this).apply {
        this.text = value; textSize = size; setTextColor(colour)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setPadding(0, dp(4), 0, dp(4))
    }
    private fun button(label: String, action: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; minHeight = dp(48)
        setSingleLine(true); setHorizontallyScrolling(false); ellipsize = TextUtils.TruncateAt.END
        setAutoSizeTextTypeUniformWithConfiguration(10, 14, 1, TypedValue.COMPLEX_UNIT_SP)
        setOnClickListener { action() }
    }
    private fun card(title: String): LinearLayout {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(14), dp(18), dp(14))
            background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(20).toFloat() }
        }
        panel.addView(text(title, 17f, ink, true))
        root.addView(panel, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) })
        return panel
    }
    private fun row(parent: LinearLayout, vararg children: View) {
        val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        children.forEachIndexed { i, child -> line.addView(child, LinearLayout.LayoutParams(0, -2, 1f).apply { if (i > 0) leftMargin = dp(8) }) }
        parent.addView(line)
    }

    private fun buildScreen() {
        val scroll = ScrollView(this).apply { isFillViewport = true; setBackgroundColor(Color.rgb(244, 247, 249)) }
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(22), dp(20), dp(22), dp(28)) }
        scroll.addView(root)
        setContentView(scroll)
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            scroll.setOnApplyWindowInsetsListener { view, insets ->
                val system = insets.getInsets(WindowInsets.Type.systemBars())
                val keyboard = insets.getInsets(WindowInsets.Type.ime())
                view.setPadding(system.left, system.top, system.right, max(system.bottom, keyboard.bottom)); insets
            }
            scroll.requestApplyInsets()
        } else scroll.fitsSystemWindows = true
        root.addView(text(getString(R.string.app_name), 34f, ink, true))
        root.addView(text(getString(R.string.tagline), 15f, muted))
        root.addView(text(getString(R.string.accuracy_note), 13f, muted).apply { id = R.id.accuracy_note })
        lateinit var locationPanel: LinearLayout
        root.addView(button(getString(R.string.set_location)) {
            scroll.smoothScrollTo(0, (locationPanel.top - dp(8)).coerceAtLeast(0))
        }.apply { id = R.id.location_shortcut })
        val ids = WidgetUpdates.ids(this)
        if (ids.isNotEmpty()) {
            // Keep a newly allocated/configuring widget selected even before
            // the launcher reports it in the provider's active widget list.
            val choices = (listOf(0) + ids.toList() + widgetId).distinct().toIntArray()
            val spinner = Spinner(this)
            spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, choices.map { if (it == 0) getString(R.string.new_widget) else getString(R.string.widget_number, it) })
            spinner.setSelection(choices.indexOf(widgetId).coerceAtLeast(0))
            spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    if (choices[position] != widgetId) { widgetId = choices[position]; settings = store.get(widgetId); snapshot = null; buildScreen(); recalculate() }
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
            root.addView(spinner)
        }
        root.addView(text(getString(R.string.settings_for, if (widgetId == 0) getString(R.string.new_widget) else getString(R.string.widget_number, widgetId)), 12f, muted))

        preview = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_XY; contentDescription = getString(R.string.widget_description) }
        root.addView(preview, LinearLayout.LayoutParams(-1, dp(142)).apply { topMargin = dp(12); bottomMargin = dp(18) })
        preview.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) drawCharts()
        }
        val detail = card(getString(R.string.forecast_details))
        current = text("—", 34f, ink, true); direction = text(getString(R.string.calculating), 14f, muted)
        detail.addView(current); detail.addView(direction)
        high = text(getString(R.string.high_tide), 15f); low = text(getString(R.string.low_tide), 15f)
        detail.addView(high); detail.addView(low)
        detail.addView(text(getString(R.string.hourly_detail), 13f, muted))
        detailChart = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_XY; contentDescription = getString(R.string.hourly_detail) }
        detail.addView(detailChart, LinearLayout.LayoutParams(-1, dp(190)))
        source = text("", 12f, muted); detail.addView(source)
        detail.addView(text(getString(R.string.astronomical_note), 13f, muted))
        detail.addView(text(getString(R.string.datum_note), 12f, muted))

        val location = card(getString(R.string.location))
        locationPanel = location
        val modes = RadioGroup(this)
        val follow = RadioButton(this).apply { id = R.id.location_mode_current; text = getString(R.string.use_current_location); minHeight = dp(48) }
        val specific = RadioButton(this).apply { id = R.id.location_mode_fixed; text = getString(R.string.specific_location); minHeight = dp(48) }
        modes.addView(follow); modes.addView(specific)
        modes.check(if (settings.followCurrent) follow.id else specific.id)
        location.addView(modes)
        locationStatus = text("", 12f, muted); location.addView(locationStatus)
        val fixed = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = if (settings.followCurrent) View.GONE else View.VISIBLE }
        fun coordinate(hintText: String, value: Double) = EditText(this).apply {
            hint = hintText; inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED; setSingleLine(true)
            if (value.isFinite()) setText(String.format(java.util.Locale.ROOT, "%.6f", value))
        }
        fixed.addView(text(getString(R.string.fixed_location_help), 12f, muted))
        val latitude = coordinate(getString(R.string.latitude), settings.latitude).apply { id = R.id.fixed_latitude }
        val longitude = coordinate(getString(R.string.longitude), settings.longitude).apply { id = R.id.fixed_longitude }
        fun field(label: Int, input: View) = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; addView(text(getString(label), 12f, muted)); addView(input)
        }
        row(fixed, field(R.string.latitude, latitude), field(R.string.longitude, longitude))
        val name = EditText(this).apply { id = R.id.fixed_location_name; hint = getString(R.string.place_name); setText(settings.place); setSingleLine(true); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS }
        fixed.addView(name)
        fixed.addView(button(getString(R.string.save_location)) {
            val lat = latitude.text.toString().trim().replace(',', '.').toDoubleOrNull()
            val lon = longitude.text.toString().trim().replace(',', '.').toDoubleOrNull()
            if (lat == null || lon == null || !lat.isFinite() || !lon.isFinite() || lat !in -90.0..90.0 || lon !in -180.0..180.0) {
                latitude.error = getString(R.string.invalid_coordinates)
            } else {
                settings.latitude = lat; settings.longitude = lon; settings.place = name.text.toString().trim(); settings.locationAt = 0; settings.accuracy = 0f
                save(); recalculate()
            }
        }.apply { id = R.id.save_fixed_location })
        location.addView(fixed)
        val locateButton = button(getString(R.string.use_my_location)) { if (follow.isChecked) requestLocation() else follow.isChecked = true }
        location.addView(locateButton)
        val background = Switch(this).apply { text = getString(R.string.background_follow); isChecked = settings.backgroundFollow; minHeight = dp(48); visibility = if (settings.followCurrent) View.VISIBLE else View.GONE }
        backgroundStatus = text("", 12f, muted)
        location.addView(background); location.addView(backgroundStatus)
        background.setOnCheckedChangeListener { _, checked ->
            if (!checked) { settings.backgroundFollow = false; save(); updateBackgroundStatus() }
            else if (!settings.backgroundFollow) {
                background.isChecked = false
                AlertDialog.Builder(this).setTitle(R.string.background_follow)
                    .setMessage(R.string.background_disclosure)
                    .setNegativeButton(R.string.not_now, null)
                    .setPositiveButton(R.string.continue_setup) { _, _ ->
                        settings.backgroundFollow = true; background.isChecked = true
                        save(); requestBackgroundPermission()
                    }.show()
            }
        }
        modes.setOnCheckedChangeListener { _, selected ->
            val checked = selected == follow.id
            settings.followCurrent = checked; fixed.visibility = if (checked) View.GONE else View.VISIBLE
            background.visibility = if (checked) View.VISIBLE else View.GONE
            if (checked) { settings.latitude = Double.NaN; settings.longitude = Double.NaN; settings.place = ""; settings.locationAt = 0 }
            if (!checked) {
                access.cancel()
                if (settings.point() != null) {
                    latitude.setText(String.format(java.util.Locale.ROOT, "%.6f", settings.latitude))
                    longitude.setText(String.format(java.util.Locale.ROOT, "%.6f", settings.longitude))
                    name.setText(settings.place)
                }
            }
            save(); recalculate(); updateBackgroundStatus(); if (checked) requestLocation()
        }
        updateLocationStatus()
        updateBackgroundStatus()

        val duration = card(getString(R.string.forecast_window))
        val spinner = Spinner(this)
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, WidgetSettings.DURATIONS.map { getString(R.string.duration_hours, it) })
        spinner.setSelection(WidgetSettings.DURATIONS.indexOf(settings.hours).coerceAtLeast(0))
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val hours = WidgetSettings.DURATIONS[position]
                if (settings.hours != hours) { settings.hours = hours; save(); drawCharts() }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        duration.addView(spinner)
        duration.addView(text(getString(R.string.refresh_explanation), 13f, muted))

        val appearance = card(getString(R.string.appearance))
        row(appearance, button(getString(R.string.light)) { applyTheme(0) }, button(getString(R.string.dark)) { applyTheme(1) }, button(getString(R.string.minimal)) { applyTheme(2) })
        fun colour(label: Int, initial: () -> Int, alpha: Boolean, apply: (Int) -> Unit) {
            appearance.addView(button(getString(label)) { ColourDialog.show(this, getString(label), initial(), alpha) { value -> apply(value); save(); drawCharts() } })
        }
        colour(R.string.background_colour, { settings.background }, true) { settings.background = it }
        colour(R.string.line_colour, { settings.curve }, false) { settings.curve = it }
        colour(R.string.text_colour, { settings.foreground }, false) { settings.foreground = it }
        fun slider(label: Int, max: Int, progress: Int, display: (Int) -> String = { it.toString() }, apply: (Int) -> Unit) {
            val caption = text("${getString(label)} · ${display(progress)}", 13f, muted); appearance.addView(caption)
            appearance.addView(SeekBar(this).apply {
                this.max = max; this.progress = progress; contentDescription = getString(label)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                        if (fromUser) { caption.text = "${getString(label)} · ${display(value)}"; apply(value); save(); drawCharts() }
                    }
                    override fun onStartTrackingTouch(bar: SeekBar?) {}
                    override fun onStopTrackingTouch(bar: SeekBar?) {}
                })
            })
        }
        slider(R.string.corner_radius, 32, settings.radius) { settings.radius = it }
        slider(R.string.padding, 32, settings.padding) { settings.padding = it }
        slider(R.string.vertical_padding, 32, settings.verticalPadding) { settings.verticalPadding = it }
        slider(R.string.line_width, 30, (settings.lineWidth * 10).roundToInt() - 7) { settings.lineWidth = (it + 7) / 10f }
        slider(R.string.label_size, 150, (settings.labelScale * 100).roundToInt() - 100, { "${it + 100}%" }) { settings.labelScale = (it + 100) / 100f }
        appearance.addView(Switch(this).apply { text = getString(R.string.show_guides); isChecked = settings.guides; minHeight = dp(48); setOnCheckedChangeListener { _, checked -> settings.guides = checked; save(); drawCharts() } })

        if (configuring) root.addView(button(getString(R.string.finish_setup)) {
            save(); setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)); WidgetUpdates.schedule(this); finish()
        }) else root.addView(button(getString(R.string.add_widget)) { pinWidget() })
        root.addView(button(getString(R.string.refresh)) { if (settings.followCurrent) requestLocation() else recalculate() })
        val about = card(getString(R.string.about_data))
        about.addView(text(getString(R.string.about_body), 13f, muted))
        about.addView(text(getString(R.string.attribution), 12f, muted))
        about.addView(button(getString(R.string.other_apps)) { openWebLink(R.string.other_apps_url) })
        about.addView(button(getString(R.string.privacy_policy)) {
            val policy = assets.open("PRIVACY.md").bufferedReader().use { it.readText() }
            AlertDialog.Builder(this).setTitle(R.string.privacy_policy).setMessage(policy.replace(Regex("(?m)^#{1,6} "), ""))
                .setNeutralButton(R.string.view_on_github) { _, _ -> openWebLink(R.string.privacy_policy_url) }
                .setPositiveButton(android.R.string.ok, null).show()
        })
        about.addView(button(getString(R.string.licences)) {
            val notices = try { assets.open("DATA_NOTICES.txt").bufferedReader().use { it.readText() } } catch (_: Exception) { getString(R.string.attribution) }
            AlertDialog.Builder(this).setTitle(R.string.licences).setMessage(notices)
                .setNeutralButton(R.string.view_on_github) { _, _ -> openWebLink(R.string.notices_url) }
                .setPositiveButton(android.R.string.ok, null).show()
        })
        drawCharts()
    }
    private fun openWebLink(url: Int) {
        try { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(getString(url)))) }
        catch (_: ActivityNotFoundException) { Toast.makeText(this, R.string.no_browser, Toast.LENGTH_SHORT).show() }
    }
    private fun applyTheme(index: Int) { settings.theme(index); save(); buildScreen(); drawCharts() }
    private fun save() { store.put(widgetId, settings); WidgetUpdates.enqueue(this); WidgetUpdates.schedule(this) }
    private fun recalculate() {
        val generation = ++requestGeneration
        val configuration = settings.copy()
        direction.text = getString(R.string.calculating)
        executor.execute {
            val value = TideRepository.snapshot(applicationContext, configuration)
            handler.post {
                if (!isFinishing && !isDestroyed && generation == requestGeneration) {
                    snapshot = value; drawCharts()
                }
            }
        }
    }
    private fun drawCharts() {
        if (!::preview.isInitialized) return
        val value = snapshot ?: TideSnapshot(System.currentTimeMillis(), settings.label(), error = getString(R.string.calculating), needsLocation = settings.point() == null)
        preview.setBackgroundColor(if (Color.alpha(settings.background) < 100) Color.rgb(38, 53, 71) else Color.TRANSPARENT)
        val width = if (preview.width > 0) (preview.width / resources.displayMetrics.density).roundToInt() else 320
        preview.setImageBitmap(ChartRenderer.bitmap(this, width.coerceAtLeast(110), 142, settings, value))
        preview.contentDescription = TideWidgetProvider.description(this, settings, value)
        if (!::detailChart.isInitialized) return
        val detailStyle = settings.copy(background = 0, foreground = ink, curve = Color.rgb(18, 107, 131), padding = 6, verticalPadding = 0)
        detailChart.setImageBitmap(ChartRenderer.bitmap(this, (width - 36).coerceAtLeast(110), 190, detailStyle, value, 24))
        if (value.signal == null) {
            current.text = "—"; direction.text = value.error ?: getString(R.string.unavailable)
            high.text = ""; low.text = ""; source.text = ""
        } else {
            current.text = ChartRenderer.cm(value.currentMetres)
            direction.text = getString(when { value.slope > .01 -> R.string.rising; value.slope < -.01 -> R.string.falling; else -> R.string.turning })
            high.text = "${getString(R.string.high_tide)}  ${event(value.nextHigh)}"
            low.text = "${getString(R.string.low_tide)}  ${event(value.nextLow)}"
            val location = value.modelLocation!!
            source.text = getString(R.string.model_support, location.method, location.distanceKm) + "\n" +
                String.format(java.util.Locale.getDefault(), "%.3f°, %.3f° · %s", location.represented.latitude, location.represented.longitude, ZoneId.systemDefault().id)
        }
        updateLocationStatus()
    }
    private fun event(value: TideEvent?): String = value?.let {
        DateTimeFormatter.ofPattern("EEE HH:mm z").format(Instant.ofEpochMilli(it.epochMillis).atZone(ZoneId.systemDefault())) + "  ·  " + ChartRenderer.cm(it.metres)
    } ?: getString(R.string.no_extremum)
    private fun updateLocationStatus() {
        if (!::locationStatus.isInitialized) return
        if (settings.followCurrent && locating) { locationStatus.text = getString(R.string.location_searching); return }
        if (settings.followCurrent && !access.permitted() && store.locationPermissionAsked) { locationStatus.text = getString(R.string.no_location_permission); return }
        locationStatus.text = if (settings.followCurrent) {
            val details = if (settings.locationAt > 0) {
                getString(R.string.location_age, DateTimeFormatter.ofPattern("d MMM HH:mm").format(Instant.ofEpochMilli(settings.locationAt).atZone(ZoneId.systemDefault())), settings.accuracy.roundToInt()) + "\n"
            } else ""
            details + getString(if (settings.backgroundFollow && BackgroundLocation.permitted(this)) R.string.location_following else R.string.location_stored)
        } else settings.label()
    }
    private fun requestLocation() {
        if (stopped || !settings.followCurrent) return
        if (!access.permitted()) {
            store.locationPermissionAsked = true
            requestPermissions(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION), 21)
            return
        }
        locationStatus.text = getString(R.string.location_searching)
        locating = true
        access.request { location ->
            locating = false
            if (stopped || !settings.followCurrent) return@request
            if (location == null) locationStatus.text = getString(R.string.location_failed)
            else {
                settings.latitude = location.latitude; settings.longitude = location.longitude; settings.place = ""
                settings.locationAt = location.time; settings.accuracy = location.accuracy
                // Refresh the single retained fix of other current-location widgets.
                for (id in WidgetUpdates.ids(this)) {
                    if (id == widgetId) continue
                    store.update(id) { other -> if (other.followCurrent && location.time >= other.locationAt) {
                        other.latitude = location.latitude; other.longitude = location.longitude; other.place = ""; other.locationAt = location.time; other.accuracy = location.accuracy
                    } }
                }
                save(); updateLocationStatus(); recalculate()
            }
        }
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grants: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grants)
        if (requestCode == 21) {
            if (access.permitted()) requestLocation() else locationStatus.text = getString(R.string.no_location_permission)
            val followSetup = continueBackgroundPermission; continueBackgroundPermission = false
            if (followSetup && settings.followCurrent && settings.backgroundFollow && access.permitted()) requestBackgroundPermission()
        }
        if (requestCode == 22) { BackgroundLocation.sync(this, force = true); updateBackgroundStatus() }
    }
    private fun updateBackgroundStatus() {
        if (!::backgroundStatus.isInitialized) return
        backgroundStatus.visibility = if (settings.followCurrent) View.VISIBLE else View.GONE
        backgroundStatus.text = getString(when {
            !settings.backgroundFollow -> R.string.background_off
            !BackgroundLocation.permitted(this) -> R.string.background_permission_missing
            else -> R.string.location_following
        })
    }
    private fun requestBackgroundPermission() {
        if (!access.permitted()) { continueBackgroundPermission = true; requestLocation(); return }
        if (BackgroundLocation.permitted(this)) { BackgroundLocation.sync(this, force = true); updateBackgroundStatus(); return }
        if (Build.VERSION.SDK_INT == 29) requestPermissions(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), 22)
        else if (Build.VERSION.SDK_INT >= 30) {
            val option = packageManager.backgroundPermissionOptionLabel
            AlertDialog.Builder(this).setTitle(R.string.background_permission_title)
                .setMessage(getString(R.string.background_permission_instructions, option))
                .setNegativeButton(R.string.not_now, null)
                .setPositiveButton(R.string.open_settings) { _, _ -> startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$packageName"))) }.show()
        }
        updateBackgroundStatus()
    }
    private fun pinWidget() {
        val manager = AppWidgetManager.getInstance(this)
        if (!manager.isRequestPinAppWidgetSupported) { Toast.makeText(this, R.string.pin_not_supported, Toast.LENGTH_LONG).show(); return }
        val draft = settings.copy(hours = if (widgetId == 0) settings.hours else 4)
        store.put(0, draft)
        val callback = PendingIntent.getBroadcast(this, 70, Intent(this, PinReceiver::class.java).putExtra("settings", draft.toJson()), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
        manager.requestPinAppWidget(ComponentName(this, TideWidgetProvider::class.java), null, callback)
    }
}
