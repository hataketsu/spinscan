package vn.npay.collmap

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import vn.npay.collmap.Ui.MATCH
import vn.npay.collmap.Ui.WRAP
import vn.npay.collmap.Ui.dp
import java.util.concurrent.Executors

/** Pick the rig, pick a project, go shoot. Also the OTA entry point. */
class MainActivity : Activity() {

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private lateinit var serverInput: EditText
    private lateinit var statusView: TextView
    private lateinit var projectList: LinearLayout
    private var installReceiver: BroadcastReceiver? = null
    private var connectedOnce = false

    private fun server(): String =
        serverInput.text.toString().trim().trimEnd('/')

    private fun prefs() = getSharedPreferences(PREFS, MODE_PRIVATE)

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val ctx = this

        val scroll = ScrollView(ctx).apply { setBackgroundColor(Ui.GROUND) }
        val col = Ui.column(ctx).apply { setPadding(dp(18f), dp(28f), dp(18f), dp(18f)) }
        scroll.addView(col)
        setContentView(scroll)

        col.addView(Ui.text(ctx, "Collmap", 26f).apply { letterSpacing = -0.02f })
        col.addView(Ui.text(ctx, "Chụp quanh vật, dựng 3D trên máy chủ", 13f, Ui.DIM))

        col.addView(Ui.label(ctx, "Máy chủ"))
        serverInput = Ui.input(ctx, DEFAULT_SERVER, prefs().getString(KEY_SERVER, DEFAULT_SERVER)!!)
        col.addView(serverInput, Ui.lp(MATCH, WRAP))

        val actions = Ui.row(ctx)
        val connect = Ui.button(ctx, "Kết nối", Ui.BLUE)
        val create = Ui.button(ctx, "Tạo project")
        actions.addView(connect, Ui.lp(0, WRAP, 1f))
        actions.addView(Ui.gap(ctx, 10f))
        actions.addView(create, Ui.lp(0, WRAP, 1f))
        col.addView(actions, Ui.lp(MATCH, WRAP, 0f, 10, ctx))

        statusView = Ui.text(ctx, "Chưa kết nối", 13f, Ui.DIM)
            .apply { setPadding(0, dp(12f), 0, 0) }
        col.addView(statusView)

        col.addView(Ui.label(ctx, "Project"))
        projectList = Ui.column(ctx)
        col.addView(projectList, Ui.lp(MATCH, WRAP))

        col.addView(Ui.label(ctx, "Ứng dụng"))
        col.addView(Ui.mono(ctx,
            "v${Updater.currentName(ctx)} · code ${Updater.currentVersion(ctx)}", 13f, Ui.DIM))
        val update = Ui.button(ctx, "Kiểm tra cập nhật", Ui.AMBER)
        update.setOnClickListener { checkUpdate() }
        col.addView(update, Ui.lp(MATCH, WRAP, 0f, 10, ctx))

        val turntable = Ui.button(ctx, "Bàn xoay (OTG)")
        turntable.setOnClickListener { startActivity(Intent(ctx, TurntableActivity::class.java)) }
        col.addView(turntable, Ui.lp(MATCH, WRAP, 0f, 8, ctx))

