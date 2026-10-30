package dev.kunnect.fixture
import android.app.*
import android.content.*
class FixtureReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("fixture", "Test messages", NotificationManager.IMPORTANCE_DEFAULT))
        val id = intent.getIntExtra("id", 42)
        if (intent.getBooleanExtra("cancel", false)) { manager.cancel(id); return }
        manager.notify(id, Notification.Builder(context, "fixture").setSmallIcon(android.R.drawable.ic_dialog_email).setContentTitle("Test sender").setContentText(intent.getStringExtra("body") ?: "Hello from Android").setOngoing(intent.getBooleanExtra("ongoing", false)).build())
    }
}
