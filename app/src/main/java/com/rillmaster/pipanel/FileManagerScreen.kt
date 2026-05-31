package com.rillmaster.pipanel

import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.NoteAdd
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rillmaster.pipanel.R.string.error_binary_file
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import android.provider.OpenableColumns

// ══════════════════════════════════════════════════════════════════════════════
//  Modèle de données
// ══════════════════════════════════════════════════════════════════════════════
data class RemoteFile(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: String,
    val date: String,
    val permissions: String
)

// ══════════════════════════════════════════════════════════════════════════════
//  FileManagerScreen
// ══════════════════════════════════════════════════════════════════════════════
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileManagerScreen(
    settings: SettingsManager,
    onOpenMenu: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var currentPath by remember { mutableStateOf(settings.username.let { if (it == "root") "/root" else "/home/$it" }) }
    var files by remember { mutableStateOf<List<RemoteFile>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var isFileLoading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showHidden by remember { mutableStateOf(false) }
    
    // États pour l'édition
    var editingFile by remember { mutableStateOf<RemoteFile?>(null) }
    var fileContent by remember { mutableStateOf("") }
    
    // États pour dialogues
    var showCreateDialog by remember { mutableStateOf<Boolean?>(null) } // true=folder, false=file, null=none
    var renamingFile by remember { mutableStateOf<RemoteFile?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }

    // ── Helper pour extraire le vrai nom du fichier ───────────────────────────
    fun getFileName(uri: Uri): String {
        var name = "uploaded_file"
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex != -1 && cursor.moveToFirst()) {
                name = cursor.getString(nameIndex)
            }
        }
        return name
    }

    // ── Launcher pour Upload ──────────────────────────────────────────────────
    val uploadLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            scope.launch {
                isLoading = true
                try {
                    val inputStream = context.contentResolver.openInputStream(it)
                    val fileName = getFileName(it)
                    val targetPath = if (currentPath.endsWith("/")) "$currentPath$fileName" else "$currentPath/$fileName"
                    
                    val result = SshClient.sftpAction(settings) { sftp ->
                        inputStream?.use { input ->
                            sftp.put(input, targetPath)
                        }
                    }
                    if (result.isSuccess) {
                        snackbarHostState.showSnackbar(context.getString(R.string.file_manager_upload_success))
                        refresh(settings, currentPath, showHidden, { files = it }, { error = it }, { isLoading = it })
                    } else {
                        snackbarHostState.showSnackbar(context.getString(R.string.file_manager_error_upload, result.exceptionOrNull()?.message))
                    }
                } catch (e: Exception) {
                    snackbarHostState.showSnackbar(context.getString(R.string.file_manager_error_upload, e.message))
                } finally {
                    isLoading = false
                }
            }
        }
    }

    // ── Vérification type de fichier ──────────────────────────────────────────
    fun isTextFile(fileName: String): Boolean {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        val textExtensions = setOf(
            "txt", "conf", "sh", "py", "yml", "yaml", "json", "xml", "md", "cfg", 
            "log", "ini", "local", "service", "env", "php", "js", "html", "css", "properties", "sql"
        )
        if (textExtensions.contains(ext)) return true
        val binaryExtensions = setOf(
            "jpg", "jpeg", "png", "gif", "bmp", "mp4", "mkv", "avi", "mov", "pdf",
            "zip", "tar", "gz", "deb", "iso", "bin", "exe", "apk", "rar", "7z"
        )
        return !binaryExtensions.contains(ext)
    }

    LaunchedEffect(currentPath, showHidden) {
        refresh(settings, currentPath, showHidden, { files = it }, { error = it }, { isLoading = it })
    }

    // Gestion du bouton retour pour naviguer vers le parent
    BackHandler(enabled = (editingFile == null && currentPath != "/")) {
        currentPath = currentPath.substringBeforeLast("/").ifEmpty { "/" }
    }

    if (editingFile != null) {
        TextEditorScreen(
            filePath = editingFile!!.path,
            initialContent = fileContent,
            isLoading = isFileLoading,
            onSave = { content ->
                RemoteFileHelper.writeFile(settings, editingFile!!.path, content)
            },
            onClose = { editingFile = null }
        )
    } else {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(stringResource(R.string.nav_file_manager), fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(currentPath, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    },
                    navigationIcon = {
                        val homePath = settings.username.let { if (it == "root") "/root" else "/home/$it" }
                        if (currentPath == "/" || currentPath == homePath) {
                            IconButton(onClick = onOpenMenu) { Icon(Icons.Default.Menu, null) }
                        } else {
                            IconButton(onClick = { currentPath = currentPath.substringBeforeLast("/").ifEmpty { "/" } }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, null)
                            }
                        }
                    },
                    actions = {
                        IconButton(onClick = { showHidden = !showHidden }) {
                            Icon(if (showHidden) Icons.Default.VisibilityOff else Icons.Default.Visibility, contentDescription = null)
                        }
                        IconButton(onClick = { refresh(settings, currentPath, showHidden, { files = it }, { error = it }, { isLoading = it }) }) {
                            Icon(Icons.Default.Refresh, null)
                        }
                    }
                )
            },
            floatingActionButton = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    FloatingActionButton(onClick = { uploadLauncher.launch("*/*") }, containerColor = MaterialTheme.colorScheme.secondaryContainer) {
                        Icon(Icons.Default.FileUpload, contentDescription = stringResource(R.string.file_manager_upload))
                    }
                    FloatingActionButton(onClick = { showCreateDialog = false }) {
                        Icon(Icons.AutoMirrored.Filled.NoteAdd, contentDescription = stringResource(R.string.file_manager_new_file))
                    }
                    FloatingActionButton(onClick = { showCreateDialog = true }) {
                        Icon(Icons.Default.CreateNewFolder, contentDescription = stringResource(R.string.file_manager_new_folder))
                    }
                }
            },
            snackbarHost = { SnackbarHost(snackbarHostState) }
        ) { padding ->
            Box(modifier = Modifier.padding(padding).fillMaxSize()) {
                if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                } else if (error != null) {
                    Column(modifier = Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.Error, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(48.dp))
                        Spacer(Modifier.height(16.dp))
                        Text(error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                        Button(onClick = { refresh(settings, currentPath, showHidden, { files = it }, { error = it }, { isLoading = it }) }, modifier = Modifier.padding(top = 16.dp)) { Text(stringResource(R.string.action_retry)) }
                    }
                } else if (files.isEmpty()) {
                    Text(stringResource(R.string.file_manager_empty), modifier = Modifier.align(Alignment.Center), color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(files) { file ->
                            FileItem(
                                file = file,
                                onClick = {
                                    if (file.isDirectory) currentPath = file.path else
                                        if (isTextFile(file.name)) {
                                            scope.launch {
                                                isFileLoading = true
                                                editingFile = file
                                                fileContent = RemoteFileHelper.readFile(settings, file.path)
                                                isFileLoading = false
                                            }
                                        } else {
                                            val msg = context.getString(error_binary_file)
                                            scope.launch { snackbarHostState.showSnackbar(msg) }
                                        }
                                },
                                onDelete = {
                                    scope.launch {
                                        val cmd = if (file.isDirectory) "sudo rm -rf \"${file.path}\"" else "sudo rm \"${file.path}\""
                                        SshClient.execute(settings.host, settings.port, settings.username, settings.password, cmd)
                                        refresh(settings, currentPath, showHidden, { files = it }, { error = it }, { isLoading = it })
                                    }
                                },
                                onRename = { renamingFile = file },
                                onDownload = {
                                    scope.launch {
                                        val result = SshClient.sftpAction(settings) { sftp ->
                                            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                                            val localFile = File(downloadsDir, file.name)
                                            FileOutputStream(localFile).use { out ->
                                                sftp.get(file.path, out)
                                            }
                                        }
                                        if (result.isSuccess) {
                                            snackbarHostState.showSnackbar(context.getString(R.string.file_manager_download_success))
                                        } else {
                                            snackbarHostState.showSnackbar(context.getString(R.string.file_manager_error_download, result.exceptionOrNull()?.message))
                                        }
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    // ── Dialogues ─────────────────────────────────────────────────────────────
    showCreateDialog?.let { isFolder ->
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCreateDialog = null },
            title = { Text(if (isFolder) stringResource(R.string.file_manager_new_folder) else stringResource(R.string.file_manager_new_file)) },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text(stringResource(R.string.file_manager_enter_name)) }, singleLine = true) },
            confirmButton = {
                Button(onClick = {
                    scope.launch {
                        val path = if (currentPath.endsWith("/")) "$currentPath$name" else "$currentPath/$name"
                        val cmd = if (isFolder) "mkdir -p \"$path\"" else "touch \"$path\""
                        SshClient.execute(settings.host, settings.port, settings.username, settings.password, cmd)
                        showCreateDialog = null
                        refresh(settings, currentPath, showHidden, { files = it }, { error = it }, { isLoading = it })
                    }
                }, enabled = name.isNotBlank()) { Text(stringResource(R.string.action_add)) }
            },
            dismissButton = { TextButton(onClick = { showCreateDialog = null }) { Text(stringResource(R.string.action_cancel)) } }
        )
    }

    renamingFile?.let { file ->
        var name by remember { mutableStateOf(file.name) }
        AlertDialog(
            onDismissRequest = { renamingFile = null },
            title = { Text(stringResource(R.string.file_manager_rename)) },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text(stringResource(R.string.file_manager_enter_name)) }, singleLine = true) },
            confirmButton = {
                Button(onClick = {
                    scope.launch {
                        val newPath = file.path.substringBeforeLast("/") + "/" + name
                        val cmd = "mv \"${file.path}\" \"$newPath\""
                        SshClient.execute(settings.host, settings.port, settings.username, settings.password, cmd)
                        renamingFile = null
                        refresh(settings, currentPath, showHidden, { files = it }, { error = it }, { isLoading = it })
                    }
                }, enabled = name.isNotBlank() && name != file.name) { Text(stringResource(R.string.file_manager_rename)) }
            },
            dismissButton = { TextButton(onClick = { renamingFile = null }) { Text(stringResource(R.string.action_cancel)) } }
        )
    }
}

