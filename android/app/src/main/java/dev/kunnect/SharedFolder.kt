package dev.kunnect

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.InputStream
import java.io.OutputStream

/** Scoped Storage Access Framework adapter. Lists only the requested directory. */
class SharedFolder(private val context: Context, tree: String) {
    private val resolver = context.contentResolver
    private val treeUri = Uri.parse(tree)
    data class Entry(val uri: Uri, val id: String, val name: String, val mime: String, val size: Long, val modified: Long, val flags: Long) {
        val directory: Boolean get() = mime == DocumentsContract.Document.MIME_TYPE_DIR
    }
    private val projection = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED, DocumentsContract.Document.COLUMN_FLAGS)
    private fun query(uri: Uri): List<Entry> {
        val result = ArrayList<Entry>()
        resolver.query(uri, projection, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                check(result.size < 10000) { "Directory contains too many entries" }
                val id = cursor.getString(0)
                result.add(Entry(DocumentsContract.buildDocumentUriUsingTree(treeUri, id), id, cursor.getString(1).orEmpty(), cursor.getString(2).orEmpty(), if (cursor.isNull(3)) -1 else cursor.getLong(3), cursor.getLong(4), cursor.getLong(5)))
            }
        }
        return result
    }
    fun root(): Entry = query(DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))).firstOrNull() ?: throw java.io.FileNotFoundException()
    fun children(parent: Entry): List<Entry> = query(DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parent.id))
    fun resolve(parts: List<String>): Entry? {
        var current = root()
        for (part in parts) {
            if (!current.directory) return null
            current = children(current).firstOrNull { it.name == part } ?: return null
        }
        return current
    }
    fun input(entry: Entry): InputStream = resolver.openInputStream(entry.uri) ?: throw java.io.FileNotFoundException()
    fun output(entry: Entry): OutputStream = resolver.openOutputStream(entry.uri, "wt") ?: throw java.io.IOException("Cannot write this file")
    fun create(parent: Entry, name: String, directory: Boolean = false): Entry {
        val mime = if (directory) DocumentsContract.Document.MIME_TYPE_DIR else "application/octet-stream"
        val uri = DocumentsContract.createDocument(resolver, parent.uri, mime, name) ?: throw java.io.IOException("Folder is not writable")
        return query(uri).first()
    }
    fun rename(entry: Entry, name: String): Entry {
        val uri = DocumentsContract.renameDocument(resolver, entry.uri, name) ?: throw java.io.IOException("Provider cannot rename")
        val result = query(uri).first()
        check(result.name == name) { "Provider changed the requested file name" }
        return result
    }
    fun move(entry: Entry, sourceParent: Entry, targetParent: Entry): Entry {
        if (sourceParent.id == targetParent.id) return entry
        val uri = DocumentsContract.moveDocument(resolver, entry.uri, sourceParent.uri, targetParent.uri) ?: throw java.io.IOException("Provider cannot move this file")
        return query(uri).first()
    }
    fun delete(entry: Entry) { check(DocumentsContract.deleteDocument(resolver, entry.uri)) { "Provider could not delete" } }
}

object ShareState {
    val status = MutableStateFlow("Sharing is off")
}
