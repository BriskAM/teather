package dev.kunnect

import android.app.Notification
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.channels.Channel
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

object BridgeState {
    val status = MutableStateFlow("Not connected")
    val running = MutableStateFlow(false)
    val count = MutableStateFlow(0)
    val lastSync = MutableStateFlow(0L)
    val dirty = AtomicBoolean(true)
    val shareDirty = AtomicBoolean(true)
    val changes = Channel<Unit>(Channel.CONFLATED)
    @Volatile var listener: NotificationBridge? = null
    fun changed() { dirty.set(true); changes.trySend(Unit) }
}

class NotificationBridge : NotificationListenerService() {
    override fun onListenerConnected() { BridgeState.listener = this; BridgeState.changed() }
    override fun onListenerDisconnected() { BridgeState.listener = null; BridgeState.changed() }
    override fun onDestroy() { if (BridgeState.listener === this) BridgeState.listener = null; super.onDestroy() }
    private val labels = mutableMapOf<String, String>()
    override fun onNotificationPosted(sbn: StatusBarNotification) { if (sbn.packageName in Preferences(this).enabledApps && sbn.packageName != packageName) BridgeState.changed() }
    override fun onNotificationRemoved(sbn: StatusBarNotification) { if (sbn.packageName in Preferences(this).enabledApps && sbn.packageName != packageName) BridgeState.changed() }

    private fun selected(sbn: StatusBarNotification, prefs: Preferences): Boolean = sbn.packageName in prefs.enabledApps && sbn.packageName != packageName && sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0
    private fun active(): List<StatusBarNotification> = runCatching { activeNotifications?.toList().orEmpty() }.getOrDefault(emptyList())
    private fun revision(sbn: StatusBarNotification): String {
        val n = sbn.notification
        val parts = JSONArray().put(sbn.key).put(sbn.postTime).put(n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()).put(body(n))
        return MessageDigest.getInstance("SHA-256").digest(parts.toString().toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
    private fun body(n: Notification): String = (n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: n.extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty().take(4000)
    fun snapshot(): JSONObject {
        val prefs = Preferences(this)
        val notifications = active().filter { selected(it, prefs) }.take(256)
        val items = JSONArray()
        var bytes = 0
        var count = 0
        for (sbn in notifications) {
            val label = labels.getOrPut(sbn.packageName) { runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString() }.getOrDefault(sbn.packageName) }
            val item = JSONObject().put("key", sbn.key).put("revision", revision(sbn)).put("app", label.take(128)).put("package", sbn.packageName)
                .put("title", if (prefs.hideContent) label.take(128) else sbn.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty().take(256))
                .put("body", if (prefs.hideContent) "Notification content hidden" else body(sbn.notification))
                .put("dismissible", sbn.isClearable && sbn.packageName !in prefs.localOnlyApps).put("sound", prefs.sound)
            val itemBytes = item.toString().toByteArray(Charsets.UTF_8).size
            if (bytes + itemBytes > 1_900_000) break
            bytes += itemBytes; count++; items.put(item)
        }
        BridgeState.count.value = count
        return JSONObject().put("type", "snapshot").put("items", items)
    }
    fun dismiss(command: JSONObject): JSONObject {
        val key = command.optString("key")
        val prefs = Preferences(this)
        val current = active().firstOrNull { it.key == key && selected(it, prefs) }
        val result = when {
            current == null -> "gone"
            revision(current) != command.optString("revision") -> "stale"
            !current.isClearable || current.packageName in prefs.localOnlyApps -> "protected"
            else -> { cancelNotification(key); "requested" }
        }
        BridgeState.changed()
        return JSONObject().put("type", "dismissResult").put("key", key).put("revision", command.optString("revision")).put("result", result)
    }
}
