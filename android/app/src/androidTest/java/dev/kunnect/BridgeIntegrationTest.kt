package dev.kunnect

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import java.net.ServerSocket
import java.net.Socket
import java.io.BufferedReader
import java.io.BufferedWriter

@RunWith(AndroidJUnit4::class)
class BridgeIntegrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private fun fixture(body: String = "Hello from Android", cancel: Boolean = false, ongoing: Boolean = false) {
        context.sendBroadcast(Intent("dev.kunnect.fixture.POST").setComponent(android.content.ComponentName("dev.kunnect.fixture", "dev.kunnect.fixture.FixtureReceiver")).putExtra("body", body).putExtra("cancel", cancel).putExtra("ongoing", ongoing))
    }
    private fun waitUntil(test: () -> Boolean) { val end = System.currentTimeMillis() + 15000; while (!test()) { check(System.currentTimeMillis() < end) { "Condition timed out" }; Thread.sleep(100) } }
    private fun readUntil(reader: BufferedReader, type: String, condition: (JSONObject) -> Boolean = { true }): JSONObject {
        val deadline = System.currentTimeMillis() + 15000
        while (System.currentTimeMillis() < deadline) {
            val event = JSONObject(reader.readLine() ?: error("Disconnected"))
            if (event.optString("type") == "ping") continue
            if (event.optString("type") == type && condition(event)) return event
        }
        error("Missing event $type")
    }
    private fun send(writer: BufferedWriter, event: JSONObject) { writer.write(event.toString() + "\n"); writer.flush() }
    @Test fun notificationsPairUpdateDismissReconnectAndFilter() {
        instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        waitUntil { BridgeState.listener != null }
        val prefs = Preferences(context)
        val oldHost = prefs.host; val oldToken = prefs.token; val oldApps = prefs.enabledApps
        val key = "a".repeat(64)
        prefs.host = "127.0.0.1"; prefs.token = key; prefs.enabledApps = setOf("dev.kunnect.fixture")
        prefs.hideContent = false; prefs.localOnlyApps = emptySet()
        try {
            ServerSocket(41691, 4, java.net.InetAddress.getByName("127.0.0.1")).use { server ->
                server.soTimeout = 15000
                context.startForegroundService(Intent(context, ConnectionService::class.java))
                var connection = server.accept().apply { soTimeout = 15000 }
                var reader = connection.getInputStream().bufferedReader(); var writer = connection.getOutputStream().bufferedWriter()
                val hello = readUntil(reader, "hello"); assertEquals(key, hello.getString("token")); assertEquals(1, hello.getInt("version"))
                send(writer, JSONObject().put("type", "ready"))
                readUntil(reader, "snapshot")
                fixture("First message")
                var event = readUntil(reader, "delta") { it.getJSONArray("items").length() > 0 }
                var item = event.getJSONArray("items").getJSONObject(0)
                assertEquals("First message", item.getString("body"))
                val firstRevision = item.getString("revision"); val notificationKey = item.getString("key")
                fixture("A newer message")
                event = readUntil(reader, "delta") { it.getJSONArray("items").length() > 0 }
                item = event.getJSONArray("items").getJSONObject(0)
                assertEquals(notificationKey, item.getString("key")); assertNotEquals(firstRevision, item.getString("revision"))
                send(writer, JSONObject().put("type", "dismiss").put("key", notificationKey).put("revision", firstRevision))
                assertEquals("stale", readUntil(reader, "dismissResult").getString("result"))
                send(writer, JSONObject().put("type", "dismiss").put("key", notificationKey).put("revision", item.getString("revision")))
                assertEquals("requested", readUntil(reader, "dismissResult").getString("result"))
                readUntil(reader, "delta") { it.getJSONArray("removed").length() == 1 }
                fixture("Remove on phone")
                readUntil(reader, "delta") { it.getJSONArray("items").length() > 0 }
                fixture(cancel = true)
                readUntil(reader, "delta") { it.getJSONArray("removed").length() == 1 }
                fixture("Protected notification", ongoing = true)
                val protectedItem = readUntil(reader, "delta") { it.getJSONArray("items").length() > 0 }.getJSONArray("items").getJSONObject(0)
                assertFalse(protectedItem.getBoolean("dismissible"))
                send(writer, JSONObject().put("type", "dismiss").put("key", protectedItem.getString("key")).put("revision", protectedItem.getString("revision")))
                assertEquals("protected", readUntil(reader, "dismissResult").getString("result"))
                fixture(cancel = true)
                readUntil(reader, "delta") { it.getJSONArray("removed").length() == 1 }
                fixture("Reconnect message")
                readUntil(reader, "delta") { it.getJSONArray("items").length() > 0 }
                connection.close()
                connection = server.accept().apply { soTimeout = 15000 }
                reader = connection.getInputStream().bufferedReader(); writer = connection.getOutputStream().bufferedWriter()
                assertEquals(hello.getString("deviceId"), readUntil(reader, "hello").getString("deviceId"))
                send(writer, JSONObject().put("type", "ready"))
                assertEquals("Reconnect message", readUntil(reader, "snapshot").getJSONArray("items").getJSONObject(0).getString("body"))
                prefs.hideContent = true; BridgeState.changed()
                assertEquals("Notification content hidden", readUntil(reader, "delta").getJSONArray("items").getJSONObject(0).getString("body"))
                prefs.localOnlyApps = setOf("dev.kunnect.fixture"); BridgeState.changed()
                assertFalse(readUntil(reader, "delta").getJSONArray("items").getJSONObject(0).getBoolean("dismissible"))
                prefs.enabledApps = emptySet(); BridgeState.changed()
                readUntil(reader, "delta") { it.getJSONArray("removed").length() == 1 }
                connection.close()
            }
        } finally {
            context.stopService(Intent(context, ConnectionService::class.java)); fixture(cancel = true)
            prefs.host = oldHost; prefs.token = oldToken; prefs.enabledApps = oldApps; prefs.hideContent = false; prefs.localOnlyApps = emptySet()
        }
    }
    @Test fun pairingKeyStoredEncryptedAndRoundTrips() {
        val prefs = Preferences(context); val old = prefs.token
        try {
            val key = "b".repeat(64); prefs.token = key; assertEquals(key, prefs.token)
            val stored = context.getSharedPreferences("kunnect", 0).getString("token", "")!!
            assertFalse(stored.contains(key)); assertNotEquals(key, stored)
        } finally { prefs.token = old }
    }
}
