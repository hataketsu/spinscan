package vn.npay.collmap

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.zip.CRC32

/**
 * Direct control of the turntable board over OTG.
 *
 * The board speaks a line protocol, and every field here is one of its settings
 * keys. Values are read back from the board's own status dump rather than kept
 * in the app, so what the screen shows is what the hardware actually holds --
 * including whatever was restored from its flash at power-on.
 */
class TurntableFragment : Fragment(R.layout.fragment_turntable), Ch340.Listener {

    private lateinit var serial: Ch340
    /* Resolved once, on the main thread. runFlash() builds its messages from a
     * background thread and must not go through the fragment for them: a
     * detached fragment throws, and it would throw inside the failure path. */
    private lateinit var appCtx: Context
    private lateinit var statusView: TextView
    private lateinit var stateLine: TextView
    private lateinit var logView: TextView
    private lateinit var runButton: MaterialButton
    private lateinit var flashButton: MaterialButton
    private lateinit var flashProgress: LinearProgressIndicator

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    /** Board replies, handed over by [onLine] while a firmware write runs. */
    private val replies = LinkedBlockingQueue<String>()
    @Volatile private var flashing = false

    private var runningSlot = -1
    private var nextSlot = -1

    /**
     * True only between a connect (or an explicit "Đọc lại") and the "ok" that
     * closes that status dump. The board is the source of truth at that moment
     * and the app is afterwards, so mirroring outside this window would fight
     * whatever the user has since set.
     */
    private var mirroring = false

    /** Settings key -> the input showing it. */
    private val fields = LinkedHashMap<String, TextInputEditText>()
    private val log = StringBuilder()
    private var running = false

    private val editable = listOf(
        "shots" to R.string.tt_shots,
        "interval" to R.string.tt_interval,
        "current" to R.string.tt_current,
        "dir" to R.string.tt_dir,
        "micro" to R.string.tt_micro,
        "gearnum" to R.string.tt_gearnum,
        "gearden" to R.string.tt_gearden,
        "dstart" to R.string.tt_dstart,
        "dmin" to R.string.tt_dmin,
        "ramp" to R.string.tt_ramp,
        "beepms" to R.string.tt_beepms,
    )

    override fun onViewCreated(view: View, state: Bundle?) {
        appCtx = requireContext().applicationContext
        stateLine = view.findViewById(R.id.state_line)
        statusView = view.findViewById(R.id.board_status)
        logView = view.findViewById(R.id.log_view)
        runButton = view.findViewById(R.id.run)
        flashButton = view.findViewById(R.id.flash)
        flashProgress = view.findViewById(R.id.flash_progress)

        view.findViewById<MaterialButton>(R.id.connect).setOnClickListener { serial.connect() }
        view.findViewById<MaterialButton>(R.id.reread).setOnClickListener {
            mirroring = true; send("?")
        }
        runButton.setOnClickListener { send(if (running) "STOP" else "RUN") }
        view.findViewById<MaterialButton>(R.id.one_step).setOnClickListener { send("STEP") }
        view.findViewById<MaterialButton>(R.id.sync).setOnClickListener { send("SYNC") }
        view.findViewById<MaterialButton>(R.id.minus90).setOnClickListener { send("TURN -90") }
        view.findViewById<MaterialButton>(R.id.plus90).setOnClickListener { send("TURN 90") }
        view.findViewById<MaterialButton>(R.id.zero).setOnClickListener { send("ZERO") }
        view.findViewById<MaterialButton>(R.id.release).setOnClickListener { send("OFF") }
        view.findViewById<MaterialButton>(R.id.save_board).setOnClickListener { send("SAVE") }
        view.findViewById<MaterialButton>(R.id.defaults).setOnClickListener {
            send("DEFAULTS"); send("?")
        }
        flashButton.setOnClickListener { confirmFlash() }

        buildFields(view.findViewById(R.id.fields))

        /* One driver per process, on the application context: the port stays
         * open after this screen goes away so the capture screen can keep
         * talking to the board, and two activities cannot claim the interface. */
        serial = Ch340.shared ?: Ch340(requireContext().applicationContext)
            .also { Ch340.shared = it }
        serial.listener = this
        if (serial.isOpen) onState(true, getString(R.string.already_wired)) else serial.connect()
    }

    private fun buildFields(box: LinearLayout) {
        val inflater = LayoutInflater.from(requireContext())
        fields.clear()
        box.removeAllViews()
        for ((key, labelRes) in editable) {
            val row = inflater.inflate(R.layout.item_turntable_field, box, false)
            row.findViewById<TextInputLayout>(R.id.field_layout).hint = getString(labelRes)
            val input = row.findViewById<TextInputEditText>(R.id.field)
            fields[key] = input
            row.findViewById<MaterialButton>(R.id.apply).setOnClickListener {
                val v = input.text.toString().trim()
                if (v.isEmpty()) return@setOnClickListener
                send("SET $key $v")
                /* The app just decided this value, so keep its own copy in step
                 * instead of waiting for a refresh to read it back. */
                saveSetting(key, v)
            }
            box.addView(row)
        }
    }

