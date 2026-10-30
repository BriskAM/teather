package dev.kunnect.fixture
import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import java.io.File

/** Isolated local document provider used only by the test fixture APK. */
class FixtureDocuments : DocumentsProvider() {
    private val root get() = File(context!!.filesDir, "shared").apply { mkdirs() }
    private fun file(id: String): File {
        val value = if (id == "root") root else File(root, id.removePrefix("root/"))
        require(value.canonicalPath == root.canonicalPath || value.canonicalPath.startsWith(root.canonicalPath + "/"))
        return value
    }
    private fun id(file: File) = if (file == root) "root" else "root/" + file.relativeTo(root).path
    override fun onCreate() = true
    override fun queryRoots(projection: Array<out String>?): Cursor {
        val cols = projection ?: arrayOf(DocumentsContract.Root.COLUMN_ROOT_ID, DocumentsContract.Root.COLUMN_DOCUMENT_ID, DocumentsContract.Root.COLUMN_TITLE, DocumentsContract.Root.COLUMN_FLAGS)
        return MatrixCursor(cols).apply { val row = newRow(); cols.forEach { col -> row.add(col, when(col) { DocumentsContract.Root.COLUMN_ROOT_ID -> "fixture"; DocumentsContract.Root.COLUMN_DOCUMENT_ID -> "root"; DocumentsContract.Root.COLUMN_TITLE -> "Kunnect Test Folder"; DocumentsContract.Root.COLUMN_FLAGS -> DocumentsContract.Root.FLAG_SUPPORTS_CREATE; else -> null }) } }
    }
    private fun query(files: List<File>, projection: Array<out String>?): Cursor {
        val cols = projection ?: arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED, DocumentsContract.Document.COLUMN_FLAGS)
        return MatrixCursor(cols).apply { files.forEach { f -> val row = newRow(); cols.forEach { col -> row.add(col, when(col) { DocumentsContract.Document.COLUMN_DOCUMENT_ID -> id(f); DocumentsContract.Document.COLUMN_DISPLAY_NAME -> if (f == root) "Test Folder" else f.name; DocumentsContract.Document.COLUMN_MIME_TYPE -> if (f.isDirectory) DocumentsContract.Document.MIME_TYPE_DIR else "application/octet-stream"; DocumentsContract.Document.COLUMN_SIZE -> f.length(); DocumentsContract.Document.COLUMN_LAST_MODIFIED -> f.lastModified(); DocumentsContract.Document.COLUMN_FLAGS -> DocumentsContract.Document.FLAG_SUPPORTS_WRITE or DocumentsContract.Document.FLAG_SUPPORTS_DELETE or DocumentsContract.Document.FLAG_SUPPORTS_RENAME or DocumentsContract.Document.FLAG_SUPPORTS_MOVE or if (f.isDirectory) DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE else 0; else -> null }) } } }
    }
    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor = query(listOf(file(documentId).also { require(it.exists()) }), projection)
    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor = query(file(parentDocumentId).listFiles()?.toList().orEmpty(), projection)
    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor = ParcelFileDescriptor.open(file(documentId), ParcelFileDescriptor.parseMode(mode))
    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        require(displayName !in listOf(".", "..") && !displayName.contains('/'))
        val f = File(file(parentDocumentId), displayName); require(!f.exists())
        check(if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) f.mkdir() else f.createNewFile()); return id(f)
    }
    override fun deleteDocument(documentId: String) { require(documentId != "root"); check(file(documentId).deleteRecursively()) }
    override fun renameDocument(documentId: String, displayName: String): String {
        require(!displayName.contains('/') && displayName !in listOf(".", ".."))
        val f = file(documentId); val target = File(f.parentFile, displayName); require(!target.exists()); check(f.renameTo(target)); return id(target)
    }
    override fun moveDocument(sourceDocumentId: String, sourceParentDocumentId: String, targetParentDocumentId: String): String {
        val f = file(sourceDocumentId); val target = File(file(targetParentDocumentId), f.name); require(!target.exists()); check(f.renameTo(target)); return id(target)
    }
    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean = file(documentId).canonicalPath.startsWith(file(parentDocumentId).canonicalPath + "/")
}
