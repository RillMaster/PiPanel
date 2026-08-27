package com.rillmaster.pipanel

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.NoteAdd
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.rillmaster.pipanel.ui.components.MediaViewer
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.time.Duration.Companion.milliseconds

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

private fun isImageFile(fileName: String): Boolean {
    val ext = fileName.substringAfterLast('.', "").lowercase()
    return setOf("jpg", "jpeg", "png", "gif", "bmp", "webp").contains(ext)
}

private fun isVideoFile(fileName: String): Boolean {
    val ext = fileName.substringAfterLast('.', "").lowercase()
    return setOf("mp4", "mkv", "avi", "mov", "webm", "flv", "wmv", "3gp").contains(ext)
}

private fun isAudioFile(fileName: String): Boolean {
    val ext = fileName.substringAfterLast('.', "").lowercase()
    return setOf("mp3", "wav", "ogg", "flac", "m4a", "aac", "wma").contains(ext)
}

// ══════════════════════════════════════════════════════════════════════════════
//  FileManagerScreen
// ══════════════════════════════════════════════════════════════════════════════
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileManagerScreen(
    settings: SettingsManager,
    onOpenMenu: () -> Unit,
    showNavigationIcon: Boolean = true
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    val initialPath = remember { settings.username.let { if (it == "root") "/root" else "/home/$it" } }
    var currentPath by remember { mutableStateOf(initialPath) }
    var files by remember { mutableStateOf<List<RemoteFile>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var isFileLoading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showHidden by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    // Sélection multiple
    var selectedFiles by remember { mutableStateOf(setOf<RemoteFile>()) }
    val isSelectionMode = selectedFiles.isNotEmpty()

    // Favoris
    var bookmarks by remember { mutableStateOf(settings.fileManagerBookmarks) }
    var showBookmarksMenu by remember { mutableStateOf(false) }

    var editingFile by remember { mutableStateOf<RemoteFile?>(null) }
    var fileContent by remember { mutableStateOf("") }
    var mediaFile by remember { mutableStateOf<RemoteFile?>(null) }
    var mediaBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var localMediaFile by remember { mutableStateOf<File?>(null) }
    var isMediaLoading by remember { mutableStateOf(false) }
    var isMediaPreview by remember { mutableStateOf(false) }
    var isDownloadingFull by remember { mutableStateOf(false) }
    var showCreateDialog by remember { mutableStateOf<Boolean?>(null) } 
    var renamingFile by remember { mutableStateOf<RemoteFile?>(null) }
    var chmodFile    by remember { mutableStateOf<RemoteFile?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    
    var folderSizes = remember { mutableStateMapOf<String, String>() }
    var isCalculatingSize by remember { mutableStateOf<String?>(null) }
    
    val uploadStartedMsg = "Upload started in background"
    val downloadStartedMsg = stringResource(R.string.file_manager_download_success) + " (Background)"
    val binaryFileErrorMsg = stringResource(R.string.error_binary_file)

    // Launcher pour Upload
    val uploadLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { enqueueUpload(context, it, currentPath, settings, snackbarHostState, scope, uploadStartedMsg) }
    }

    // Launcher pour choisir le dossier de destination (Download)
    val folderPickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        uri?.let { treeUri ->
            context.contentResolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            selectedFiles.forEach { file ->
                enqueueDownload(context, file, treeUri.toString(), settings)
            }
            selectedFiles = emptySet()
            scope.launch { snackbarHostState.showSnackbar(downloadStartedMsg) }
        }
    }

    LaunchedEffect(currentPath, showHidden) {
        refreshFiles(settings, currentPath, showHidden, { files = it }, { error = it }, { isLoading = it })
    }

    BackHandler(enabled = isSelectionMode || (editingFile == null && currentPath != "/")) {
        if (isSelectionMode) {
            selectedFiles = emptySet()
        } else {
            currentPath = currentPath.substringBeforeLast("/").ifEmpty { "/" }
        }
    }

    if (editingFile != null) {
        TextEditorScreen(
            filePath = editingFile!!.path,
            initialContent = fileContent,
            isLoading = isFileLoading,
            onSave = { content -> RemoteFileHelper.writeFile(settings, editingFile!!.path, content) },
            onClose = { editingFile = null }
        )
    } else {
        val filteredFiles = remember(files, searchQuery) {
            if (searchQuery.isEmpty()) files 
            else files.filter { it.name.contains(searchQuery, ignoreCase = true) }
        }

        Scaffold(
            topBar = {
                if (isSelectionMode) {
                    TopAppBar(
                        title = { Text("${selectedFiles.size} selected") },
                        navigationIcon = {
                            IconButton(onClick = { selectedFiles = emptySet() }) { Icon(Icons.Default.Close, null) }
                        },
                        actions = {
                            IconButton(onClick = { folderPickerLauncher.launch(null) }) {
                                Icon(Icons.Default.FileDownload, null)
                            }
                            IconButton(onClick = {
                                scope.launch {
                                    selectedFiles.forEach { file ->
                                        val cmd = if (file.isDirectory) "sudo rm -rf \"${file.path}\"" else "sudo rm \"${file.path}\""
                                        SshClient.execute(settings.host, settings.port, settings.username, settings.password, cmd)
                                    }
                                    selectedFiles = emptySet()
                                    refreshFiles(settings, currentPath, showHidden, { files = it }, { error = it }, { isLoading = it })
                                }
                            }) {
                                Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    )
                } else {
                    TopAppBar(
                        title = {
                            Column(modifier = Modifier.clickable { showBookmarksMenu = true }) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(stringResource(R.string.nav_file_manager), fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Icon(Icons.Default.ArrowDropDown, null)
                                }
                                Text(currentPath, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            DropdownMenu(expanded = showBookmarksMenu, onDismissRequest = { showBookmarksMenu = false }) {
                                DropdownMenuItem(
                                    text = { Text("Add current to bookmarks") },
                                    onClick = { 
                                        if (!bookmarks.contains(currentPath)) {
                                            val newB = bookmarks + currentPath
                                            settings.fileManagerBookmarks = newB
                                            bookmarks = newB
                                        }
                                        showBookmarksMenu = false
                                    },
                                    leadingIcon = { Icon(Icons.Default.BookmarkAdd, null) }
                                )
                                if (bookmarks.isNotEmpty()) {
                                    HorizontalDivider()
                                    bookmarks.forEach { bPath ->
                                        DropdownMenuItem(
                                            text = { Text(bPath, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                            onClick = { currentPath = bPath; showBookmarksMenu = false },
                                            trailingIcon = {
                                                IconButton(onClick = {
                                                    val newB = bookmarks - bPath
                                                    settings.fileManagerBookmarks = newB
                                                    bookmarks = newB
                                                }) { Icon(Icons.Default.Delete, null, modifier = Modifier.size(16.dp)) }
                                            }
                                        )
                                    }
                                }
                            }
                        },
                        navigationIcon = {
                            val homePath = settings.username.let { if (it == "root") "/root" else "/home/$it" }
                            if (currentPath == "/" || currentPath == homePath) {
                                if (showNavigationIcon) {
                                    IconButton(onClick = onOpenMenu) { Icon(Icons.Default.Menu, null) }
                                }
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
                            IconButton(onClick = { refreshFiles(settings, currentPath, showHidden, { files = it }, { error = it }, { isLoading = it }) }) {
                                Icon(Icons.Default.Refresh, null)
                            }
                        }
                    )
                }
            },
            floatingActionButton = {
                if (!isSelectionMode) {
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
                }
            },
            snackbarHost = { SnackbarHost(snackbarHostState) }
        ) { padding ->
            Column(modifier = Modifier.padding(padding).fillMaxSize()) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    placeholder = { Text("Search files...") },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    trailingIcon = { if (searchQuery.isNotEmpty()) IconButton(onClick = { searchQuery = "" }) { Icon(Icons.Default.Close, null) } },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp)
                )

                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    if (isLoading) {
                        CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                    } else if (error != null) {
                        Column(modifier = Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.Error, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(48.dp))
                            Spacer(Modifier.height(16.dp))
                            Text(error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                            Button(onClick = { refreshFiles(settings, currentPath, showHidden, { files = it }, { error = it }, { isLoading = it }) }, modifier = Modifier.padding(top = 16.dp)) { Text(stringResource(R.string.action_retry)) }
                        }
                    } else if (filteredFiles.isEmpty()) {
                        Text(stringResource(R.string.file_manager_empty), modifier = Modifier.align(Alignment.Center), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(filteredFiles) { file ->
                                val isSelected = selectedFiles.contains(file)
                                FileItem(
                                    file = file,
                                    settings = settings,
                                    isSelected = isSelected,
                                    onClick = {
                                        if (isSelectionMode) {
                                            selectedFiles = if (isSelected) selectedFiles - file else selectedFiles + file
                                        } else {
                                            if (file.isDirectory) currentPath = file.path 
                                            else handleFileClick(context, file, settings, scope, snackbarHostState,
                                                onEdit = { c -> fileContent = c; editingFile = file; isFileLoading = false },
                                                onMedia = { b, preview, local -> 
                                                    mediaBitmap = b
                                                    mediaFile = file
                                                    localMediaFile = local
                                                    isMediaPreview = preview
                                                    isFileLoading = false
                                                    isMediaLoading = false 
                                                },
                                                onLoading = { isFileLoading = it; isMediaLoading = it },
                                                binaryMsg = binaryFileErrorMsg
                                            )
                                        }
                                    },
                                    onLongClick = {
                                        if (!isSelectionMode) selectedFiles = setOf(file)
                                    },
                                    onDelete = {
                                        scope.launch {
                                            val cmd = if (file.isDirectory) "sudo rm -rf \"${file.path}\"" else "sudo rm \"${file.path}\""
                                            SshClient.execute(settings.host, settings.port, settings.username, settings.password, cmd)
                                            refreshFiles(settings, currentPath, showHidden, { files = it }, { error = it }, { isLoading = it })
                                        }
                                    },
                                    onRename = { renamingFile = file },
                                    onChmod = { chmodFile = file },
                                    onDownload = {
                                        selectedFiles = setOf(file)
                                        folderPickerLauncher.launch(null)
                                    },
                                    overrideSize = folderSizes[file.path],
                                    onCalculateSize = {
                                        if (file.isDirectory) {
                                            isCalculatingSize = file.path
                                            scope.launch(Dispatchers.IO) {
                                                val res = SshClient.execute(settings.host, settings.port, settings.username, settings.password, "sudo du -sh \"${file.path}\" | cut -f1")
                                                withContext(Dispatchers.Main) {
                                                    if (!res.startsWith("[err]")) {
                                                        folderSizes[file.path] = res.trim()
                                                    }
                                                    isCalculatingSize = null
                                                }
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
    }

    // Media Viewer Dialog
    mediaFile?.let { file ->
        val isImg = isImageFile(file.name)
        val isVid = isVideoFile(file.name)
        val isAud = isAudioFile(file.name)

        if (isImg || ( (isVid || isAud) && !isMediaPreview )) {
            // Viewer Intégré (Gallery / Player)
            androidx.compose.ui.window.Dialog(
                onDismissRequest = { 
                    if (!isDownloadingFull) {
                        mediaFile = null; mediaBitmap = null; localMediaFile = null; isMediaPreview = false 
                    }
                },
                properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
            ) {
                MediaViewer(
                    fileName = file.name,
                    fileSize = file.size,
                    isImage = isImg,
                    isVideo = isVid,
                    initialBitmap = mediaBitmap,
                    localFile = localMediaFile,
                    onClose = { mediaFile = null; mediaBitmap = null; localMediaFile = null; isMediaPreview = false },
                    onDownload = { enqueueDownload(context, file, "", settings) }
                )
            }
        } else {
            // Dialogue de Preview (pour Vidéo/Audio avant téléchargement complet)
            Dialog(onDismissRequest = { 
                if (!isDownloadingFull) {
                    mediaFile = null; mediaBitmap = null; isMediaPreview = false 
                }
            }) {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(240.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center
                        ) {
                            if (isMediaLoading) {
                                CircularProgressIndicator()
                            } else if (mediaBitmap != null) {
                                Image(
                                    bitmap = mediaBitmap!!.asImageBitmap(),
                                    contentDescription = null,
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Fit
                                )
                                Icon(
                                    imageVector = if (isVid) Icons.Default.PlayCircleFilled else Icons.Default.MusicNote,
                                    contentDescription = null,
                                    tint = Color.White.copy(alpha = 0.8f),
                                    modifier = Modifier.size(64.dp)
                                )
                            }
                        }

                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(file.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${file.size} • ${file.date}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }

                        Button(
                            onClick = {
                                isDownloadingFull = true
                                scope.launch(Dispatchers.IO) {
                                    val cacheFile = File(context.cacheDir, file.name)
                                    val res = SshClient.sftpAction(settings) { sftp ->
                                        sftp.get(file.path, cacheFile.absolutePath)
                                    }
                                    withContext(Dispatchers.Main) {
                                        isDownloadingFull = false
                                        if (res.isSuccess) {
                                            localMediaFile = cacheFile
                                            isMediaPreview = false // Basculer vers le MediaViewer
                                        } else {
                                            snackbarHostState.showSnackbar("Error: ${res.exceptionOrNull()?.message}")
                                        }
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !isDownloadingFull,
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            if (isDownloadingFull) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                                Spacer(Modifier.width(12.dp))
                                Text("Downloading...")
                            } else {
                                Icon(Icons.Default.PlayArrow, null)
                                Spacer(Modifier.width(8.dp))
                                Text("Play Integrated")
                            }
                        }

                        TextButton(
                            onClick = { mediaFile = null; mediaBitmap = null; isMediaPreview = false },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !isDownloadingFull
                        ) {
                            Text("Close")
                        }
                    }
                }
            }
        }
    }

    // Create Folder/File Dialogue
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
                        refreshFiles(settings, currentPath, showHidden, { files = it }, { error = it }, { isLoading = it })
                    }
                }, enabled = name.isNotBlank()) { Text(stringResource(R.string.action_add)) }
            },
            dismissButton = { TextButton(onClick = { showCreateDialog = null }) { Text(stringResource(R.string.action_cancel)) } }
        )
    }

    // Rename Dialogue
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
                        refreshFiles(settings, currentPath, showHidden, { files = it }, { error = it }, { isLoading = it })
                    }
                }, enabled = name.isNotBlank() && name != file.name) { Text(stringResource(R.string.file_manager_rename)) }
            },
            dismissButton = { TextButton(onClick = { renamingFile = null }) { Text(stringResource(R.string.action_cancel)) } }
        )
    }

    // Chmod Dialogue
    chmodFile?.let { file ->
        var perms by remember { mutableStateOf(file.permissions) } // ex: "-rw-r--r--"
        
        fun toggle(index: Int, char: Char) {
            val arr = perms.toCharArray()
            arr[index] = if (arr[index] == char) '-' else char
            perms = String(arr)
        }

        AlertDialog(
            onDismissRequest = { chmodFile = null },
            title = { Text("Permissions: ${file.name}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("User", fontWeight = FontWeight.Bold)
                    Row {
                        FilterChip(selected = perms[1] == 'r', onClick = { toggle(1, 'r') }, label = { Text("Read") })
                        Spacer(Modifier.width(4.dp))
                        FilterChip(selected = perms[2] == 'w', onClick = { toggle(2, 'w') }, label = { Text("Write") })
                        Spacer(Modifier.width(4.dp))
                        FilterChip(selected = perms[3] == 'x', onClick = { toggle(3, 'x') }, label = { Text("Exec") })
                    }
                    Text("Group", fontWeight = FontWeight.Bold)
                    Row {
                        FilterChip(selected = perms[4] == 'r', onClick = { toggle(4, 'r') }, label = { Text("Read") })
                        Spacer(Modifier.width(4.dp))
                        FilterChip(selected = perms[5] == 'w', onClick = { toggle(5, 'w') }, label = { Text("Write") })
                        Spacer(Modifier.width(4.dp))
                        FilterChip(selected = perms[6] == 'x', onClick = { toggle(6, 'x') }, label = { Text("Exec") })
                    }
                    Text("Others", fontWeight = FontWeight.Bold)
                    Row {
                        FilterChip(selected = perms[7] == 'r', onClick = { toggle(7, 'r') }, label = { Text("Read") })
                        Spacer(Modifier.width(4.dp))
                        FilterChip(selected = perms[8] == 'w', onClick = { toggle(8, 'w') }, label = { Text("Write") })
                        Spacer(Modifier.width(4.dp))
                        FilterChip(selected = perms[9] == 'x', onClick = { toggle(9, 'x') }, label = { Text("Exec") })
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    scope.launch {
                        // Conversion -rw-r--r-- -> 644
                        fun toOctal(r: Char, w: Char, x: Char): Int {
                            var n = 0
                            if (r != '-') n += 4
                            if (w != '-') n += 2
                            if (x != '-') n += 1
                            return n
                        }
                        val octal = "${toOctal(perms[1], perms[2], perms[3])}${toOctal(perms[4], perms[5], perms[6])}${toOctal(perms[7], perms[8], perms[9])}"
                        SshClient.execute(settings.host, settings.port, settings.username, settings.password, "sudo chmod $octal \"${file.path}\"")
                        chmodFile = null
                        refreshFiles(settings, currentPath, showHidden, { files = it }, { error = it }, { isLoading = it })
                    }
                }) { Text(stringResource(R.string.action_apply)) }
            },
            dismissButton = {
                TextButton(onClick = { chmodFile = null }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }
}

private fun handleFileClick(
    context: Context,
    file: RemoteFile,
    settings: SettingsManager,
    scope: kotlinx.coroutines.CoroutineScope,
    snackbarHostState: SnackbarHostState,
    onEdit: (String) -> Unit,
    onMedia: (android.graphics.Bitmap?, Boolean, java.io.File?) -> Unit,
    onLoading: (Boolean) -> Unit,
    binaryMsg: String
) {
    val ext = file.name.substringAfterLast('.', "").lowercase()
    val isImg = isImageFile(file.name)
    val isVid = isVideoFile(file.name)
    val isAud = isAudioFile(file.name)
    val isText = setOf("txt", "conf", "sh", "py", "yml", "yaml", "json", "xml", "md", "cfg", "log", "ini", "service").contains(ext)

    if (isImg || isVid || isAud) {
        onLoading(true)
        scope.launch(Dispatchers.IO) {
            if (isImg) {
                val cacheFile = File(context.cacheDir, file.name)
                val res = SshClient.sftpAction(settings) { sftp ->
                    sftp.get(file.path, cacheFile.absolutePath)
                }
                if (res.isSuccess) {
                    val bitmap = BitmapFactory.decodeFile(cacheFile.absolutePath)
                    withContext(Dispatchers.Main) { onMedia(bitmap, false, cacheFile) }
                } else {
                    withContext(Dispatchers.Main) {
                        onLoading(false)
                        snackbarHostState.showSnackbar("Error: ${res.exceptionOrNull()?.message}")
                    }
                }
            } else {
                // Pour Vidéo/Audio, on récupère d'abord une miniature
                val type = if (isVid) "video" else if (isAud) "audio" else "image"
                val bitmap = RemoteFileHelper.getThumbnail(settings, file.path, type)
                withContext(Dispatchers.Main) {
                    onMedia(bitmap, true, null)
                }
            }
        }
    } else if (isText) {
        onLoading(true)
        scope.launch {
            val content = RemoteFileHelper.readFile(settings, file.path)
            onEdit(content)
        }
    } else {
        scope.launch { snackbarHostState.showSnackbar(binaryMsg) }
    }
}


private fun enqueueUpload(context: Context, uri: Uri, targetDir: String, settings: SettingsManager, snackState: SnackbarHostState, scope: kotlinx.coroutines.CoroutineScope, uploadMsg: String) {
    val fileName = getFileName(context, uri)
    val targetPath = if (targetDir.endsWith("/")) "$targetDir$fileName" else "$targetDir/$fileName"
    
    val workRequest = OneTimeWorkRequestBuilder<FileTransferWorker>()
        .setInputData(workDataOf(
            "type" to "upload",
            "remotePath" to targetPath,
            "fileName" to fileName,
            "localUri" to uri.toString(),
            "host" to settings.host,
            "port" to settings.port,
            "user" to settings.username,
            "pass" to settings.password
        ))
        .build()
    WorkManager.getInstance(context).enqueue(workRequest)
    scope.launch { snackState.showSnackbar(uploadMsg) }
}

private fun enqueueDownload(context: Context, file: RemoteFile, destTreeUri: String, settings: SettingsManager) {
    val workRequest = OneTimeWorkRequestBuilder<FileTransferWorker>()
        .setInputData(workDataOf(
            "type" to "download",
            "remotePath" to file.path,
            "fileName" to file.name,
            "isDirectory" to file.isDirectory,
            "destTreeUri" to destTreeUri,
            "host" to settings.host,
            "port" to settings.port,
            "user" to settings.username,
            "pass" to settings.password
        ))
        .build()
    WorkManager.getInstance(context).enqueue(workRequest)
}

private fun getFileName(context: Context, uri: Uri): String {
    var name = "file"
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (idx != -1 && cursor.moveToFirst()) name = cursor.getString(idx)
    }
    return name
}

private fun refreshFiles(
    settings: SettingsManager,
    path: String,
    showHidden: Boolean,
    onFetched: (List<RemoteFile>) -> Unit,
    onError: (String?) -> Unit,
    onLoading: (Boolean) -> Unit
) {
    onLoading(true)
    onError(null)
    @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
    kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
        try {
            // Script Python ultra-rapide pour la liste de base
            val pythonScript = """
import os, stat, datetime
path = r'''$path'''
try:
    if not os.path.exists(path):
        print('[err]Path not found')
    else:
        items = os.listdir(path)
        if not {{showHidden}}: items = [f for f in items if not f.startswith('.')]
        items.sort(key=lambda x: (not os.path.isdir(os.path.join(path, x)), x.lower()))
        
        for item in items:
            p = os.path.join(path, item)
            try:
                st = os.lstat(p)
                mode = stat.filemode(st.st_mode)
                if stat.S_ISDIR(st.st_mode):
                    try: size = str(len(os.listdir(p))) + ' items'
                    except: size = 'folder'
                else:
                    s = st.st_size
                    if s < 1024: size = str(s) + ' B'
                    elif s < 1024*1024: size = '{:.1f} KB'.format(s/1024)
                    elif s < 1024*1024*1024: size = '{:.1f} MB'.format(s/(1024*1024))
                    else: size = '{:.1f} GB'.format(s/(1024*1024*1024))
                dt = datetime.datetime.fromtimestamp(st.st_mtime).strftime('%b %d %H:%M')
                print(f'{mode}|{size}|{dt}|{item}')
            except: continue
except Exception as e: print('[err]' + str(e))
            """.trimIndent().replace("$path", path).replace("{{showHidden}}", if (showHidden) "True" else "False")

            val b64 = android.util.Base64.encodeToString(pythonScript.toByteArray(), android.util.Base64.NO_WRAP)
            val cmd = "echo '$b64' | base64 -d | python3"
            val raw = SshClient.execute(settings.host, settings.port, settings.username, settings.password, cmd, settings.sshTimeoutMs)
            
            if (raw.startsWith("[err]")) {
                withContext(Dispatchers.Main) { onError(raw.removePrefix("[err]")) }
            } else {
                val lines = raw.lines().filter { it.contains("|") }
                val fetched = lines.mapNotNull { line ->
                    val parts = line.split("|")
                    if (parts.size >= 4) {
                        val perms = parts[0]
                        val size = parts[1]
                        val date = parts[2]
                        val name = parts.drop(3).joinToString("|")
                        RemoteFile(
                            name = name,
                            path = if (path.endsWith("/")) "$path$name" else "$path/$name",
                            isDirectory = perms.startsWith("d"),
                            size = size,
                            date = date,
                            permissions = perms
                        )
                    } else null
                }
                withContext(Dispatchers.Main) { onFetched(fetched) }
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) { onError(e.message) }
        } finally {
            withContext(Dispatchers.Main) { onLoading(false) }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun FileItem(
    file: RemoteFile,
    settings: SettingsManager,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onDelete: () -> Unit,
    onRename: () -> Unit,
    onDownload: () -> Unit,
    onChmod: () -> Unit,
    overrideSize: String? = null,
    onCalculateSize: () -> Unit = {}
) {
    var showMenu by remember { mutableStateOf(false) }
    var thumbnail by remember(file.path) { mutableStateOf<android.graphics.Bitmap?>(null) }
    
    val isImg = isImageFile(file.name)
    val isVid = isVideoFile(file.name)
    val isAud = isAudioFile(file.name)
    val isMedia = !file.isDirectory && (isImg || isVid || isAud)

    if (isMedia || file.isDirectory) {
        LaunchedEffect(file.path) {
            delay(200.milliseconds) // Évite de spammer pendant le scroll rapide
            val type = if (file.isDirectory) "folder" else if (isVid) "video" else if (isAud) "audio" else "image"
            thumbnail = RemoteFileHelper.getThumbnail(settings, file.path, type)
        }
    }

    ListItem(
        modifier = Modifier
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .background(if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f) else Color.Transparent),
        headlineContent = { Text(file.name, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text("${overrideSize ?: file.size} • ${file.date}", style = MaterialTheme.typography.labelSmall) },
        leadingContent = {
            val ext = file.name.substringAfterLast('.', "").uppercase(Locale.ROOT)
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(40.dp)) {
                if (isSelected) {
                    Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp))
                } else if (thumbnail != null) {
                    Image(
                        bitmap = thumbnail!!.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)),
                        contentScale = ContentScale.Crop
                    )
                    if (isVid || isAud) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.Center)
                                .background(Color.Black.copy(alpha = 0.3f), CircleShape)
                                .padding(2.dp)
                        ) {
                            Icon(
                                if (isVid) Icons.Default.PlayArrow else Icons.Default.MusicNote,
                                null,
                                tint = Color.White,
                                modifier = Modifier.size(12.dp)
                            )
                        }
                    }
                } else {
                    val icon = if (file.isDirectory) Icons.Default.Folder else {
                        if (isImg) Icons.Default.Image
                        else if (isVid) Icons.Default.VideoFile
                        else if (isAud) Icons.Default.AudioFile
                        else {
                            when (ext.lowercase()) {
                                "sh", "py", "js", "php", "sql", "conf", "yml", "yaml", "json", "xml" -> Icons.Default.Code
                                "zip", "tar", "gz", "7z", "rar" -> Icons.Default.FolderZip
                                "pdf" -> Icons.Default.PictureAsPdf
                                else -> Icons.Default.Description
                            }
                        }
                    }
                    Icon(icon, null, tint = if (file.isDirectory) Color(0xFFFFA000) else MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
                    
                    if (!file.isDirectory && ext.isNotEmpty() && ext.length <= 4 && thumbnail == null) {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f),
                            shape = RoundedCornerShape(4.dp),
                            modifier = Modifier.align(Alignment.BottomCenter).offset(y = 4.dp)
                        ) {
                            Text(ext, style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp), modifier = Modifier.padding(horizontal = 2.dp, vertical = 1.dp))
                        }
                    }
                }
            }
        },
        trailingContent = {
            if (!isSelected) {
                Box {
                    IconButton(onClick = { showMenu = true }) { Icon(Icons.Default.MoreVert, null) }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        if (file.isDirectory) {
                            DropdownMenuItem(text = { Text("Calculate folder size") }, onClick = { showMenu = false; onCalculateSize() }, leadingIcon = { Icon(Icons.Default.Calculate, null) })
                            HorizontalDivider()
                        }
                        DropdownMenuItem(text = { Text(stringResource(R.string.file_manager_download)) }, onClick = { showMenu = false; onDownload() }, leadingIcon = { Icon(Icons.Default.FileDownload, null) })
                        DropdownMenuItem(text = { Text(stringResource(R.string.file_manager_rename)) }, onClick = { showMenu = false; onRename() }, leadingIcon = { Icon(Icons.Default.DriveFileRenameOutline, null) })
                        DropdownMenuItem(text = { Text(stringResource(R.string.file_manager_permissions)) }, onClick = { showMenu = false; onChmod() }, leadingIcon = { Icon(Icons.Default.Lock, null) })
                        DropdownMenuItem(text = { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) }, onClick = { showMenu = false; onDelete() }, leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) })
                    }
                }
            }
        }
    )
    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
}
