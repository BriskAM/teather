package dev.kunnect

import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.net.Socket

@RunWith(AndroidJUnit4::class)
class FileShareTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val token = "d".repeat(64)
    private val auth = android.util.Base64.encodeToString("kunnect:$token".toByteArray(), android.util.Base64.NO_WRAP)
    private val treeUri = DocumentsContract.buildTreeDocumentUri("dev.kunnect.fixture.documents", "root")
    private fun setup(): Preferences {
        context.sendBroadcast(android.content.Intent().setComponent(android.content.ComponentName("dev.kunnect.fixture", "dev.kunnect.fixture.FixtureReceiver")).putExtra("grantFolder", true))
        val deadline = System.currentTimeMillis() + 10000
        while (context.checkUriPermission(treeUri, android.os.Process.myPid(), android.os.Process.myUid(), android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            check(System.currentTimeMillis() < deadline) { "Fixture folder permission was not granted" }; Thread.sleep(50)
        }
        return Preferences(context).apply { host = "127.0.0.1"; this.token = this@FileShareTest.token; sharedTree = treeUri.toString(); shareEnabled = true; shareWrite = false }
    }
    private data class Response(val status: Int, val headers: Map<String, String>, val body: ByteArray)
    private fun request(port: Int, method: String, path: String = "/Kunnect/", body: String = "", headers: Map<String, String> = emptyMap(), authorized: Boolean = true): Response {
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 15000
            val values = mutableMapOf("Host" to "127.0.0.1:$port", "Connection" to "close")
            if (!headers.containsKey("Transfer-Encoding")) values["Content-Length"] = body.toByteArray().size.toString()
            if (authorized) values["Authorization"] = "Basic $auth"
            values.putAll(headers)
            socket.getOutputStream().write(("$method $path HTTP/1.1\r\n" + values.entries.joinToString("") { "${it.key}: ${it.value}\r\n" } + "\r\n" + body).toByteArray())
            val all = socket.getInputStream().readBytes(); val split = all.indices.first { it + 3 < all.size && all.copyOfRange(it, it + 4).contentEquals("\r\n\r\n".toByteArray()) }
            val rows = String(all.copyOfRange(0, split)).split("\r\n")
            return Response(rows[0].split(' ')[1].toInt(), rows.drop(1).associate { it.substringBefore(':').lowercase() to it.substringAfter(':').trim() }, all.copyOfRange(split + 4, all.size))
        }
    }
    @Test fun scopedWebDavReadsWritesRangesLocksAndRevocation() {
        val prefs = setup(); val folder = SharedFolder(context, treeUri.toString())
        folder.children(folder.root()).forEach { folder.delete(it) }
        val file = folder.create(folder.root(), "hello & space.txt")
        folder.output(file).use { it.write("Hello from scoped Android storage".toByteArray()) }
        val server = FileShareServer(context, 0)
        try {
            server.reconfigure(); assertTrue(server.port > 0)
            assertEquals(401, request(server.port, "PROPFIND", authorized = false).status)
            val listing = request(server.port, "PROPFIND", headers = mapOf("Depth" to "1"))
            assertEquals(207, listing.status); assertTrue(String(listing.body).contains("hello &amp; space.txt")); assertTrue(String(listing.body).contains("hello%20%26%20space.txt"))
            assertEquals(403, request(server.port, "PROPFIND", headers = mapOf("Depth" to "infinity")).status)
            assertEquals(403, request(server.port, "GET", "/Kunnect/%2e%2e/secret").status)
            assertEquals(403, request(server.port, "GET", "/Kunnect/a%2fb").status)
            val content = request(server.port, "GET", "/Kunnect/hello%20%26%20space.txt")
            assertEquals("Hello from scoped Android storage", String(content.body))
            val range = request(server.port, "GET", "/Kunnect/hello%20%26%20space.txt", headers = mapOf("Range" to "bytes=6-9"))
            assertEquals(206, range.status); assertEquals("from", String(range.body))
            assertEquals(416, request(server.port, "GET", "/Kunnect/hello%20%26%20space.txt", headers = mapOf("Range" to "bytes=9999-")).status)
            assertEquals(403, request(server.port, "PUT", "/Kunnect/new.txt", "blocked").status)
            prefs.shareWrite = true
            assertEquals(201, request(server.port, "PUT", "/Kunnect/new.txt", "new data").status)
            assertEquals("new data", String(request(server.port, "GET", "/Kunnect/new.txt").body))
            assertEquals(204, request(server.port, "PUT", "/Kunnect/new.txt", "replacement").status)
            assertEquals("replacement", String(request(server.port, "GET", "/Kunnect/new.txt").body))
            Socket("127.0.0.1", server.port).use { socket ->
                socket.soTimeout = 5000
                socket.getOutputStream().write(("PUT /Kunnect/new.txt HTTP/1.1\r\nHost: 127.0.0.1:${server.port}\r\nAuthorization: Basic $auth\r\nContent-Length: 100\r\n\r\npartial").toByteArray())
                socket.shutdownOutput(); socket.getInputStream().readBytes()
            }
            assertEquals("replacement", String(request(server.port, "GET", "/Kunnect/new.txt").body))
            assertFalse(folder.children(folder.root()).any { it.name.startsWith(".kunnect-upload-") })
            assertEquals(201, request(server.port, "PUT", "/Kunnect/chunks.txt", "3\r\nabc\r\n2\r\nde\r\n0\r\n\r\n", mapOf("Transfer-Encoding" to "chunked")).status)
            assertEquals("abcde", String(request(server.port, "GET", "/Kunnect/chunks.txt").body))
            assertEquals(201, request(server.port, "MKCOL", "/Kunnect/sub/").status)
            assertEquals(201, request(server.port, "MOVE", "/Kunnect/new.txt", headers = mapOf("Destination" to "http://127.0.0.1:${server.port}/Kunnect/sub/moved.txt")).status)
            assertEquals("replacement", String(request(server.port, "GET", "/Kunnect/sub/moved.txt").body))
            val lock = request(server.port, "LOCK", "/Kunnect/sub/moved.txt", headers = mapOf("Depth" to "0"))
            assertEquals(200, lock.status); val lockToken = lock.headers["lock-token"]!!
            assertEquals(423, request(server.port, "PUT", "/Kunnect/sub/moved.txt", "without token").status)
            assertEquals(204, request(server.port, "PUT", "/Kunnect/sub/moved.txt", "with token", mapOf("If" to "($lockToken)")).status)
            assertEquals(204, request(server.port, "UNLOCK", "/Kunnect/sub/moved.txt", headers = mapOf("Lock-Token" to lockToken)).status)
            assertEquals(403, request(server.port, "MOVE", "/Kunnect/chunks.txt", headers = mapOf("Destination" to "http://evil.example/Kunnect/a.txt")).status)
            assertEquals(204, request(server.port, "DELETE", "/Kunnect/sub/moved.txt").status)
            prefs.shareWrite = false
            assertEquals(403, request(server.port, "DELETE", "/Kunnect/chunks.txt").status)
            prefs.shareEnabled = false; server.reconfigure(); assertEquals(0, server.port)
        } finally { server.close(); prefs.shareEnabled = false; prefs.shareWrite = false; prefs.sharedTree = ""; prefs.host = ""; prefs.token = "" }
    }
    @Test fun serveNativeMacMountFixture() {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("mountFixture") == "true")
        val prefs = setup()
        prefs.shareWrite = InstrumentationRegistry.getArguments().getString("mountWritable") == "true"
        val folder = SharedFolder(context, treeUri.toString())
        folder.children(folder.root()).forEach { folder.delete(it) }
        folder.output(folder.create(folder.root(), "Read me.txt")).use { it.write("Hello from the Android emulator over WebDAV.\n".toByteArray()) }
        val server = FileShareServer(context)
        val stop = java.util.concurrent.CountDownLatch(1)
        val receiver = object : android.content.BroadcastReceiver() { override fun onReceive(c: android.content.Context, i: android.content.Intent) { stop.countDown() } }
        androidx.core.content.ContextCompat.registerReceiver(context, receiver, android.content.IntentFilter("dev.kunnect.test.STOP_SHARE"), androidx.core.content.ContextCompat.RECEIVER_EXPORTED)
        try { server.reconfigure(); assertEquals(41692, server.port); stop.await(180, java.util.concurrent.TimeUnit.SECONDS) }
        finally { context.unregisterReceiver(receiver); server.close(); prefs.shareEnabled = false; prefs.sharedTree = ""; prefs.token = ""; prefs.host = "" }
    }
}
