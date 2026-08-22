package vn.npay.collmap

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build

/**
 * Brings the app back after it has updated itself.
 *
 * PackageInstaller kills the process to replace it and the platform does not
 * start it again, so from the rig it looks like the app simply quit. The one
 * thing that still arrives afterwards is MY_PACKAGE_REPLACED, delivered only to
 * the package that was replaced.
 *
 * Two attempts here, and only one of them is a real mechanism: since Android 10
 * an app cannot reliably start an activity from the background, so the
 * startActivity below is a best effort the system is free to ignore without
 * saying so. The notification is the part that is guaranteed to work, which is
 * why it is posted whether or not the launch appeared to go through.
 */
class UpdatedReceiver : BroadcastReceiver() {

    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val open = Intent(ctx, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        try {
            ctx.startActivity(open)
        } catch (_: Exception) {
        }
        postReopen(ctx, open)
    }

    private fun postReopen(ctx: Context, open: Intent) {
        /* Android 13 needs the runtime grant before anything appears. If the
         * user refused it that is an answer, not a problem to work around. */
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) return
        val manager = ctx.getSystemService(NotificationManager::class.java) ?: return
        // Low importance: this is a way back in, not something to interrupt for.
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Cập nhật", NotificationManager.IMPORTANCE_LOW))
        val tap = PendingIntent.getActivity(ctx, 0, open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        manager.notify(NOTIFICATION_ID, Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Collmap đã cập nhật")
            .setContentText("Chạm để mở lại")
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build())
    }

    companion object {
        private const val CHANNEL = "update"
        const val NOTIFICATION_ID = 4201
    }
}
