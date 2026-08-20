package com.filebridge.app.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import java.io.InputStream
import java.io.OutputStream

/**
 * Thin layer over the Storage Access Framework. A shared folder is a *tree*
 * grant; we navigate it entirely by document ids so no bulk storage
 * permission is required, and each access is confined to the granted tree.
 */
class DocStore(private val context: Context) {

    private val resolver get() = context.applicationContext.contentResolver

    data class DocEntry(
        val documentId: String,
        val name: String,
        val isDirectory: Boolean,
        val size: Long,
    )

    /** True if [childId] belongs to [rootTreeId] (defaults to the tree's own id). */
    private fun inTree(rootTreeId: String, childId: String): Boolean =
        childId == rootTreeId || childId.startsWith("$rootTreeId/")

    private fun rootDocId(treeUri: Uri): String = DocumentsContract.getTreeDocumentId(treeUri)

    fun listChildren(treeUri: Uri, docId: String?): List<DocEntry> {
        val rootId = rootDocId(treeUri)
        val parentId = docId ?: rootId
        if (!inTree(rootId, parentId)) return emptyList()

        val childUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_FLAGS,
        )
        val result = mutableListOf<DocEntry>()
        resolver.query(childUri, projection, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                val name = c.getString(1) ?: id
                val mime = c.getString(2) ?: ""
                val size = if (c.isNull(3)) 0L else c.getLong(3)
                result += DocEntry(
                    documentId = id,
                    name = name,
                    isDirectory = mime == DocumentsContract.Document.MIME_TYPE_DIR,
                    size = size,
                )
            }
        }
        return result.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
    }

    fun open(treeUri: Uri, docId: String): InputStream? {
        if (!inTree(rootDocId(treeUri), docId)) return null
        val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
        return runCatching { resolver.openInputStream(docUri) }.getOrNull()
    }

    /** Open a document together with its name and size (queries metadata first). */
    fun openDocument(treeUri: Uri, docId: String): OpenedDoc? {
        if (!inTree(rootDocId(treeUri), docId)) return null
        val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
        var name: String? = null
        var size = -1L
        resolver.query(docUri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                if (!c.isNull(0)) name = c.getString(0)
                if (!c.isNull(1)) size = c.getLong(1)
            }
        }
        val stream = runCatching { resolver.openInputStream(docUri) }.getOrNull() ?: return null
        return OpenedDoc(name ?: docId.substringAfterLast('/'), size, stream)
    }

    data class OpenedDoc(val name: String, val size: Long, val stream: InputStream)

    fun displayName(treeUri: Uri, docId: String): String {
        val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
        resolver.query(docUri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) return c.getString(0)
        }
        return docId.substringAfterLast('/').substringAfterLast(':')
    }

    /** Create a document inside [dirDocId] and write to it. */
    fun createDirDocId(treeUri: Uri, dirDocId: String, name: String): String? {
        val rootId = rootDocId(treeUri)
        if (!inTree(rootId, dirDocId)) return null
        if (!childExists(treeUri, dirDocId, name)) {
            val dirUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, dirDocId)
            val created = DocumentsContract.createDocument(
                resolver, dirUri, DocumentsContract.Document.MIME_TYPE_DIR, name
            ) ?: return null
            return DocumentsContract.getDocumentId(created)
        }
        return childDocumentId(treeUri, dirDocId, name)
    }

    fun openWrite(treeUri: Uri, dirDocId: String, name: String): OpenTarget? {
        val rootId = rootDocId(treeUri)
        if (!inTree(rootId, dirDocId)) return null
        // Try to reuse an existing file of that name, else create one.
        var fileDocId = childDocumentId(treeUri, dirDocId, name)
        if (fileDocId == null) {
            val dirUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, dirDocId)
            val created = DocumentsContract.createDocument(
                resolver, dirUri, "application/octet-stream", name
            ) ?: return null
            fileDocId = DocumentsContract.getDocumentId(created)
        }
        val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, fileDocId)
        val out = runCatching { resolver.openOutputStream(docUri, "wt") }.getOrNull() ?: return null
        return OpenTarget(fileDocId, out)
    }

    fun childDocumentId(treeUri: Uri, dirDocId: String, name: String): String? {
        return listChildren(treeUri, dirDocId).firstOrNull { it.name == name }?.documentId
    }

    fun childExists(treeUri: Uri, dirDocId: String, name: String): Boolean =
        childDocumentId(treeUri, dirDocId, name) != null

    class OpenTarget(val documentId: String, val output: OutputStream)
}