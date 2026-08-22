package vn.npay.collmap

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbManager
import android.os.Handler
import android.os.Looper

/**
 * Just enough CH340/CH341 to talk to the turntable board over OTG.
 *
 * The board's USB port is a CH340 wired to the STM32's USART1, so from the
 * phone's side this is a plain USB device with one bulk-in and one bulk-out
 * endpoint plus a pile of vendor control requests to set the line up. There is
 * no Android USB-serial API to lean on and no third-party jar in this build, so
 * the init sequence is written out.
 *
 * Only what the turntable needs: 115200 8N1, line-oriented text, no flow
 * control, no modem status.
 */
class Ch340(private val ctx: Context) {

    interface Listener {
        fun onLine(line: String)
        fun onState(connected: Boolean, message: String)
    }

    var listener: Listener? = null

    /**
     * A second subscriber, delivered the same lines as [listener].
     *
     * The capture screen has to hear the board answer its STEP, but the port
     * belongs to whoever opened it and the turntable screen holds [listener]
     * for as long as it lives. Taking that over would leave that screen deaf
     * the next time the user walks back into it.
     */
    var extraListener: Listener? = null

    private val main = Handler(Looper.getMainLooper())

    private var connection: UsbDeviceConnection? = null
    private var device: UsbDevice? = null
    private var epIn: UsbEndpoint? = null
    private var epOut: UsbEndpoint? = null
    private var reader: Thread? = null
    @Volatile private var running = false
    private val partial = StringBuilder()

    private var permissionReceiver: BroadcastReceiver? = null

    val isOpen: Boolean get() = connection != null

    // ------------------------------------------------------------------ find

    private fun manager() = ctx.getSystemService(Context.USB_SERVICE) as UsbManager

    fun findBoard(): UsbDevice? =
        manager().deviceList.values.firstOrNull { d ->
            d.vendorId == VENDOR_QINHENG && d.productId in PRODUCTS
        }

    /**
     * Opens the board, asking for USB permission first if Android has not
     * granted it yet. The permission dialog is asynchronous, so the result
     * always comes back through [Listener.onState].
     */
    fun connect() {
        val dev = findBoard()
        if (dev == null) {
            report(false, "Không thấy board CH340 — kiểm tra cáp OTG")
            return
        }
        val mgr = manager()
        if (mgr.hasPermission(dev)) {
            open(dev)
            return
        }
        registerPermissionReceiver()
        val intent = Intent(ACTION_PERMISSION).setPackage(ctx.packageName)
        mgr.requestPermission(dev, PendingIntent.getBroadcast(
            ctx, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE))
        report(false, "Đang xin quyền dùng cổng USB…")
    }

