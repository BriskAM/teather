package dev.kunnect

import android.content.Context
import android.util.Base64
import org.json.JSONObject
import java.io.*
import java.net.*
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** A bounded, demand-driven WebDAV endpoint. No scans, file watchers, or idle timers. */
class FileShareServer(private val context: Context, private val requestedPort: Int = 41692) : Closeable {
    private var server: ServerSocket? = null
    private var workers: ThreadPoolExecutor? = null
    private val connections = ConcurrentHashMap.newKeySet<Socket>()
    private val mutations = ReentrantLock()
    private var configuration = ""
    private var folder: SharedFolder? = null
    private var key = ""
    private var sharedName = ""
    private var allowedPeer = ""
    private data class DavLock(val token: String, var expires: Long)
    private val locks = mutableMapOf<String, DavLock>() // guarded by mutations
    val port: Int get() = server?.localPort ?: 0

    @Synchronized fun reconfigure() {
        val prefs = Preferences(context)
        val token = prefs.token
        val config = if (prefs.shareEnabled && prefs.sharedTree.isNotEmpty() && token.length == 64 && (TailnetAddress.valid(prefs.host) || (BuildConfig.DEBUG && prefs.host == "127.0.0.1"))) "${prefs.sharedTree}|${prefs.host}|$token" else ""
        if (config == configuration && server != null) return
        close()
        if (config.isEmpty()) { ShareState.status.value = if (prefs.shareEnabled) "Choose a folder and pair your Mac first" else "Sharing is off"; return }
        try {
            val selected = SharedFolder(context, prefs.sharedTree)
            val root = selected.root(); check(root.directory); sharedName = root.name
            key = token; allowedPeer = prefs.host; folder = selected
            val listener = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(requestedPort), 4) }
            server = listener; configuration = config
            val pool = ThreadPoolExecutor(0, 4, 20, TimeUnit.SECONDS, ArrayBlockingQueue(4), { task -> Thread(task, "Kunnect files").apply { isDaemon = true } })
            workers = pool
            Thread({
                while (!listener.isClosed) {
                    val socket = try { listener.accept() } catch (_: IOException) { break }
                    val source = socket.inetAddress.hostAddress.orEmpty()
                    if (source != allowedPeer && !(BuildConfig.DEBUG && socket.inetAddress.isLoopbackAddress)) { socket.close(); continue }
                    connections.add(socket)
                    try { pool.execute { try { socket.use { handle(it, selected) } } finally { connections.remove(socket) } } }
                    catch (_: java.util.concurrent.RejectedExecutionException) { connections.remove(socket); socket.close() }
                }
            }, "Kunnect file listener").apply { isDaemon = true; start() }
            ShareState.status.value = "Ready · ${root.name}"
        } catch (_: Exception) { close(); ShareState.status.value = "Folder unavailable · choose it again" }
    }
    fun info(): JSONObject {
        val prefs = Preferences(context)
        return JSONObject().put("type", "fileShare").put("enabled", server != null).put("name", if (server != null) sharedName.take(128) else "").put("writable", prefs.shareWrite)
    }
    @Synchronized override fun close() {
        runCatching { server?.close() }; server = null; configuration = ""
        for (socket in connections) runCatching { socket.close() }
        connections.clear(); workers?.shutdownNow(); workers = null
        mutations.withLock { locks.clear() }
        folder = null; key = ""; ShareState.status.value = "Sharing is off"
    }
    private class DavError(val status: Int, message: String = "") : IOException(message)
    private data class Request(val method: String, val parts: List<String>, val headers: Map<String, String>, val body: InputStream)
    private fun line(input: InputStream, max: Int): String {
        val bytes = ByteArrayOutputStream()
        while (true) {
            val byte = input.read(); if (byte < 0) throw EOFException()
            if (byte == 10) return bytes.toString("ISO-8859-1").removeSuffix("\r")
            if (bytes.size() >= max) throw DavError(431)
            bytes.write(byte)
        }
    }
    private fun parts(target: String): List<String> {
        val path = try { URI(target).rawPath ?: throw DavError(400) } catch (_: URISyntaxException) { throw DavError(400) }
        val segments = path.split('/').filter { it.isNotEmpty() }.map {
            val decoded = try { URLDecoder.decode(it.replace("+", "%2B"), "UTF-8") } catch (_: IllegalArgumentException) { throw DavError(400) }
            if (decoded == "." || decoded == ".." || decoded.any { c -> c == '/' || c == '\\' || c.code < 32 } || decoded.length > 255) throw DavError(403)
            decoded
        }
        if (segments.isEmpty()) return emptyList()
        if (segments.firstOrNull() != "Kunnect") throw DavError(404)
        return segments.drop(1)
    }
    private fun readRequest(input: InputStream): Request {
        val first = line(input, 8192).split(' ')
        if (first.size != 3 || !first[2].startsWith("HTTP/1.")) throw DavError(400)
        val headers = mutableMapOf<String, String>(); var total = 0
        while (true) {
            val row = line(input, 8192); total += row.length + 2; if (total > 32768) throw DavError(431)
            if (row.isEmpty()) break
            val colon = row.indexOf(':'); if (colon < 1) throw DavError(400)
            val name = row.substring(0, colon).lowercase(Locale.ROOT)
            if (headers.containsKey(name)) throw DavError(400)
            headers[name] = row.substring(colon + 1).trim()
        }
        val expected = "kunnect:$key".toByteArray(Charsets.UTF_8)
        val auth = headers["authorization"].orEmpty()
        val actual = if (auth.startsWith("Basic ", true)) runCatching { Base64.decode(auth.substring(6), Base64.DEFAULT) }.getOrNull() else null
        if (actual == null || key.length != 64 || !MessageDigest.isEqual(actual, expected)) throw DavError(401)
        val length = headers["content-length"]?.toLongOrNull()
        if (headers.containsKey("content-length") && (length == null || length < 0 || length > MAX_UPLOAD)) throw DavError(413)
        val transfer = headers["transfer-encoding"]
        if (transfer != null && (!transfer.equals("chunked", true) || length != null)) throw DavError(400)
        val body = if (transfer != null) ChunkedInput(input) else LimitedInput(input, length ?: 0)
        return Request(first[0].uppercase(Locale.ROOT), parts(first[1]), headers, body)
    }
    private fun handle(socket: Socket, tree: SharedFolder) {
        socket.soTimeout = 30000
        val output = socket.getOutputStream().buffered(65536)
        var started = false
        try {
            val request = readRequest(socket.getInputStream().buffered(65536))
            // The paired Mac is the only authorized client; each connection serves one request.
            if (request.headers["expect"].equals("100-continue", true)) { output.write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray()); output.flush() }
            when (request.method) {
                "OPTIONS" -> reply(output, 200, extra = mapOf("DAV" to if (Preferences(context).shareWrite) "1, 2" else "1", "Allow" to allowedMethods()))
                "PROPFIND" -> {
                    val depth = request.headers["depth"] ?: "1"
                    if (depth !in listOf("0", "1")) throw DavError(403)
                    val entry = tree.resolve(request.parts) ?: throw DavError(404)
                    val listing = arrayListOf(request.parts to entry)
                    if (depth == "1" && entry.directory) listing.addAll(tree.children(entry).map { (request.parts + it.name) to it })
                    val xml = "<?xml version=\"1.0\" encoding=\"utf-8\"?><D:multistatus xmlns:D=\"DAV:\">" + listing.joinToString("") { (path, node) -> propertyResponse(path, node) } + "</D:multistatus>"
                    reply(output, 207, xml.toByteArray(Charsets.UTF_8), mapOf("Content-Type" to "application/xml; charset=utf-8", "DAV" to "1, 2"))
                }
                "GET", "HEAD" -> {
                    val entry = tree.resolve(request.parts) ?: throw DavError(404)
                    if (entry.directory && request.method == "GET") throw DavError(405)
                    val size = if (entry.directory) 0 else entry.size
                    val range = request.headers["range"]
                    var start = 0L; var end = size - 1
                    if (range != null) {
                        if (size < 0 || !range.matches(Regex("bytes=\\d*-\\d*"))) throw DavError(416)
                        val values = range.removePrefix("bytes=").split('-')
                        if (values[0].isEmpty()) { val count = values[1].toLongOrNull() ?: throw DavError(416); if (count <= 0) throw DavError(416); start = (size - count).coerceAtLeast(0) }
                        else { start = values[0].toLongOrNull() ?: throw DavError(416); if (values[1].isNotEmpty()) end = (values[1].toLongOrNull() ?: throw DavError(416)).coerceAtMost(end) }
                        if (start > end || start < 0) throw DavError(416)
                    }
                    val length = if (size < 0) null else (end - start + 1).coerceAtLeast(0)
                    val headers = mutableMapOf("Content-Type" to entry.mime.ifEmpty { "application/octet-stream" }, "Last-Modified" to date(entry.modified), "Accept-Ranges" to "bytes", "ETag" to etag(entry))
                    if (range != null) headers["Content-Range"] = "bytes $start-$end/$size"
                    if (request.method == "HEAD") { header(output, if (range != null) 206 else 200, length ?: 0, headers); started = true }
                    else tree.input(entry).use { source ->
                        var remainingSkip = start
                        while (remainingSkip > 0) { val skipped = source.skip(remainingSkip); if (skipped > 0) remainingSkip -= skipped else if (source.read() < 0) throw EOFException() else remainingSkip-- }
                        header(output, if (range != null) 206 else 200, length, headers); started = true
                        if (length != null) copy(source, output, length) else chunkedCopy(source, output)
                    }
                }
                "PUT", "MKCOL", "DELETE", "MOVE", "COPY", "LOCK", "UNLOCK", "PROPPATCH" -> {
                    if (!Preferences(context).shareWrite) throw DavError(403)
                    mutations.withLock { mutate(tree, request, output) }
                }
                else -> throw DavError(405)
            }
        } catch (e: Exception) {
            if (!started) runCatching { reply(output, if (e is DavError) e.status else if (e is FileNotFoundException) 404 else if (e is SecurityException) 403 else 507) }
        } finally { runCatching { output.flush() } }
    }
    private fun mutate(tree: SharedFolder, request: Request, output: OutputStream) {
        // A queued writer must recheck after acquiring the lock: sharing can be revoked at any time.
        if (!Preferences(context).shareWrite || !Preferences(context).shareEnabled) throw DavError(403)
        locks.entries.removeAll { it.value.expires < System.currentTimeMillis() }
        val path = request.parts.joinToString("/")
        val existing = tree.resolve(request.parts)
        val held = locks[path]
        if (held != null && request.method !in listOf("LOCK", "UNLOCK") && !request.headers["if"].orEmpty().contains(held.token)) throw DavError(423)
        if (request.method == "LOCK") {
            if (held != null) {
                if (!request.headers["if"].orEmpty().contains(held.token)) throw DavError(423)
                held.expires = System.currentTimeMillis() + 180000
                return reply(output, 200, lockXml(held).toByteArray(), mapOf("Content-Type" to "application/xml; charset=utf-8", "Lock-Token" to "<${held.token}>"))
            }
            if (existing?.directory == true || request.headers["depth"] !in listOf(null, "0")) throw DavError(501)
            if (existing == null) { if (request.parts.isEmpty()) throw DavError(403); val parent = tree.resolve(request.parts.dropLast(1)) ?: throw DavError(409); tree.create(parent, request.parts.last()) }
            val lock = DavLock("opaquelocktoken:${UUID.randomUUID()}", System.currentTimeMillis() + 180000); locks[path] = lock
            return reply(output, if (existing == null) 201 else 200, lockXml(lock).toByteArray(), mapOf("Content-Type" to "application/xml; charset=utf-8", "Lock-Token" to "<${lock.token}>"))
        }
        if (request.method == "UNLOCK") {
            if (held == null || request.headers["lock-token"] != "<${held.token}>") throw DavError(409)
            locks.remove(path); return reply(output, 204)
        }
        if (request.method == "PROPPATCH") {
            if (existing == null) throw DavError(404)
            // SAF has no portable API to set Finder timestamps/extended attributes.
            return reply(output, 403)
        }
        if (request.parts.isEmpty()) throw DavError(403)
        if (request.headers["if-match"]?.let { it != "*" && it != existing?.let(::etag) } == true || (request.headers["if-none-match"] == "*" && existing != null)) throw DavError(412)
        when (request.method) {
            "PUT" -> {
                if (existing?.directory == true) throw DavError(405)
                if (!request.headers.containsKey("content-length") && !request.headers.containsKey("transfer-encoding")) throw DavError(411)
                val parent = tree.resolve(request.parts.dropLast(1)) ?: throw DavError(409)
                val stage = tree.create(parent, ".kunnect-upload-${UUID.randomUUID()}")
                var backup: SharedFolder.Entry? = null; var committed = false
                try {
                    tree.output(stage).use { sink -> copy(request.body, sink, MAX_UPLOAD, exact = false) }
                    if (!Preferences(context).shareWrite || !Preferences(context).shareEnabled) throw DavError(403)
                    if (existing != null) backup = tree.rename(existing, ".kunnect-backup-${UUID.randomUUID()}")
                    tree.rename(stage, request.parts.last()); committed = true
                    backup?.let { tree.delete(it) }
                } catch (e: Exception) {
                    if (!committed && backup != null) runCatching { tree.rename(backup!!, request.parts.last()) }
                    throw e
                } finally { if (!committed) runCatching { tree.delete(stage) } }
                reply(output, if (existing == null) 201 else 204)
            }
            "MKCOL" -> { if (existing != null) throw DavError(405); if (request.headers["content-length"]?.toLongOrNull()?.let { it > 0 } == true) throw DavError(415); val parent = tree.resolve(request.parts.dropLast(1)) ?: throw DavError(409); tree.create(parent, request.parts.last(), true); reply(output, 201) }
            "DELETE" -> { if (existing == null) throw DavError(404); tree.delete(existing); locks.remove(path); reply(output, 204) }
            "MOVE", "COPY" -> {
                if (existing == null) throw DavError(404)
                val target = request.headers["destination"] ?: throw DavError(400)
                val uri = URI(target)
                val host = request.headers["host"].orEmpty()
                if (uri.rawAuthority != null && !uri.rawAuthority.equals(host, true)) throw DavError(403)
                val destination = parts(target); if (destination.isEmpty() || destination == request.parts || destination.take(request.parts.size) == request.parts) throw DavError(403)
                val destinationPath = destination.joinToString("/")
                if (locks[destinationPath]?.let { !request.headers["if"].orEmpty().contains(it.token) } == true) throw DavError(423)
                val targetParent = tree.resolve(destination.dropLast(1)) ?: throw DavError(409)
                val previous = tree.resolve(destination)
                // Overwrites use PUT's staged replacement path; renames never silently delete a target.
                if (previous != null) throw DavError(412)
                if (request.method == "COPY") {
                    if (existing.directory) throw DavError(501)
                    val created = tree.create(targetParent, destination.last())
                    try { tree.input(existing).use { source -> tree.output(created).use { copy(source, it, MAX_UPLOAD, exact = false) } } } catch (e: Exception) { runCatching { tree.delete(created) }; throw e }
                } else {
                    val parent = tree.resolve(request.parts.dropLast(1)) ?: throw DavError(409)
                    val moved = tree.move(existing, parent, targetParent)
                    if (moved.name != destination.last()) tree.rename(moved, destination.last())
                    locks.remove(path)
                }
                reply(output, 201)
            }
        }
    }
    private fun allowedMethods(): String = "OPTIONS, PROPFIND, GET, HEAD" + if (Preferences(context).shareWrite) ", PUT, MKCOL, DELETE, MOVE, COPY, LOCK, UNLOCK" else ""
    private fun propertyResponse(path: List<String>, entry: SharedFolder.Entry): String {
        val href = "/Kunnect/" + path.joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") } + if (entry.directory && path.isNotEmpty()) "/" else ""
        val write = Preferences(context).shareWrite
        return "<D:response><D:href>${xml(href)}</D:href><D:propstat><D:prop><D:displayname>${xml(entry.name)}</D:displayname><D:resourcetype>${if (entry.directory) "<D:collection/>" else ""}</D:resourcetype><D:getcontentlength>${entry.size.coerceAtLeast(0)}</D:getcontentlength><D:getcontenttype>${xml(entry.mime)}</D:getcontenttype><D:getlastmodified>${date(entry.modified)}</D:getlastmodified><D:getetag>${xml(etag(entry))}</D:getetag><D:supportedlock>${if (write) "<D:lockentry><D:lockscope><D:exclusive/></D:lockscope><D:locktype><D:write/></D:locktype></D:lockentry>" else ""}</D:supportedlock><D:lockdiscovery/></D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>"
    }
    private fun lockXml(lock: DavLock): String = "<?xml version=\"1.0\"?><D:prop xmlns:D=\"DAV:\"><D:lockdiscovery><D:activelock><D:locktype><D:write/></D:locktype><D:lockscope><D:exclusive/></D:lockscope><D:depth>0</D:depth><D:timeout>Second-180</D:timeout><D:locktoken><D:href>${lock.token}</D:href></D:locktoken></D:activelock></D:lockdiscovery></D:prop>"
    private fun xml(value: String): String = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")
    private fun date(millis: Long): String = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US).apply { timeZone = TimeZone.getTimeZone("GMT") }.format(Date(millis.coerceAtLeast(0)))
    private fun etag(entry: SharedFolder.Entry): String = "\"${entry.modified.toString(16)}-${entry.size.toString(16)}\""
    private fun reply(output: OutputStream, status: Int, body: ByteArray = byteArrayOf(), extra: Map<String, String> = emptyMap()) { header(output, status, body.size.toLong(), extra); output.write(body) }
    private fun header(output: OutputStream, status: Int, length: Long?, extra: Map<String, String>) {
        val reason = mapOf(200 to "OK", 201 to "Created", 204 to "No Content", 206 to "Partial Content", 207 to "Multi-Status", 400 to "Bad Request", 401 to "Unauthorized", 403 to "Forbidden", 404 to "Not Found", 405 to "Method Not Allowed", 409 to "Conflict", 411 to "Length Required", 412 to "Precondition Failed", 413 to "Content Too Large", 415 to "Unsupported Media Type", 416 to "Range Not Satisfiable", 423 to "Locked", 431 to "Request Header Fields Too Large", 501 to "Not Implemented", 507 to "Insufficient Storage")[status] ?: "Error"
        val fields = LinkedHashMap(extra); fields["Connection"] = "close"; fields["Cache-Control"] = "no-store"
        if (length != null) fields["Content-Length"] = length.toString() else fields["Transfer-Encoding"] = "chunked"
        if (status == 401) fields["WWW-Authenticate"] = "Basic realm=\"Kunnect\", charset=\"UTF-8\""
        if (fields.any { (name, value) -> name.any { it == '\r' || it == '\n' } || value.any { it == '\r' || it == '\n' } }) throw DavError(400)
        val text = "HTTP/1.1 $status $reason\r\n" + fields.entries.joinToString("") { "${it.key}: ${it.value}\r\n" } + "\r\n"
        output.write(text.toByteArray(Charsets.ISO_8859_1))
    }
    private fun copy(source: InputStream, sink: OutputStream, limit: Long, exact: Boolean = true) {
        val buffer = ByteArray(65536); var total = 0L
        while (total < limit) { val n = source.read(buffer, 0, minOf(buffer.size.toLong(), limit - total).toInt()); if (n < 0) { if (exact) throw EOFException(); return }; sink.write(buffer, 0, n); total += n }
        if (!exact && source.read() >= 0) throw DavError(413)
    }
    private fun chunkedCopy(source: InputStream, sink: OutputStream) {
        val buffer = ByteArray(65536)
        while (true) { val n = source.read(buffer); if (n < 0) break; sink.write("${n.toString(16)}\r\n".toByteArray()); sink.write(buffer, 0, n); sink.write("\r\n".toByteArray()) }; sink.write("0\r\n\r\n".toByteArray())
    }
    private class LimitedInput(private val input: InputStream, private var left: Long) : InputStream() {
        override fun read(): Int { if (left == 0L) return -1; val value = input.read(); if (value < 0) throw EOFException(); left--; return value }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int { if (left == 0L) return -1; val count = input.read(buffer, offset, minOf(left, length.toLong()).toInt()); if (count < 0) throw EOFException(); left -= count; return count }
    }
    private inner class ChunkedInput(private val input: InputStream) : InputStream() {
        private var left = 0L; private var total = 0L; private var done = false
        private fun next() { if (done || left > 0) return; val value = line(input, 128).substringBefore(';').toLongOrNull(16) ?: throw DavError(400); if (value < 0 || total + value > MAX_UPLOAD) throw DavError(413); left = value; total += value; if (left == 0L) { if (line(input, 8192).isNotEmpty()) throw DavError(400); done = true } }
        override fun read(): Int { val one = ByteArray(1); return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 255 }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int { next(); if (done) return -1; val n = input.read(buffer, offset, minOf(left, length.toLong()).toInt()); if (n < 0) throw EOFException(); left -= n; if (left == 0L && line(input, 2).isNotEmpty()) throw DavError(400); return n }
    }
    companion object { private const val MAX_UPLOAD = 8L * 1024 * 1024 * 1024 }
}
