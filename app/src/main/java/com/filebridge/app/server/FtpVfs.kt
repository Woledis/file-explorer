package com.filebridge.app.server

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.filebridge.app.data.DocStore
import com.filebridge.app.data.SecurityManager
import org.apache.ftpserver.ftplet.Authentication
import org.apache.ftpserver.ftplet.AuthenticationFailedException
import org.apache.ftpserver.ftplet.FileSystemFactory
import org.apache.ftpserver.ftplet.FileSystemView
import org.apache.ftpserver.ftplet.FtpFile
import org.apache.ftpserver.ftplet.User
import org.apache.ftpserver.ftplet.UserManager
import org.apache.ftpserver.usermanager.UsernamePasswordAuthentication
import org.apache.ftpserver.usermanager.impl.BaseUser
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Mint an in-memory virtual tree over the SAF shared folders so the FTP server
 * sees a stable "/style" hierarchy: "/" contains one directory per shared root,
 * and everything below maps to document ids inside the granted tree.
 */
class FtpVfs(
    private val context: Context,
    private val docStore: DocStore,
    private val roots: List<SharedRoot>,
) {
    private val resolver get() = context.applicationContext.contentResolver

    data class Node(
        val path: String,
        val treeUri: Uri?,
        /** document id of this node; null only for the virtual root. */
        val selfDocId: String?,
        /** document id of the containing directory (null for top-level roots). */
        val parentDocId: String?,
        val exists: Boolean,
        val isDir: Boolean,
        val size: Long,
    ) {
        val name: String
            get() {
                val trimmed = path.removePrefix("/")
                return if (trimmed.isEmpty()) "/" else trimmed.substringAfterLast('/')
            }
    }

    fun root(): Node = Node("/", null, null, null, exists = true, isDir = true, size = 0)

    fun resolve(path: String): Node {
        val p = normalize(path)
        if (p == "/") return root()

        val parts = p.split('/').filter { it.isNotEmpty() }
        val root = roots.firstOrNull { it.label == parts[0] }
            ?: return Node(p, null, null, null, exists = false, isDir = false, size = 0)

        val treeUri = Uri.parse(root.treeUri)
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        var curPath = "/${root.label}"
        var curDoc = rootId
        var curParent: String? = null
        var curIsDir = true
        var curSize = 0L
        for (i in 1 until parts.size) {
            val childName = parts[i]
            val entry = docStore.listChildren(treeUri, curDoc).firstOrNull { it.name == childName }
            if (entry == null) {
                // Target does not exist yet (a fresh STOR/MKD destination).
                return Node(curPath + "/" + childName, treeUri, null, curDoc, exists = false, isDir = false, size = 0)
            }
            curParent = curDoc
            curDoc = entry.documentId
            curIsDir = entry.isDirectory
            curSize = if (curIsDir) 0L else entry.size
            curPath += "/" + childName
        }
        return Node(curPath, treeUri, curDoc, curParent, exists = true, isDir = curIsDir, size = curSize)
    }

    fun list(node: Node): List<Node> {
        if (!node.exists || !node.isDir) return emptyList()
        if (node.path == "/") {
            return roots.map { r ->
                runCatching {
                    val tree = Uri.parse(r.treeUri)
                    val rootId = DocumentsContract.getTreeDocumentId(tree)
                    Node("/${r.label}", tree, rootId, null, exists = true, isDir = true, size = 0)
                }.getOrNull()
            }.filterNotNull()
        }
        val tree = node.treeUri ?: return emptyList()
        return docStore.listChildren(tree, node.selfDocId).map { e ->
            Node(
                path = node.path + "/" + e.name,
                treeUri = tree,
                selfDocId = e.documentId,
                parentDocId = node.selfDocId,
                exists = true,
                isDir = e.isDirectory,
                size = if (e.isDirectory) 0L else e.size,
            )
        }
    }

    fun openRead(node: Node, offset: Long): InputStream? {
        if (!node.exists || node.isDir || node.treeUri == null || node.selfDocId == null) return null
        if (offset != 0L) return null
        return docStore.open(node.treeUri, node.selfDocId)
    }

    fun openWrite(node: Node): OutputStream? {
        if (node.isDir || node.treeUri == null || node.parentDocId == null) return null
        return docStore.openWrite(node.treeUri, node.parentDocId, node.name)?.output
    }

    fun mkdir(node: Node): Boolean {
        if (node.treeUri == null || node.parentDocId == null) return false
        return docStore.createDirDocId(node.treeUri, node.parentDocId, node.name) != null
    }

    fun delete(node: Node): Boolean {
        if (node.treeUri == null || node.selfDocId == null) return false
        val docUri = DocumentsContract.buildDocumentUriUsingTree(node.treeUri, node.selfDocId)
        return runCatching { resolver.delete(docUri, null, null) }.getOrDefault(0) > 0 ||
            runCatching { DocumentsContract.deleteDocument(resolver, docUri) }.getOrDefault(false)
    }

    /** Rename within the same shared tree; cross-tree moves are not supported. */
    fun move(src: Node, dst: Node): Boolean {
        if (src.treeUri == null || src.selfDocId == null || dst.treeUri == null) return false
        if (src.treeUri.toString() != dst.treeUri.toString()) return false
        val docUri = DocumentsContract.buildDocumentUriUsingTree(src.treeUri, src.selfDocId)
        return runCatching { DocumentsContract.renameDocument(resolver, docUri, dst.name) != null }
            .getOrDefault(false)
    }

    private fun normalize(path: String): String {
        if (path.isBlank()) return "/"
        val joined = "/" + path.replace('\\', '/').split('/').filter { it.isNotEmpty() }.joinToString("/")
        return if (joined.isEmpty()) "/" else joined
    }
}

