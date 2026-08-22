package vn.npay.collmap

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import vn.npay.collmap.Ui.MATCH
import vn.npay.collmap.Ui.WRAP
import vn.npay.collmap.Ui.dp
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
class TurntableActivity : Activity(), Ch340.Listener {

    private lateinit var serial: Ch340
    private lateinit var statusView: TextView
    private lateinit var stateLine: TextView
    private lateinit var logView: TextView
    private lateinit var runButton: Button
    private lateinit var flashButton: Button

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
    private val fields = LinkedHashMap<String, EditText>()
    private val log = StringBuilder()
    private var running = false

    private val editable = listOf(
        "shots"    to "Số nấc mỗi vòng",
        "interval" to "Chu kỳ đứng yên (ms)",
        "current"  to "Dòng driver (%)",
        "dir"      to "Chiều (1 / -1)",
        "micro"    to "Vi bước (theo jumper)",
        "gearnum"  to "Giảm tốc: tử",
        "gearden"  to "Giảm tốc: mẫu",
        "dstart"   to "Delay đầu ramp (µs)",
        "dmin"     to "Delay khi chạy đều (µs)",
        "ramp"     to "Số bước ramp",
        "beepms"   to "Độ dài bíp (ms)",
    )

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val ctx = this

        val scroll = ScrollView(ctx).apply { setBackgroundColor(Ui.GROUND) }
        val col = Ui.column(ctx).apply { setPadding(dp(18f), dp(24f), dp(18f), dp(18f)) }
        scroll.addView(col)
        setContentView(scroll)

        col.addView(Ui.text(ctx, "Bàn xoay", 24f).apply { letterSpacing = -0.02f })
        stateLine = Ui.text(ctx, "Chưa nối", 13f, Ui.DIM)
        col.addView(stateLine, Ui.lp(MATCH, WRAP, 0f, 6, ctx))

        val top = Ui.row(ctx)
        val connect = Ui.button(ctx, "Nối OTG", Ui.BLUE)
        val refresh = Ui.button(ctx, "Đọc lại")
        top.addView(connect, Ui.lp(0, WRAP, 1f))
        top.addView(Ui.gap(ctx, 10f))
        top.addView(refresh, Ui.lp(0, WRAP, 1f))
        col.addView(top, Ui.lp(MATCH, WRAP, 0f, 12, ctx))

        col.addView(Ui.label(ctx, "Trạng thái"))
        statusView = Ui.mono(ctx, "—", 13f, Ui.DIM)
        col.addView(statusView)

        col.addView(Ui.label(ctx, "Điều khiển"))
        runButton = Ui.button(ctx, "Chạy", Ui.AMBER)
        val row1 = Ui.row(ctx)
        row1.addView(runButton, Ui.lp(0, WRAP, 1.2f))
        row1.addView(Ui.gap(ctx, 8f))
        row1.addView(Ui.button(ctx, "Một nấc").also { it.setOnClickListener { send("STEP") } },
            Ui.lp(0, WRAP, 1f))
        row1.addView(Ui.gap(ctx, 8f))
        row1.addView(Ui.button(ctx, "Đồng bộ").also { it.setOnClickListener { send("SYNC") } },
            Ui.lp(0, WRAP, 1f))
        col.addView(row1, Ui.lp(MATCH, WRAP, 0f, 8, ctx))

        val row2 = Ui.row(ctx)
        row2.addView(Ui.button(ctx, "−90°").also { it.setOnClickListener { send("TURN -90") } },
            Ui.lp(0, WRAP, 1f))
        row2.addView(Ui.gap(ctx, 8f))
        row2.addView(Ui.button(ctx, "+90°").also { it.setOnClickListener { send("TURN 90") } },
            Ui.lp(0, WRAP, 1f))
        row2.addView(Ui.gap(ctx, 8f))
        row2.addView(Ui.button(ctx, "Đặt gốc").also { it.setOnClickListener { send("ZERO") } },
            Ui.lp(0, WRAP, 1f))
        row2.addView(Ui.gap(ctx, 8f))
        row2.addView(Ui.button(ctx, "Nhả").also { it.setOnClickListener { send("OFF") } },
            Ui.lp(0, WRAP, 1f))
        col.addView(row2, Ui.lp(MATCH, WRAP, 0f, 8, ctx))

