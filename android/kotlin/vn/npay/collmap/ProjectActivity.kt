package vn.npay.collmap

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import vn.npay.collmap.Ui.MATCH
import vn.npay.collmap.Ui.WRAP
import vn.npay.collmap.Ui.dp
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * What the web page shows, on the phone that took the photos.
 *
 * The rig is a phone in a holder next to a turntable; walking back to a laptop
 * to find out whether the run registered, whether frame 43 is soft, or how far
 * the dense stage has got is the slowest part of the whole loop. Everything
 * here is read from the same endpoints the page uses, so there is one source of
 * truth and no second copy of the pipeline's state to keep in step.
 *
 * Two rules run through the whole file, both of them lessons this codebase has
 * already paid for:
 *
 *  - Every poller is a Handler tick that reschedules itself and nothing else.
 *    A poll that restarts from inside its own network callback turns one slow
 *    reply into two overlapping pollers, and a screen that is no longer visible
 *    keeps them both.
 *  - Nothing is decoded at full size. These are 12 MP JPEGs; a grid of them at
 *    native resolution is an OutOfMemoryError with a progress bar. Thumbnails
 *    are downsampled to the tile, loaded only when scrolled into view, and
 *    recycled when they fall out of the cache.
 */
class ProjectActivity : Activity() {

    private val io = Executors.newSingleThreadExecutor()      // status, log, analysis
    private val loader = Executors.newSingleThreadExecutor()  // photos, one at a time
    private val main = Handler(Looper.getMainLooper())

    private var server = ""
    private var project = ""

    private var polling = false
    private val statusBusy = AtomicBoolean(false)
    private val logBusy = AtomicBoolean(false)

    private var status = "idle"
    private var logOffset = 0
    private val logText = StringBuilder()
    private var lastOutputs = ""
    private var lastImageCount = -1

    /** Filenames, in the server's order; the grid tiles line up with it. */
    private var images = emptyList<String>()
    private var tiles = emptyList<ImageView>()
    private val wanted = HashSet<String>()

