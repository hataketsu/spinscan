package vn.npay.collmap

import android.Manifest
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.fragment.app.Fragment
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.snackbar.Snackbar

/**
 * The shell, and the only activity in the app.
 *
 * There used to be four: a launcher, a capture screen, a project screen and a
 * turntable screen, each starting the next with startActivity. The bottom bar
 * is the reason they collapsed into one -- a navigation bar that disappears
 * whenever you go somewhere is not a navigation bar -- but the bigger win is
 * the camera. Tabs are switched with hide/show rather than replace, so the
 * capture fragment is never torn down: its camera session, its preview surface
 * and, most importantly, a capture run in progress all survive a trip to the
 * project list and back. Under the old activity stack that trip closed the
 * camera and stopped the run.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var bottomNav: BottomNavigationView
    private var installReceiver: BroadcastReceiver? = null

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContentView(R.layout.activity_main)
        bottomNav = findViewById(R.id.bottom_nav)
        bottomNav.setOnItemSelectedListener { item ->
            show(tagFor(item.itemId))
            true
        }
        val start = state?.getString(KEY_TAB) ?: TAG_PROJECTS
        bottomNav.selectedItemId = itemFor(start)
        show(start)

        requestMissingPermissions()
        // The "đã cập nhật" notification has done its job the moment the app is
        // open again; leaving it in the shade is litter.
        getSystemService(NotificationManager::class.java)
            ?.cancel(UpdatedReceiver.NOTIFICATION_ID)
        registerInstallReceiver()
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        out.putString(KEY_TAB, tagFor(bottomNav.selectedItemId))
    }

    override fun onDestroy() {
        installReceiver?.let { runCatching { unregisterReceiver(it) } }
        super.onDestroy()
    }

    // ------------------------------------------------------------ navigation

    private fun tagFor(itemId: Int) = when (itemId) {
        R.id.nav_capture -> TAG_CAPTURE
        R.id.nav_turntable -> TAG_TURNTABLE
        else -> TAG_PROJECTS
    }

    private fun itemFor(tag: String) = when (tag) {
        TAG_CAPTURE -> R.id.nav_capture
        TAG_TURNTABLE -> R.id.nav_turntable
        else -> R.id.nav_projects
    }

    private fun create(tag: String): Fragment = when (tag) {
        TAG_CAPTURE -> CaptureFragment()
        TAG_TURNTABLE -> TurntableFragment()
        else -> ProjectsFragment()
    }

    /**
     * Hide and show, never replace. Replacing would destroy the capture
     * fragment's view every time the user looked at something else, which is
     * exactly the camera restart this navigation is here to avoid. The cost is
     * that a visited tab stays in memory; that is the trade being made.
     */
    private fun show(tag: String) {
        val fm = supportFragmentManager
        if (fm.isStateSaved) return
        val tx = fm.beginTransaction().setReorderingAllowed(true)
        for (other in TAGS) {
            val f = fm.findFragmentByTag(other) ?: continue
            if (other != tag) tx.hide(f)
        }
        val existing = fm.findFragmentByTag(tag)
        if (existing == null) tx.add(R.id.nav_host, create(tag), tag) else tx.show(existing)
        /* commitNow, not commit: selecting a bottom-bar item calls this and so
         * does onCreate, and with a queued transaction the second call would
         * not yet see the fragment the first one added -- and would add a
         * second copy of it. */
        tx.commitNow()
    }

    fun goToProjects() {
        bottomNav.selectedItemId = R.id.nav_projects
    }

    /**
     * Point the capture screen at a project and go there. The fragment is told
     * rather than recreated, so choosing a different project does not cost a
     * camera restart either.
     */
    fun openCapture(server: String, project: String) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putString(KEY_SERVER, server)
            .putString(KEY_PROJECT, project)
            .apply()
        (supportFragmentManager.findFragmentByTag(TAG_CAPTURE) as? CaptureFragment)
            ?.setTarget(server, project)
        bottomNav.selectedItemId = R.id.nav_capture
    }

    // ---------------------------------------------------------------- chrome

    /** One Snackbar host for the whole app, always clear of the bottom bar. */
    fun snack(message: String) {
        val root = findViewById<CoordinatorLayout>(R.id.root) ?: return
        Snackbar.make(root, message, Snackbar.LENGTH_LONG)
            .setAnchorView(bottomNav)
            .show()
    }

    /**
     * Camera is the obvious one. Notifications are asked for in the same breath
     * because the only notification this app ever posts is the "mở lại" after a
     * self-update, and on Android 13 that needs the runtime grant to appear.
     */
    private fun requestMissingPermissions() {
        val want = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            want += Manifest.permission.CAMERA
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) {
            want += Manifest.permission.POST_NOTIFICATIONS
        }
        if (want.isNotEmpty()) requestPermissions(want.toTypedArray(), 1)
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
                    PackageInstaller.STATUS_SUCCESS -> snack(getString(R.string.update_done))
                    -1 -> Unit
                    else -> snack(getString(R.string.install_failed,
                        intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""))
                }
            }
        }
        registerReceiver(installReceiver, IntentFilter(Updater.ACTION_INSTALL),
            Context.RECEIVER_NOT_EXPORTED)
    }

    companion object {
        const val PREFS = "collmap"
        const val KEY_SERVER = "server"
        const val KEY_PROJECT = "project"
        const val DEFAULT_SERVER = "http://thinkcentre:8000"

        private const val KEY_TAB = "nav_tab"
        private const val TAG_CAPTURE = "capture"
        private const val TAG_PROJECTS = "projects"
        private const val TAG_TURNTABLE = "turntable"
        private val TAGS = listOf(TAG_CAPTURE, TAG_PROJECTS, TAG_TURNTABLE)
    }
}