        col.addView(Ui.label(ctx, "Tham số"))
        for ((key, label) in editable) {
            col.addView(Ui.text(ctx, label, 12f, Ui.DIM), Ui.lp(MATCH, WRAP, 0f, 8, ctx))
            val row = Ui.row(ctx)
            val input = Ui.input(ctx, key, "")
            input.inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                    android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
            fields[key] = input
            row.addView(input, Ui.lp(0, WRAP, 1f))
            row.addView(Ui.gap(ctx, 8f))
            val apply = Ui.button(ctx, "Đặt")
            apply.setOnClickListener {
                val v = input.text.toString().trim()
                if (v.isEmpty()) return@setOnClickListener
                send("SET $key $v")
                /* The app just decided this value, so keep its own copy in step
                 * instead of waiting for a refresh to read it back. */
                saveSetting(key, v)
            }
            row.addView(apply, Ui.lp(WRAP, WRAP))
            col.addView(row, Ui.lp(MATCH, WRAP, 0f, 4, ctx))
        }

        val row3 = Ui.row(ctx)
        row3.addView(Ui.button(ctx, "Lưu vào board", Ui.GREEN)
            .also { it.setOnClickListener { send("SAVE") } }, Ui.lp(0, WRAP, 1f))
        row3.addView(Ui.gap(ctx, 8f))
        row3.addView(Ui.button(ctx, "Về mặc định")
            .also { it.setOnClickListener { send("DEFAULTS"); send("?") } }, Ui.lp(0, WRAP, 1f))
        col.addView(row3, Ui.lp(MATCH, WRAP, 0f, 12, ctx))

        col.addView(Ui.label(ctx, "Firmware"))
        flashButton = Ui.button(ctx, "Cập nhật firmware", Ui.BLUE)
        flashButton.setOnClickListener { confirmFlash() }
        col.addView(flashButton, Ui.lp(MATCH, WRAP, 0f, 4, ctx))

        col.addView(Ui.label(ctx, "Nhật ký"))
        logView = Ui.mono(ctx, "", 11f, Ui.DIM)
        logView.background = Ui.box(ctx, Ui.PANEL, Ui.LINE, 10f)
        logView.setPadding(dp(12f), dp(12f), dp(12f), dp(12f))
        col.addView(logView, Ui.lp(MATCH, WRAP, 0f, 4, ctx))

        connect.setOnClickListener { serial.connect() }
        refresh.setOnClickListener { mirroring = true; send("?") }
        runButton.setOnClickListener { send(if (running) "STOP" else "RUN") }