    /** Access-ordered, so the eldest entry really is the least recently seen. */
    private val cache = object : LinkedHashMap<String, Bitmap>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>): Boolean {
            if (size <= CACHE_MAX) return false
            // Clear the tile first: recycling a bitmap that is still on screen
            // is a crash at the next draw, not a saving.
            tileFor(eldest.key)?.setImageDrawable(null)
            eldest.value.recycle()
            return true
        }
    }

    private var runFast = false
    private var useMask = false
    private var tab = 0
    private var viewerIndex = 0
    private var viewerBitmap: Bitmap? = null

    // ---- views ---------------------------------------------------------------
    private lateinit var titleView: TextView
    private lateinit var statusView: TextView
    private lateinit var stageView: TextView
    private lateinit var stageBar: ProgressBar
    private lateinit var runButton: android.widget.Button
    private lateinit var stopButton: android.widget.Button
    private lateinit var presetButton: android.widget.Button
    private lateinit var maskButton: android.widget.Button
    private lateinit var photoGrid: LinearLayout
    private lateinit var photoScroll: ScrollView
    private lateinit var photoNote: TextView
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var outputBox: LinearLayout
    private lateinit var analysisBox: LinearLayout
    private lateinit var analyzeButton: android.widget.Button
    private lateinit var tabViews: List<TextView>
    private lateinit var tabPanels: List<View>
    private lateinit var viewer: FrameLayout
    private lateinit var viewerImage: ImageView
    private lateinit var viewerLabel: TextView

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        server = intent.getStringExtra("server") ?: ""
        project = intent.getStringExtra("project") ?: ""
        buildUi()
    }

    override fun onResume() {
        super.onResume()
        polling = true
        main.post(poll)
    }

    override fun onPause() {
        // The screen is gone; so is every timer it owns.
        polling = false
        main.removeCallbacks(poll)
        super.onPause()
    }

    override fun onDestroy() {
        io.shutdownNow()
        loader.shutdownNow()
        for (b in cache.values) b.recycle()
        cache.clear()
        viewerBitmap?.recycle()
        super.onDestroy()
    }

    override fun onBackPressed() {
        if (viewer.visibility == View.VISIBLE) closeViewer() else super.onBackPressed()
    }

    // -------------------------------------------------------------------- ui

    private fun buildUi() {
        val ctx = this
        val root = FrameLayout(ctx).apply { setBackgroundColor(Ui.GROUND) }
        val col = Ui.column(ctx)

        val head = Ui.row(ctx).apply { setPadding(dp(16f), dp(18f), dp(16f), dp(8f)) }
        titleView = Ui.text(ctx, project, 20f)
        statusView = Ui.text(ctx, "…", 13f, Ui.DIM)
        head.addView(Ui.column(ctx).apply { addView(titleView); addView(statusView) },
            Ui.lp(0, WRAP, 1f))
        col.addView(head, Ui.lp(MATCH, WRAP))

        val tabRow = Ui.row(ctx).apply { setPadding(dp(8f), 0, dp(8f), 0) }
        tabViews = TABS.mapIndexed { i, name ->
            Ui.text(ctx, name, 13f, Ui.DIM).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(8f), 0, dp(8f))
                setOnClickListener { selectTab(i) }
            }
        }
        for (t in tabViews) tabRow.addView(t, Ui.lp(0, WRAP, 1f))
        col.addView(tabRow, Ui.lp(MATCH, WRAP))

        val panelBox = FrameLayout(ctx)
        tabPanels = listOf(buildRunPanel(), buildPhotoPanel(), buildLogPanel(),
            buildOutputPanel(), buildAnalysisPanel())
        for (p in tabPanels) panelBox.addView(p, FrameLayout.LayoutParams(MATCH, MATCH))
        col.addView(panelBox, Ui.lp(MATCH, 0, 1f))

        root.addView(col, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(buildViewer(), FrameLayout.LayoutParams(MATCH, MATCH))
        setContentView(root)
        selectTab(0)
    }

    private fun panel(content: View): ScrollView = ScrollView(this).apply {
        addView(content, FrameLayout.LayoutParams(MATCH, WRAP))
    }

    private fun buildRunPanel(): View {
        val ctx = this
        val box = Ui.column(ctx).apply { setPadding(dp(16f), dp(8f), dp(16f), dp(24f)) }
        stageView = Ui.text(ctx, "—", 14f, Ui.INK)
        stageBar = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 6
        }
        box.addView(stageView, Ui.lp(MATCH, WRAP))
        box.addView(stageBar, Ui.lp(MATCH, WRAP, 0f, 8, ctx))

        box.addView(Ui.label(ctx, "Dựng lại"))
        presetButton = Ui.button(ctx, "Chuẩn")
        maskButton = Ui.button(ctx, "Mask: Không")
        val optRow = Ui.row(ctx)
        optRow.addView(presetButton, Ui.lp(0, WRAP, 1f))
        optRow.addView(Ui.gap(ctx, 10f))
        optRow.addView(maskButton, Ui.lp(0, WRAP, 1f))
        box.addView(optRow, Ui.lp(MATCH, WRAP))

        runButton = Ui.button(ctx, "Chạy pipeline", Ui.AMBER)
        stopButton = Ui.button(ctx, "Dừng", Ui.RED)
        val actRow = Ui.row(ctx)
        actRow.addView(runButton, Ui.lp(0, WRAP, 1.4f))
        actRow.addView(Ui.gap(ctx, 10f))
        actRow.addView(stopButton, Ui.lp(0, WRAP, 1f))
        box.addView(actRow, Ui.lp(MATCH, WRAP, 0f, 10, ctx))

        presetButton.setOnClickListener {
            runFast = !runFast
            presetButton.text = if (runFast) "Nhanh" else "Chuẩn"
        }
        maskButton.setOnClickListener {
            useMask = !useMask
            maskButton.text = if (useMask) "Mask: Có" else "Mask: Không"
            maskButton.setTextColor(if (useMask) Ui.AMBER else Ui.INK)
        }
        runButton.setOnClickListener { startRun() }
        stopButton.setOnClickListener { stopRun() }
        return panel(box)
    }

    private fun buildPhotoPanel(): View {
        val ctx = this
        val box = Ui.column(ctx).apply { setPadding(dp(12f), dp(8f), dp(12f), dp(24f)) }
        photoNote = Ui.text(ctx, "Đang tải danh sách ảnh…", 13f, Ui.DIM)
        photoGrid = Ui.column(ctx)
        box.addView(photoNote, Ui.lp(MATCH, WRAP))
        box.addView(photoGrid, Ui.lp(MATCH, WRAP))
        photoScroll = panel(box)
        /* Lazy for a reason the emulator hides: a thumbnail costs a whole 12 MP
         * JPEG over the WiFi, so only what is on screen is ever fetched. */
        photoScroll.setOnScrollChangeListener { _, _, _, _, _ -> loadVisibleTiles() }
        // Tiles have no position until the grid is laid out, so the first pass
        // has to wait for that rather than for the list to arrive.
        photoGrid.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> loadVisibleTiles() }
        return photoScroll
    }

    private fun buildLogPanel(): View {
        val ctx = this
        logView = Ui.mono(ctx, "", 11f, Ui.DIM).apply {
            setPadding(dp(12f), dp(8f), dp(12f), dp(24f))
        }
        logScroll = panel(logView)
        return logScroll
    }

    private fun buildOutputPanel(): View {
        outputBox = Ui.column(this).apply { setPadding(dp(16f), dp(8f), dp(16f), dp(24f)) }
        return panel(outputBox)
    }

    private fun buildAnalysisPanel(): View {
        val ctx = this
        val box = Ui.column(ctx).apply { setPadding(dp(16f), dp(8f), dp(16f), dp(24f)) }
        analyzeButton = Ui.button(ctx, "Phân tích lần chụp", Ui.BLUE)
        analysisBox = Ui.column(ctx)
        box.addView(analyzeButton, Ui.lp(MATCH, WRAP))
        box.addView(analysisBox, Ui.lp(MATCH, WRAP, 0f, 10, ctx))
        analyzeButton.setOnClickListener { analyze(refresh = true) }
        return panel(box)
    }

    private fun buildViewer(): View {
        val ctx = this
        viewer = FrameLayout(ctx).apply {
            setBackgroundColor(0xF00B0F14.toInt())
            visibility = View.GONE
            isClickable = true
        }
        viewerImage = ImageView(ctx).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        viewer.addView(viewerImage, FrameLayout.LayoutParams(MATCH, MATCH))
        viewerLabel = Ui.mono(ctx, "", 12f, Ui.AMBER).apply {
            setPadding(dp(16f), dp(16f), dp(16f), dp(16f))
        }
        viewer.addView(viewerLabel, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.TOP))

        val bar = Ui.row(ctx).apply { setPadding(dp(12f), dp(12f), dp(12f), dp(20f)) }
        val prev = Ui.button(ctx, "‹ Trước")
        val close = Ui.button(ctx, "Đóng")
        val next = Ui.button(ctx, "Sau ›")
        bar.addView(prev, Ui.lp(0, WRAP, 1f))
        bar.addView(Ui.gap(ctx, 8f))
        bar.addView(close, Ui.lp(0, WRAP, 1f))
        bar.addView(Ui.gap(ctx, 8f))
        bar.addView(next, Ui.lp(0, WRAP, 1f))
        viewer.addView(bar, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM))
        prev.setOnClickListener { showPhoto(viewerIndex - 1) }
        next.setOnClickListener { showPhoto(viewerIndex + 1) }
        close.setOnClickListener { closeViewer() }
        return viewer
    }

    private fun selectTab(index: Int) {
        tab = index.coerceIn(0, tabPanels.size - 1)
        tabViews.forEachIndexed { i, v -> v.setTextColor(if (i == tab) Ui.AMBER else Ui.DIM) }
        tabPanels.forEachIndexed { i, v ->
            v.visibility = if (i == tab) View.VISIBLE else View.GONE
        }
        when (tab) {
            1 -> main.post { loadVisibleTiles() }
            2 -> tickLog()
            4 -> if (analysisBox.childCount == 0) analyze(refresh = false)
        }
    }

    // ----------------------------------------------------------------- polling

    /**
     * One tick, one reschedule, and the reschedule happens here rather than in
     * any of the callbacks it fires.
     */
    private val poll = object : Runnable {
        override fun run() {
            if (!polling) return
            tickStatus()
            if (tab == 2 || status == "running") tickLog()
            main.postDelayed(this, POLL_MS)
        }
    }

    private fun tickStatus() {
        if (server.isEmpty() || !statusBusy.compareAndSet(false, true)) return
        io.execute {
            try {
                val o = Net.getObject(server, "/api/projects/${Net.enc(project)}")
                main.post { renderStatus(o) }
            } catch (e: Exception) {
                main.post {
                    statusView.setTextColor(Ui.RED)
                    statusView.text = "Không đọc được: ${e.message}"
                }
            } finally {
                statusBusy.set(false)
            }
        }
    }

    private fun renderStatus(o: JSONObject) {
        status = o.optString("status", "idle")
        val images = o.optInt("images")
        val depth = o.optInt("depth_maps")
        statusView.setTextColor(statusColor())
        statusView.text = buildString {
            append(statusLabel())
            append(" · ").append(images).append(" ảnh")
            if (depth > 0) append(" · ").append(depth).append(" depth map")
        }
        runButton.isEnabled = status != "running"
        stopButton.isEnabled = status == "running"
        renderProgress(o.optJSONObject("progress"))

        val outs = o.optJSONArray("outputs")?.toString() ?: "[]"
        if (outs != lastOutputs) {
            lastOutputs = outs
            renderOutputs(o)
        }
        if (images != lastImageCount) {
            lastImageCount = images
            refreshImages()
        }
    }

    private fun statusLabel() = when (status) {
        "running" -> "Đang dựng"
        "done" -> "Đã dựng xong"
        "failed" -> "Dựng lỗi"
        "stopped" -> "Đã dừng"
        else -> "Chưa dựng"
    }

    private fun statusColor() = when (status) {
        "running" -> Ui.AMBER
        "done" -> Ui.GREEN
        "failed" -> Ui.RED
        else -> Ui.DIM
    }

    private fun renderProgress(p: JSONObject?) {
        if (p == null) {
            stageView.text = if (status == "running") "Đang chạy…" else "Không có bước nào đang chạy"
            stageBar.visibility = View.GONE
            return
        }
        val step = p.optInt("step")
        val done = p.optInt("done")
        val total = p.optInt("total")
        stageView.text = buildString {
            append("Bước ").append(step).append("/6 · ").append(p.optString("label"))
            if (total > 0) append(" · ").append(done).append('/').append(total)
        }
        stageBar.visibility = View.VISIBLE
        /* Inside patch_match the view counter is the only honest measure of
         * progress; everywhere else all that is known is which of the six
         * stages is running. */
        if (total > 0) {
            stageBar.max = total
            stageBar.progress = done
        } else {
            stageBar.max = 6
            stageBar.progress = step
        }
    }

    private fun renderOutputs(o: JSONObject) {
        outputBox.removeAllViews()
        val arr = o.optJSONArray("outputs")
        if (arr == null || arr.length() == 0) {
            outputBox.addView(Ui.text(this, "Chưa có kết quả nào.", 13f, Ui.DIM))
            return
        }
        for (i in 0 until arr.length()) {
            val f = arr.optJSONObject(i) ?: continue
            val name = f.optString("name")
            val card = Ui.column(this).apply {
                background = Ui.box(this@ProjectActivity, Ui.PANEL, Ui.LINE, 12f)
                setPadding(dp(14f), dp(12f), dp(14f), dp(12f))
            }
            card.addView(Ui.text(this, name, 15f))
            card.addView(Ui.text(this,
                "${f.optString("kind")} · ${mb(f.optLong("size"))}", 12f, Ui.DIM))
            /* The browser downloads it; a mesh viewer is a different app and
             * pretending otherwise here would be a worse one. */
            val get = Ui.button(this, "Tải về", Ui.BLUE)
            get.setOnClickListener {
                open("$server/api/projects/${Net.enc(project)}/download/${Net.enc(name)}")
            }
            card.addView(get, Ui.lp(MATCH, WRAP, 0f, 8, this))
            outputBox.addView(card, Ui.lp(MATCH, WRAP, 0f, 8, this))
        }
    }

    private fun mb(bytes: Long): String =
        if (bytes >= 1 shl 20) "%.1f MB".format(bytes / 1048576.0)
        else "${bytes / 1024} KB"

    // --------------------------------------------------------------------- log

    private fun tickLog() {
        if (server.isEmpty() || !logBusy.compareAndSet(false, true)) return
        val at = logOffset
        io.execute {
            try {
                val o = Net.getObject(server,
                    "/api/projects/${Net.enc(project)}/log?offset=$at")
                val text = o.optString("text")
                val next = o.optInt("offset")
                main.post { appendLog(text, next) }
            } catch (_: Exception) {
                /* The status poll reports the connection; a missing log chunk
                 * comes back on the next tick with the same offset. */
            } finally {
                logBusy.set(false)
            }
        }
    }

    private fun appendLog(text: String, next: Int) {
        logOffset = next
        if (text.isEmpty()) return
        logText.append(text)
        // The log outgrows any screen; keep the tail, which is the part being read.
        if (logText.length > LOG_KEEP) logText.delete(0, logText.length - LOG_KEEP)
        logView.text = logText.toString()
        if (tab == 2) logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
    }

    // ------------------------------------------------------------------- run

    private fun startRun() {
        val payload = JSONObject()
            .put("preset", if (runFast) "fast" else "normal")
            .put("mesher", "poisson")
            .put("use_mask", useMask)
        io.execute {
            try {
                Net.postJson(server, "/api/projects/${Net.enc(project)}/run", payload)
                main.post {
                    // A new run truncates the log server-side, so start reading
                    // it from the top again.
                    logOffset = 0
                    logText.setLength(0)
                    logView.text = ""
                    selectTab(2)
                    toast("Đã bắt đầu dựng")
                }
            } catch (e: Exception) {
                main.post { toast("Không chạy được: ${e.message}") }
            }
        }
    }

    private fun stopRun() {
        io.execute {
            try {
                Net.postJson(server, "/api/projects/${Net.enc(project)}/stop")
                main.post { toast("Đã gửi lệnh dừng") }
            } catch (e: Exception) {
                main.post { toast("Không dừng được: ${e.message}") }
            }
        }
    }

    // ---------------------------------------------------------------- photos

    private fun refreshImages() {
        io.execute {
            try {
                val arr = Net.getArray(server, "/api/projects/${Net.enc(project)}/images")
                val names = (0 until arr.length()).map { arr.optString(it) }
                main.post { buildGrid(names) }
            } catch (e: Exception) {
                main.post { photoNote.text = "Không tải được danh sách: ${e.message}" }
            }
        }
    }

    private fun buildGrid(names: List<String>) {
        images = names
        wanted.clear()
        photoGrid.removeAllViews()
        tiles = emptyList()
        photoNote.text = if (names.isEmpty()) "Chưa có ảnh nào."
                         else "${names.size} ảnh · chạm để xem to"
        if (names.isEmpty()) return
        val side = (resources.displayMetrics.widthPixels - dp(24f) - dp(8f)) / COLUMNS
        val built = ArrayList<ImageView>(names.size)
        var row: LinearLayout? = null
        for ((i, name) in names.withIndex()) {
            if (i % COLUMNS == 0) {
                row = Ui.row(this)
                photoGrid.addView(row, Ui.lp(MATCH, WRAP, 0f, 4, this))
            }
            val tile = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = Ui.box(this@ProjectActivity, Ui.PANEL2, Ui.LINE, 8f)
                setOnClickListener { showPhoto(i) }
            }
            row?.addView(tile, LinearLayout.LayoutParams(side, side).apply {
                if (i % COLUMNS != 0) leftMargin = dp(4f)
            })
            built += tile
        }
        tiles = built
        main.post { loadVisibleTiles() }
    }

    private fun tileFor(name: String): ImageView? {
        val i = images.indexOf(name)
        return if (i >= 0) tiles.getOrNull(i) else null
    }

    /**
     * Only the tiles on screen, plus a screen's worth either side so scrolling
     * does not stare at empty boxes.
     */
    private fun loadVisibleTiles() {
        if (tab != 1 || tiles.isEmpty()) return
        val top = photoScroll.scrollY - photoScroll.height
        val bottom = photoScroll.scrollY + photoScroll.height * 2
        for ((i, tile) in tiles.withIndex()) {
            val rowTop = (tile.parent as? View)?.top ?: continue
            val y = rowTop + tile.top
            if (y < top || y > bottom) continue
            val name = images.getOrNull(i) ?: continue
            val cached = cache[name]
            if (cached != null) {
                tile.setImageBitmap(cached)
                continue
            }
            if (!wanted.add(name)) continue
            loadThumb(name, tile.width.coerceAtLeast(dp(96f)))
        }
    }

    private fun loadThumb(name: String, target: Int) {
        val url = imageUrl(name)
        loader.execute {
            val bmp = try {
                decode(Net.bytes(url), target)
            } catch (e: Exception) {
                null
            } catch (e: OutOfMemoryError) {
                null
            }
            main.post {
                wanted.remove(name)
                if (bmp == null) return@post
                if (isFinishing || isDestroyed) {
                    bmp.recycle()
                    return@post
                }
                cache[name] = bmp
                tileFor(name)?.setImageBitmap(bmp)
            }
        }
    }

    /**
     * Decodes at roughly the size it will be shown at. inSampleSize is the only
     * knob that keeps the full frame from being materialised at all: halving
     * twice is a sixteenth of the memory, and a 110 dp tile cannot tell.
     */
    private fun decode(data: ByteArray, target: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
        val short = minOf(bounds.outWidth, bounds.outHeight)
        var sample = 1
        while (short / (sample * 2) >= target) sample *= 2
        return BitmapFactory.decodeByteArray(data, 0, data.size,
            BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private fun imageUrl(name: String) =
        "$server/api/projects/${Net.enc(project)}/image/${Net.enc(name)}"

    /** A thumbnail is enough to count photos, not to judge one; this is why the
     *  screen exists at all. */
    private fun showPhoto(index: Int) {
        if (images.isEmpty()) return
        viewerIndex = ((index % images.size) + images.size) % images.size
        val name = images[viewerIndex]
        viewer.visibility = View.VISIBLE
        viewerLabel.text = "${viewerIndex + 1}/${images.size} · $name"
        viewerImage.setImageDrawable(null)
        viewerBitmap?.recycle()
        viewerBitmap = null
        val target = resources.displayMetrics.widthPixels
        loader.execute {
            val bmp = try {
                decode(Net.bytes(imageUrl(name)), target)
            } catch (e: Exception) {
                null
            } catch (e: OutOfMemoryError) {
                null
            }
            main.post {
                if (bmp == null) {
                    toast("Không tải được ảnh")
                    return@post
                }
                // The user may have moved on while it was downloading.
                if (viewer.visibility != View.VISIBLE || images.getOrNull(viewerIndex) != name) {
                    bmp.recycle()
                    return@post
                }
                viewerBitmap = bmp
                viewerImage.setImageBitmap(bmp)
            }
        }
    }

    private fun closeViewer() {
        viewer.visibility = View.GONE
        viewerImage.setImageDrawable(null)
        viewerBitmap?.recycle()
        viewerBitmap = null
    }

    // ------------------------------------------------------------- analysis

    private fun analyze(refresh: Boolean) {
        if (refresh) {
            analyzeButton.isEnabled = false
            renderMarkdown("Đang phân tích… mất khoảng 15 giây.")
        }
        io.execute {
            try {
                val path = "/api/projects/${Net.enc(project)}"
                val o = if (refresh)
                    Net.postJson(server, "$path/analyze", JSONObject().put("refresh", true))
                else Net.getObject(server, "$path/analysis")
                val text = o.optString("text")
                main.post {
                    analyzeButton.isEnabled = true
                    renderMarkdown(text)
                }
            } catch (e: Exception) {
                main.post {
                    analyzeButton.isEnabled = true
                    renderMarkdown("Không phân tích được: ${e.message}")
                }
            }
        }
    }

    /**
     * The advice comes back as markdown. A heading and a bold pass is all the
     * structure it actually uses, and a real renderer would be a dependency
     * this build has no way to take.
     */
    private fun renderMarkdown(text: String) {
        analysisBox.removeAllViews()
        if (text.isBlank()) {
            analysisBox.addView(Ui.text(this, "Chưa có phân tích. Bấm nút ở trên.",
                13f, Ui.DIM))
            return
        }
        for (raw in text.split("\n")) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val heading = line.startsWith("#")
            val body = when {
                heading -> line.trimStart('#').trim()
                line.startsWith("- ") || line.startsWith("* ") -> "•  ${line.substring(2)}"
                else -> line
            }
            val tv = Ui.text(this, "", if (heading) 15f else 13f,
                if (heading) Ui.AMBER else Ui.INK)
            tv.text = inlineBold(body)
            if (heading) tv.setTypeface(tv.typeface, Typeface.BOLD)
            tv.setPadding(0, dp(if (heading) 12f else 3f), 0, 0)
            analysisBox.addView(tv, Ui.lp(MATCH, WRAP))
        }
    }

    private fun inlineBold(s: String): CharSequence {
        val out = SpannableStringBuilder()
        for ((i, part) in s.split("**").withIndex()) {
            val start = out.length
            out.append(part)
            if (i % 2 == 1) {
                out.setSpan(StyleSpan(Typeface.BOLD), start, out.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        return out
    }

    private fun open(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {
            toast("Không mở được trình duyệt")
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    companion object {
        private const val POLL_MS = 2_000L
        /** Tail worth keeping in memory; the log itself runs to megabytes. */
        private const val LOG_KEEP = 40_000
        private const val COLUMNS = 3
        /** Roughly three screens of tiles at ~50 KB each. */
        private const val CACHE_MAX = 48
        private val TABS = listOf("Tiến độ", "Ảnh", "Nhật ký", "Kết quả", "Phân tích")
    }
}
