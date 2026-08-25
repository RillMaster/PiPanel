package com.rillmaster.pipanel

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.text.SpannableStringBuilder
import androidx.compose.runtime.MutableIntState
import androidx.core.content.FileProvider
import androidx.fragment.app.FragmentActivity
import com.rillmaster.pipanel.update.ChangelogParser
import com.rillmaster.pipanel.update.MarkdownRenderer
import com.rillmaster.pipanel.update.UpdateApi
import kotlinx.coroutines.*
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.time.Duration.Companion.seconds

class UpdateManager(private val context: Context) {

    private val retrofit = Retrofit.Builder()
        .baseUrl("https://raw.githubusercontent.com/")
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    private val api = retrofit.create(UpdateApi::class.java)

    companion object {
        const val VERSION_URL =
            "https://raw.githubusercontent.com/RillMaster/PiPanel/main/version.json"
        const val CHANGELOG_URL =
            "https://raw.githubusercontent.com/RillMaster/PiPanel/main/changelog.md"
    }

    // ── Vérification des mises à jour ─────────────────────────────────────────
    fun checkForUpdates(
        activity: FragmentActivity,
        scope: CoroutineScope,
        downloadProgress: MutableIntState
    ) {
        scope.launch {
            try {
                val timestamp = System.currentTimeMillis()
                val versionInfo = api.getVersion("${VERSION_URL}?t=$timestamp")
                val latestVersionCode = versionInfo.versionCode
                val latestVersionName = versionInfo.versionName
                val apkUrl = versionInfo.url

                val currentVersionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    activity.packageManager.getPackageInfo(activity.packageName, 0).longVersionCode
                } else {
                    @Suppress("DEPRECATION")
                    activity.packageManager.getPackageInfo(activity.packageName, 0).versionCode.toLong()
                }

                if (latestVersionCode > currentVersionCode) {
                    val changelog = try {
                        api.getChangelog("${CHANGELOG_URL}?t=$timestamp").string().trim()
                    } catch (_: Exception) { "" }
                    showUpdateDialog(activity, scope, downloadProgress, changelog, apkUrl, latestVersionName)
                }
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    private fun showUpdateDialog(
        activity: FragmentActivity,
        scope: CoroutineScope,
        downloadProgress: MutableIntState,
        changelog: String,
        downloadUrl: String,
        latestVersion: String
    ) {
        val section = ChangelogParser.extractVersionChangelog(changelog, latestVersion)
        val message = SpannableStringBuilder().apply {
            append(activity.getString(R.string.update_message, latestVersion))
            if (section.isNotEmpty()) {
                append(activity.getString(R.string.update_changelog, ""))
                append(MarkdownRenderer.renderMarkdown(section))
            }
            append(activity.getString(R.string.update_prompt))
        }
        android.app.AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.update_available_title))
            .setMessage(message)
            .setPositiveButton(activity.getString(R.string.update_action_now)) { _, _ ->
                downloadProgress.intValue = 0
                downloadAndInstall(downloadUrl) { progress ->
                    downloadProgress.intValue = progress
                    if (progress == 100) {
                        scope.launch { delay(3.seconds); downloadProgress.intValue = -2 }
                    }
                }
            }
            .setNegativeButton(activity.getString(R.string.update_action_later), null)
            .show()
    }

    fun downloadAndInstall(url: String, onProgress: (Int) -> Unit) {

        val downloadDir = File(context.getExternalFilesDir(null), "my_download").apply {
            if (!exists()) mkdirs()
        }

        val apkFile = File(downloadDir, "update.apk")
        if (apkFile.exists()) apkFile.delete()

        CoroutineScope(Dispatchers.IO).launch {
            try {

                var connection = openConnection(url)
                var redirects = 0

                // gestion redirections GitHub
                while (connection.responseCode in 300..399 && redirects < 10) {
                    val newUrl = connection.getHeaderField("Location")
                        ?: throw Exception("Redirect sans Location")

                    connection.disconnect()
                    connection = openConnection(newUrl)
                    redirects++
                }

                if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                    connection.disconnect()
                    withContext(Dispatchers.Main) { onProgress(-1) }
                    return@launch
                }

                val contentType = connection.contentType
                println("CONTENT-TYPE = $contentType")

                val total = connection.contentLengthLong
                var downloaded = 0L

                connection.inputStream.use { input ->
                    FileOutputStream(apkFile).use { output ->
                        val buffer = ByteArray(8192)
                        var bytes: Int

                        while (input.read(buffer).also { bytes = it } != -1) {
                            output.write(buffer, 0, bytes)
                            downloaded += bytes

                            if (total > 0) {
                                val progress = (downloaded * 100 / total).toInt()
                                withContext(Dispatchers.Main) {
                                    onProgress(progress)
                                }
                            }
                        }
                    }
                }

                connection.disconnect()

                // 🔴 Vérification critique
                if (!apkFile.exists() || apkFile.length() < 100000) {
                    throw Exception("APK invalide (trop petit ou inexistant)")
                }

                withContext(Dispatchers.Main) {
                    onProgress(100)
                    installApk(apkFile)
                }

            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) { onProgress(-1) }
            }
        }
    }

    private fun openConnection(url: String): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection

        connection.apply {
            requestMethod = "GET"
            connectTimeout = 15000
            readTimeout = 60000
            instanceFollowRedirects = true

            // 🔥 IMPORTANT pour GitHub
            setRequestProperty("User-Agent", "Mozilla/5.0")
            setRequestProperty("Accept", "*/*")
        }

        return connection
    }

    private fun installApk(file: File) {

        if (!file.exists()) {
            throw Exception("APK introuvable: ${file.absolutePath}")
        }

        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        context.startActivity(intent)
    }
}