    override fun onDestroyView() {
        /* Deliberately not released: only the listener goes, the link stays. */
        if (serial.listener === this) serial.listener = null
        super.onDestroyView()
    }

    override fun onDestroy() {
        /* shutdown(), not shutdownNow(): a write already streaming has to reach
         * the end of the image, or the board sits half-fed until it times out. */
        io.shutdown()
        super.onDestroy()
    }

    private fun attrColor(attr: Int): Int = with(Ui) { requireContext().themeColor(attr) }

    private fun colorDim() = attrColor(com.google.android.material.R.attr.colorOnSurfaceVariant)
    private fun colorError() = attrColor(com.google.android.material.R.attr.colorError)
    private fun colorOk() = attrColor(com.google.android.material.R.attr.colorTertiary)

    private fun send(cmd: String) {
        if (!serial.isOpen) {
            notConnected()
            return
        }
        if (flashing) {
            stateLine.setTextColor(colorError())
            stateLine.setText(R.string.flashing_wait)
            return
        }
        appendLog("> $cmd")
        serial.send(cmd)
        /* Any command can move the machine or change a value, so pull a fresh
         * status afterwards instead of guessing what it did. */
        if (cmd != "?") serial.send("?")
    }

    // ------------------------------------------------------------- callbacks

    override fun onState(connected: Boolean, message: String) {
        if (!isAdded) return
        stateLine.setTextColor(if (connected) colorOk() else colorDim())
        stateLine.text = message
        if (connected) {
            mirroring = true
            serial.send("?")
        }
    }

    override fun onLine(line: String) {
        if (!isAdded) return
        appendLog(line)
        if (flashing) replies.offer(line)
        if (line == "ok") mirroring = false          /* end of the status dump */
        val i = line.indexOf('=')
        if (i <= 0) return
        val key = line.substring(0, i)
        val value = line.substring(i + 1)

        fields[key]?.let { field ->
            /* Do not fight the user: a field being edited keeps its text. */
            if (!field.hasFocus()) field.setText(value)
        }
        if (mirroring) saveSetting(key, value)
        when (key) {
            "running" -> {
                running = value == "1"
                runButton.setText(if (running) R.string.stop else R.string.run)
            }
            "slot" -> {
                runningSlot = value.toIntOrNull() ?: -1
                summary[key] = value
            }
            "next_slot" -> nextSlot = value.toIntOrNull() ?: -1
            "shot", "pos", "next_ms", "steps_per_rev", "deg_per_shot_x100", "enabled" ->
                summary[key] = value
        }
        renderSummary()
    }

    private val summary = LinkedHashMap<String, String>()

    private fun renderSummary() {
        val deg = summary["deg_per_shot_x100"]?.toIntOrNull()
        statusView.text = buildString {
            append(getString(R.string.board_line1,
                summary["shot"] ?: "?", summary["pos"] ?: "?"))
            if (deg != null) {
                append(' ').append(getString(R.string.deg_per_step, deg / 100, deg % 100))
            }
            append('\n')
            append(getString(R.string.board_line2, summary["steps_per_rev"] ?: "?",
                getString(if (summary["enabled"] == "1") R.string.yes_short
                          else R.string.no_short)))
            summary["next_ms"]?.let {
                if (running) append(' ').append(getString(R.string.next_step_ms, it))
            }
            if (runningSlot >= 0) {
                append(' ').append(getString(R.string.slot_fmt,
                    slotName(runningSlot).uppercase()))
            }
        }
    }

    private fun appendLog(s: String) {
        log.append(s).append('\n')
        if (log.length > 4000) log.delete(0, log.length - 3000)
        logView.text = log
    }

    private fun notConnected() {
        stateLine.setTextColor(colorError())
        stateLine.setText(R.string.not_wired_hint)
    }

