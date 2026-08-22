package vn.npay.collmap

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

/** Pick the rig, pick a project, go shoot. Also the OTA entry point. */
class ProjectListFragment : Fragment(R.layout.fragment_project_list) {

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private lateinit var serverInput: TextInputEditText
    private lateinit var statusView: TextView
    private lateinit var busy: CircularProgressIndicator
    private lateinit var projectList: LinearLayout
    private lateinit var updateProgress: LinearProgressIndicator
    private var connectedOnce = false

    private fun server(): String = serverInput.text.toString().trim().trimEnd('/')

    private fun prefs() =
        requireContext().getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)

    override fun onViewCreated(view: View, state: Bundle?) {
        serverInput = view.findViewById(R.id.server)
        statusView = view.findViewById(R.id.status)
        busy = view.findViewById(R.id.busy)
        projectList = view.findViewById(R.id.project_list)
        updateProgress = view.findViewById(R.id.update_progress)

        view.findViewById<TextInputLayout>(R.id.server_layout).hint =
            MainActivity.DEFAULT_SERVER
        serverInput.setText(
            prefs().getString(MainActivity.KEY_SERVER, MainActivity.DEFAULT_SERVER))

        view.findViewById<TextView>(R.id.app_version).text = getString(
            R.string.app_version_fmt,
            Updater.currentName(requireContext()), Updater.currentVersion(requireContext()))

        view.findViewById<MaterialButton>(R.id.connect).setOnClickListener { refresh() }
        view.findViewById<MaterialButton>(R.id.create).setOnClickListener { createProject() }
        view.findViewById<MaterialButton>(R.id.check_update).setOnClickListener { checkUpdate() }
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refreshIfConnected()
    }

    /**
     * Coming back to this tab re-reads the list, the way returning to the old
     * launcher activity did. It needs saying out loud now: the bottom bar hides
     * and shows fragments instead of stopping them, so onResume no longer fires
     * on the way back in.
     */
    fun refreshIfConnected() {
        if (connectedOnce) refresh()
    }

    override fun onDestroy() {
        io.shutdownNow()
        super.onDestroy()
    }

    // ----------------------------------------------------------------- server

    /* Named setStatus rather than status: the Updater.Listener below has a
     * status() of its own, and inside that object an unqualified status(...)
     * would call itself. */
    private fun setStatus(message: String, error: Boolean = false) {
        if (!isAdded) return
        statusView.text = message
        statusView.setTextColor(attrColor(
            if (error) com.google.android.material.R.attr.colorError
            else com.google.android.material.R.attr.colorOnSurfaceVariant))
    }

    private fun attrColor(attr: Int): Int = with(Ui) { requireContext().themeColor(attr) }

    private fun refresh() {
        prefs().edit().putString(MainActivity.KEY_SERVER, server()).apply()
        val base = server()
        busy.visibility = View.VISIBLE
        setStatus(getString(R.string.connecting_to, base))
        io.execute {
            try {
                val arr = Net.getArray(base, "/api/projects")
                main.post {
                    if (!isAdded) return@post
                    connectedOnce = true
                    busy.visibility = View.GONE
                    setStatus(getString(R.string.connected_count, arr.length()))
                    fillProjects(arr)
                }
            } catch (e: Exception) {
                main.post {
                    if (!isAdded) return@post
                    busy.visibility = View.GONE
                    setStatus(getString(R.string.connect_failed, e.message ?: ""), error = true)
                    projectList.removeAllViews()
                }
            }
        }
    }

    private fun fillProjects(arr: JSONArray) {
        val inflater = LayoutInflater.from(requireContext())
        projectList.removeAllViews()
        if (arr.length() == 0) {
            val empty = TextView(requireContext()).apply {
                text = getString(R.string.no_projects)
                setTextColor(attrColor(
                    com.google.android.material.R.attr.colorOnSurfaceVariant))
            }
            projectList.addView(empty)
            return
        }
        for (i in 0 until arr.length()) {
            val p = arr.optJSONObject(i) ?: continue
            val name = p.optString("name")
            val runState = p.optString("status")
            val card = inflater.inflate(R.layout.item_project, projectList, false)
            card.findViewById<TextView>(R.id.name).text = name
            card.findViewById<TextView>(R.id.status).apply {
                text = runState
                setTextColor(attrColor(
                    if (runState == "running") com.google.android.material.R.attr.colorPrimary
                    else com.google.android.material.R.attr.colorOnSurfaceVariant))
            }
            card.findViewById<TextView>(R.id.summary).text = getString(
                R.string.project_summary,
                p.optInt("images"), p.optJSONArray("outputs")?.length() ?: 0)

            // Two ways in: shoot into it, or look at what is already there --
            // progress, photos, log and results, without walking to a laptop.
            val open = { (parentFragment as? ProjectsFragment)?.openDetail(server(), name) }
            card.setOnClickListener { open() }
            card.findViewById<MaterialButton>(R.id.open).setOnClickListener { open() }
            card.findViewById<MaterialButton>(R.id.shoot).setOnClickListener {
                (activity as? MainActivity)?.openCapture(server(), name)
            }
            projectList.addView(card)
        }
    }

    private fun createProject() {
        val (dialogView, input) = inputView(getString(R.string.project_name_hint), "")
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.new_project)
            .setView(dialogView)
            .setPositiveButton(R.string.create) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) return@setPositiveButton
                val base = server()
                io.execute {
                    try {
                        Net.postJson(base, "/api/projects", JSONObject().put("name", name))
                        main.post { refresh() }
                    } catch (e: Exception) {
                        main.post { snack(R.string.error_fmt, e.message ?: "") }
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun inputView(hint: String, value: String): Pair<View, TextInputEditText> {
        val view = layoutInflater.inflate(R.layout.dialog_input, null)
        view.findViewById<TextInputLayout>(R.id.input_layout).hint = hint
        val input = view.findViewById<TextInputEditText>(R.id.input)
        input.setText(value)
        return view to input
    }

    // -------------------------------------------------------------------- OTA

    private fun checkUpdate() {
        val base = server()
        setStatus(getString(R.string.asking_for_update))
        io.execute {
            try {
                val info = Updater.check(base)
                val cur = Updater.currentVersion(requireContext())
                main.post {
                    if (!isAdded) return@post
                    if (info.versionCode <= cur) {
                        setStatus(getString(R.string.already_latest, cur))
                        return@post
                    }
                    val body = StringBuilder(getString(R.string.update_body,
                        info.versionName, info.versionCode, info.size / 1024 / 1024))
                    if (info.notes.isNotEmpty()) body.append("\n\n").append(info.notes)
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle(R.string.update_available)
                        .setMessage(body.toString())
                        .setPositiveButton(R.string.download_and_install) { _, _ ->
                            startInstall(info)
                        }
                        .setNegativeButton(R.string.later, null)
                        .show()
                }
            } catch (e: Exception) {
                main.post {
                    if (isAdded) setStatus(
                        getString(R.string.update_check_failed, e.message ?: ""), error = true)
                }
            }
        }
    }

    private fun startInstall(info: Updater.Info) {
        val ctx = requireContext()
        if (Updater.needsUnknownSourcesPermission(ctx)) {
            MaterialAlertDialogBuilder(ctx)
                .setTitle(R.string.need_install_permission)
                .setMessage(R.string.need_install_permission_body)
                .setPositiveButton(R.string.open_settings) { _, _ ->
                    startActivity(Updater.unknownSourcesSettings(ctx))
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
            return
        }
        val app = ctx.applicationContext
        updateProgress.visibility = View.VISIBLE
        updateProgress.isIndeterminate = true
        io.execute {
            Updater.install(app, info, object : Updater.Listener {
                override fun status(message: String) {
                    main.post { setStatus(message) }
                }

                override fun progress(done: Long, total: Long) {
                    main.post {
                        if (!isAdded) return@post
                        if (total > 0) {
                            updateProgress.isIndeterminate = false
                            updateProgress.max = 1000
                            updateProgress.setProgressCompat(
                                (done * 1000 / total).toInt(), true)
                        }
                        setStatus(getString(R.string.downloading_mb,
                            done / 1024 / 1024, total / 1024 / 1024))
                    }
                }

                override fun done(ok: Boolean, message: String) {
                    main.post {
                        if (!isAdded) return@post
                        updateProgress.visibility = View.GONE
                        setStatus(message, error = !ok)
                    }
                }
            })
        }
    }
}
