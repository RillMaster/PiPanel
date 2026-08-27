package com.rillmaster.pipanel

import android.annotation.SuppressLint
import android.content.pm.ActivityInfo
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.rillmaster.pipanel.ui.terminal.*
import com.rillmaster.pipanel.ui.viewmodels.TerminalViewModel
import kotlin.time.Duration.Companion.milliseconds

/**
 * Convertit un caractère tapé au clavier en code de contrôle terminal
 * (équivalent d'un Ctrl+lettre physique).
 * Renvoie null si le caractère n'a pas d'équivalent Ctrl connu.
 */
private fun ctrlCode(c: Char): String? {
    val upper = c.uppercaseChar()
    return when {
        upper in 'A'..'Z' -> (upper - 'A' + 1).toChar().toString() // Ctrl+A..Z -> 0x01..0x1A
        c == ' ' || c == '@' -> "\u0000"   // Ctrl+Space / Ctrl+@
        c == '[' -> "\u001B"               // Ctrl+[ = Esc
        c == '\\' -> "\u001C"
        c == ']' -> "\u001D"
        c == '^' -> "\u001E"
        c == '_' -> "\u001F"
        c == '?' -> "\u007F"               // Ctrl+? = Del
        else -> null
    }
}

@Composable
fun SnippetDialog(
    initialLabel: String,
    initialCommand: String,
    title: String,
    onConfirm: (label: String, command: String) -> Unit,
    onDelete: (() -> Unit)? = null,
    onDismiss: () -> Unit
) {
    var label by remember { mutableStateOf(initialLabel) }
    var command by remember { mutableStateOf(initialCommand) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Label") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = command,
                    onValueChange = { command = it },
                    label = { Text("Command") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2
                )
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(label, command) }, enabled = label.isNotBlank() && command.isNotBlank()) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            Row {
                if (onDelete != null) {
                    TextButton(onClick = onDelete) {
                        Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(
    settings: SettingsManager,
    onClose: () -> Unit,
    onOpenMenu: () -> Unit,
    showNavigationIcon: Boolean = true
) {
    val context        = LocalContext.current
    val viewModel      = remember { TerminalViewModel(settings) }
    val uiState by viewModel.uiState.collectAsState()
    val emulator       = viewModel.emulator

    val scope          = rememberCoroutineScope()
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }


    fun forceShowKeyboard() {
        focusRequester.requestFocus()
        keyboardController?.show()
    }


    var ctrlActive  by remember { mutableStateOf(false) }
    var altActive   by remember { mutableStateOf(false) }
    var isLandscape by remember { mutableStateOf(false) }
    var showBars    by remember { mutableStateOf(true) }
    var fontSize    by remember { mutableFloatStateOf(13f) }

    var termCols   by remember { mutableIntStateOf(80) }
    var termRows   by remember { mutableIntStateOf(24) }

    val commandHistory   = viewModel.commandHistory
    val localShortcuts   = remember { settings.sshShortcuts.toMutableStateList() }
    var typedLine        by remember { mutableStateOf("") }

    val showAddSnippet       = remember { mutableStateOf(false) }
    val editSnippetIndex     = remember { mutableStateOf<Int?>(null) }

    // Recherche d'historique (Ctrl+R)
    var isHistorySearchActive by remember { mutableStateOf(false) }
    var historySearchQuery    by remember { mutableStateOf("") }
    val filteredHistory = remember(historySearchQuery, commandHistory.entries) {
        if (historySearchQuery.isEmpty()) emptyList()
        else commandHistory.entries.asReversed().filter { it.contains(historySearchQuery, ignoreCase = true) }.take(10)
    }

    var cursorVisible by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        viewModel.connect(context)
        delay(500.milliseconds)
        forceShowKeyboard()
        while (true) { delay(530.milliseconds); cursorVisible = !cursorVisible }
    }

    val screenLines        = remember(uiState.renderTick) { emulator.toScreenLines() }
    val cursorRow          = emulator.cursorRow
    val cursorCol          = emulator.cursorCol
    val scrollbackSnapshot = remember(uiState.renderTick) { emulator.scrollback.toList() }
    val totalLines         = scrollbackSnapshot.size + screenLines.size
    val listState          = rememberLazyListState()

    LaunchedEffect(totalLines) {
        if (totalLines > 0) try { listState.scrollToItem(totalLines - 1) } catch (_: Exception) {}
    }

    LaunchedEffect(termCols, termRows) {
        viewModel.resize(termCols, termRows)
    }

    fun toggleRotation() {
        val activity = context as? android.app.Activity
        if (isLandscape) {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            isLandscape = false
        } else {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            isLandscape = true
        }
    }

    fun pasteFromClipboard() {
        val clip = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val text = clip.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
        if (text.isNotEmpty()) viewModel.sendRaw(text)
    }

    fun sendCommand(cmd: String) {
        if (cmd.isNotBlank()) {
            viewModel.sendCommand(cmd)
        }
    }

    fun runShortcut(shortcut: SshShortcut) {
        if (!uiState.isConnected) return
        shortcut.commands.forEach { cmd ->
            viewModel.sendCommand(cmd)
        }
    }

    // Gère la saisie clavier en tenant compte des modificateurs Ctrl/Alt.
    // Quand Ctrl ou Alt est actif, le PROCHAIN caractère tapé n'est plus ajouté
    // au champ de commande : il est converti en séquence de contrôle et envoyé
    // directement au terminal (comportement attendu, cf. le bandeau
    // "Ctrl actif — tapez une lettre").
    fun handleTypedLineChange(newValue: String) {
        val isSingleInsertion = newValue.length == typedLine.length + 1 && newValue.startsWith(typedLine)

        if ((ctrlActive || altActive) && isSingleInsertion) {
            val typedChar = newValue.last()

            if (uiState.isConnected) {
                when {
                    ctrlActive -> ctrlCode(typedChar)?.let { viewModel.sendRaw(it) }
                    altActive  -> viewModel.sendRaw("\u001B$typedChar")
                }
            }

            // Le modificateur ne s'applique qu'à une seule frappe.
            ctrlActive = false
            altActive = false
            typedLine = ""
            forceShowKeyboard()
        } else {
            typedLine = newValue
        }
    }

    BackHandler {
        viewModel.disconnect()
        onClose()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            settings.profiles.find { it.id == settings.currentProfileId }?.name ?: "Terminal",
                            fontWeight = FontWeight.Bold,
                            fontSize   = 16.sp,
                            color      = Color.White,
                            maxLines   = 1,
                            overflow   = TextOverflow.Ellipsis
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val dotColor = when {
                                uiState.isConnected                                -> TerminalGreen
                                uiState.isReconnecting                             -> Color.Yellow
                                else                                               -> Color.Red
                            }
                            Box(modifier = Modifier.size(6.dp).background(dotColor, RoundedCornerShape(50)))
                            Spacer(Modifier.width(6.dp))
                            Text(uiState.status, fontSize = 10.sp, color = Color.Gray, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                },
                navigationIcon = {
                    if (showNavigationIcon) {
                        IconButton(onClick = onOpenMenu) {
                            Icon(Icons.Default.Menu, contentDescription = stringResource(R.string.open_menu), tint = TerminalGreen)
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { if (fontSize > 9f)  fontSize -= 1f }) {
                        Text("A−", color = TerminalGreen.copy(0.75f), fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                    }
                    IconButton(onClick = { if (fontSize < 22f) fontSize += 1f }) {
                        Text("A+", color = TerminalGreen.copy(0.75f), fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                    }
                    IconButton(onClick = { emulator.fullReset(); viewModel.resize(termCols, termRows) }) {
                        Icon(Icons.Default.DeleteSweep, contentDescription = "Clear", tint = TerminalGreen.copy(0.75f))
                    }
                    IconButton(
                        onClick = { if (uiState.isConnected) pasteFromClipboard() },
                        enabled = uiState.isConnected
                    ) {
                        Icon(
                            Icons.Default.ContentPaste,
                            contentDescription = stringResource(R.string.action_paste),
                            tint = if (uiState.isConnected) TerminalGreen.copy(0.75f) else Color(0xFF3A3A3A)
                        )
                    }
                    if (!uiState.isConnected && !uiState.isReconnecting) {
                        IconButton(onClick = {
                            viewModel.connect(context)
                        }) {
                            Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.action_reconnect), tint = Color.Yellow)
                        }
                    }
                    if (isLandscape) {
                        IconButton(onClick = { showBars = !showBars }) {
                            Icon(
                                if (showBars) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
                                contentDescription = stringResource(R.string.action_bars),
                                tint = TerminalGreen.copy(0.75f)
                            )
                        }
                    }
                    IconButton(onClick = { toggleRotation() }) {
                        Icon(
                            Icons.Default.ScreenRotation,
                            contentDescription = stringResource(R.string.action_rotation),
                            tint = if (isLandscape) TerminalGreen else TerminalGreen.copy(0.45f)
                        )
                    }
                    IconButton(onClick = {
                        viewModel.disconnect()
                        onClose()
                    }) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.action_close), tint = TerminalGreen)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = TerminalBg)
            )
        },
        bottomBar = {
            if (showBars) {
                Surface(
                    color = TerminalBg,
                    tonalElevation = 4.dp,
                    modifier = Modifier.imePadding()
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        // 1. Barre Ctrl/Alt/Esc/Tab
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            TerminalKeyButton("Ctrl", active = ctrlActive) {
                                ctrlActive = !ctrlActive
                                if (ctrlActive) altActive = false
                                forceShowKeyboard()
                            }
                            TerminalKeyButton("Alt", active = altActive) {
                                altActive = !altActive
                                if (altActive) ctrlActive = false
                                forceShowKeyboard()
                            }
                            SPECIAL_KEYS.forEach { (label, code) ->
                                TerminalKeyButton(label) {
                                    viewModel.sendRaw(code)
                                    forceShowKeyboard()
                                }
                            }
                        }

                        // 2. Barre des raccourcis (ls, top...)
                        ScrollableRow(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            localShortcuts.forEachIndexed { idx, snip ->
                                val color = shortcutColor(idx)
                                Button(
                                    onClick = { runShortcut(snip) },
                                    modifier = Modifier.height(32.dp),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                                    shape = RoundedCornerShape(8.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = color.copy(alpha = 0.15f),
                                        contentColor = color
                                    ),
                                    border = BorderStroke(1.dp, color.copy(alpha = 0.3f))
                                ) {
                                    Text(snip.label, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                            IconButton(
                                onClick = { showAddSnippet.value = true },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(Icons.Default.Add, null, tint = TerminalGreen, modifier = Modifier.size(18.dp))
                            }
                        }

                        // 3. Zone de saisie principale (en bas, proche du clavier)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color.Black.copy(0.3f))
                                .padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = typedLine,
                                onValueChange = ::handleTypedLineChange,
                                modifier = Modifier
                                    .weight(1f)
                                    .focusRequester(focusRequester),
                                textStyle = TextStyle(
                                    color = TerminalGreen,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 15.sp
                                ),
                                placeholder = { Text("Command...", color = Color.Gray, fontSize = 14.sp) },
                                leadingIcon = { Text("> ", color = TerminalGreen, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 8.dp)) },
                                trailingIcon = {
                                    if (typedLine.isNotEmpty()) {
                                        IconButton(onClick = { typedLine = "" }) {
                                            Icon(Icons.Default.Clear, null, tint = Color.Gray, modifier = Modifier.size(18.dp))
                                        }
                                    }
                                },
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = TerminalGreen,
                                    unfocusedBorderColor = Color.DarkGray,
                                    cursorColor = TerminalGreen
                                ),
                                keyboardOptions = KeyboardOptions(
                                    autoCorrect = false,
                                    keyboardType = KeyboardType.Ascii,
                                    imeAction = ImeAction.Send
                                ),
                                keyboardActions = KeyboardActions(onSend = {
                                    if (typedLine.isNotEmpty()) {
                                        viewModel.sendCommand(typedLine)
                                        typedLine = ""
                                    }
                                }),
                                singleLine = true,
                                shape = RoundedCornerShape(12.dp)
                            )

                            Spacer(Modifier.width(8.dp))

                            IconButton(
                                onClick = {
                                    if (typedLine.isNotEmpty()) {
                                        viewModel.sendCommand(typedLine)
                                        typedLine = ""
                                    }
                                },
                                enabled = uiState.isConnected && typedLine.isNotEmpty(),
                                modifier = Modifier
                                    .size(48.dp)
                                    .background(
                                        if (typedLine.isNotEmpty()) TerminalGreen.copy(0.2f) else Color.Transparent,
                                        RoundedCornerShape(12.dp)
                                    )
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Filled.Send,
                                    contentDescription = "Send",
                                    tint = if (uiState.isConnected && typedLine.isNotEmpty()) TerminalGreen else Color.Gray
                                )
                            }
                        }
                    }
                }
            }
        }
    ) { padding ->
        val density = LocalDensity.current
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(TerminalBg)
                .onSizeChanged { size ->
                    val cw = with(density) { fontSize.sp.toPx() * 0.6f }
                    val ch = with(density) { fontSize.sp.toPx() * 1.2f }
                    termCols = (size.width / cw).toInt().coerceAtLeast(10)
                    termRows = (size.height / ch).toInt().coerceAtLeast(5)
                }
                .pointerInput(Unit) {
                    detectTapGestures { forceShowKeyboard() }
                }
        ) {
            SelectionContainer {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp)
                ) {
                    items(scrollbackSnapshot.size, key = { "sb_$it" }) { idx ->
                        Text(
                            text = scrollbackSnapshot[idx],
                            style = TextStyle(
                                color      = TERM_DEFAULT_FG,
                                fontFamily = FontFamily.Monospace,
                                fontSize   = fontSize.sp,
                                lineHeight = (fontSize * 1.2f).sp
                            )
                        )
                    }
                    items(screenLines.size, key = { "sr_$it" }) { idx ->
                        val line = screenLines[idx]
                        val showCursorHere = uiState.isConnected && idx == cursorRow && cursorVisible && emulator.getCursorVisible()

                        Box {
                            Text(
                                text = line,
                                style = TextStyle(
                                    color      = TERM_DEFAULT_FG,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize   = fontSize.sp,
                                    lineHeight = (fontSize * 1.2f).sp
                                )
                            )
                            if (showCursorHere) {
                                val cw = with(density) { fontSize.sp.toPx() * 0.6f }
                                Box(
                                    modifier = Modifier
                                        .offset(x = with(density) { (cursorCol * cw).toDp() })
                                        .size(with(density) { (cw / 1.5f).toDp() }, fontSize.dp)
                                        .background(TerminalGreen.copy(0.5f))
                                )
                            }
                        }
                    }
                }
            }

            // Overlays (Ctrl/Alt indicators)
            if (ctrlActive || altActive) {
                Surface(
                    modifier = Modifier.align(Alignment.TopEnd).padding(16.dp),
                    color = Color.Black.copy(0.7f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        if (ctrlActive) stringResource(R.string.ctrl_active_msg) else stringResource(R.string.alt_active_msg),
                        color = TerminalGreen,
                        modifier = Modifier.padding(8.dp),
                        fontSize = 12.sp
                    )
                }
            }
        }
    }

    if (showAddSnippet.value) {
        SnippetDialog(
            initialLabel = "",
            initialCommand = "",
            title = stringResource(R.string.snippet_new_title),
            onConfirm = { l, c ->
                val news = SshShortcut(id = java.util.UUID.randomUUID().toString(), label = l, commands = listOf(c))
                localShortcuts.add(news)
                settings.sshShortcuts = localShortcuts.toList()
                showAddSnippet.value = false
            },
            onDismiss = { showAddSnippet.value = false }
        )
    }
}

@Composable
private fun TerminalKeyButton(
    label: String,
    active: Boolean = false,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        color = if (active) TerminalGreen else Color.White.copy(0.1f),
        contentColor = if (active) Color.Black else Color.White,
        shape = RoundedCornerShape(4.dp),
        modifier = Modifier.height(32.dp).widthIn(min = 40.dp)
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 8.dp)) {
            Text(label, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
private fun ScrollableRow(
    modifier: Modifier = Modifier,
    horizontalArrangement: Arrangement.Horizontal = Arrangement.Start,
    content: @Composable () -> Unit
) {
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = horizontalArrangement,
        verticalAlignment = Alignment.CenterVertically
    ) {
        content()
    }
}