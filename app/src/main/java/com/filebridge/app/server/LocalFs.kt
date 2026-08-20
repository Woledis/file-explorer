package com.filebridge.app.server

import org.apache.ftpserver.ftplet.FileSystemFactory
import org.apache.ftpserver.ftplet.FileSystemView
import org.apache.ftpserver.ftplet.FtpFile
import org.apache.ftpserver.ftplet.User
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * FTP backend that exposes a real filesystem (@see root) instead of the SAF
 * virtual tree. Used for "访问所有文件": the FTP root is the phone's primary
 * storage, so Windows can browse every file, and the earlier "450
 * Non-existing file" (caused by missing virtual paths) disappears.
 */
class LocalFs(private val root: File) : FileSystemView {
    private var cwd: File = root

    override fun getHomeDirectory(): FtpFile = LocalFtpFile(root)

    override fun getWorkingDirectory(): FtpFile = LocalFtpFile(cwd)

    override fun changeWorkingDirectory(dir: String): Boolean {
        val target = resolve(dir)
        return if (target.isDirectory) {
            cwd = target.canonicalFile
            true
        } else false
    }

    override fun getFile(file: String): FtpFile = LocalFtpFile(resolve(file))

    override fun isRandomAccessible(): Boolean = false

    override fun dispose() {}

    private fun resolve(name: String): File {
        val f = File(if (name.startsWith("/")) name else cwd.path + "/" + name)
        return if (f.isAbsolute) f else File(cwd, name)
    }
}

/** Builds a [LocalFs] rooted at [root] for every FTP user. */
class LocalFsFactory(private val root: File) : FileSystemFactory {
    override fun createFileSystemView(user: User): FileSystemView = LocalFs(root)
}

private class LocalFtpFile(private val file: File) : FtpFile {
    override fun getAbsolutePath(): String = file.absolutePath
    override fun getName(): String = if (file == file.parentFile) "/" else file.name
    override fun isHidden(): Boolean = file.isHidden
    override fun isDirectory(): Boolean = file.isDirectory
    override fun isFile(): Boolean = file.isFile
    override fun doesExist(): Boolean = file.exists()
    override fun isReadable(): Boolean = file.canRead()
    override fun isWritable(): Boolean =
        // 上传目标通常是尚不存在的文件:File.canWrite() 对不存在文件恒为 false,
        // 会导致 MINA 误判"不可写"而拒绝上传(550)。此时应看父目录是否可写。
        if (file.exists()) file.canWrite() else (file.parentFile?.canWrite() ?: false)
    override fun isRemovable(): Boolean = true
    override fun getOwnerName(): String = "filebridge"
    override fun getGroupName(): String = "filebridge"
    override fun getLinkCount(): Int = if (file.isDirectory) 2 else 1
    override fun getLastModified(): Long = file.lastModified()
    override fun setLastModified(time: Long): Boolean = file.setLastModified(time)
    override fun getSize(): Long = file.length()
    override fun getPhysicalFile(): Any = file
    override fun mkdir(): Boolean = file.mkdirs()
    override fun delete(): Boolean = file.delete()
    override fun move(destination: FtpFile): Boolean =
        (destination as? LocalFtpFile)?.let { file.renameTo(it.file) } ?: false

    override fun listFiles(): List<FtpFile> =
        file.listFiles().orEmpty().sortedBy { it.isFile }.map { LocalFtpFile(it) }

    override fun createOutputStream(offset: Long): OutputStream {
        if (offset != 0L) throw IOException("random offset unsupported")
        return FileOutputStream(file)
    }

    override fun createInputStream(offset: Long): InputStream {
        if (offset != 0L) throw IOException("random offset unsupported")
        return FileInputStream(file)
    }
}