        /* One driver per process, on the application context: the port stays
         * open after this screen goes away so the capture screen can keep
         * talking to the board, and two activities cannot claim the interface. */
        serial = Ch340.shared ?: Ch340(applicationContext).also { Ch340.shared = it }
        serial.listener = this
        if (serial.isOpen) onState(true, "Đã nối bàn xoay") else serial.connect()
    }

    override fun onDestroy() {
        /* Deliberately not released: only the listener goes, the link stays. */
        if (serial.listener === this) serial.listener = null
        /* shutdown(), not shutdownNow(): a write already streaming has to reach
         * the end of the image, or the board sits half-fed until it times out. */
        io.shutdown()
        super.onDestroy()
    }

    private fun send(cmd: String) {
        if (!serial.isOpen) {
            notConnected()
            return
        }
        if (flashing) {
            stateLine.setTextColor(Ui.RED)
            stateLine.text = "Đang ghi firmware — chờ xong đã"
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
        stateLine.setTextColor(if (connected) Ui.GREEN else Ui.DIM)
        stateLine.text = message
        if (connected) {
            mirroring = true
            serial.send("?")
        }
    }

    override fun onLine(line: String) {
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
                runButton.text = if (running) "Dừng" else "Chạy"
                runButton.setTextColor(if (running) Ui.RED else Ui.AMBER)
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
            append("nấc ").append(summary["shot"] ?: "?")
            append(" · vị trí ").append(summary["pos"] ?: "?")
            if (deg != null) append(" · ").append(deg / 100).append('.')
                .append((deg % 100).toString().padStart(2, '0')).append("°/nấc")
            append("\n")
            append(summary["steps_per_rev"] ?: "?").append(" vi bước/vòng")
            append(" · giữ mô-men ").append(if (summary["enabled"] == "1") "có" else "không")
            summary["next_ms"]?.let { if (running) append(" · nấc sau ").append(it).append(" ms") }
            if (runningSlot >= 0) append(" · slot ").append(slotName(runningSlot).uppercase())
        }
    }

    private fun appendLog(s: String) {
        log.append(s).append('\n')
        if (log.length > 4000) log.delete(0, log.length - 3000)
        logView.text = log
    }

    private fun notConnected() {
        stateLine.setTextColor(Ui.RED)
        stateLine.text = "Chưa nối — bấm Nối OTG"
    }

    private fun prefs() = getSharedPreferences(PREFS, MODE_PRIVATE)

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
            stateLine.setTextColor(Ui.RED)
            stateLine.text = "Chưa đọc được trạng thái board — bấm Đọc lại"
            return
        }
        val target = slotName(nextSlot).uppercase()
        AlertDialog.Builder(this)
            .setTitle("Cập nhật firmware")
            .setMessage("Sẽ tải firmware mới từ máy chủ và ghi vào slot $target, "
                    + "trong khi board đang chạy slot ${slotName(runningSlot).uppercase()}.\n\n"
                    + "Đừng rút cáp OTG và đừng tắt nguồn cho tới khi board báo khởi động "
                    + "lại. Nếu ảnh tải về sai, board từ chối và vẫn chạy bản cũ.")
            .setPositiveButton("Ghi firmware") { _, _ -> startFlash(slotName(nextSlot)) }
            .setNegativeButton("Huỷ", null)
            .show()
    }

    private fun startFlash(slot: String) {
        val base = prefs().getString(KEY_SERVER, "")?.trimEnd('/').orEmpty()
        if (base.isEmpty()) {
            stateLine.setTextColor(Ui.RED)
            stateLine.text = "Chưa có địa chỉ máy chủ — mở màn hình chính trước"
            return
        }
        flashing = true
        flashButton.isEnabled = false
        replies.clear()
        flashStatus("Đang hỏi máy chủ về firmware…")
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

            flashStatus("Đang tải firmware v${manifest.optString("version")} " +
                    "slot ${slot.uppercase()}…")
            val image = Net.bytes("$base/api/fw/download/$slot")

            /* The board cannot tell a truncated download from a good one until
             * it has already taken the motor down and answered "ready", so
             * everything checkable is checked before the cable is involved. */
            if (image.size != size) {
                flashFailed("Ảnh tải về ${image.size} byte, manifest ghi $size — huỷ")
                return
            }
            val sum = CRC32().apply { update(image, 0, image.size) }.value
            if (sum != crc) {
                flashFailed("CRC32 ảnh tải về $sum khác manifest $crc — huỷ")
                return
            }

            logLine("> FLASH $size $crc")
            replies.clear()
            serial.send("FLASH $size $crc")
            val ready = await(5_000) { it == "ready" || it.startsWith("err") }
            when {
                ready == null -> { flashFailed("Board không trả lời 'ready' — huỷ"); return }
                ready != "ready" -> { flashFailed("Board từ chối: $ready"); return }
            }

            val ok = serial.sendBytes(image) { sent, total ->
                flashStatus("Đang gửi %.1f/%.1f KB".format(sent / 1024f, total / 1024f))
            }
            if (!ok) {
                flashFailed("Gửi ảnh qua USB thất bại — board sẽ tự huỷ sau 5 giây")
                return
            }

            flashStatus("Đã gửi xong, chờ board kiểm tra…")
            val verdict = await(10_000) { it.startsWith("ok ") || it.startsWith("err") }
            when {
                verdict == null -> { flashFailed("Board không trả lời sau khi gửi"); return }
                !verdict.startsWith("ok ") -> { flashFailed("Board từ chối: $verdict"); return }
            }

            flashStatus("Board đang ghi flash — đừng rút cáp")
            /* The write ends in a reset, and the new image says hello the same
             * way the old one did. */
            val rebooted = await(20_000) { it.contains("turntable ready") }
            main.post {
                flashing = false
                flashButton.isEnabled = true
                stateLine.setTextColor(if (rebooted != null) Ui.GREEN else Ui.RED)
                stateLine.text = if (rebooted != null)
                    "Đã cập nhật firmware, board khởi động lại xong"
                else
                    "Đã ghi xong nhưng chưa thấy board khởi động lại — bấm Đọc lại"
                mirroring = true
                serial.send("?")
            }
        } catch (e: Exception) {
            flashFailed("Lỗi: ${e.javaClass.simpleName}: ${e.message}")
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

    private fun flashStatus(message: String) {
        main.post {
            stateLine.setTextColor(Ui.DIM)
            stateLine.text = message
        }
    }

    private fun flashFailed(message: String) {
        main.post {
            flashing = false
            flashButton.isEnabled = true
            stateLine.setTextColor(Ui.RED)
            stateLine.text = message
            appendLog("! $message")
        }
    }

    private fun logLine(s: String) {
        main.post { appendLog(s) }
    }

    companion object {
        private const val PREFS = "collmap"
        private const val KEY_SERVER = "server"
        const val KEY_INTERVAL = "interval"
        const val KEY_SHOTS = "shots"
    }
}