    private fun prefs() =
        requireContext().getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)

    /** Keeps the app's copy of the two settings the capture screen also needs. */
    private fun saveSetting(key: String, value: String) {
        if (key != KEY_INTERVAL && key != KEY_SHOTS) return
        val n = value.toIntOrNull() ?: return
        prefs().edit().putInt(key, n).apply()
    }

    private fun slotName(slot: Int) = if (slot == 1) "b" else "a"

    // -------------------------------------------------------------- firmware

    private fun confirmFlash() {
        if (!serial.isOpen) {
            notConnected()
            return
        }
        if (nextSlot < 0) {
            stateLine.setTextColor(colorError())
            stateLine.setText(R.string.fw_no_state)
            return
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.flash_firmware)
            .setMessage(getString(R.string.fw_confirm_body,
                slotName(nextSlot).uppercase(), slotName(runningSlot).uppercase()))
            .setPositiveButton(R.string.fw_write) { _, _ -> startFlash(slotName(nextSlot)) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun startFlash(slot: String) {
        val base = prefs().getString(MainActivity.KEY_SERVER, "")?.trimEnd('/').orEmpty()
        if (base.isEmpty()) {
            stateLine.setTextColor(colorError())
            stateLine.setText(R.string.fw_no_server)
            return
        }
        flashing = true
        flashButton.isEnabled = false
        flashProgress.visibility = View.VISIBLE
        flashProgress.isIndeterminate = true
        replies.clear()
        flashStatus(getString(R.string.fw_asking))
        io.execute { runFlash(base, slot) }
    }

    /**
     * Fetch, verify, hand over. Runs on [io]; every reply from the board comes
     * back through [onLine] into [replies], so nothing here polls the UI.
     */
    private fun runFlash(base: String, slot: String) {
        try {
            val manifest = Net.getObject(base, "/api/fw/latest")
            val entry = manifest.getJSONObject("slots").getJSONObject(slot)
            val size = entry.getInt("size")
            val crc = entry.getLong("crc32")

            flashStatus(appCtx.getString(R.string.fw_downloading,
                manifest.optString("version"), slot.uppercase()))
            val image = Net.bytes("$base/api/fw/download/$slot")

            /* The board cannot tell a truncated download from a good one until
             * it has already taken the motor down and answered "ready", so
             * everything checkable is checked before the cable is involved. */
            if (image.size != size) {
                flashFailed(appCtx.getString(R.string.fw_size_mismatch, image.size, size))
                return
            }
            val sum = CRC32().apply { update(image, 0, image.size) }.value
            if (sum != crc) {
                flashFailed(appCtx.getString(R.string.fw_crc_mismatch, sum, crc))
                return
            }

            logLine("> FLASH $size $crc")
            replies.clear()
            serial.send("FLASH $size $crc")
            val ready = await(5_000) { it == "ready" || it.startsWith("err") }
            when {
                ready == null -> { flashFailed(appCtx.getString(R.string.fw_no_ready)); return }
                ready != "ready" -> { flashFailed(appCtx.getString(R.string.fw_refused, ready)); return }
            }

            val ok = serial.sendBytes(image) { sent, total ->
                flashProgressTo(sent, total)
                flashStatus(appCtx.getString(R.string.fw_sending, sent / 1024f, total / 1024f))
            }
            if (!ok) {
                flashFailed(appCtx.getString(R.string.fw_send_failed))
                return
            }

            flashStatus(appCtx.getString(R.string.fw_sent_wait))
            val verdict = await(10_000) { it.startsWith("ok ") || it.startsWith("err") }
            when {
                verdict == null -> { flashFailed(appCtx.getString(R.string.fw_no_verdict)); return }
                !verdict.startsWith("ok ") ->
                    { flashFailed(appCtx.getString(R.string.fw_refused, verdict)); return }
            }

            flashStatus(appCtx.getString(R.string.fw_writing))
            /* The write ends in a reset, and the new image says hello the same
             * way the old one did. */
            val rebooted = await(20_000) { it.contains("turntable ready") }
            main.post {
                flashing = false
                if (!isAdded) return@post
                flashButton.isEnabled = true
                flashProgress.visibility = View.GONE
                stateLine.setTextColor(if (rebooted != null) colorOk() else colorError())
                stateLine.setText(
                    if (rebooted != null) R.string.fw_rebooted else R.string.fw_no_reboot)
                mirroring = true
                serial.send("?")
            }
        } catch (e: Exception) {
            flashFailed(appCtx.getString(R.string.fw_error, e.javaClass.simpleName, e.message ?: ""))
        }
    }

    /** Blocks the caller's background thread until the board says [match]. */
    private fun await(timeoutMs: Long, match: (String) -> Boolean): String? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val left = deadline - System.currentTimeMillis()
            if (left <= 0) return null
            val line = replies.poll(left, TimeUnit.MILLISECONDS) ?: return null
            if (match(line)) return line
        }
    }

    private fun flashProgressTo(sent: Int, total: Int) {
        main.post {
            if (!isAdded || total <= 0) return@post
            flashProgress.isIndeterminate = false
            flashProgress.max = total
            flashProgress.setProgressCompat(sent, true)
        }
    }

    private fun flashStatus(message: String) {
        main.post {
            if (!isAdded) return@post
            stateLine.setTextColor(colorDim())
            stateLine.text = message
        }
    }

    private fun flashFailed(message: String) {
        main.post {
            flashing = false
            if (!isAdded) return@post
            flashButton.isEnabled = true
            flashProgress.visibility = View.GONE
            stateLine.setTextColor(colorError())
            stateLine.text = message
            appendLog("! $message")
        }
    }

    private fun logLine(s: String) {
        main.post { if (isAdded) appendLog(s) }
    }

    companion object {
        const val KEY_INTERVAL = "interval"
        const val KEY_SHOTS = "shots"
    }
}