private fun refresh(
    settings: SettingsManager,
    currentPath: String,
    showHidden: Boolean,
    onFilesFetched: (List<RemoteFile>) -> Unit,
    onError: (String?) -> Unit,
    onLoading: (Boolean) -> Unit
) {
    onLoading(true)
    onError(null)
    kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
        try {
            val flags = if (showHidden) "-Ahl" else "-hl"
            val cmd = "sudo ls $flags --group-directories-first \"$currentPath\""
            val raw = SshClient.execute(settings.host, settings.port, settings.username, settings.password, cmd, settings.sshTimeoutMs)
            
            if (raw.startsWith("[err]")) {
                withContext(Dispatchers.Main) { onError(raw) }
            } else {
                val lines = raw.lines().drop(1).filter { it.isNotBlank() }
                val fetchedFiles = lines.mapNotNull { line ->
                    val parts = line.split(Regex("\\s+"))
                    if (parts.size >= 9) {
                        val perms = parts[0]
                        val isDir = perms.startsWith("d")
                        val name = parts.drop(8).joinToString(" ")
                        RemoteFile(
                            name = name,
                            path = if (currentPath.endsWith("/")) "$currentPath$name" else "$currentPath/$name",
                            isDirectory = isDir,
                            size = parts[4],
                            date = "${parts[5]} ${parts[6]} ${parts[7]}",
                            permissions = perms
                        )
                    } else null
                }
                withContext(Dispatchers.Main) { onFilesFetched(fetchedFiles) }
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) { onError(e.message) }
        } finally {
            withContext(Dispatchers.Main) { onLoading(false) }
        }
    }
}