        connect.setOnClickListener { refresh() }
        create.setOnClickListener { createProject() }

        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 1)
        }
        registerInstallReceiver()
        refresh()
    }

    override fun onResume() {
        super.onResume()
        if (connectedOnce) refresh()
    }

    override fun onDestroy() {
        installReceiver?.let { unregisterReceiver(it) }
        io.shutdownNow()
        super.onDestroy()
    }

    // ----------------------------------------------------------------- server

    private fun refresh() {
        prefs().edit().putString(KEY_SERVER, server()).apply()
        statusView.setTextColor(Ui.DIM)
        statusView.text = "Đang kết nối ${server()} …"
        val base = server()
        io.execute {
            try {
                val arr = Net.getArray(base, "/api/projects")
                main.post {
                    connectedOnce = true
                    statusView.setTextColor(Ui.DIM)
                    statusView.text = "Đã kết nối · ${arr.length()} project"
                    fillProjects(arr)
                }
            } catch (e: Exception) {
                main.post {
                    statusView.setTextColor(Ui.RED)
                    statusView.text = "Không kết nối được: ${e.message}"
                    projectList.removeAllViews()
                }
            }
        }
    }

    private fun fillProjects(arr: JSONArray) {
        val ctx = this
        projectList.removeAllViews()
        if (arr.length() == 0) {
            projectList.addView(Ui.text(ctx, "Chưa có project nào.", 13f, Ui.DIM))
            return
        }
        for (i in 0 until arr.length()) {
            val p = arr.optJSONObject(i) ?: continue
            val name = p.optString("name")
            val status = p.optString("status")
            val card = Ui.column(ctx).apply {
                background = Ui.box(ctx, Ui.PANEL, Ui.LINE, 12f)
                setPadding(dp(14f), dp(14f), dp(14f), dp(14f))
            }
            val head = Ui.row(ctx)
            head.addView(Ui.text(ctx, name, 16f), Ui.lp(0, WRAP, 1f))
            head.addView(Ui.text(ctx, status, 12f,
                if (status == "running") Ui.AMBER else Ui.DIM))
            card.addView(head)
            card.addView(Ui.text(ctx,
                "${p.optInt("images")} ảnh · ${p.optJSONArray("outputs")?.length() ?: 0} kết quả",
                13f, Ui.DIM))

            val shoot = Ui.button(ctx, "Chụp vào project này", Ui.AMBER)
            shoot.setOnClickListener {
                startActivity(Intent(ctx, CaptureActivity::class.java).apply {
                    putExtra("server", server())
                    putExtra("project", name)
                })
            }
            card.addView(shoot, Ui.lp(MATCH, WRAP, 0f, 10, ctx))
            projectList.addView(card, Ui.lp(MATCH, WRAP, 0f, 8, ctx))
        }
    }

    private fun createProject() {
        val input = Ui.input(this, "ten-project", "")
        val wrap = Ui.column(this).apply {
            setPadding(dp(20f), dp(20f), dp(20f), 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle("Project mới")
            .setView(wrap)
            .setPositiveButton("Tạo") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) return@setPositiveButton
                val base = server()
                io.execute {
                    try {
                        Net.postJson(base, "/api/projects", JSONObject().put("name", name))
                        main.post { refresh() }
                    } catch (e: Exception) {
                        main.post { toast("Lỗi: ${e.message}") }
                    }
                }
            }
            .setNegativeButton("Huỷ", null)
            .show()
    }

    // -------------------------------------------------------------------- OTA

    private fun checkUpdate() {
        val base = server()
        statusView.setTextColor(Ui.DIM)
        statusView.text = "Đang hỏi máy chủ về bản mới…"
        io.execute {
            try {
                val info = Updater.check(base)
                val cur = Updater.currentVersion(this)
                main.post {
                    if (info.versionCode <= cur) {
                        statusView.text = "Đang dùng bản mới nhất (code $cur)"
                        return@post
                    }
                    val msg = buildString {
                        append("Bản ${info.versionName} (code ${info.versionCode})\n")
                        append("${info.size / 1024 / 1024} MB")
                        if (info.notes.isNotEmpty()) append("\n\n${info.notes}")
                    }
                    AlertDialog.Builder(this)
                        .setTitle("Có bản cập nhật")
                        .setMessage(msg)
                        .setPositiveButton("Tải và cài") { _, _ -> startInstall(info) }
                        .setNegativeButton("Để sau", null)
                        .show()
                }
            } catch (e: Exception) {
                main.post {
                    statusView.setTextColor(Ui.RED)
                    statusView.text = "Không lấy được thông tin cập nhật: ${e.message}"
                }
            }
        }
    }

    private fun startInstall(info: Updater.Info) {
        if (Updater.needsUnknownSourcesPermission(this)) {
            AlertDialog.Builder(this)
                .setTitle("Cần quyền cài đặt")
                .setMessage("Android chặn cài ứng dụng ngoài cửa hàng cho tới khi bạn bật "
                        + "quyền cho Collmap. Mở phần cài đặt đó ngay?")
                .setPositiveButton("Mở cài đặt") { _, _ ->
                    startActivity(Updater.unknownSourcesSettings(this))
                }
                .setNegativeButton("Huỷ", null)
                .show()
            return
        }
        io.execute {
            Updater.install(this, info, object : Updater.Listener {
                override fun status(message: String) =
                    main.post { statusView.text = message }.let {}

                override fun progress(done: Long, total: Long) = main.post {
                    statusView.text = "Đang tải ${done / 1024 / 1024}/${total / 1024 / 1024} MB"
                }.let {}

                override fun done(ok: Boolean, message: String) = main.post {
                    statusView.setTextColor(if (ok) Ui.DIM else Ui.RED)
                    statusView.text = message
                }.let {}
            })
        }
    }

    /**
     * The installer answers asynchronously. STATUS_PENDING_USER_ACTION carries the
     * intent that shows the confirm dialog; without launching it the session just
     * sits there and nothing appears to happen.
     */
    private fun registerInstallReceiver() {
        installReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)) {
                    PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                        val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                        confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        confirm?.let { startActivity(it) }
                    }
                    PackageInstaller.STATUS_SUCCESS -> toast("Cập nhật xong")
                    -1 -> Unit
                    else -> {
                        statusView.setTextColor(Ui.RED)
                        statusView.text = "Cài đặt thất bại: " +
                                intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                    }
                }
            }
        }
        registerReceiver(installReceiver, IntentFilter(Updater.ACTION_INSTALL),
            Context.RECEIVER_NOT_EXPORTED)
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    companion object {
        private const val PREFS = "collmap"
        private const val KEY_SERVER = "server"
        private const val DEFAULT_SERVER = "http://thinkcentre:8000"
    }
}
