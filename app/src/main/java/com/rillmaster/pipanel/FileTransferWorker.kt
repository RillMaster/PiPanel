package com.rillmaster.pipanel

import android.content.Context
import android.os.Environment
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import com.jcraft.jsch.SftpProgressMonitor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream

class FileTransferWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val type        = inputData.getString("type")       ?: return@withContext Result.failure()
        val remotePath  = inputData.getString("remotePath") ?: return@withContext Result.failure()
        var fileName    = inputData.getString("fileName")   ?: "file"
        val isDirectory = inputData.getBoolean("isDirectory", false)
        val host        = inputData.getString("host")       ?: return@withContext Result.failure()
        val port        = inputData.getInt("port", 22)
        val user        = inputData.getString("user")       ?: return@withContext Result.failure()
        val pass        = inputData.getString("pass")       ?: return@withContext Result.failure()
        val destTreeUri = inputData.getString("destTreeUri")
        val notifId     = inputData.getInt("notifId", remotePath.hashCode())

        val title = if (type == "download") "Downloading $fileName" else "Uploading $fileName"
        NotificationHelper.updateProgress(applicationContext, notifId, title, 0)

        val monitor = object : SftpProgressMonitor {
            private var max: Long = 0
            private var current: Long = 0
            private var lastPercent = -1

            override fun init(op: Int, src: String?, dest: String?, max: Long) { this.max = max }
            override fun count(count: Long): Boolean {
                current += count
                if (max > 0) {
                    val percent = (current * 100 / max).toInt()
                    if (percent != lastPercent) {
                        lastPercent = percent
                        NotificationHelper.updateProgress(applicationContext, notifId, title, percent)
                        setProgressAsync(workDataOf("progress" to percent))
                    }
                }
                return true
            }
            override fun end() {}
        }

        return@withContext runCatching {
            val jsch    = JSch()
            val session = jsch.getSession(user, host, port)
            @Suppress("DEPRECATION")
            session.setPassword(pass)
            session.setConfig("StrictHostKeyChecking", "no")
            session.connect(10000)

            var actualRemotePath = remotePath
            var tempArchivePath: String? = null

            if (type == "download" && isDirectory) {
                val archiveName = "${fileName}_${System.currentTimeMillis()}.tar.gz"
                tempArchivePath = "/tmp/$archiveName"
                val parentDir = remotePath.substringBeforeLast("/")
                val dirName   = remotePath.substringAfterLast("/")
                val tarCmd    = "tar -czf \"$tempArchivePath\" -C \"$parentDir\" \"$dirName\""

                val exec = session.openChannel("exec") as ChannelExec
                exec.setCommand(tarCmd)
                exec.connect()
                while (!exec.isClosed) { Thread.sleep(100) }
                exec.disconnect()

                actualRemotePath = tempArchivePath
                fileName = "$fileName.tar.gz"
            }

            val channel = session.openChannel("sftp") as ChannelSftp
            channel.connect()

            if (type == "download") {
                val outputStream: OutputStream? = if (destTreeUri != null) {
                    val treeUri = destTreeUri.toUri()
                    val pickedDir = DocumentFile.fromTreeUri(applicationContext, treeUri)
                    val newFile = pickedDir?.createFile("*/*", fileName)
                    newFile?.let { applicationContext.contentResolver.openOutputStream(it.uri) }
                } else {
                    val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    val localFile = File(downloadsDir, fileName)
                    localFile.outputStream()
                }

                outputStream?.use { out ->
                    channel.get(actualRemotePath, out, monitor)
                } ?: error("Could not create output stream")

            } else {
                val uriString   = inputData.getString("localUri") ?: error("Missing localUri")
                val inputStream = applicationContext.contentResolver.openInputStream(uriString.toUri())
                inputStream?.use { input ->
                    channel.put(input, actualRemotePath, monitor)
                }
            }

            channel.disconnect()

            if (tempArchivePath != null) {
                val rmExec = session.openChannel("exec") as ChannelExec
                rmExec.setCommand("rm \"$tempArchivePath\"")
                rmExec.connect()
                while (!rmExec.isClosed) { Thread.sleep(100) }
                rmExec.disconnect()
            }

            session.disconnect()

            NotificationHelper.updateProgress(applicationContext, notifId, "Done: $fileName", 100, isDone = true)
        }.fold(
            onSuccess = { Result.success() },
            onFailure = { e ->
                NotificationHelper.updateProgress(applicationContext, notifId, "Error: $fileName", -1, isDone = true)
                Result.failure(workDataOf("error" to (e.message ?: "Unknown error")))
            }
        )
    }
}
