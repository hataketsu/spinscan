package vn.npay.collmap

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
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import androidx.activity.OnBackPressedCallback
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import org.json.JSONObject
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
 *    are downsampled to the tile and the grid is a RecyclerView, so a tile is
 *    fetched when it scrolls into view and dropped when it leaves.
 */
class ProjectDetailFragment : Fragment(R.layout.fragment_project_detail) {

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

    /**
     * Access-ordered by construction. Bitmaps are dropped rather than recycled:
     * from API 26 the pixels live on the Java heap, so letting go of the last
     * reference is enough, and an explicit recycle() risked freeing one that a
     * tile on screen was still drawing.
     */
    private val cache = object : LruCache<String, Bitmap>(CACHE_MAX) {}

    private var runFast = false
    private var useMask = false
    private var viewerIndex = 0

    // ---- views ---------------------------------------------------------------
    private lateinit var toolbar: MaterialToolbar
    private lateinit var tabs: TabLayout
    private lateinit var pager: ViewPager2
    private lateinit var stageView: TextView
    private lateinit var stageBar: LinearProgressIndicator
    private lateinit var runButton: MaterialButton
    private lateinit var stopButton: MaterialButton
    private lateinit var photoGrid: RecyclerView
    private lateinit var photoNote: TextView
    private lateinit var logView: TextView
    private lateinit var logScroll: NestedScrollView
    private lateinit var outputBox: LinearLayout
    private lateinit var analysisBox: LinearLayout
    private lateinit var analyzeButton: MaterialButton
    private lateinit var analyzeProgress: LinearProgressIndicator
    private lateinit var viewer: View
    private lateinit var photoPager: ViewPager2
    private lateinit var viewerLabel: TextView
    private lateinit var backCallback: OnBackPressedCallback

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        server = arguments?.getString(ARG_SERVER) ?: ""
        project = arguments?.getString(ARG_PROJECT) ?: ""
    }

    override fun onViewCreated(view: View, state: Bundle?) {
        toolbar = view.findViewById(R.id.toolbar)
        toolbar.title = project
        toolbar.subtitle = getString(R.string.status_idle)
        toolbar.setNavigationOnClickListener {
            parentFragmentManager.popBackStack()
        }

        val inflater = LayoutInflater.from(requireContext())
        val pages = listOf(
            inflater.inflate(R.layout.page_project_progress, null),
            inflater.inflate(R.layout.page_project_photos, null),
            inflater.inflate(R.layout.page_project_log, null),
            inflater.inflate(R.layout.page_project_outputs, null),
            inflater.inflate(R.layout.page_project_analysis, null))
        bindProgressPage(pages[0])
        bindPhotoPage(pages[1])
        bindLogPage(pages[2])
        outputBox = pages[3].findViewById(R.id.output_box)
        bindAnalysisPage(pages[4])

        tabs = view.findViewById(R.id.tabs)
        pager = view.findViewById(R.id.pager)
        pager.adapter = PageAdapter(pages)
        pager.offscreenPageLimit = pages.size
        TabLayoutMediator(tabs, pager) { tab, position ->
            tab.setText(TABS[position])
        }.attach()
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) = onTabShown(position)
        })

        bindViewer(view)
        backCallback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() = closeViewer()
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backCallback)
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
        cache.evictAll()
        super.onDestroy()
    }

    private fun attrColor(attr: Int): Int = with(Ui) { requireContext().themeColor(attr) }

    // -------------------------------------------------------------------- ui

    private fun bindProgressPage(page: View) {
        stageView = page.findViewById(R.id.stage)
        stageView.text = getString(R.string.tilt_unknown)
        stageBar = page.findViewById(R.id.stage_bar)
        runButton = page.findViewById(R.id.run)
        stopButton = page.findViewById(R.id.stop)

        val presets = page.findViewById<MaterialButtonToggleGroup>(R.id.preset_group)
        presets.check(R.id.preset_normal)
        presets.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) runFast = checkedId == R.id.preset_fast
        }
        page.findViewById<MaterialSwitch>(R.id.use_mask).setOnCheckedChangeListener { _, on ->
            useMask = on
        }
        runButton.setOnClickListener { startRun() }
        stopButton.setOnClickListener { stopRun() }
    }

    private fun bindPhotoPage(page: View) {
        photoNote = page.findViewById(R.id.photo_note)
        photoGrid = page.findViewById(R.id.photo_grid)
        photoGrid.layoutManager = GridLayoutManager(requireContext(), COLUMNS)
        photoGrid.adapter = ThumbAdapter()
        photoGrid.setHasFixedSize(true)
    }

    private fun bindLogPage(page: View) {
        logView = page.findViewById(R.id.log_view)
        logScroll = page.findViewById(R.id.log_scroll)
    }

    private fun bindAnalysisPage(page: View) {
        analyzeButton = page.findViewById(R.id.analyze)
        analyzeProgress = page.findViewById(R.id.analyze_progress)
        analysisBox = page.findViewById(R.id.analysis_box)
        analyzeButton.setOnClickListener { analyze(refresh = true) }
    }

    private fun onTabShown(position: Int) {
        when (position) {
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
            /* Switching tabs hides this fragment rather than pausing it, so
             * onPause is not the whole story any more: isShown is false while
             * another destination is up, and there is nothing to poll for a
             * screen nobody can see. The tick still reschedules itself -- it is
             * the network calls that are skipped, not the timer. */
            if (view?.isShown == true) {
                tickStatus()
                if (pager.currentItem == 2 || status == "running") tickLog()
            }
            main.postDelayed(this, POLL_MS)
        }
    }

    private fun tickStatus() {
        if (server.isEmpty() || !statusBusy.compareAndSet(false, true)) return
        io.execute {
            try {
                val o = Net.getObject(server, "/api/projects/${Net.enc(project)}")
                main.post { if (isAdded) renderStatus(o) }
            } catch (e: Exception) {
                main.post {
                    if (!isAdded) return@post
                    toolbar.subtitle = getString(R.string.read_failed, e.message ?: "")
                    toolbar.setSubtitleTextColor(
                        attrColor(com.google.android.material.R.attr.colorError))
                }
            } finally {
                statusBusy.set(false)
            }
        }
    }

    private fun renderStatus(o: JSONObject) {
        status = o.optString("status", "idle")
        val imageCount = o.optInt("images")
        val depth = o.optInt("depth_maps")
        toolbar.setSubtitleTextColor(statusColor())
        toolbar.subtitle = buildString {
            append(getString(R.string.status_line, statusLabel(), imageCount))
            if (depth > 0) append(" ").append(getString(R.string.status_depth, depth))
        }
        runButton.isEnabled = status != "running"
        stopButton.isEnabled = status == "running"
        renderProgress(o.optJSONObject("progress"))

        val outs = o.optJSONArray("outputs")?.toString() ?: "[]"
        if (outs != lastOutputs) {
            lastOutputs = outs
            renderOutputs(o)
        }
        if (imageCount != lastImageCount) {
            lastImageCount = imageCount
            refreshImages()
        }
    }

    private fun statusLabel() = getString(when (status) {
        "running" -> R.string.status_running
        "done" -> R.string.status_done
        "failed" -> R.string.status_failed
        "stopped" -> R.string.status_stopped
        else -> R.string.status_idle
    })

    private fun statusColor() = attrColor(when (status) {
        "running" -> com.google.android.material.R.attr.colorPrimary
        "done" -> com.google.android.material.R.attr.colorTertiary
        "failed" -> com.google.android.material.R.attr.colorError
        else -> com.google.android.material.R.attr.colorOnSurfaceVariant
    })

    private fun renderProgress(p: JSONObject?) {
        if (p == null) {
            stageView.text = getString(
                if (status == "running") R.string.stage_running else R.string.stage_none)
            stageBar.visibility = View.GONE
            return
        }
        val step = p.optInt("step")
        val done = p.optInt("done")
        val total = p.optInt("total")
        val head = getString(R.string.stage_fmt, step, p.optString("label"))
        stageView.text =
            if (total > 0) getString(R.string.stage_count_fmt, head, done, total) else head
        stageBar.visibility = View.VISIBLE
        /* Inside patch_match the view counter is the only honest measure of
         * progress; everywhere else all that is known is which of the six
         * stages is running. */
        if (total > 0) {
            stageBar.max = total
            stageBar.setProgressCompat(done, true)
        } else {
            stageBar.max = 6
            stageBar.setProgressCompat(step, true)
        }
    }

    private fun renderOutputs(o: JSONObject) {
        outputBox.removeAllViews()
        val arr = o.optJSONArray("outputs")
        if (arr == null || arr.length() == 0) {
            outputBox.addView(TextView(requireContext()).apply {
                text = getString(R.string.no_outputs)
                setTextColor(attrColor(
                    com.google.android.material.R.attr.colorOnSurfaceVariant))
            })
            return
        }
        val inflater = LayoutInflater.from(requireContext())
        for (i in 0 until arr.length()) {
            val f = arr.optJSONObject(i) ?: continue
            val name = f.optString("name")
            val card = inflater.inflate(R.layout.item_output, outputBox, false)
            card.findViewById<TextView>(R.id.name).text = name
            card.findViewById<TextView>(R.id.meta).text =
                getString(R.string.output_meta, f.optString("kind"), mb(f.optLong("size")))
            /* The browser downloads it; a mesh viewer is a different app and
             * pretending otherwise here would be a worse one. */
            card.findViewById<MaterialButton>(R.id.download).setOnClickListener {
                open("$server/api/projects/${Net.enc(project)}/download/${Net.enc(name)}")
            }
            outputBox.addView(card)
        }
    }

    private fun mb(bytes: Long): String =
        if (bytes >= 1 shl 20) getString(R.string.size_mb, bytes / 1048576.0)
        else getString(R.string.size_kb, bytes / 1024)

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
                main.post { if (isAdded) appendLog(text, next) }
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
        if (pager.currentItem == 2) logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
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
                    if (!isAdded) return@post
                    // A new run truncates the log server-side, so start reading
                    // it from the top again.
                    logOffset = 0
                    logText.setLength(0)
                    logView.text = ""
                    pager.currentItem = 2
                    snack(R.string.run_started)
                }
            } catch (e: Exception) {
                main.post { snack(getString(R.string.run_start_failed, e.message ?: "")) }
            }
        }
    }

    private fun stopRun() {
        io.execute {
            try {
                Net.postJson(server, "/api/projects/${Net.enc(project)}/stop")
                main.post { snack(R.string.stop_sent) }
            } catch (e: Exception) {
                main.post { snack(getString(R.string.stop_failed, e.message ?: "")) }
            }
        }
    }

    // ---------------------------------------------------------------- photos

    private fun refreshImages() {
        io.execute {
            try {
                val arr = Net.getArray(server, "/api/projects/${Net.enc(project)}/images")
                val names = (0 until arr.length()).map { arr.optString(it) }
                main.post { if (isAdded) setImages(names) }
            } catch (e: Exception) {
                main.post {
                    if (isAdded) photoNote.text =
                        getString(R.string.photo_list_failed, e.message ?: "")
                }
            }
        }
    }

    private fun setImages(names: List<String>) {
        images = names
        photoNote.text = if (names.isEmpty()) getString(R.string.no_photos)
        else getString(R.string.photos_hint, names.size)
        photoGrid.adapter?.notifyDataSetChanged()
        photoPager.adapter?.notifyDataSetChanged()
    }

    private fun imageUrl(name: String) =
        "$server/api/projects/${Net.enc(project)}/image/${Net.enc(name)}"

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

    private fun fetch(name: String, target: Int, into: (Bitmap?) -> Unit) {
        loader.execute {
            val bmp = try {
                decode(Net.bytes(imageUrl(name)), target)
            } catch (_: Exception) {
                null
            } catch (_: OutOfMemoryError) {
                null
            }
            main.post { if (isAdded) into(bmp) else Unit }
        }
    }

    /**
     * The grid. RecyclerView does the "only what is on screen" part that used to
     * be a scroll listener walking every tile: a thumbnail costs a whole 12 MP
     * JPEG over the WiFi, so one is fetched when its tile is bound and not
     * before.
     */
    private inner class ThumbAdapter : RecyclerView.Adapter<ThumbHolder>() {
        override fun getItemCount() = images.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ThumbHolder {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_photo, parent, false)
            /* Square tiles, and the side is the column width. Falls back to the
             * screen when the RecyclerView has not been measured yet, because a
             * tile that came out zero high would simply never appear. */
            val available = (parent.width - parent.paddingLeft - parent.paddingRight)
                .takeIf { it > 0 }
                ?: (resources.displayMetrics.widthPixels - with(Ui) { requireContext().dp(24f) })
            v.layoutParams.height = available / COLUMNS
            return ThumbHolder(v)
        }

        override fun onBindViewHolder(holder: ThumbHolder, position: Int) {
            val name = images[position]
            holder.name = name
            holder.itemView.setOnClickListener { showPhoto(position) }
            val cached = cache.get(name)
            if (cached != null) {
                holder.thumb.setImageBitmap(cached)
                return
            }
            holder.thumb.setImageDrawable(null)
            fetch(name, holder.thumb.width.coerceAtLeast(THUMB_MIN_PX)) { bmp ->
                if (bmp == null) return@fetch
                cache.put(name, bmp)
                // The tile may have been reused for a different photo meanwhile.
                if (holder.name == name) holder.thumb.setImageBitmap(bmp)
            }
        }
    }

    private inner class ThumbHolder(view: View) : RecyclerView.ViewHolder(view) {
        val thumb: ImageView = view.findViewById(R.id.thumb)
        var name: String = ""
    }

    // ---------------------------------------------------------------- viewer

    /** A thumbnail is enough to count photos, not to judge one; this is why the
     *  screen exists at all. */
    private fun bindViewer(root: View) {
        viewer = root.findViewById(R.id.viewer)
        viewerLabel = root.findViewById(R.id.viewer_label)
        photoPager = root.findViewById(R.id.photo_pager)
        photoPager.adapter = PhotoAdapter()
        photoPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                viewerIndex = position
                renderViewerLabel()
            }
        })
        root.findViewById<MaterialButton>(R.id.viewer_close).setOnClickListener { closeViewer() }
    }

    private fun showPhoto(index: Int) {
        if (images.isEmpty()) return
        viewerIndex = index.coerceIn(0, images.size - 1)
        viewer.visibility = View.VISIBLE
        backCallback.isEnabled = true
        photoPager.setCurrentItem(viewerIndex, false)
        renderViewerLabel()
    }

    private fun renderViewerLabel() {
        val name = images.getOrNull(viewerIndex) ?: return
        viewerLabel.text = getString(R.string.viewer_label, viewerIndex + 1, images.size, name)
    }

    private fun closeViewer() {
        viewer.visibility = View.GONE
        backCallback.isEnabled = false
    }

    /**
     * One photo per page, so a swipe in the viewer moves between photos and not
     * between tabs. Each page decodes at screen width, never at 12 MP.
     */
    private inner class PhotoAdapter : RecyclerView.Adapter<PhotoHolder>() {
        override fun getItemCount() = images.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = PhotoHolder(
            LayoutInflater.from(parent.context)
                .inflate(R.layout.item_photo_page, parent, false))

        override fun onBindViewHolder(holder: PhotoHolder, position: Int) {
            val name = images[position]
            holder.name = name
            holder.image.setImageDrawable(null)
            holder.busy.visibility = View.VISIBLE
            fetch(name, resources.displayMetrics.widthPixels) { bmp ->
                if (holder.name != name) return@fetch
                holder.busy.visibility = View.GONE
                if (bmp == null) {
                    snack(R.string.photo_load_failed)
                    return@fetch
                }
                holder.image.setImageBitmap(bmp)
            }
        }

        override fun onViewRecycled(holder: PhotoHolder) {
            holder.name = ""
            holder.image.setImageDrawable(null)
        }
    }

    private inner class PhotoHolder(view: View) : RecyclerView.ViewHolder(view) {
        val image: ImageView = view.findViewById(R.id.photo)
        val busy: CircularProgressIndicator = view.findViewById(R.id.photo_busy)
        var name: String = ""
    }

    // ------------------------------------------------------------- analysis

    private fun analyze(refresh: Boolean) {
        if (refresh) {
            analyzeButton.isEnabled = false
            analyzeProgress.visibility = View.VISIBLE
            renderMarkdown(getString(R.string.analyzing))
        }
        io.execute {
            try {
                val path = "/api/projects/${Net.enc(project)}"
                val o = if (refresh)
                    Net.postJson(server, "$path/analyze", JSONObject().put("refresh", true))
                else Net.getObject(server, "$path/analysis")
                val text = o.optString("text")
                main.post {
                    if (!isAdded) return@post
                    analyzeButton.isEnabled = true
                    analyzeProgress.visibility = View.GONE
                    renderMarkdown(text)
                }
            } catch (e: Exception) {
                main.post {
                    if (!isAdded) return@post
                    analyzeButton.isEnabled = true
                    analyzeProgress.visibility = View.GONE
                    renderMarkdown(getString(R.string.analyze_failed, e.message ?: ""))
                }
            }
        }
    }

    /**
     * The advice comes back as markdown. A heading and a bold pass is all the
     * structure it actually uses, and a real renderer would be a dependency
     * worth more than the two spans it would replace.
     */
    private fun renderMarkdown(text: String) {
        analysisBox.removeAllViews()
        val dim = attrColor(com.google.android.material.R.attr.colorOnSurfaceVariant)
        val ink = attrColor(com.google.android.material.R.attr.colorOnSurface)
        val accent = attrColor(com.google.android.material.R.attr.colorPrimary)
        if (text.isBlank()) {
            analysisBox.addView(TextView(requireContext()).apply {
                setText(R.string.no_analysis)
                setTextColor(dim)
            })
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
            val tv = TextView(requireContext())
            tv.setTextAppearance(
                if (heading) R.style.TextAppearance_Collmap_TitleSmall
                else R.style.TextAppearance_Collmap_BodyMedium)
            tv.setTextColor(if (heading) accent else ink)
            tv.text = inlineBold(body)
            if (heading) tv.setTypeface(tv.typeface, Typeface.BOLD)
            tv.setPadding(0, with(Ui) { requireContext().dp(if (heading) 12f else 3f) }, 0, 0)
            analysisBox.addView(tv, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT))
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
            snack(R.string.no_browser)
        }
    }

    companion object {
        private const val ARG_SERVER = "server"
        private const val ARG_PROJECT = "project"
        private const val POLL_MS = 2_000L
        /** Tail worth keeping in memory; the log itself runs to megabytes. */
        private const val LOG_KEEP = 40_000
        private const val COLUMNS = 3
        /** Roughly three screens of tiles at ~50 KB each. */
        private const val CACHE_MAX = 48
        private const val THUMB_MIN_PX = 240
        private val TABS = listOf(R.string.tab_progress, R.string.tab_photos,
            R.string.tab_log, R.string.tab_outputs, R.string.tab_analysis)

        fun create(server: String, project: String) = ProjectDetailFragment().apply {
            arguments = Bundle().apply {
                putString(ARG_SERVER, server)
                putString(ARG_PROJECT, project)
            }
        }
    }
}
