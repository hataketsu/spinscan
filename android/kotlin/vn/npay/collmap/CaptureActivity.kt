package vn.npay.collmap

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.ImageReader
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.util.Range
import android.util.Size
import android.view.Gravity
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import vn.npay.collmap.Ui.MATCH
import vn.npay.collmap.Ui.WRAP
import vn.npay.collmap.Ui.dp
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The capture screen, built for photogrammetry rather than for pretty pictures.
 *
 * Three things matter to the reconstruction and all three are fought for here:
 *
 *  - Consistency. Once locked, exposure, white balance and focus stay put for the
 *    whole run. Auto-everything makes each frame a slightly different camera, and
 *    COLMAP is calibrating one camera across all of them.
 *  - Sharpness. A capture is held back while the phone is still moving, so a
 *    turntable that has not settled does not put a smeared frame in the set.
 *  - Coverage. The tilt readout exists because a single elevation ring is the
 *    most common way a capture comes out unreconstructable from above or below.
 *  - Framing. The elliptical mask is dialled in here, over the viewfinder,
 *    because what it throws away is the room, and on a rig where only the table
 *    moves the room is what turns into geometry sitting on top of the subject.
 *    The reasoning behind the shape lives in Mask.kt.
 *
 * A run is a whole session and not a toggle to babysit: the two numbers the
 * user sets -- how many photos and how long between them -- decide the step
 * angle and the length of the run, and the sequence stops by itself at the end.
 *
 * With a board on the OTG port the run is closed-loop: shutter, STEP, wait for
 * the board's "ok", shutter again. Handheld it falls back to the free-running
 * interval timer, where the phone and the table each keep their own clock and
 * only the stillness gate stands between the two drifting apart.
 */
class CaptureActivity : Activity() {

    // ---- config -----------------------------------------------------------
    private var server = ""
    private var project = ""
    private var intervalMs = 4_000L
    private var shots = DEFAULT_SHOTS     // photos per run, and the board's nấc count
    private var preset = DEFAULT_PRESET   // index into PRESETS, or -1 once touched by hand
    private var capResolution = true      // cap at ~12 MP unless the user opts out
    private var ratio = DEFAULT_RATIO     // aspect the still stream is pinned to
    private var ratioOptions = listOf(DEFAULT_RATIO)
    private var tier = 1                  // 0 low, 1 level, 2 high
    private var stateDetail = ""

    // ---- camera -----------------------------------------------------------
    private lateinit var textureView: TextureView
    private var cameraDevice: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var previewBuilder: CaptureRequest.Builder? = null
    private var imageReader: ImageReader? = null
    private var cameraId = ""
    private var sensorOrientation = 90
    private var jpegSize: Size = Size(4000, 3000)
    private var previewSize: Size = Size(1920, 1080)
    private var zoomRange: Range<Float>? = null
    private var activeArray: Rect? = null
    private var maxDigitalZoom = 1f
    private var zoom = 1f
    /** Dioptres at the near end; 0 means the lens does not focus at all. */
    private var minFocusDiopters = 0f
    private var manualFocus = false
    private var focusDiopters = 0f
    private var thread: HandlerThread? = null
    private var camHandler: Handler? = null
    private val main = Handler(Looper.getMainLooper())

    // ---- run state --------------------------------------------------------
    private val uploads = Executors.newSingleThreadExecutor()
    private val pending = AtomicInteger()
    private val failed = AtomicInteger()
    private val shot = AtomicInteger()
    private var autoRunning = false
    private var locked = false
    private var awaitingLock = false
    private var frameIndex = 0            // frames landed in this run
    private var requested = 0             // shutters fired in this run
    private var runStart = 0L             // elapsedRealtime when the run began
    private var runFailedAt = 0           // upload failures already on the counter
    private var runId = 0                 // so a stale guard cannot end the next run

    // ---- turntable sequence ------------------------------------------------
    private var boardRunning = false
    private var awaitingStep = false      // STEP sent, board has not said "ok"
    private var awaitingFrame = false     // shutter fired, JPEG not queued yet
    private var frameRetries = 0
    private var stallMessage = ""

    // ---- live view + remote control ----------------------------------------
    private val liveSender = Executors.newSingleThreadExecutor()
    private val liveBusy = AtomicBoolean(false)
    private var liveOn = false
    @Volatile private var commandsOn = false
    @Volatile private var commandThread: Thread? = null

    // ---- motion -----------------------------------------------------------
    private var sensors: SensorManager? = null
    private var accel: Sensor? = null
    private var lastAccel = FloatArray(3)
    private var jitter = 0f
    private var pitchDeg = 0f

    // ---- mask ---------------------------------------------------------------
    private var mask = MaskGeom.DEFAULT
    private var maskMode = Mask.MODE_OFF
    private var maskEditing = false
    private val maskWarned = AtomicBoolean(false)

