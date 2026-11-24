package io.keyflow.remotekeyboard

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.TextView

/** Debug-only local trigger for observing Android activity start and notification behavior. */
class ActivationProbeActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private val probe = Runnable {
        val target = Intent(this, MainActivity::class.java)
        startActivity(target)
        postFallback(target)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply { setText(R.string.probe_wait) })
        handler.postDelayed(probe, 20_000)
    }

    override fun onDestroy() {
        handler.removeCallbacks(probe)
        super.onDestroy()
    }

    private fun postFallback(target: Intent) {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return

        val manager = getSystemService(NotificationManager::class.java)
        val channel = "activation-probe"
        manager.createNotificationChannel(
            NotificationChannel(channel, getString(R.string.probe_channel), NotificationManager.IMPORTANCE_DEFAULT)
        )
        val open = PendingIntent.getActivity(this, 0, target, PendingIntent.FLAG_IMMUTABLE)
        manager.notify(
            1,
            Notification.Builder(this, channel)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(getString(R.string.probe_notification))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
        )
    }
}