    private fun registerPermissionReceiver() {
        if (permissionReceiver != null) return
        permissionReceiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                if (i.action != ACTION_PERMISSION) return
                val granted = i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                val dev = findBoard()
                if (granted && dev != null) open(dev) else report(false, "Bị từ chối quyền USB")
            }
        }
        ctx.registerReceiver(permissionReceiver, IntentFilter(ACTION_PERMISSION),
            Context.RECEIVER_NOT_EXPORTED)
    }

    // ------------------------------------------------------------------ open

    private fun open(dev: UsbDevice) {
        close()
        val mgr = manager()
        val iface = (0 until dev.interfaceCount).map { dev.getInterface(it) }
            .firstOrNull { it.endpointCount >= 2 } ?: run {
                report(false, "Thiết bị không có endpoint phù hợp"); return
            }
        val conn = mgr.openDevice(dev) ?: run { report(false, "Mở USB thất bại"); return }
        if (!conn.claimInterface(iface, true)) {
            conn.close(); report(false, "Không giành được interface"); return
        }
        for (i in 0 until iface.endpointCount) {
            val ep = iface.getEndpoint(i)
            if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
            if (ep.direction == UsbConstants.USB_DIR_IN) epIn = ep else epOut = ep
        }
        if (epIn == null || epOut == null) {
            conn.close(); report(false, "Thiếu endpoint bulk"); return
        }

        connection = conn
        device = dev
        if (!initChip(conn)) {
            close(); report(false, "CH340 không nhận lệnh khởi tạo"); return
        }
        startReader()
        report(true, "Đã nối bàn xoay (${dev.deviceName})")
    }

    fun close() {
        running = false
        reader?.interrupt()
        reader = null
        connection?.close()
        connection = null
        device = null
        epIn = null
        epOut = null
        partial.setLength(0)
    }

    fun release() {
        close()
        permissionReceiver?.let { runCatching { ctx.unregisterReceiver(it) } }
        permissionReceiver = null
    }

    // ------------------------------------------------- vendor control traffic

    private fun ctrlOut(conn: UsbDeviceConnection, req: Int, value: Int, index: Int): Boolean =
        conn.controlTransfer(0x40, req, value, index, null, 0, TIMEOUT) >= 0

    private fun ctrlIn(conn: UsbDeviceConnection, req: Int, value: Int, index: Int,
                       buf: ByteArray): Boolean =
        conn.controlTransfer(0xC0, req, value, index, buf, buf.size, TIMEOUT) >= 0

    /**
     * The CH341 baud generator is a prescaler (divisor) plus a 16-bit reload
     * (factor). Shift the factor down until it fits the register, spending one
     * prescaler step each time.
     */
    private fun setBaud(conn: UsbDeviceConnection, baud: Int): Boolean {
        var factor = (BAUD_BASE / baud).toLong()
        var divisor = 3L
        while (factor > 0xFFF0 && divisor > 0) {
            factor = factor shr 3
            divisor--
        }
        if (factor > 0xFFF0) return false
        factor = 0x10000L - factor
        divisor = divisor or 0x0080L          /* bit 7: use the prescaler */
        val v1 = ((factor and 0xFF00L) or divisor).toInt()
        val v2 = (factor and 0xFFL).toInt()
        return ctrlOut(conn, 0x9A, 0x1312, v1) && ctrlOut(conn, 0x9A, 0x0F2C, v2)
    }

    private fun initChip(conn: UsbDeviceConnection): Boolean {
        val buf = ByteArray(2)
        if (!ctrlIn(conn, 0x5F, 0, 0, buf)) return false      /* chip version */
        if (!ctrlOut(conn, 0xA1, 0, 0)) return false          /* serial mode on */
        if (!setBaud(conn, BAUD)) return false
        if (!ctrlOut(conn, 0x9A, 0x2518, 0x0050)) return false /* 8 data, 1 stop, no parity */
        if (!ctrlIn(conn, 0x95, 0x0706, 0, buf)) return false  /* status */
        if (!ctrlOut(conn, 0xA1, 0x501F, 0xD90A)) return false
        if (!setBaud(conn, BAUD)) return false
        /* Leave DTR and RTS deasserted. On this board they are tied to the MCU's
         * reset and boot pins, and asserting them on open reboots the firmware
         * mid-session -- which is exactly what a desktop terminal does to it. */
        return ctrlOut(conn, 0xA4, 0xFF, 0)
    }

    // ---------------------------------------------------------------- traffic

    fun send(line: String) {
        val conn = connection ?: return
        val out = epOut ?: return
        val bytes = (line + "\n").toByteArray()
        Thread {
            val n = conn.bulkTransfer(out, bytes, bytes.size, TIMEOUT)
            if (n < 0) report(false, "Gửi lệnh thất bại")
        }.start()
    }

    /**
     * Streams raw bytes out of the bulk endpoint on the calling thread.
     *
     * Unlike [send] this does not spawn its own: a firmware image is only
     * useful if the sender knows when the last byte landed and whether every
     * chunk before it went out, and a fire-and-forget write can say neither.
     * Chunks stay small because the far end is a 115200 UART draining into a
     * ring buffer, not a device that can swallow a whole image at once.
     */
    fun sendBytes(data: ByteArray, chunk: Int = 256,
                  onProgress: ((Int, Int) -> Unit)? = null): Boolean {
        val conn = connection ?: return false
        val out = epOut ?: return false
        val buf = ByteArray(chunk)
        var sent = 0
        while (sent < data.size) {
            val n = minOf(chunk, data.size - sent)
            System.arraycopy(data, sent, buf, 0, n)
            if (conn.bulkTransfer(out, buf, n, TIMEOUT) < 0) return false
            sent += n
            onProgress?.invoke(sent, data.size)
        }
        return true
    }

    private fun startReader() {
        running = true
        reader = Thread {
            val conn = connection ?: return@Thread
            val ep = epIn ?: return@Thread
            val buf = ByteArray(ep.maxPacketSize.coerceAtLeast(64))
            while (running) {
                val started = System.currentTimeMillis()
                val n = try {
                    conn.bulkTransfer(ep, buf, buf.size, 300)
                } catch (_: Exception) {
                    -1
                }
                if (n > 0) {
                    feed(String(buf, 0, n))
                    continue
                }
                if (!running) break
                /* An idle read fails only when its timeout expires; one that
                 * fails instantly means the cable is gone, and without this the
                 * loop would spin hot for as long as the driver is kept alive. */
                if (System.currentTimeMillis() - started < 50 && findBoard() == null) {
                    main.post { close() }
                    report(false, "Mất bàn xoay — kiểm tra cáp OTG")
                    break
                }
            }
        }.also { it.start() }
    }

    /** Reassembles bulk chunks into whole lines; a reply can split across URBs. */
    private fun feed(chunk: String) {
        partial.append(chunk)
        while (true) {
            val i = partial.indexOfFirst { it == '\n' || it == '\r' }
            if (i < 0) break
            val line = partial.substring(0, i).trim()
            partial.delete(0, i + 1)
            if (line.isNotEmpty()) main.post {
                listener?.onLine(line)
                extraListener?.onLine(line)
            }
        }
        if (partial.length > 4096) partial.setLength(0)   // runaway guard
    }

    private fun report(connected: Boolean, msg: String) {
        main.post {
            listener?.onState(connected, msg)
            extraListener?.onState(connected, msg)
        }
    }

    companion object {
        /**
         * The one driver in the process, so the capture screen can push a
         * setting to the board without opening the port a second time -- two
         * activities cannot claim the same USB interface.
         */
        @Volatile var shared: Ch340? = null

        const val ACTION_PERMISSION = "vn.npay.collmap.USB_PERMISSION"
        private const val VENDOR_QINHENG = 0x1A86
        private val PRODUCTS = setOf(0x7523, 0x5523, 0x7522, 0x5512)
        private const val BAUD = 115200
        /* CH341's internal reference for the baud generator. */
        private const val BAUD_BASE = 1_532_620_800L
        private const val TIMEOUT = 1500
    }
}
