package dev.kunnect

import android.app.*
import android.content.Intent
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicReference

class ConnectionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val socket = AtomicReference<Socket?>(null)
    private var job: Job? = null
    private val files by lazy { FileShareServer(this) }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") { stopSelf(); return START_NOT_STICKY }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("connection", "Device connection", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, ConnectionService::class.java).setAction("stop"), PendingIntent.FLAG_IMMUTABLE)
        startForeground(1, Notification.Builder(this, "connection").setSmallIcon(dev.kunnect.R.drawable.ic_teather).setContentTitle("Teather is running").setContentText("Keeping your Mac in sync over Tailscale").setContentIntent(open).setOngoing(true).addAction(Notification.Action.Builder(null, "Disconnect", stop).build()).build())
        BridgeState.running.value = true
        scope.launch { files.reconfigure(); BridgeState.changed() }
        if (job?.isActive != true) job = scope.launch { connectLoop() }
        return START_STICKY
    }
    private suspend fun connectLoop() {
        var delayMs = 1000L
        while (currentCoroutineContext().isActive) {
            try {
                val prefs = Preferences(this)
                require((TailnetAddress.valid(prefs.host) || (BuildConfig.DEBUG && prefs.host == "127.0.0.1"))) { "Enter your Mac’s Tailscale IPv4 address" }
                require(prefs.token.matches(Regex("[a-f0-9]{64}"))) { "Copy the pairing key from your Mac" }
                BridgeState.status.value = "Connecting to your Mac…"
                val connection = Socket()
                socket.set(connection)
                connection.connect(InetSocketAddress(prefs.host, 41691), 7000)
                connection.soTimeout = 300000
                connection.tcpNoDelay = true
                val output = connection.getOutputStream()
                fun send(value: JSONObject) {
                    val bytes = (value.toString() + "\n").toByteArray(Charsets.UTF_8)
                    require(bytes.size <= 2_097_152) { "Too many notifications to sync" }
                    synchronized(output) { output.write(bytes); output.flush() }
                }
                send(JSONObject().put("type", "hello").put("version", 1).put("token", prefs.token).put("deviceId", prefs.deviceId).put("name", Build.MODEL.take(128)))
                val input = connection.getInputStream().buffered()
                val line = ByteArrayOutputStream()
                // Authentication is read before the event sender starts; there is no polling loop.
                connection.soTimeout = 12000
                val ready = readFrame(input, line)
                check(ready.optString("type") == "ready") { ready.optString("message", "Pairing failed") }
                val features = ready.optJSONArray("features")
                val supportsFiles = features != null && (0 until features.length()).any { features.optString(it) == "fileShare" }
                val heartbeatMillis = if (supportsFiles) 120000L else 30000L
                connection.soTimeout = if (supportsFiles) 300000 else 75000
                delayMs = 1000
                BridgeState.status.value = "Connected"
                BridgeState.changed()
                coroutineScope {
                    val sender = launch {
                        var firstSnapshot = true
                        var lastShare = ""
                        var previous = emptyMap<String, String>()
                        while (isActive) {
                            val changed = withTimeoutOrNull(heartbeatMillis) { BridgeState.changes.receive(); true } ?: false
                            if (changed) delay(150) // Fold a burst of notification updates into one batch.
                            if (BridgeState.shareDirty.getAndSet(false)) files.reconfigure()
                            val share = files.info()
                            if (supportsFiles && share.toString() != lastShare) { send(share); lastShare = share.toString() }
                            if (BridgeState.dirty.getAndSet(false)) {
                                val snapshot = withContext(Dispatchers.Main) { BridgeState.listener?.snapshot() }
                                if (snapshot != null) {
                                    val items = snapshot.getJSONArray("items")
                                    val current = (0 until items.length()).associate { i -> items.getJSONObject(i).let { it.getString("key") to it.toString() } }
                                    if (firstSnapshot) { send(snapshot); firstSnapshot = false }
                                    else {
                                        val updates = org.json.JSONArray()
                                        current.forEach { (key, value) -> if (previous[key] != value) updates.put(JSONObject(value)) }
                                        val removed = org.json.JSONArray(previous.keys.filter { it !in current })
                                        if (updates.length() > 0 || removed.length() > 0) send(JSONObject().put("type", "delta").put("items", updates).put("removed", removed))
                                    }
                                    previous = current
                                    BridgeState.lastSync.value = System.currentTimeMillis()
                                } else send(JSONObject().put("type", "unavailable"))
                            }
                            send(JSONObject().put("type", "ping"))
                        }
                    }
                    sender.invokeOnCompletion { runCatching { connection.close() } }
                    try {
                        while (isActive) {
                            val event = readFrame(input, line)
                            if (event.optString("type") == "dismiss") {
                                val result = withContext(Dispatchers.Main) { BridgeState.listener?.dismiss(event) }
                                send(result ?: JSONObject().put("type", "dismissResult").put("key", event.optString("key")).put("revision", event.optString("revision")).put("result", "unavailable"))
                            }
                        }
                    } finally { sender.cancel(); runCatching { connection.close() } }
                }
            } catch (e: Exception) {
                if (!currentCoroutineContext().isActive) break
                BridgeState.status.value = when (e) {
                    is java.net.ConnectException -> "Mac unreachable · retrying"
                    is java.net.NoRouteToHostException -> "Check Tailscale · retrying"
                    else -> (e.message ?: "Connection interrupted").take(140)
                }
            } finally { runCatching { socket.getAndSet(null)?.close() } }
            delay(delayMs); delayMs = (delayMs * 2).coerceAtMost(120000)
        }
    }
    private fun readFrame(input: java.io.InputStream, line: ByteArrayOutputStream): JSONObject {
        while (true) {
            val byte = input.read()
            check(byte >= 0) { "Mac disconnected" }
            if (byte == 10) {
                val event = JSONObject(line.toString("UTF-8")); line.reset(); return event
            }
            check(line.size() < 65536) { "Invalid command size" }; line.write(byte)
        }
    }
    override fun onDestroy() { scope.cancel(); files.close(); runCatching { socket.getAndSet(null)?.close() }; BridgeState.running.value = false; BridgeState.status.value = "Not connected"; super.onDestroy() }
}
