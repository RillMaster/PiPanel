package com.example.raspberrycontroller

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import com.example.raspberrycontroller.R.string.error_binary_file
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    onClose: () -> Unit,
    onOpenMenu: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var currentPath by remember { mutableStateOf(settings.username.let { if (it == "root") "/root" else "/home/$it" }) }
    var files by remember { mutableStateOf<List<RemoteFile>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var isFileLoading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    
    // États pour l'édition
    var editingFile by remember { mutableStateOf<RemoteFile?>(null) }
    var fileContent by remember { mutableStateOf("") }
    
    // États pour les messages
    val snackbarHostState = remember { SnackbarHostState() }

    // ── Vérification type de fichier ──────────────────────────────────────────
    fun isTextFile(fileName: String): Boolean {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        val textExtensions = setOf(
            "txt", "conf", "sh", "py", "yml", "yaml", "json", "xml", "md", "cfg", 
            "log", "ini", "local", "service", "env", "php", "js", "html", "css"
        )
        if (textExtensions.contains(ext)) return true
        
        val binaryExtensions = setOf(
            "jpg", "jpeg", "png", "gif", "bmp", "mp4", "mkv", "avi", "mov", "pdf",
            "zip", "tar", "gz", "deb", "iso", "bin", "exe", "apk"
        )
        return !binaryExtensions.contains(ext)
    }

    // ── Chargement des fichiers ───────────────────────────────────────────────
    fun refresh() {
        scope.launch {
            isLoading = true
            error = null
            try {
                val cmd = "sudo ls -Ahl --group-directories-first \"$currentPath\""
                val raw = withContext(Dispatchers.IO) {
                    SshClient.execute(settings.host, settings.port, settings.username, settings.password, cmd, settings.sshTimeoutMs)
                }
                
                if (raw.startsWith("[err]")) {
                    error = raw
                } else {
                    val lines = raw.lines().drop(1).filter { it.isNotBlank() } // Drop "total X"
                    files = lines.mapNotNull { line ->
                        // Format: drwxr-xr-x 2 user group 4.0K Jan 1 10:00 folder
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
                }
            } catch (e: Exception) {
                error = e.message
            } finally {
                isLoading = false
            }
        }
    }

    LaunchedEffect(currentPath) { refresh() }

    // Gestion du bouton retour pour naviguer vers le parent
    BackHandler(enabled = editingFile == null && currentPath != "/") {
        currentPath = currentPath.substringBeforeLast("/").ifEmpty { "/" }
    }

    if (editingFile != null) {
        TextEditorScreen(
            filePath = editingFile!!.path,
            initialContent = fileContent,
            isLoading = isFileLoading,
            onSave = { content ->
                val success = RemoteFileHelper.writeFile(settings, editingFile!!.path, content)
                if (success) fileContent = content
                success
            },
            onClose = { editingFile = null }
        )
    } else {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(stringResource(R.string.nav_file_manager), fontWeight = FontWeight.Bold)
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
                        IconButton(onClick = { refresh() }) { Icon(Icons.Default.Refresh, null) }
                    }
                )
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
                        Button(onClick = { refresh() }, modifier = Modifier.padding(top = 16.dp)) { Text(stringResource(R.string.action_retry)) }
                    }
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(files) { file ->
                            FileItem(
                                file = file,
                                onClick = {
                                    if (file.isDirectory) currentPath = file.path else
                                        if (isTextFile(file.name)) {
                                            // Charger pour édition
                                            scope.launch {
                                                isFileLoading = true
                                                editingFile = file
                                                fileContent = RemoteFileHelper.readFile(settings, file.path)
                                                isFileLoading = false
                                            }
                                        } else {
                                            val msg = context.getString(error_binary_file)
                                            scope.launch {
                                                snackbarHostState.showSnackbar(msg)
                                            }
                                        }
                                },
                                onDelete = {
                                    scope.launch {
                                        val cmd = if (file.isDirectory) "sudo rm -rf \"${file.path}\"" else "sudo rm \"${file.path}\""
                                        SshClient.execute(settings.host, settings.port, settings.username, settings.password, cmd)
                                        refresh()
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

@Composable
private fun FileItem(
    file: RemoteFile,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }

    ListItem(
        modifier = Modifier.clickable { onClick() },
        headlineContent = { Text(file.name, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text("${file.size} • ${file.date}", style = MaterialTheme.typography.labelSmall) },
        leadingContent = {
            val icon = if (file.isDirectory) Icons.Default.Folder else Icons.Default.Description
            val tint = if (file.isDirectory) Color(0xFFFFA000) else MaterialTheme.colorScheme.primary
            Icon(icon, null, tint = tint)
        },
        trailingContent = {
            Box {
                IconButton(onClick = { showMenu = true }) { Icon(Icons.Default.MoreVert, null) }
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
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