    // ---- views ------------------------------------------------------------
    private lateinit var counterView: TextView
    private lateinit var planView: TextView
    private lateinit var timeView: TextView
    private lateinit var stateView: TextView
    private lateinit var tiltView: TextView
    private lateinit var lockButton: android.widget.Button
    private lateinit var autoButton: android.widget.Button
    private lateinit var intervalButton: android.widget.Button
    private lateinit var shotsButton: android.widget.Button
    private lateinit var presetButton: android.widget.Button
    private lateinit var tierButton: android.widget.Button
    private lateinit var resButton: android.widget.Button
    private lateinit var ratioButton: android.widget.Button
    private lateinit var focusButton: android.widget.Button
    private lateinit var focusBar: SeekBar
    private lateinit var focusView: TextView
    private lateinit var focusRow: View
    private lateinit var maskButton: android.widget.Button
    private lateinit var maskEditButton: android.widget.Button
    private lateinit var maskResetButton: android.widget.Button
    private lateinit var maskOverlay: MaskOverlay
    private lateinit var maskBars: View
    private lateinit var maskSizeBar: SeekBar
    private lateinit var maskSquashBar: SeekBar
    private lateinit var maskRotBar: SeekBar
    private lateinit var zoomView: TextView
    private lateinit var tabViews: List<TextView>
    private lateinit var tabPanels: List<View>
    private var tab = 0
    private lateinit var topStrip: View
    private lateinit var bottomStrip: View

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        server = intent.getStringExtra("server") ?: ""
        project = intent.getStringExtra("project") ?: ""
        /* Whatever the turntable screen last read off the board: the shutter
         * period only makes sense if it matches the table's step period, and
         * making the user type it twice is how the two drift apart. */
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        preset = prefs.getInt(KEY_PRESET, DEFAULT_PRESET).coerceIn(-1, PRESETS.size - 1)
        val base = PRESETS.getOrNull(preset) ?: PRESETS[DEFAULT_PRESET]
        /* Whatever was last set, falling back to the preset's own numbers so a
         * first run is already a sane one. */
        shots = prefs.getInt(TurntableActivity.KEY_SHOTS, 0).takeIf { it in MIN_SHOTS..MAX_SHOTS }
            ?: base.shots
        val saved = prefs.getInt(TurntableActivity.KEY_INTERVAL, 0).toLong()
        intervalMs = if (saved in 1_000..120_000) saved else base.intervalMs
        capResolution = prefs.getBoolean(KEY_CAP, base.cap)
        ratio = prefs.getString(KEY_RATIO, DEFAULT_RATIO) ?: DEFAULT_RATIO
        zoom = prefs.getFloat(KEY_ZOOM, 1f)
        mask = Mask.load(prefs)
        maskMode = Mask.mode(prefs)
        tab = prefs.getInt(KEY_TAB, 0)
        buildUi()
        sensors = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accel = sensors?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    }

    // ------------------------------------------------------------------- UI

    private fun buildUi() {
        val ctx = this
        val root = FrameLayout(ctx).apply { setBackgroundColor(Ui.GROUND) }
        textureView = TextureView(ctx)
        root.addView(textureView, FrameLayout.LayoutParams(MATCH, MATCH))
        // Above the picture, under the control strips: it is a framing aid, not
        // a control surface, and it only takes touches while it is being edited.
        maskOverlay = MaskOverlay(ctx).apply {
            geom = mask
            showing = maskMode != Mask.MODE_OFF
            onCommit = { g -> mask = g; Mask.save(prefs(), g) }
        }
        root.addView(maskOverlay, FrameLayout.LayoutParams(MATCH, MATCH))

        // top strip
        val top = Ui.row(ctx).apply {
            setPadding(dp(16f), dp(12f), dp(16f), dp(12f))
            background = Ui.box(ctx, 0xAA0B0F14.toInt(), 0, 0f)
        }
        counterView = Ui.mono(ctx, "0 ảnh", 15f, Ui.AMBER)
        planView = Ui.text(ctx, "", 12f, Ui.DIM)
        stateView = Ui.text(ctx, project, 13f, Ui.DIM)
        timeView = Ui.mono(ctx, "", 12f, Ui.AMBER).apply { visibility = View.GONE }
        renderState()
        renderPlan()
        val titleCol = Ui.column(ctx).apply {
            addView(counterView); addView(planView); addView(stateView); addView(timeView)
        }
        top.addView(titleCol, Ui.lp(0, WRAP, 1f))
        zoomView = Ui.mono(ctx, "1.0x", 13f, Ui.DIM)
        top.addView(zoomView)
        top.addView(Ui.gap(ctx, 10f))
        tiltView = Ui.mono(ctx, "—", 13f, Ui.DIM)
        top.addView(tiltView)
        root.addView(top, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.TOP))
        topStrip = top
        top.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            configureTransform(textureView.width, textureView.height)
        }

        /* Bottom strip: a row of tabs over one panel, with the shutter and the
         * auto-run button below and always visible. Thirteen controls stacked in
         * rows stopped being a layout a while ago -- and of all of them only
         * those two are wanted once the shooting has actually started, so they
         * are the two that never move and never scroll away. */
        val bottom = Ui.column(ctx).apply {
            setPadding(dp(12f), dp(10f), dp(12f), dp(14f))
            background = Ui.box(ctx, 0xAA0B0F14.toInt(), 0, 0f)
        }
        lockButton = Ui.button(ctx, "Khoá AE/AF")
        autoButton = Ui.button(ctx, autoLabel(), Ui.AMBER)
        intervalButton = Ui.button(ctx, "${intervalMs / 1000}s")
        shotsButton = Ui.button(ctx, "$shots ảnh")
        presetButton = Ui.button(ctx, presetLabel())
        tierButton = Ui.button(ctx, "Ngang")
        resButton = Ui.button(ctx, if (capResolution) "12MP" else "Max")
        ratioButton = Ui.button(ctx, ratio)
        focusButton = Ui.button(ctx, "Nét: Tự động")
        maskButton = Ui.button(ctx, maskLabel())
        maskEditButton = Ui.button(ctx, "Sửa vòng")
        maskResetButton = Ui.button(ctx, "Mặc định")
        val shutter = Ui.button(ctx, "Chụp", Ui.BLUE)

        fun fill(row: LinearLayout, items: List<Pair<View, Float>>) {
            for ((v, w) in items) {
                row.addView(v, Ui.lp(0, WRAP, w))
                row.addView(Ui.gap(ctx, 6f))
            }
        }
        // "Chụp": what a run is -- the numbers, and the plan they imply, which
        // is spelled out on the top strip where it stays readable mid-run.
        val runPanel = Ui.column(ctx)
        val runRow = Ui.row(ctx)
        fill(runRow, listOf(presetButton to 1f, shotsButton to 1f, intervalButton to 0.7f))
        runPanel.addView(runRow, Ui.lp(MATCH, WRAP))

        // "Ảnh": what the file looks like.
        val imagePanel = Ui.column(ctx)
        val frameRow = Ui.row(ctx)
        fill(frameRow, listOf(ratioButton to 0.8f, resButton to 0.8f, tierButton to 0.9f))
        imagePanel.addView(frameRow, Ui.lp(MATCH, WRAP))

        // "Nét": everything that has to hold still for the whole set.
        val focusPanel = Ui.column(ctx)
        val lockRow = Ui.row(ctx)
        fill(lockRow, listOf(lockButton to 1.2f, focusButton to 1.2f))
        // Only shown in manual focus; a slider with nothing behind it is a trap.
        val focusLine = Ui.row(ctx)
        focusBar = SeekBar(ctx).apply { max = FOCUS_STEPS }
        focusView = Ui.mono(ctx, "∞", 13f, Ui.AMBER)
        focusLine.addView(focusBar, Ui.lp(0, WRAP, 1f))
        focusLine.addView(Ui.gap(ctx, 10f))
        focusLine.addView(focusView, Ui.lp(WRAP, WRAP))
        focusLine.visibility = View.GONE
        focusRow = focusLine
        focusPanel.addView(lockRow, Ui.lp(MATCH, WRAP))
        focusPanel.addView(focusRow, Ui.lp(MATCH, WRAP, 0f, 6, ctx))
        focusPanel.addView(
            Ui.text(ctx, "Chụm hai ngón trên khung ngắm để đổi zoom · đọc ở góc trên",
                12f, Ui.DIM), Ui.lp(MATCH, WRAP, 0f, 8, ctx))

        // "Mask": the ellipse and the three numbers that shape it.
        val maskPanel = Ui.column(ctx)
        val maskRow = Ui.row(ctx)
        fill(maskRow, listOf(maskButton to 2f, maskEditButton to 1.1f))
        // Sliders and not a pinch: pinch is already zoom, and a second meaning
        // for it would cost the one that is there.
        fun slider(name: String, bar: SeekBar): View = Ui.row(ctx).apply {
            addView(Ui.text(ctx, name, 12f, Ui.DIM).apply { width = dp(58f) })
            addView(bar, Ui.lp(0, WRAP, 1f))
        }
        maskSizeBar = SeekBar(ctx).apply { max = MASK_STEPS }
        maskSquashBar = SeekBar(ctx).apply { max = MASK_STEPS }
        maskRotBar = SeekBar(ctx).apply { max = MASK_STEPS }
        maskBars = Ui.column(ctx).apply {
            addView(slider("Cỡ", maskSizeBar), Ui.lp(MATCH, WRAP))
            addView(slider("Dẹt", maskSquashBar), Ui.lp(MATCH, WRAP))
            addView(slider("Nghiêng", maskRotBar), Ui.lp(MATCH, WRAP))
            addView(maskResetButton, Ui.lp(MATCH, WRAP, 0f, 4, ctx))
            visibility = View.GONE
        }
        maskPanel.addView(maskRow, Ui.lp(MATCH, WRAP))
        maskPanel.addView(maskBars, Ui.lp(MATCH, WRAP, 0f, 4, ctx))

        tabPanels = listOf(runPanel, imagePanel, focusPanel, maskPanel)
        val panelBox = FrameLayout(ctx)
        for (panel in tabPanels) panelBox.addView(panel, FrameLayout.LayoutParams(MATCH, WRAP))
        /* The panel scrolls instead of growing: the tallest one is the mask, and
         * on a short phone it would otherwise walk the shutter off the screen. */
        val panelScroll = object : ScrollView(ctx) {
            override fun onMeasure(widthSpec: Int, heightSpec: Int) = super.onMeasure(
                widthSpec, MeasureSpec.makeMeasureSpec(dp(220f), MeasureSpec.AT_MOST))
        }
        panelScroll.addView(panelBox, FrameLayout.LayoutParams(MATCH, WRAP))

        val tabRow = Ui.row(ctx)
        tabViews = TAB_NAMES.mapIndexed { i, name ->
            Ui.text(ctx, name, 14f, Ui.DIM).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(6f), 0, dp(8f))
                setOnClickListener { selectTab(i) }
            }
        }
        for (t in tabViews) tabRow.addView(t, Ui.lp(0, WRAP, 1f))

        val shutterRow = Ui.row(ctx)
        fill(shutterRow, listOf(shutter to 1f, autoButton to 1.4f))
        bottom.addView(tabRow, Ui.lp(MATCH, WRAP))
        bottom.addView(panelScroll, Ui.lp(MATCH, WRAP))
        bottom.addView(shutterRow, Ui.lp(MATCH, WRAP, 0f, 8, ctx))
        root.addView(bottom, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM))
        bottomStrip = bottom
        // The strips only get a height once laid out, and the letterboxed frame
        // is positioned around them, so re-run the fit when that height lands.
        bottom.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            configureTransform(textureView.width, textureView.height)
        }
        setContentView(root)

        lockButton.setOnClickListener { toggleLock() }
        shutter.setOnClickListener { requestCapture(manual = true) }
        autoButton.setOnClickListener { if (autoRunning) stopAuto() else startAuto() }
        intervalButton.setOnClickListener { cycleInterval() }
        intervalButton.setOnLongClickListener {
            if (!runFrozen()) askNumber("Giây giữa hai ảnh", (intervalMs / 1000).toInt()) {
                intervalMs = it.coerceIn(1, 120) * 1000L
                onRunSettingChanged()
            }
            true
        }
        shotsButton.setOnClickListener { cycleShots() }
        shotsButton.setOnLongClickListener {
            if (!runFrozen()) askNumber("Số ảnh mỗi vòng", shots) {
                shots = it.coerceIn(MIN_SHOTS, MAX_SHOTS)
                onRunSettingChanged()
            }
            true
        }
        presetButton.setOnClickListener { cyclePreset() }
        maskButton.setOnClickListener { cycleMaskMode() }
        maskEditButton.setOnClickListener { setMaskEditing(!maskEditing) }
        maskResetButton.setOnClickListener {
            if (maskFrozen()) return@setOnClickListener
            maskOverlay.reset()
            mask = maskOverlay.geom
            Mask.save(prefs(), mask)
            syncMaskBars()
        }
        maskBar(maskSizeBar) { f -> maskOverlay.setSize(Mask.MIN_R + f * (Mask.MAX_R - Mask.MIN_R)) }
        maskBar(maskSquashBar) { f -> maskOverlay.setSquash(SQUASH_MIN + f * (SQUASH_MAX - SQUASH_MIN)) }
        maskBar(maskRotBar) { f -> maskOverlay.setRot((f * 2f - 1f) * Mask.ROT_MAX) }
        tierButton.setOnClickListener { cycleTier() }
        resButton.setOnClickListener { toggleResolution() }
        ratioButton.setOnClickListener { cycleRatio() }
        focusButton.setOnClickListener { toggleFocusMode() }
        focusBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, p: Int, fromUser: Boolean) {
                if (!fromUser) return
                focusDiopters = minFocusDiopters * p / FOCUS_STEPS
                focusView.text = focusLabel()
                applyFocus()
            }
            override fun onStartTrackingTouch(bar: SeekBar) = Unit
            override fun onStopTrackingTouch(bar: SeekBar) = Unit
        })
        textureView.setOnTouchListener { _, e -> zoomDetector.onTouchEvent(e); true }
        syncMaskUi()
        selectTab(tab)
    }

    /** Amber for the tab you are on, dim for the rest; that is enough of a
     *  selected state without a widget library to draw an indicator with. */
    private fun selectTab(index: Int) {
        tab = index.coerceIn(0, tabPanels.size - 1)
        tabViews.forEachIndexed { i, v -> v.setTextColor(if (i == tab) Ui.AMBER else Ui.DIM) }
        tabPanels.forEachIndexed { i, v ->
            v.visibility = if (i == tab) View.VISIBLE else View.GONE
        }
        prefs().edit().putInt(KEY_TAB, tab).apply()
    }

    private fun prefs() = getSharedPreferences(PREFS, MODE_PRIVATE)

    /** Every mask slider behaves the same: live while dragged, saved on release. */
    private fun maskBar(bar: SeekBar, apply: (Float) -> Unit) {
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(b: SeekBar, p: Int, fromUser: Boolean) {
                if (!fromUser) return
                if (maskFrozen()) { syncMaskBars(); return }
                apply(p.toFloat() / MASK_STEPS)
                mask = maskOverlay.geom
            }
            override fun onStartTrackingTouch(b: SeekBar) = Unit
            override fun onStopTrackingTouch(b: SeekBar) = Mask.save(prefs(), mask)
        })
    }

    // ----------------------------------------------------------------- zoom

    /**
     * Zoom crops the sensor optically, so the object fills more pixels without
     * anyone moving the rig -- but it also moves the focal length COLMAP solves
     * for as one fixed intrinsic, so it is set before a run and never during.
     */
    private val zoomDetector: ScaleGestureDetector by lazy {
        ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(d: ScaleGestureDetector): Boolean {
                if (autoRunning) {
                    toast("Đang chụp — dừng lại rồi mới đổi zoom")
                    return false
                }
                return true
            }
            override fun onScale(d: ScaleGestureDetector): Boolean {
                setZoom(zoom * d.scaleFactor)
                return true
            }
        })
    }

    private fun setZoom(value: Float) {
        val range = zoomRange
        val clamped = if (range != null) value.coerceIn(range.lower, range.upper)
                      else value.coerceIn(1f, maxDigitalZoom)
        if (abs(clamped - zoom) < 0.01f) return
        zoom = clamped
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putFloat(KEY_ZOOM, zoom).apply()
        previewBuilder?.let { applyZoom(it) }
        repeatPreview()
        renderZoom()
    }

    private fun applyZoom(b: CaptureRequest.Builder) {
        val range = zoomRange
        if (range != null) {
            b.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoom.coerceIn(range.lower, range.upper))
            return
        }
        // Older API levels, and the odd device that omits the ratio key: the
        // same crop written out as a rectangle of the active array.
        val a = activeArray ?: return
        if (zoom <= 1f) {
            b.set(CaptureRequest.SCALER_CROP_REGION, a)
            return
        }
        val w = (a.width() / zoom).toInt()
        val h = (a.height() / zoom).toInt()
        val x = a.left + (a.width() - w) / 2
        val y = a.top + (a.height() - h) / 2
        b.set(CaptureRequest.SCALER_CROP_REGION, Rect(x, y, x + w, y + h))
    }

    private fun renderZoom() {
        zoomView.text = "%.1fx".format(zoom)
        zoomView.setTextColor(if (zoom > 1.05f) Ui.AMBER else Ui.DIM)
    }

    // ---------------------------------------------------------------- focus

    /**
     * The whole set has to share one focus distance or COLMAP is calibrating a
     * lens that keeps changing. Auto-then-lock covers most rigs; manual is the
     * only guarantee on a phone whose AF hunts over a repetitive pattern, which
     * is exactly what an ArUco mat is.
     */
    private fun toggleFocusMode() {
        if (minFocusDiopters <= 0f) return
        manualFocus = !manualFocus
        if (manualFocus) {
            awaitingLock = false
            focusBar.progress = (focusDiopters / minFocusDiopters * FOCUS_STEPS).toInt()
        }
        applyFocus()
        syncFocusUi()
    }

    private fun applyFocus() {
        val b = previewBuilder ?: return
        if (manualFocus) {
            b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
            b.set(CaptureRequest.LENS_FOCUS_DISTANCE, focusDiopters)
        } else {
            b.set(CaptureRequest.CONTROL_AF_MODE,
                if (locked) CameraMetadata.CONTROL_AF_MODE_AUTO
                else CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        }
        repeatPreview()
    }

    private fun syncFocusUi() {
        val hasFocus = minFocusDiopters > 0f
        focusButton.visibility = if (hasFocus) View.VISIBLE else View.GONE
        focusRow.visibility = if (hasFocus && manualFocus) View.VISIBLE else View.GONE
        focusButton.text = if (manualFocus) "Nét: Tay" else "Nét: Tự động"
        focusButton.setTextColor(if (manualFocus) Ui.AMBER else Ui.INK)
        focusView.text = focusLabel()
    }

    /** The camera speaks dioptres; a user standing at the rig thinks in metres. */
    private fun focusLabel(): String =
        if (focusDiopters <= 0.05f) "∞" else "%.2f m".format(1f / focusDiopters)

    // ----------------------------------------------------------- run settings

    /**
     * A run is described by two numbers -- how many photos and how long between
     * them -- and everything else follows: the step angle is 360/shots, the
     * session lasts shots*interval. Those consequences are displayed rather
     * than typed, which is the whole reason for setting it this way round.
     */
    private fun renderPlan() {
        planView.text = "$shots ảnh · ${twoDp(360f / shots)}°/ảnh · ${roughly(shots * intervalMs)}"
    }

    /** Vietnamese writes the decimal with a comma, whatever the phone's locale. */
    private fun twoDp(v: Float) = String.format(Locale.US, "%.2f", v).replace('.', ',')

    private fun roughly(ms: Long): String =
        if (ms < 60_000) "${ms / 1000} giây" else "${Math.round(ms / 60_000.0)} phút"

    private fun clock(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60)
               else "%02d:%02d".format(s / 60, s % 60)
    }

    private fun spoken(ms: Long): String {
        val s = ms / 1000
        return if (s < 60) "$s giây" else "${s / 60} phút %02d".format(s % 60)
    }

    /** Nothing that describes a run may change while the run is happening. */
    private fun runFrozen(): Boolean {
        if (!autoRunning) return false
        toast("Đang chụp — dừng lại rồi mới đổi cài đặt")
        return true
    }

    private fun cycleShots() {
        if (runFrozen()) return
        shots = SHOT_STEPS.firstOrNull { it > shots } ?: SHOT_STEPS.first()
        onRunSettingChanged()
    }

    private fun cycleInterval() {
        if (runFrozen()) return
        intervalMs = INTERVAL_STEPS.firstOrNull { it > intervalMs } ?: INTERVAL_STEPS.first()
        onRunSettingChanged()
    }

    /** The cycles are a shortcut, not a cage: an object that rings for eight
     *  seconds needs eight seconds, and 137 photos is a legitimate answer. */
    private fun askNumber(title: String, value: Int, apply: (Int) -> Unit) {
        val input = Ui.input(this, value.toString(), value.toString()).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        val wrap = Ui.column(this).apply {
            setPadding(dp(20f), dp(20f), dp(20f), 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(wrap)
            .setPositiveButton("OK") { _, _ ->
                input.text.toString().trim().toIntOrNull()?.let(apply)
            }
            .setNegativeButton("Huỷ", null)
            .show()
    }

    private fun cyclePreset() {
        if (runFrozen()) return
        preset = (preset + 1 + PRESETS.size) % PRESETS.size
        val p = PRESETS[preset]
        shots = p.shots
        intervalMs = p.intervalMs
        val resChanged = capResolution != p.cap
        capResolution = p.cap
        persistRun()
        pushRunToBoard()
        syncRunUi()
        if (resChanged) {
            closeCamera()
            openCamera()
        }
    }

    private fun presetLabel() = PRESETS.getOrNull(preset)?.name ?: "Tuỳ chỉnh"

    /** One control touched by hand and the bundle no longer describes the
     *  state, so the label stops claiming that it does. */
    private fun onRunSettingChanged() {
        preset = -1
        persistRun()
        pushRunToBoard()
        syncRunUi()
    }

    private fun persistRun() {
        prefs().edit()
            .putInt(TurntableActivity.KEY_INTERVAL, intervalMs.toInt())
            .putInt(TurntableActivity.KEY_SHOTS, shots)
            .putInt(KEY_PRESET, preset)
            .putBoolean(KEY_CAP, capResolution)
            .apply()
    }

    /**
     * The board is told how many photos, never how many steps: it divides
     * steps_per_rev by the shot count itself and spreads the remainder over the
     * revolution so the angle cannot drift, and only it knows its microstepping
     * and its gearing.
     *
     * It follows the phone's interval too, because the table has to stand still
     * for as long as the shutter waits or the two run out of step within a few
     * nấc. Nothing goes down mid-sequence: there the board is stepped one nấc
     * at a time and its "ok" to a SET would be mistaken for the answer to a STEP.
     */
    private fun pushRunToBoard() {
        if (boardRunning) return
        val serial = Ch340.shared?.takeIf { it.isOpen } ?: return
        serial.send("SET shots $shots")
        serial.send("SET interval $intervalMs")
    }

    private fun syncRunUi() {
        shotsButton.text = "$shots ảnh"
        intervalButton.text = "${intervalMs / 1000}s"
        resButton.text = if (capResolution) "12MP" else "Max"
        presetButton.text = presetLabel()
        presetButton.setTextColor(if (preset < 0) Ui.DIM else Ui.INK)
        if (!autoRunning) autoButton.text = autoLabel()
        renderPlan()
        renderState()
        updateCounter()
    }

    private fun cycleTier() {
        if (runFrozen()) return
        tier = (tier + 1) % 3
        tierButton.text = listOf("Thấp", "Ngang", "Cao")[tier]
    }

    private fun toggleResolution() {
        if (runFrozen()) return
        capResolution = !capResolution
        preset = -1
        persistRun()
        syncRunUi()
        // Reopen so the still stream is rebuilt at the new size.
        closeCamera()
        openCamera()
    }

    // ------------------------------------------------------------------- mask

    private fun maskLabel() = "Vòng cắt: ${Mask.modeLabel(maskMode)}"

    /**
     * The mask decides the crop and the crop decides the intrinsics, so every
     * frame of a set has to be cut by the same ellipse. Same rule as zoom, and
     * the same refusal.
     */
    private fun maskFrozen(): Boolean {
        if (!autoRunning) return false
        toast("Đang chụp — dừng lại rồi mới đổi vòng cắt")
        return true
    }

    private fun cycleMaskMode() {
        if (maskFrozen()) return
        maskMode = (maskMode + 1) % 3
        Mask.setMode(prefs(), maskMode)
        syncMaskUi()
    }

    private fun setMaskEditing(on: Boolean) {
        if (on && maskFrozen()) return
        maskEditing = on
        syncMaskUi()
    }

    private fun syncMaskUi() {
        maskButton.text = maskLabel()
        maskButton.setTextColor(if (maskMode == Mask.MODE_OFF) Ui.INK else Ui.AMBER)
        maskEditButton.text = if (maskEditing) "Xong vòng" else "Sửa vòng"
        maskEditButton.setTextColor(if (maskEditing) Ui.AMBER else Ui.INK)
        maskBars.visibility = if (maskEditing) View.VISIBLE else View.GONE
        maskOverlay.editing = maskEditing
        maskOverlay.showing = maskMode != Mask.MODE_OFF
        if (maskEditing) syncMaskBars()
    }

    private fun syncMaskBars() {
        val g = maskOverlay.geom
        maskSizeBar.progress = barAt((g.rx - Mask.MIN_R) / (Mask.MAX_R - Mask.MIN_R))
        maskSquashBar.progress =
            barAt((maskOverlay.squash() - SQUASH_MIN) / (SQUASH_MAX - SQUASH_MIN))
        maskRotBar.progress = barAt((g.rot / Mask.ROT_MAX + 1f) / 2f)
    }

    private fun barAt(f: Float) = (f.coerceIn(0f, 1f) * MASK_STEPS).toInt()

    /** Once per session: a mask that cannot run should not bury the screen. */
    private fun warnMaskFailed() {
        if (!maskWarned.compareAndSet(false, true)) return
        main.post { toast("Hết bộ nhớ cho vòng cắt — gửi ảnh gốc") }
    }

    private fun cycleRatio() {
        if (runFrozen()) return
        if (ratioOptions.size < 2) {
            toast("Camera chỉ báo một tỉ lệ ảnh: $ratio")
            return
        }
        ratio = ratioOptions[(ratioOptions.indexOf(ratio).coerceAtLeast(0) + 1) % ratioOptions.size]
        ratioButton.text = ratio
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_RATIO, ratio).apply()
        if (ratio != DEFAULT_RATIO) {
            toast("Tỉ lệ $ratio là vùng cắt của cảm biến, ảnh mất bớt điểm ảnh cho việc dựng hình.")
        }
        // Same reopen as the resolution toggle: the still stream size changes.
        closeCamera()
        openCamera()
    }

    // --------------------------------------------------------------- camera

    override fun onResume() {
        super.onResume()
        attachBoard()
        renderState()
        autoButton.text = autoLabel()
        startLive()
        startCommands()
        thread = HandlerThread("cam").also { it.start() }
        camHandler = Handler(thread!!.looper)
        accel?.let {
            sensors?.registerListener(motionListener, it, SensorManager.SENSOR_DELAY_GAME)
        }
        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(s: SurfaceTexture, w: Int, h: Int) {
                configureTransform(w, h)
                openCamera()
            }
            override fun onSurfaceTextureSizeChanged(s: SurfaceTexture, w: Int, h: Int) =
                configureTransform(w, h)
            override fun onSurfaceTextureDestroyed(s: SurfaceTexture) = true
            override fun onSurfaceTextureUpdated(s: SurfaceTexture) = Unit
        }
        if (textureView.isAvailable) {
            configureTransform(textureView.width, textureView.height)
            openCamera()
        }
    }

    override fun onPause() {
        stopAuto()
        stopLive()
        stopCommands()
        detachBoard()
        sensors?.unregisterListener(motionListener)
        closeCamera()
        thread?.quitSafely()
        thread = null
        camHandler = null
        super.onPause()
    }

    override fun onDestroy() {
        uploads.shutdown()
        liveSender.shutdown()
        super.onDestroy()
    }

    private fun openCamera() {
        val mgr = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            cameraId = mgr.cameraIdList.first {
                mgr.getCameraCharacteristics(it)
                    .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            }
            val ch = mgr.getCameraCharacteristics(cameraId)
            sensorOrientation = ch.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
            syncRatioOptions(map)
            /* CONTROL_ZOOM_RATIO_RANGE only exists from API 30; below that the
             * crop region is all there is. */
            zoomRange = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                ch.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE) else null
            activeArray = ch.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            maxDigitalZoom = ch.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
            zoom = zoomRange?.let { zoom.coerceIn(it.lower, it.upper) }
                ?: zoom.coerceIn(1f, maxDigitalZoom)
            /* A fixed-focus lens reports 0 dioptres at the near end: there is no
             * distance to set, so the manual option is not offered at all. */
            minFocusDiopters = ch.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
            if (minFocusDiopters <= 0f) manualFocus = false
            focusDiopters = focusDiopters.coerceIn(0f, minFocusDiopters)
            main.post { renderZoom(); syncFocusUi() }
            /* Still first, viewfinder second: the preview has to follow whatever
             * the JPEG stream ends up being, or the user frames one crop and the
             * file keeps another. */
            jpegSize = pickJpegSize(map)
            previewSize = pickPreviewSize(map, jpegSize)
            configureTransform(textureView.width, textureView.height)

            imageReader = ImageReader.newInstance(
                jpegSize.width, jpegSize.height, ImageFormat.JPEG, 3).apply {
                setOnImageAvailableListener({ reader ->
                    val image = reader.acquireNextImage() ?: return@setOnImageAvailableListener
                    val buf = image.planes[0].buffer
                    val bytes = ByteArray(buf.remaining()).also { buf.get(it) }
                    image.close()
                    enqueueUpload(bytes)
                }, camHandler)
            }
            stateDetail = "${jpegSize.width}x${jpegSize.height} · $ratio"
            renderState()
            mgr.openCamera(cameraId, deviceCallback, camHandler)
        } catch (e: Exception) {
            toast("Không mở được camera: ${e.message}")
        }
    }

    /**
     * Full sensor resolution is rarely worth it: COLMAP downsizes to 1600-3200 px
     * for features anyway, and a 50 MP JPEG is ~10 MB that has to cross the WiFi
     * for every frame of a 120-shot run.
     */
    private fun pickJpegSize(map: StreamConfigurationMap): Size {
        val all = map.getOutputSizes(ImageFormat.JPEG).sortedByDescending {
            it.width.toLong() * it.height
        }
        val target = ratioValue(ratio)
        // Falling back to every size keeps a camera with an odd size list usable.
        val sizes = all.filter { target == null || matchesRatio(it, target) }.ifEmpty { all }
        if (!capResolution) return sizes.first()
        return sizes.firstOrNull { it.width.toLong() * it.height <= 13_000_000L } ?: sizes.first()
    }

    /**
     * Only ratios the driver really lists are offered. Synthesising, say, a 1:1
     * by cropping a 4:3 still would quietly throw away pixels the reconstruction
     * could have matched on, so an absent ratio is simply not shown.
     */
    private fun syncRatioOptions(map: StreamConfigurationMap) {
        val sizes = map.getOutputSizes(ImageFormat.JPEG) ?: emptyArray()
        val found = RATIO_TARGETS.filter { (_, r) -> sizes.any { matchesRatio(it, r) } }
            .map { it.first }
        ratioOptions = if (found.isEmpty()) listOf(DEFAULT_RATIO) else found
        if (ratio !in ratioOptions) ratio = ratioOptions.first()
        main.post { if (::ratioButton.isInitialized) ratioButton.text = ratio }
    }

    private fun ratioValue(label: String): Float? =
        RATIO_TARGETS.firstOrNull { it.first == label }?.second

    private fun matchesRatio(s: Size, target: Float): Boolean =
        s.height != 0 && abs(s.width.toFloat() / s.height - target) <= target * RATIO_TOLERANCE

    /**
     * The viewfinder must have the still's aspect, not the nicest one on offer:
     * a 16:9 preview over a 4:3 capture means the user frames a crop that the
     * file never gets. Resolution barely matters here - the preview is only ever
     * looked at - so take the biggest matching size inside a ~2 MP budget.
     */
    private fun pickPreviewSize(map: StreamConfigurationMap, jpeg: Size): Size {
        val sizes = map.getOutputSizes(SurfaceTexture::class.java)
        val budget = sizes.filter { it.width.toLong() * it.height <= 2_100_000L }
            .ifEmpty { sizes.toList() }
        val target = if (jpeg.height == 0) null else jpeg.width.toFloat() / jpeg.height
        val area = { s: Size -> s.width.toLong() * s.height }
        return budget.filter { target != null && matchesRatio(it, target) }.maxByOrNull(area)
            ?: budget.maxByOrNull(area) ?: sizes.first()
    }

    /**
     * TextureView stretches its buffer to the view box, which turns a landscape
     * camera frame into a squashed one in a portrait window.
     *
     * Fit, not fill: a viewfinder that covers the screen hides the edges of the
     * frame, so the user composes against a rectangle smaller than the file they
     * get. Letterboxing instead makes the visible rectangle the output frame
     * edge for edge, which is the whole point of a framing aid.
     */
    private fun configureTransform(viewW: Int, viewH: Int) {
        if (viewW == 0 || viewH == 0) return
        // Camera output sizes are landscape while this screen is locked portrait.
        val effW = previewSize.height
        val effH = previewSize.width
        val scale = min(viewW.toFloat() / effW, viewH.toFloat() / effH)
        val frameW = effW * scale
        val frameH = effH * scale
        /* Slide the frame into the band the two control strips leave free, so the
         * overlays sit on the dark bars rather than on the picture. A shift only:
         * the frame is never shrunk to make room, it just moves. */
        val free = viewH - topStrip.height - bottomStrip.height
        val centreY = if (frameH <= free) topStrip.height + free / 2f else viewH / 2f
        val matrix = Matrix()
        matrix.setScale(frameW / viewW, frameH / viewH, viewW / 2f, viewH / 2f)
        matrix.postTranslate(0f, centreY - viewH / 2f)
        textureView.setTransform(matrix)
        // The mask is stored in fractions of this rectangle -- the frame that
        // ends up in the file -- and not of the view it is letterboxed into.
        maskOverlay.setFrame((viewW - frameW) / 2f, centreY - frameH / 2f,
            (viewW + frameW) / 2f, centreY + frameH / 2f)
    }

    private val deviceCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(device: CameraDevice) {
            cameraDevice = device
            startPreview()
        }
        override fun onDisconnected(device: CameraDevice) { device.close(); cameraDevice = null }
        override fun onError(device: CameraDevice, error: Int) {
            device.close(); cameraDevice = null
            main.post { toast("Camera lỗi $error") }
        }
    }

    private fun startPreview() {
        val device = cameraDevice ?: return
        val texture = textureView.surfaceTexture ?: return
        texture.setDefaultBufferSize(previewSize.width, previewSize.height)
        val previewSurface = Surface(texture)

        previewBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(previewSurface)
            set(CaptureRequest.CONTROL_AF_MODE,
                CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            applySteadyImaging(this)
        }

        @Suppress("DEPRECATION")
        device.createCaptureSession(
            listOf(previewSurface, imageReader!!.surface),
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    session = s
                    repeatPreview()
                }
                override fun onConfigureFailed(s: CameraCaptureSession) {
                    main.post { toast("Không cấu hình được camera session") }
                }
            }, camHandler)
    }

    /**
     * Settings that stay on for the whole run.
     *
     * Stabilisation is turned off on purpose: OIS physically shifts the lens
     * between frames, which moves the optical centre that COLMAP is trying to
     * solve for as one fixed intrinsic.
     */
    private fun applySteadyImaging(b: CaptureRequest.Builder) {
        b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        b.set(CaptureRequest.CONTROL_SCENE_MODE, CameraMetadata.CONTROL_SCENE_MODE_DISABLED)
        b.set(CaptureRequest.CONTROL_EFFECT_MODE, CameraMetadata.CONTROL_EFFECT_MODE_OFF)
        b.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
            CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
        b.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
            CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF)
        b.set(CaptureRequest.NOISE_REDUCTION_MODE, CameraMetadata.NOISE_REDUCTION_MODE_FAST)
        b.set(CaptureRequest.EDGE_MODE, CameraMetadata.EDGE_MODE_FAST)
        b.set(CaptureRequest.JPEG_QUALITY, 95.toByte())
        // Both the preview and the still go through here, so the viewfinder and
        // the file can never end up with different crops.
        applyZoom(b)
    }

    private fun repeatPreview() {
        val s = session ?: return
        val b = previewBuilder ?: return
        try {
            s.setRepeatingRequest(b.build(), captureCallback, camHandler)
        } catch (e: Exception) {
            main.post { toast("Preview lỗi: ${e.message}") }
        }
    }

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest,
                                        result: TotalCaptureResult) {
            if (!awaitingLock) return
            val af = result.get(CaptureResult.CONTROL_AF_STATE) ?: return
            if (af == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED ||
                af == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED) {
                awaitingLock = false
                finishLock()
            }
        }
    }

    // ----------------------------------------------------------------- lock

    private fun toggleLock() = setLock(!locked)

    /**
     * Explicit rather than a toggle, because the web page sends "lock" without
     * knowing what the phone is doing: a command that arrives twice, or after
     * the user already locked by hand, must land on the same state and not
     * flip the camera loose mid-run.
     */
    private fun setLock(on: Boolean) {
        if (on == locked && !awaitingLock) return
        if (!on) {
            locked = false
            awaitingLock = false
            previewBuilder?.apply {
                set(CaptureRequest.CONTROL_AE_LOCK, false)
                set(CaptureRequest.CONTROL_AWB_LOCK, false)
                if (!manualFocus) set(CaptureRequest.CONTROL_AF_MODE,
                    CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            }
            repeatPreview()
            lockButton.text = "Khoá AE/AF"
            lockButton.setTextColor(Ui.INK)
            return
        }
        if (awaitingLock) return
        // Manual focus has nothing to hunt for: the distance is already fixed,
        // so this is only ever the exposure half of the lock.
        if (manualFocus) {
            finishLock()
            return
        }
        // Focus once, then freeze everything the phone would otherwise re-decide
        // on every frame.
        val b = previewBuilder ?: return
        b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_AUTO)
        b.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)
        awaitingLock = true
        lockButton.text = "Đang lấy nét…"
        try {
            session?.capture(b.build(), captureCallback, camHandler)
        } catch (_: Exception) {}
        b.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE)
        repeatPreview()
        // Some devices never report a locked AF state; fall back on a timer so the
        // button cannot get stuck saying "focusing".
        main.postDelayed({ if (awaitingLock) { awaitingLock = false; finishLock() } }, 2_500)
    }

    private fun finishLock() {
        previewBuilder?.apply {
            set(CaptureRequest.CONTROL_AE_LOCK, true)
            set(CaptureRequest.CONTROL_AWB_LOCK, true)
        }
        repeatPreview()
        locked = true
        main.post {
            lockButton.text = "Đã khoá"
            lockButton.setTextColor(Ui.GREEN)
        }
    }

    // -------------------------------------------------------------- capture

    /**
     * A run is the whole session: press start once and the rig shoots all
     * [shots] frames and stops by itself, board-stepped or on the timer. A
     * half-finished run is never resumed -- the table is no longer at a known
     * angle -- so every start begins the count again from zero.
     */
    private fun startAuto() {
        if (autoRunning) return
        if (!locked) {
            toast("Nên khoá AE/AF trước khi chạy tự động")
        }
        if (maskEditing) setMaskEditing(false)
        autoRunning = true
        runId++
        frameIndex = 0
        requested = 0
        runFailedAt = failed.get()
        runStart = SystemClock.elapsedRealtime()
        markRunning()
        updateCounter()
        renderTime()
        main.post(timeTick)
        if (boardAttached()) startBoardRun() else main.post(autoTick)
    }

    private fun stopAuto() {
        autoRunning = false
        boardRunning = false
        awaitingStep = false
        awaitingFrame = false
        frameRetries = 0
        main.removeCallbacks(autoTick)
        main.removeCallbacks(stallGuard)
        main.removeCallbacks(timeTick)
        if (::autoButton.isInitialized) {
            autoButton.text = autoLabel()
            autoButton.setTextColor(Ui.AMBER)
        }
        if (::timeView.isInitialized) timeView.visibility = View.GONE
        updateCounter()
    }

    /**
     * The end of a run is announced and not merely silent: the user is across
     * the room, which is the same reason an attached board is asked to beep.
     */
    private fun finishRun() {
        val n = frameIndex
        val errs = failed.get() - runFailedAt
        val took = SystemClock.elapsedRealtime() - runStart
        stopAuto()
        Ch340.shared?.takeIf { it.isOpen }?.send("BEEP")
        toast("Xong $n ảnh · ${spoken(took)} · $errs lỗi")
    }

    private fun markRunning() {
        autoButton.text = "Dừng"
        autoButton.setTextColor(Ui.RED)
    }

    private fun autoLabel() = "Bắt đầu $shots ảnh"

    /** Free-running mode: the phone's clock, used only with no board attached. */
    private val autoTick = object : Runnable {
        override fun run() {
            if (!autoRunning) return
            if (requested >= shots) {
                /* Every frame has been asked for. The run ends when the last
                 * one lands; this guard only covers the one that never does. */
                val id = runId
                main.postDelayed({ if (autoRunning && runId == id) finishRun() }, FRAME_TIMEOUT_MS)
                return
            }
            requested++
            requestCapture(manual = false)
            main.postDelayed(this, intervalMs)
        }
    }

    // ------------------------------------------------------------- turntable

    private fun boardAttached() = Ch340.shared?.isOpen == true

    /**
     * The closed loop: shoot, wait for the JPEG to actually arrive, hand it to
     * the uploader, STEP, wait for the board's "ok", settle, shoot again.
     *
     * Two free-running clocks - the phone's interval and the board's - have
     * nothing tying them together, so they drift until the shutter is firing
     * mid-index and only the stillness gate is keeping the set usable. Here the
     * board never moves until the frame is in hand and the shutter never fires
     * until the board says the move is done, so there is no drift to accumulate
     * and the run ends by itself after a full revolution.
     */
    private fun startBoardRun() {
        boardRunning = true
        frameRetries = 0
        /* The table is wherever the last run left it, so the board is told to
         * call this position index 0: the sequence and the table have to agree
         * on where the revolution begins. */
        Ch340.shared?.takeIf { it.isOpen }?.send("ZERO")
        shootBoardFrame()
    }

    private fun shootBoardFrame() {
        awaitingFrame = true
        armStall(FRAME_TIMEOUT_MS, "Không chụp được khung hình — đã dừng chuỗi")
        requestCapture(manual = false)
    }

    /**
     * The ImageReader has the JPEG for the current index and the uploader has
     * taken it, so the sequence can move on. This, not the return of
     * session.capture(), is the end of a capture: that call returns as soon as
     * the request is queued, with the exposure and the readout still to come.
     */
    private fun onFrameQueued() {
        if (!autoRunning) return
        if (boardRunning) {
            if (!awaitingFrame) return
            awaitingFrame = false
            frameRetries = 0
            main.removeCallbacks(stallGuard)
        }
        frameIndex++
        updateCounter()
        renderTime()
        if (frameIndex >= shots) {
            finishRun()
            return
        }
        if (boardRunning) main.postDelayed({ if (boardRunning) stepBoard() }, STEP_GUARD_MS)
    }

    private fun stepBoard() {
        val serial = Ch340.shared?.takeIf { it.isOpen } ?: run { boardLost(); return }
        awaitingStep = true
        armStall(STEP_TIMEOUT_MS, "Bàn xoay không trả lời — đã dừng chuỗi")
        serial.send("STEP")
    }

    /** The board finished the index; let the object stop ringing, then shoot. */
    private fun onStepDone() {
        awaitingStep = false
        main.removeCallbacks(stallGuard)
        if (!boardRunning) return
        /* The settle is the interval the user dialled in: a heavy object on a
         * springy mat needs longer to stop swinging than a light one, and that
         * button is the only knob for it. */
        main.postDelayed({ if (boardRunning) shootBoardFrame() }, intervalMs)
    }

    private fun boardLost() {
        stopAuto()
        toast("Mất kết nối bàn xoay — đã dừng chuỗi")
    }

    /**
     * The board's replies, taken through [Ch340.extraListener] so the turntable
     * screen keeps the listener it registered.
     */
    private val boardListener = object : Ch340.Listener {
        override fun onLine(line: String) {
            if (line.startsWith("shots=")) {
                /* On connect the board is the source of truth and seeds the
                 * count; from then on the app is, and pushes changes down. */
                val n = line.substring(6).trim().toIntOrNull()
                if (n != null && n in MIN_SHOTS..MAX_SHOTS && n != shots && !autoRunning) {
                    shots = n
                    preset = -1
                    persistRun()
                    syncRunUi()
                }
            }
            if (awaitingStep && line == "ok") onStepDone()
        }
        override fun onState(connected: Boolean, message: String) {
            renderState()
            if (!autoRunning) autoButton.text = autoLabel()
            if (!connected && boardRunning) boardLost()
        }
    }

    private fun attachBoard() {
        val serial = Ch340.shared?.takeIf { it.isOpen } ?: return
        serial.extraListener = boardListener
        /* Ask on entry: the run length is whatever the board holds now, and a
         * value cached from a previous session may be a setting ago. */
        serial.send("?")
    }

    private fun detachBoard() {
        Ch340.shared?.let { if (it.extraListener === boardListener) it.extraListener = null }
    }

    /**
     * Stops the sequence instead of shooting the same angle forever. A missing
     * frame is worth one more try -- a dropped JPEG is a phone problem and
     * costs one nấc -- but a board that has gone quiet will not come back.
     */
    private val stallGuard = Runnable {
        if (awaitingFrame && frameRetries < 1) {
            frameRetries++
            toast("Không thấy khung hình — chụp lại nấc này")
            shootBoardFrame()
            return@Runnable
        }
        stopAuto()
        toast(stallMessage)
    }

    private fun armStall(ms: Long, message: String) {
        stallMessage = message
        main.removeCallbacks(stallGuard)
        main.postDelayed(stallGuard, ms)
    }

    /**
     * Fires the shutter, but only once the phone has stopped moving.
     *
     * On a turntable the table's step ends before its wobble does. Waiting on the
     * accelerometer costs a fraction of a second and is the difference between a
     * frame that contributes features and one that quietly poisons the match.
     */
    private fun requestCapture(manual: Boolean, attempt: Int = 0) {
        if (jitter > STILL_THRESHOLD && attempt < 10) {
            main.postDelayed({ requestCapture(manual, attempt + 1) }, 120)
            return
        }
        val device = cameraDevice ?: return
        val s = session ?: return
        try {
            val b = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            b.addTarget(imageReader!!.surface)
            applySteadyImaging(b)
            previewBuilder?.let { p ->
                b.set(CaptureRequest.CONTROL_AF_MODE, p.get(CaptureRequest.CONTROL_AF_MODE))
                b.set(CaptureRequest.CONTROL_AE_LOCK, p.get(CaptureRequest.CONTROL_AE_LOCK))
                b.set(CaptureRequest.CONTROL_AWB_LOCK, p.get(CaptureRequest.CONTROL_AWB_LOCK))
                // A still request left on its own re-focuses; in manual that
                // would hand back the one thing manual mode exists to hold.
                if (manualFocus) {
                    b.set(CaptureRequest.LENS_FOCUS_DISTANCE,
                        p.get(CaptureRequest.LENS_FOCUS_DISTANCE))
                }
            }
            b.set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation())
            s.capture(b.build(), null, camHandler)
        } catch (e: Exception) {
            main.post { toast("Chụp lỗi: ${e.message}") }
        }
    }

    private fun jpegOrientation(): Int {
        val rotation = when (windowManager.defaultDisplay.rotation) {
            Surface.ROTATION_0 -> 0
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            else -> 270
        }
        return (sensorOrientation - rotation + 360) % 360
    }

    // --------------------------------------------------------------- upload

    private fun enqueueUpload(jpeg: ByteArray) {
        val n = shot.incrementAndGet()
        pending.incrementAndGet()
        val tierTag = listOf("low", "mid", "high")[tier]
        val name = "%s_%04d_%d.jpg".format(tierTag, n, System.currentTimeMillis() % 100000)
        val geom = mask
        val mode = maskMode
        val cache = cacheDir
        updateCounter()
        uploads.execute {
            /* The mask runs here and not on the main thread: a 12 MP decode
             * plus re-encode is far too slow for the UI, and it must not stand
             * between the table and the next shutter. A frame that cannot be
             * masked goes up untouched -- losing the mask beats losing the shot. */
            val data = if (mode == Mask.MODE_OFF) jpeg
                       else Mask.process(jpeg, geom, mode, cache) ?: jpeg.also { warnMaskFailed() }
            var ok = false
            var lastError = ""
            // One retry: a single dropped frame on a flaky link is normal, a second
            // failure in a row means the rig is gone and retrying just piles up.
            for (attempt in 0..1) {
                try {
                    Net.uploadPhoto(server, project, name, data)
                    ok = true
                    break
                } catch (e: Exception) {
                    lastError = e.message ?: e.javaClass.simpleName
                }
            }
            pending.decrementAndGet()
            if (!ok) failed.incrementAndGet()
            main.post {
                updateCounter()
                if (!ok) toast("Upload hỏng: $lastError")
            }
        }
        /* The frame is the uploader's problem now, so the table is free to
         * move: waiting for the WiFi to finish would put the whole run at the
         * mercy of the link speed. */
        main.post { onFrameQueued() }
    }

    /** Second line of the top strip: project, still size, and the capture mode. */
    private fun renderState() {
        stateView.text = buildString {
            append(project)
            if (stateDetail.isNotEmpty()) append(" · ").append(stateDetail)
            append(" · ").append(
                if (boardAttached()) "bàn xoay · lắng ${intervalMs / 1000}s"
                else "hẹn giờ ${intervalMs / 1000}s")
        }
    }

    private fun updateCounter() {
        main.post {
            val f = failed.get()
            counterView.text = buildString {
                if (autoRunning) append("$frameIndex/$shots ảnh")
                else append("${shot.get()} ảnh")
                if (pending.get() > 0) append(" · đang gửi ${pending.get()}")
                if (f > 0) append(" · hỏng $f")
            }
            counterView.setTextColor(if (f > 0) Ui.RED else Ui.AMBER)
        }
    }

    /** A display timer over values the run loop already keeps; it adds nothing
     *  to the capture path. */
    private val timeTick = object : Runnable {
        override fun run() {
            if (!autoRunning) return
            renderTime()
            main.postDelayed(this, 1_000)
        }
    }

    private fun renderTime() {
        if (!autoRunning) {
            timeView.visibility = View.GONE
            return
        }
        val elapsed = SystemClock.elapsedRealtime() - runStart
        val left = (shots - frameIndex).coerceAtLeast(0)
        /* Measured, never assumed. The nominal interval leaves out the stillness
         * gate, the STEP round trip and the upload, so a plan-based estimate is
         * optimistic every single time and stops being believed after one run.
         * Only the very first frame, with nothing measured yet, uses the plan. */
        val eta = if (frameIndex > 0) elapsed / frameIndex * left else intervalMs * left
        timeView.visibility = View.VISIBLE
        timeView.text = "${clock(elapsed)} đã chạy · còn ~${clock(eta)}"
    }

    // ------------------------------------------------------------ live view

    private fun startLive() {
        if (server.isEmpty() || project.isEmpty()) return
        liveOn = true
        main.post(liveTick)
    }

    private fun stopLive() {
        liveOn = false
        main.removeCallbacks(liveTick)
    }

    private val liveTick = object : Runnable {
        override fun run() {
            if (!liveOn) return
            pushLive()
            main.postDelayed(this, LIVE_PERIOD_MS)
        }
    }

    /**
     * A viewfinder for whoever is standing at the web page instead of at the
     * rig. Small and lossy on purpose: it must never compete with the upload
     * of a real capture, so a frame is dropped whenever the previous POST is
     * still in flight rather than queued behind it. Nothing here touches disk -
     * these are previews, not captures.
     */
    private fun pushLive() {
        if (!textureView.isAvailable) return
        val vw = textureView.width
        val vh = textureView.height
        if (vw == 0 || vh == 0) return
        if (!liveBusy.compareAndSet(false, true)) return
        val scale = (LIVE_EDGE / maxOf(vw, vh)).coerceAtMost(1f)
        // getBitmap has to run here: the surface belongs to the view.
        val bmp = textureView.getBitmap((vw * scale).toInt().coerceAtLeast(1),
            (vh * scale).toInt().coerceAtLeast(1))
        if (bmp == null) {
            liveBusy.set(false)
            return
        }
        /* Whoever is watching the web page sees the same keep region as whoever
         * is standing at the rig. Cheap by construction: the frame is 480 px, so
         * this is two paths on a small bitmap. The geometry is read here, on the
         * main thread, because it is view state. */
        val showMask = maskMode != Mask.MODE_OFF || maskEditing
        val fr = maskOverlay.frame
        val liveFrame = if (showMask && fr.width() > 0f) scaled(fr, scale) else null
        val liveOval = scaled(maskOverlay.oval(), scale)
        val liveRot = maskOverlay.geom.rot
        val liveStroke = dp(1f).toFloat()
        liveSender.execute {
            try {
                if (liveFrame != null) {
                    Mask.drawScrim(Canvas(bmp), liveFrame, liveOval, liveRot, liveStroke)
                }
                val out = ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.JPEG, LIVE_QUALITY, out)
                Net.postBytes(server, "/api/projects/${Net.enc(project)}/live",
                    "image/jpeg", out.toByteArray())
            } catch (_: Exception) {
                /* A lost preview frame is not worth a toast; the next one is
                 * 300 ms away and the capture path reports its own failures. */
            } finally {
                bmp.recycle()
                liveBusy.set(false)
            }
        }
    }

    // -------------------------------------------------------------- commands

    /**
     * The web page's shutter. One thread long-polling the server's mailbox, so
     * the phone can sit on the rig with nobody next to it.
     */
    private fun startCommands() {
        if (server.isEmpty() || project.isEmpty() || commandThread != null) return
        commandsOn = true
        commandThread = Thread {
            /* Identity, not just the flag: a quick pause-resume starts the next
             * poller while this one is still parked in its long poll, and the
             * flag it would wake up to is the new poller's. */
            val me = Thread.currentThread()
            while (commandsOn && commandThread === me) {
                try {
                    val reply = Net.getObject(server,
                        "/api/projects/${Net.enc(project)}/command?wait=$COMMAND_WAIT_S")
                    val cmd = if (reply.isNull("cmd")) "" else reply.optString("cmd")
                    if (cmd.isNotEmpty()) main.post { if (commandsOn) runCommand(cmd) }
                } catch (_: Exception) {
                    /* A phone that has lost WiFi must wait, not spin: without
                     * this the failing poll returns instantly and burns the
                     * battery for as long as the screen is up. */
                    try {
                        Thread.sleep(COMMAND_RETRY_MS)
                    } catch (_: InterruptedException) {
                        return@Thread
                    }
                }
            }
        }.also { it.isDaemon = true; it.start() }
    }

    private fun stopCommands() {
        commandsOn = false
        commandThread?.interrupt()
        commandThread = null
    }

    private fun runCommand(cmd: String) = when (cmd) {
        "shoot" -> requestCapture(manual = true)
        "start" -> startAuto()
        "stop" -> stopAuto()
        "lock" -> setLock(true)
        "unlock" -> setLock(false)
        else -> Unit
    }

    // --------------------------------------------------------------- motion

    private val motionListener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            val d = sqrt(
                (e.values[0] - lastAccel[0]).let { it * it } +
                (e.values[1] - lastAccel[1]).let { it * it } +
                (e.values[2] - lastAccel[2]).let { it * it })
            // Exponential decay, so a single jolt does not block the shutter for
            // the rest of the run.
            jitter = jitter * 0.8f + d * 0.2f
            System.arraycopy(e.values, 0, lastAccel, 0, 3)

            // Pitch of the phone's optical axis above horizontal.
            val g = sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1]
                    + e.values[2] * e.values[2])
            if (g > 1f) {
                pitchDeg = Math.toDegrees(
                    atan2(e.values[2].toDouble(), sqrt(
                        (e.values[0] * e.values[0] + e.values[1] * e.values[1]).toDouble()))
                ).toFloat()
            }
            if (abs(System.currentTimeMillis() % 200) < 30) updateTilt()
        }
        override fun onAccuracyChanged(s: Sensor?, a: Int) = Unit
    }

    private fun updateTilt() {
        main.post {
            val steady = jitter <= STILL_THRESHOLD
            tiltView.text = "%+.0f° %s".format(pitchDeg, if (steady) "yên" else "rung")
            tiltView.setTextColor(if (steady) Ui.GREEN else Ui.RED)
        }
    }

    private fun closeCamera() {
        try { session?.close() } catch (_: Exception) {}
        try { cameraDevice?.close() } catch (_: Exception) {}
        try { imageReader?.close() } catch (_: Exception) {}
        session = null
        cameraDevice = null
        imageReader = null
    }

    private fun scaled(r: RectF, s: Float) =
        RectF(r.left * s, r.top * s, r.right * s, r.bottom * s)

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    companion object {
        /** m/s^2 of smoothed frame-to-frame change that still counts as still. */
        private const val STILL_THRESHOLD = 0.35f
        /**
         * Between the JPEG arriving and the table being told to move.
         *
         * Having the image in hand does not mean the sensor pipeline is idle,
         * and a stepper kicking the table during the tail of a readout costs
         * exactly the frame that everything else on this screen is arranged to
         * protect.
         */
        private const val STEP_GUARD_MS = 500L
        /** A nấc is well under a second; anything near this means a dead board. */
        private const val STEP_TIMEOUT_MS = 10_000L
        /** A 12 MP JPEG is in hand in about a second, so this is already long. */
        private const val FRAME_TIMEOUT_MS = 6_000L
        /** ~3 preview frames a second, small and lossy. */
        private const val LIVE_PERIOD_MS = 330L
        private const val LIVE_EDGE = 480f
        private const val LIVE_QUALITY = 60
        private const val COMMAND_WAIT_S = 25
        private const val COMMAND_RETRY_MS = 2_000L
        private const val PREFS = "collmap"
        private const val KEY_RATIO = "jpeg_ratio"
        private const val KEY_ZOOM = "zoom"
        private const val KEY_PRESET = "preset"
        private const val KEY_TAB = "capture_tab"
        private val TAB_NAMES = listOf("Chụp", "Ảnh", "Nét", "Mask")
        private const val KEY_CAP = "cap_res"
        private const val DEFAULT_SHOTS = 120
        /** "Chuẩn": the one actually measured end to end on this rig. */
        private const val DEFAULT_PRESET = 1
        private const val MIN_SHOTS = 8
        private const val MAX_SHOTS = 2000
        private val SHOT_STEPS = listOf(36, 48, 60, 72, 90, 120, 180)
        private val INTERVAL_STEPS =
            listOf(2_000L, 3_000L, 4_000L, 5_000L, 6_000L, 8_000L, 12_000L)
        /** Slider resolution for the mask; the geometry behind it is continuous. */
        private const val MASK_STEPS = 1000
        /** On-screen ry/rx: 1.0 is a circle, below it the disc is squashed. */
        private const val SQUASH_MIN = 0.25f
        private const val SQUASH_MAX = 1.25f

        private data class Preset(val name: String, val shots: Int, val intervalMs: Long,
                                  val cap: Boolean)

        /**
         * The bundles worth a single tap. Each exists for a different reason:
         *
         *  - Thử nhanh: 6°/ảnh in three minutes, to check the light, the framing
         *    and the mask before committing to a real run.
         *  - Chuẩn: the one that was measured end to end on this rig -- 115
         *    frames gave 115/115 registered, 5141 observations per image and
         *    0.79 px reprojection error. It is the default for that reason.
         *  - Kỹ: more angles and the full sensor, for a subject with fine
         *    relief. It costs disk and upload roughly in proportion, and COLMAP
         *    downsizes to 1600-3200 px for features anyway, so the gain is in
         *    the dense stage and not in matching.
         *  - Vật lắc: fewer, slower steps for something tall or springy that
         *    keeps ringing after the table stops. The long interval is settle
         *    time, which is the only real cure for a smeared frame.
         */
        private val PRESETS = listOf(
            Preset("Thử nhanh", 60, 3_000L, true),
            Preset("Chuẩn", 120, 3_000L, true),
            Preset("Kỹ", 180, 4_000L, false),
            Preset("Vật lắc", 90, 6_000L, true))
        /** Slider resolution; dioptres are a continuous value behind it. */
        private const val FOCUS_STEPS = 1000
        private const val DEFAULT_RATIO = "4:3"
        /** The ratios worth offering; anything else a sensor lists is a niche crop. */
        private val RATIO_TARGETS = listOf("4:3" to 4f / 3f, "16:9" to 16f / 9f, "1:1" to 1f)
        /** 4096x3072 and 4000x3000 are both "4:3"; a couple of percent covers the rest. */
        private const val RATIO_TOLERANCE = 0.02f
    }
}
