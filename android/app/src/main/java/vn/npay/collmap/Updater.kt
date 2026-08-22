package vn.npay.collmap

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * Over-the-air update, no cable.
 *
 * The rig serves its own APK plus a small manifest beside it. The app compares
 * versionCode, downloads, checks the SHA-256 the server declared, then hands the
 * file to PackageInstaller. The system still shows its own confirmation dialog --
 * this is self-distribution, not silent installation.
 *
 * A PackageInstaller session is used rather than ACTION_VIEW on a file URI:
 * since Android 7 that route needs a FileProvider, and on Android 12+ it is
 * unreliable for self-updates. Streaming into a session sidesteps both.
 */
object Updater {
    const val ACTION_INSTALL = "vn.npay.collmap.INSTALL_RESULT"

    data class Info(
        val versionCode: Int,
        val versionName: String,
        val url: String,
        val sha256: String,
        val size: Long,
        val notes: String,
    )

    interface Listener {
        fun status(message: String)
        fun progress(done: Long, total: Long)
        fun done(ok: Boolean, message: String)
    }

    fun currentVersion(ctx: Context): Int = try {
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode.toInt() else @Suppress("DEPRECATION") pi.versionCode
    } catch (_: Exception) {
        0
    }

    fun currentName(ctx: Context): String = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"
    } catch (_: Exception) {
        "?"
    }

    fun check(base: String): Info {
        val o = Net.getObject(base, "/api/app/latest")
        var url = o.optString("url")
        if (url.startsWith("/")) url = base + url
        return Info(
            versionCode = o.optInt("version_code"),
            versionName = o.optString("version_name"),
            url = url,
            sha256 = o.optString("sha256"),
            size = o.optLong("size"),
            notes = o.optString("notes"),
        )
    }

    /** Download, verify, install. Call from a background thread. */
    fun install(ctx: Context, info: Info, cb: Listener) {
        try {
            val apk = File(ctx.cacheDir, "update.apk")
            if (apk.exists()) apk.delete()

            cb.status(ctx.getString(R.string.updater_downloading, info.versionName))
            FileOutputStream(apk).use { out ->
                Net.download(info.url, out) { done, total ->
                    cb.progress(done, if (total > 0) total else info.size)
                }
            }

            if (info.sha256.isNotEmpty()) {
                cb.status(ctx.getString(R.string.updater_checking_sha))
                if (!sha256(apk).equals(info.sha256, ignoreCase = true)) {
                    apk.delete()
                    cb.done(false, ctx.getString(R.string.updater_sha_mismatch))
                    return
                }
            }

            cb.status(ctx.getString(R.string.updater_handoff))
            commit(ctx, apk)
            cb.done(true, ctx.getString(R.string.updater_confirm))
        } catch (e: Exception) {
            cb.done(false, "${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun commit(ctx: Context, apk: File) {
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(
            PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(ctx.packageName)
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("collmap", 0, apk.length()).use { out ->
                FileInputStream(apk).use { it.copyTo(out) }
                session.fsync(out)
            }
            val intent = Intent(ACTION_INSTALL).setPackage(ctx.packageName)
            // MUTABLE: the installer fills in its own extras on the way back.
            val sender = PendingIntent.getBroadcast(
                ctx, sessionId, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
            session.commit(sender.intentSender)
        }
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(f).use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** True when the device will refuse the install until the user allows it. */
    fun needsUnknownSourcesPermission(ctx: Context): Boolean =
        Build.VERSION.SDK_INT >= 26 && !ctx.packageManager.canRequestPackageInstalls()

    fun unknownSourcesSettings(ctx: Context): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${ctx.packageName}"))
}