/** One node of the virtual tree exposed to MINA's FTP engine. */
private class VfsFile(private val vfs: FtpVfs, private val node: FtpVfs.Node) : FtpFile {

    // 原 meta() 对每次属性访问都重新 resolve(),深层路径会反复走 SAF 查询。
    // 改为单次惰性求值并复用:同一 node 的属性只解析一次。
    private val self: FtpVfs.Node by lazy(LazyThreadSafetyMode.NONE) { vfs.resolve(node.path) }

    private fun meta(): FtpVfs.Node = self

    override fun getAbsolutePath(): String = node.path
    override fun getName(): String = node.name
    override fun isHidden(): Boolean = false
    override fun isDirectory(): Boolean = meta().isDir
    override fun isFile(): Boolean = meta().exists && !meta().isDir
    override fun doesExist(): Boolean = meta().exists
    override fun isReadable(): Boolean = meta().exists
    override fun isWritable(): Boolean = meta().treeUri != null
    override fun isRemovable(): Boolean = meta().treeUri != null
    override fun getOwnerName(): String = "filebridge"
    override fun getGroupName(): String = "filebridge"
    override fun getLinkCount(): Int = if (meta().isDir) 2 else 1
    override fun getLastModified(): Long = 0
    override fun setLastModified(time: Long): Boolean = false
    override fun getSize(): Long = meta().size
    override fun getPhysicalFile(): Any = node.path

    override fun mkdir(): Boolean = vfs.mkdir(vfs.resolve(node.path))
    override fun delete(): Boolean = vfs.delete(meta())
    override fun move(destination: FtpFile): Boolean =
        if (destination is VfsFile) vfs.move(meta(), destination.node) else false

    override fun listFiles(): List<FtpFile>? {
        val n = meta()
        if (!n.isDir || !n.exists) return null
        return vfs.list(n).map { VfsFile(vfs, it) }
    }

    override fun createOutputStream(offset: Long): OutputStream {
        if (offset != 0L) throw IOException("random offset unsupported")
        vfs.openWrite(meta())?.let { return it }
        throw IOException("cannot open output stream")
    }

    override fun createInputStream(offset: Long): InputStream {
        if (offset != 0L) throw IOException("random offset unsupported")
        vfs.openRead(meta(), offset)?.let { return it }
        throw IOException("cannot open input stream")
    }
}

class VfsView(private val vfs: FtpVfs) : FileSystemView {
    private var working: String = "/"

    override fun getHomeDirectory(): FtpFile = VfsFile(vfs, vfs.root())
    override fun getWorkingDirectory(): FtpFile = VfsFile(vfs, vfs.resolve(working))
    override fun changeWorkingDirectory(dir: String): Boolean {
        val n = vfs.resolve(if (dir.startsWith("/")) dir else working + "/" + dir)
        return if (n.exists && n.isDir) { working = n.path; true } else false
    }
    override fun getFile(file: String): FtpFile = VfsFile(vfs, vfs.resolve(if (file.startsWith("/")) file else working + "/" + file))
    override fun isRandomAccessible(): Boolean = false
    override fun dispose() {}
}

/** Builds the shared [FileSystemView] for every FTP user. */
class AppFileSystem(private val view: FileSystemView) : FileSystemFactory {
    override fun createFileSystemView(user: User): FileSystemView = view
}

/** Accepts any username as long as the access password matches. */
class AppUserManager(private val security: SecurityManager) : UserManager {

    override fun authenticate(authentication: Authentication): User {
        val up = authentication as? UsernamePasswordAuthentication
            ?: throw AuthenticationFailedException("unsupported authentication")
        val pwd = up.password?.toCharArray() ?: charArrayOf()
        if (!security.verifyPassword(pwd)) throw AuthenticationFailedException("wrong password")
        return BaseUser().apply {
            setName(up.username ?: "filebridge")
            setHomeDirectory("/")
        }
    }

    override fun doesExist(username: String): Boolean = true
    override fun getUserByName(username: String): User? = null
    override fun getAllUserNames(): Array<String> = arrayOf()
    override fun delete(username: String) {}
    override fun save(user: User) {}
    override fun getAdminName(): String = "admin"
    override fun isAdmin(username: String): Boolean = false
}