@Composable
private fun FileItem(
    file: RemoteFile,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onRename: () -> Unit,
    onDownload: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }

    ListItem(
        modifier = Modifier.clickable { onClick() },
        headlineContent = { Text(file.name, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { 
            Text("${file.size} • ${file.date}", style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis) 
        },
        leadingContent = {
            val icon = if (file.isDirectory) Icons.Default.Folder else {
                val ext = file.name.substringAfterLast('.', "").lowercase()
                when (ext) {
                    "sh", "py", "js", "php", "sql" -> Icons.Default.Code
                    "jpg", "jpeg", "png", "gif" -> Icons.Default.Image
                    "mp4", "mkv", "avi" -> Icons.Default.VideoFile
                    "mp3", "wav", "flac" -> Icons.Default.AudioFile
                    "zip", "tar", "gz", "7z" -> Icons.Default.FolderZip
                    "deb", "apk", "exe" -> Icons.Default.SettingsApplications
                    "pdf" -> Icons.Default.PictureAsPdf
                    else -> Icons.Default.Description
                }
            }
            val tint = if (file.isDirectory) Color(0xFFFFA000) else MaterialTheme.colorScheme.primary
            Icon(icon, null, tint = tint)
        },
        trailingContent = {
            Box {
                IconButton(onClick = { showMenu = true }) { Icon(Icons.Default.MoreVert, null) }
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.file_manager_download)) },
                        onClick = { showMenu = false; onDownload() },
                        leadingIcon = { Icon(Icons.Default.FileDownload, null) }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.file_manager_rename)) },
                        onClick = { showMenu = false; onRename() },
                        leadingIcon = { Icon(Icons.Default.DriveFileRenameOutline, null) }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) },
                        onClick = { showMenu = false; onDelete() },
                        leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) }
                    )
                }
            }
        }
    )
    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
}
