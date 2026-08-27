package com.rillmaster.pipanel

import android.util.Base64

object RemoteFileHelper {
    suspend fun readFile(settings: SettingsManager, filePath: String): String =
        SshClient.execute(host = settings.host, port = settings.port, user = settings.username, password = settings.password, command = "cat \"$filePath\" 2>/dev/null || true")

    suspend fun writeFile(settings: SettingsManager, filePath: String, content: String): Boolean {
        val encoded = Base64.encodeToString(content.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val tmpPath = "/tmp/.editor_${System.currentTimeMillis()}"
        val cmd = "printf '%s' '$encoded' | base64 -d > \"$tmpPath\" && mv \"$tmpPath\" \"$filePath\""
        val result = SshClient.execute(host = settings.host, port = settings.port, user = settings.username, password = settings.password, command = cmd)
        return !result.startsWith("[err]")
    }

    suspend fun getThumbnail(settings: SettingsManager, filePath: String, type: String): android.graphics.Bitmap? {
        val targetPath = if (type == "folder") {
            val findCmd = "find \"$filePath\" -maxdepth 1 -type f \\( -iname \"*.jpg\" -o -iname \"*.png\" -o -iname \"*.jpeg\" \\) | head -n 1"
            val found = SshClient.execute(settings.host, settings.port, settings.username, settings.password, findCmd).trim()
            if (found.isEmpty() || found.startsWith("[err]")) return null
            found
        } else {
            filePath
        }

        val cmd = when (type) {
            "video" -> "ffmpeg -i \"$targetPath\" -ss 00:00:01 -vframes 1 -s 128:128 -f mjpeg - 2>/dev/null | base64"
            "audio" -> "ffmpeg -i \"$targetPath\" -an -vcodec copy -f mjpeg - 2>/dev/null | base64"
            "image", "folder" -> "ffmpeg -i \"$targetPath\" -vf \"scale=128:128:force_original_aspect_ratio=decrease\" -f mjpeg - 2>/dev/null | base64"
            else -> return null
        }
        val b64 = SshClient.execute(settings.host, settings.port, settings.username, settings.password, cmd).trim()
        if (b64.isEmpty() || b64.startsWith("[err]")) return null
        return try {
            val bytes = Base64.decode(b64, Base64.DEFAULT)
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (_: Exception) {
            null
        }
    }
}
