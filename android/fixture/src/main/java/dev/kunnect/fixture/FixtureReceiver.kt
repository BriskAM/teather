package dev.kunnect.fixture
import android.app.*
import android.content.*
class FixtureReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.getBooleanExtra("grantFolder", false)) {
            context.grantUriPermission("dev.kunnect", android.provider.DocumentsContract.buildTreeDocumentUri("dev.kunnect.fixture.documents", "root"), Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            return
        }
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("fixture", "Test messages", NotificationManager.IMPORTANCE_DEFAULT))
        val id = intent.getIntExtra("id", 42)
        if (intent.getBooleanExtra("cancel", false)) { manager.cancel(id); return }
        manager.notify(id, Notification.Builder(context, "fixture").setSmallIcon(android.R.drawable.ic_dialog_email).setContentTitle("Test sender").setContentText(intent.getStringExtra("body") ?: "Hello from Android").setOngoing(intent.getBooleanExtra("ongoing", false)).build())
    }
}
