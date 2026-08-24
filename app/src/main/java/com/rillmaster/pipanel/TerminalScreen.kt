package com.rillmaster.pipanel

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ActivityInfo
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds

// ══════════════════════════════════════════════════════════════════════════════
//  Constantes et Couleurs
// ══════════════════════════════════════════════════════════════════════════════
private val TerminalGreen = Color(0xFF50FA7B)
private val TerminalBg    = Color(0xFF1A1A1A)

private val ANSI_COLORS = arrayOf(
    Color(0xFF000000), Color(0xFFFF5555), Color(0xFF50FA7B), Color(0xFFF1FA8C),
    Color(0xFFBD93F9), Color(0xFFFF79C6), Color(0xFF8BE9FD), Color(0xFFBFBFBF),
    Color(0xFF4D4D4D), Color(0xFFFF6E67), Color(0xFF5AF78E), Color(0xFFF4F99D),
    Color(0xFFCAA9FA), Color(0xFFFF92D0), Color(0xFF9AEDFE), Color(0xFFFFFFFF)
)
private val TERM_DEFAULT_FG = Color(0xFFF8F8F2)
private fun ansiIndex(i: Int) = ANSI_COLORS.getOrElse(i) { TERM_DEFAULT_FG }
private fun ansi256(i: Int): Color = when {
    i < 16  -> ansiIndex(i)
    i < 232 -> {
        val r = (((i - 16) / 36) * 51).coerceIn(0, 255)
        val g = ((((i - 16) % 36) / 6) * 51).coerceIn(0, 255)
        val b = (((i - 16) % 6) * 51).coerceIn(0, 255)
        Color(r, g, b)
    }
    else    -> {
        val v = (i - 232) * 10 + 8
        Color(v, v, v)
    }
}

private val SHORTCUT_COLORS = listOf(
    Color(0xFF50FA7B), Color(0xFF8BE9FD), Color(0xFFBD93F9), 
    Color(0xFFFF79C6), Color(0xFFFFD93D), Color(0xFFFF5555)
)
private fun shortcutColor(idx: Int) = SHORTCUT_COLORS[idx % SHORTCUT_COLORS.size]

private val ERROR_REGEX   = Regex("(?i)error|fail|failed|fatal|critical|severe")
private val SUCCESS_REGEX = Regex("(?i)success|succeeded|done|complete")
private val WARNING_REGEX = Regex("(?i)warning|warn|caution|alert")

private val OUTPUT_ERROR_COLOR   = Color(0xFFFF5555)
private val OUTPUT_SUCCESS_COLOR = Color(0xFF50FA7B)
private val OUTPUT_WARNING_COLOR = Color(0xFFFFD93D)

/** Retourne une couleur de teinte si la ligne brute correspond à un pattern, sinon null */
fun syntaxTintForLine(rawText: String): Color? = when {
    ERROR_REGEX.containsMatchIn(rawText)   -> OUTPUT_ERROR_COLOR
    SUCCESS_REGEX.containsMatchIn(rawText) -> OUTPUT_SUCCESS_COLOR
    WARNING_REGEX.containsMatchIn(rawText) -> OUTPUT_WARNING_COLOR
    else                                   -> null
}

// ══════════════════════════════════════════════════════════════════════════════
//  Modèles et Logique
// ══════════════════════════════════════════════════════════════════════════════
class CommandHistory(val maxSize: Int = 50) {
    private val _entries = mutableStateListOf<String>()
    val entries: List<String> get() = _entries

    private var browseIndex = -1

    fun add(cmd: String) {
        if (cmd.isNotBlank()) {
            _entries.remove(cmd)
            _entries.add(cmd)
            if (_entries.size > maxSize) _entries.removeAt(0)
        }
        reset()
    }

    fun reset() { browseIndex = _entries.size }
}

private val COMMON_COMMANDS = listOf(
    "sudo apt update", "sudo apt upgrade -y", "htop", "docker ps",
    "docker-compose up -d", "ls -lah", "df -h", "free -m", "neofetch",
    "sudo reboot", "sudo shutdown now", "tail -f /var/log/syslog",
    "ping google.com", "top", "git pull", "python3 ", "nano ", "cat ",
    "rm -rf ", "mkdir ", "chmod +x ", "ssh ", "curl ", "wget "
)

fun computeSuggestions(input: String, history: List<String>): List<String> {
    if (input.isBlank()) return emptyList()
    val all = (history.asReversed() + COMMON_COMMANDS).distinct()
    return all.filter { it.startsWith(input, ignoreCase = true) && it != input }.take(5)
}

// ── Émulateur Terminal Minimaliste ──────────────────────────────────────────
class TerminalEmulator(var cols: Int, var rows: Int) {
    data class Cell(val char: Char = ' ', val fg: Color = TERM_DEFAULT_FG, val bg: Color = Color.Transparent, val bold: Boolean = false)

    var grid = Array(rows) { Array(cols) { Cell() } }
    private var rowCache = arrayOfNulls<AnnotatedString>(rows)

    private val _scrollback = mutableListOf<AnnotatedString>()
    val scrollback: List<AnnotatedString> get() = _scrollback

    var cursorRow = 0
    var cursorCol = 0
    private var savedRow = 0
    private var savedCol = 0
    private var savedFg: Color? = null
    private var savedBg: Color? = null
    private var savedBold = false
    private var scrollTop = 0
    private var scrollBot = rows - 1

    private var currentFg: Color? = null
    private var currentBg: Color? = null
    private var bold      = false
    private var underline = false
    private var reverse   = false

    private var inEscape = false
    private var inCSI    = false
    private var inOSC    = false
    private var isCursorVisible = true
    private val buf      = StringBuilder()

    private fun markDirty(r: Int) { if (r in 0 until rows) rowCache[r] = null }

    fun resize(newCols: Int, newRows: Int) {
        if (newCols == cols && newRows == rows) return
        val newGrid = Array(newRows) { r ->
            Array(newCols) { c -> if (r < rows && c < cols) grid[r][c] else Cell() }
        }
        grid = newGrid
        rowCache = arrayOfNulls<AnnotatedString>(newRows)
        cols = newCols; rows = newRows
        scrollTop = 0; scrollBot = rows - 1
        cursorRow = cursorRow.coerceIn(0, rows - 1)
        cursorCol = cursorCol.coerceIn(0, cols - 1)
    }

    fun getCursorVisible() = isCursorVisible

    fun process(data: String) { for (c in data) processChar(c) }

    fun toScreenLines(): List<AnnotatedString> =
        List(rows) { ri -> rowCache[ri] ?: renderRow(ri).also { rowCache[ri] = it } }

    fun toScreenLinesRaw(): List<String> =
        grid.asSequence().map { row -> row.map { it.char }.joinToString("") }.toList()

    private fun processChar(c: Char) {
        when {
            inOSC    -> { if (c == '\u0007' || c == '\u001B') { inOSC = false; buf.clear() } }
            inCSI    -> {
                buf.append(c)
                if (c in '\u0040'..'\u007E') { handleCSI(buf.toString()); inCSI = false; buf.clear() }
            }
            inEscape -> {
                inEscape = false
                when (c) {
                    '[' -> { inCSI = true; buf.clear() }
                    ']' -> { inOSC = true; buf.clear() }
                    '7' -> saveCursor()
                    '8' -> restoreCursor()
                    'M' -> reverseIndex()
                    'c' -> fullReset()
                    'D' -> lineFeed()
                    'E' -> { cursorCol = 0; lineFeed() }
                    else -> {}
                }
            }
            c == '\u001B' -> inEscape = true
            c == '\r'     -> cursorCol = 0
            c == '\n'     -> lineFeed()
            c == '\u0008' -> if (cursorCol > 0) cursorCol--
            c == '\u007F' -> if (cursorCol > 0) cursorCol--
            c == '\u0009' -> {
                cursorCol = ((cursorCol / 8) + 1) * 8
                if (cursorCol >= cols) cursorCol = cols - 1
            }
            c == '\u0007' || c == '\u000E' || c == '\u000F' -> {}
            c.code >= 32  -> putChar(c)
            else          -> {}
        }
    }

    private fun lineFeed() { if (cursorRow >= scrollBot) scrollUp() else cursorRow++ }

    private fun putChar(c: Char) {
        if (cursorCol >= cols) { cursorCol = 0; lineFeed() }
        val fg = if (reverse) currentBg ?: Color.Transparent else currentFg ?: TERM_DEFAULT_FG
        val bg = if (reverse) currentFg ?: TERM_DEFAULT_FG   else currentBg ?: Color.Transparent
        grid[cursorRow][cursorCol] = Cell(c, fg, bg, bold)
        markDirty(cursorRow)
        cursorCol++
    }

    private fun scrollUp() {
        if (scrollTop == 0) {
            val rawLine = grid[scrollTop].map { it.char }.joinToString("")
            val rendered = rowCache[scrollTop] ?: renderRow(scrollTop)
            
            val tint = syntaxTintForLine(rawLine)
            val finalLine = if (tint != null) {
                buildAnnotatedString {
                    withStyle(SpanStyle(color = tint.copy(alpha = 0.85f))) { append(rendered.text) }
                }
            } else rendered

            _scrollback.add(finalLine)
            if (_scrollback.size > 5000) { _scrollback.removeAt(0) }
        }
        for (i in scrollTop until scrollBot) {
            grid[i] = grid[i + 1]
            rowCache[i] = rowCache[i + 1]
        }
        grid[scrollBot] = Array(cols) { Cell() }
        rowCache[scrollBot] = null
    }

    private fun renderRow(ri: Int): AnnotatedString = buildAnnotatedString {
        val row = grid[ri]
        var i = 0
        while (i < cols) {
            val cell = row[i]
            val start = i
            while (i < cols && row[i].fg == cell.fg && row[i].bg == cell.bg && row[i].bold == cell.bold) {
                i++
            }
            val fg = cell.fg
            val bg = if (cell.bg == Color.Transparent) Color.Unspecified else cell.bg
            withStyle(SpanStyle(color = fg, background = bg, fontWeight = if (cell.bold) FontWeight.Bold else FontWeight.Normal)) {
                for (j in start until i) append(row[j].char)
            }
        }
    }

    private fun reverseIndex() {
        if (cursorRow == scrollTop) {
            for (i in scrollBot downTo scrollTop + 1) {
                grid[i] = grid[i - 1]
                rowCache[i] = rowCache[i - 1]
            }
            grid[scrollTop] = Array(cols) { Cell() }
            rowCache[scrollTop] = null
        } else cursorRow--
    }

    private fun clearRow(ri: Int) { grid[ri] = Array(cols) { Cell() }; markDirty(ri) }

    private fun fillLine(r: Int, s: Int, e: Int) {
        val row = grid[r]
        for (i in s until minOf(e, cols)) row[i] = Cell()
        markDirty(r)
    }

    private fun handleCSI(seq: String) {
        if (seq.isEmpty()) return
        val cmd    = seq.last()
        val raw    = seq.dropLast(1)
        val pStr   = if (raw.firstOrNull() in listOf('?', '!', '>')) raw.drop(1) else raw
        val params = pStr.split(";").mapNotNull { it.toIntOrNull() }
        fun p(i: Int, d: Int = 0)  = params.getOrElse(i) { d }
        fun p1(i: Int, d: Int = 1) = params.getOrElse(i) { d }.let { if (it == 0) d else it }

        when (cmd) {
            'A'       -> cursorRow = maxOf(scrollTop, cursorRow - p1(0))
            'B'       -> cursorRow = minOf(scrollBot, cursorRow + p1(0))
            'C'       -> cursorCol = minOf(cols - 1, cursorCol + p1(0))
            'D'       -> cursorCol = maxOf(0, cursorCol - p1(0))
            'E'       -> { cursorCol = 0; cursorRow = minOf(rows - 1, cursorRow + p1(0)) }
            'F'       -> { cursorCol = 0; cursorRow = maxOf(0, cursorRow - p1(0)) }
            'G', '`'  -> cursorCol = (p1(0) - 1).coerceIn(0, cols - 1)
            'H', 'f'  -> { cursorRow = (p1(0) - 1).coerceIn(0, rows - 1); cursorCol = (p1(1) - 1).coerceIn(0, cols - 1) }
            'd'       -> cursorRow = (p1(0) - 1).coerceIn(0, rows - 1)
            'J'       -> when (p(0)) {
                0     -> {
                    fillLine(cursorRow, cursorCol, cols)
                    if (cursorRow + 1 < rows) {
                        for (r in cursorRow + 1 until rows) clearRow(r)
                    }
                }
                1     -> {
                    repeat(cursorRow) { r -> clearRow(r) }
                    fillLine(cursorRow, 0, cursorCol + 1)
                }
                2, 3  -> { repeat(rows) { r -> clearRow(r) }; cursorRow = 0; cursorCol = 0 }
            }
            'K'       -> when (p(0)) {
                0     -> fillLine(cursorRow, cursorCol, cols)
                1     -> fillLine(cursorRow, 0, cursorCol + 1)
                2     -> clearRow(cursorRow)
            }
            'L'       -> {
                val cnt = p1(0)
                for (i in scrollBot downTo cursorRow + cnt) {
                    grid[i] = grid[i - cnt]
                    rowCache[i] = rowCache[i - cnt]
                }
                for (r in cursorRow until minOf(cursorRow + cnt, scrollBot + 1)) clearRow(r)
            }
            'M'       -> {
                val cnt     = p1(0)
                val safeEnd = (scrollBot - cnt).coerceAtLeast(cursorRow)
                if (cursorRow <= safeEnd) {
                    for (i in cursorRow..safeEnd) {
                        grid[i] = grid[i + cnt]
                        rowCache[i] = rowCache[i + cnt]
                    }
                }
                val clearFrom = maxOf(cursorRow, scrollBot - cnt + 1)
                if (clearFrom <= scrollBot) {
                    for (i in clearFrom..scrollBot) clearRow(i)
                }
            }
            'P'       -> {
                val cnt = p1(0); val row = grid[cursorRow]
                for (i in cursorCol until cols) row[i] = if (i + cnt < cols) row[i + cnt] else Cell()
                markDirty(cursorRow)
            }
            '@'       -> {
                val cnt = p1(0); val row = grid[cursorRow]
                for (i in cols - 1 downTo cursorCol + cnt) row[i] = row[i - cnt]
                for (i in cursorCol until minOf(cursorCol + cnt, cols)) row[i] = Cell()
                markDirty(cursorRow)
            }
            'X'       -> fillLine(cursorRow, cursorCol, cursorCol + p1(0))
            'S'       -> repeat(p1(0)) { scrollUp() }
            'T'       -> repeat(p1(0)) { reverseIndex() }
            'r'       -> {
                scrollTop = (p1(0) - 1).coerceIn(0, rows - 1)
                scrollBot = (p1(1, rows) - 1).coerceIn(scrollTop, rows - 1)
            }
            'h'       -> if (seq.startsWith("?")) {
                when (p(0)) {
                    25 -> isCursorVisible = true
                }
            }
            'l'       -> if (seq.startsWith("?")) {
                when (p(0)) {
                    25 -> isCursorVisible = false
                }
            }
            'm'           -> handleSGR(params)
            's'           -> saveCursor()
            'u'           -> restoreCursor()
            'n'           -> {}
            else          -> {}
        }
    }

    private fun handleSGR(params: List<Int>) {
        if (params.isEmpty()) {
            currentFg = null; currentBg = null; bold = false; underline = false; reverse = false
            return
        }
        var i = 0
        while (i < params.size) {
            when (val code = params[i]) {
                0           -> { currentFg = null; currentBg = null; bold = false; underline = false; reverse = false }
                1           -> bold = true
                4           -> underline = true
                7           -> reverse = true
                22          -> bold = false
                24          -> underline = false
                27          -> reverse = false
                in 30..37   -> currentFg = ansiIndex(code - 30)
                in 40..47   -> currentBg = ansiIndex(code - 40)
                38          -> when {
                    i + 2 < params.size && params[i + 1] == 5 -> { currentFg = ansi256(params[i + 2]); i += 2 }
                    i + 4 < params.size && params[i + 1] == 2 -> { currentFg = Color(params[i + 2], params[i + 3], params[i + 4]); i += 4 }
                }
                48          -> when {
                    i + 2 < params.size && params[i + 1] == 5 -> { currentBg = ansi256(params[i + 2]); i += 2 }
                    i + 4 < params.size && params[i + 1] == 2 -> { currentBg = Color(params[i + 2], params[i + 3], params[i + 4]); i += 4 }
                }
            }
            i++
        }
    }

    private fun saveCursor()    { savedRow = cursorRow; savedCol = cursorCol; savedFg = currentFg; savedBg = currentBg; savedBold = bold }
    private fun restoreCursor() { cursorRow = savedRow; cursorCol = savedCol; currentFg = savedFg; currentBg = savedBg; bold = savedBold }

    fun fullReset() {
        for (r in 0 until rows) clearRow(r)
        cursorRow = 0; cursorCol = 0; currentFg = null; currentBg = null
        bold = false; underline = false; reverse = false
        _scrollback.clear()
        scrollTop = 0; scrollBot = rows - 1
        buf.clear(); inEscape = false; inCSI = false; inOSC = false
        rowCache.fill(null)
    }
}

val SPECIAL_KEYS = listOf(
    "Esc" to "\u001B",
    "Tab" to "\u0009",
    "Ctrl+C" to "\u0003",
    "Ctrl+D" to "\u0004",
    "Ctrl+Z" to "\u001A",
    "Ctrl+L" to "\u000C",
    "Home" to "\u001B[H",
    "End" to "\u001B[F",
    "Del" to "\u001B[3~",
    "PgUp" to "\u001B[5~",
    "PgDn" to "\u001B[6~",
    "F1" to "\u001BOP",
    "F2" to "\u001BOQ",
    "F3" to "\u001BOR",
    "F4" to "\u001BOS",
    "F5" to "\u001B[15~",
    "F10" to "\u001B[21~",
    "Up" to "\u001B[A",
    "Down" to "\u001B[B",
    "Left" to "\u001B[D",
    "Right" to "\u001B[C"
)

private const val KEEP_ALIVE_INTERVAL_MS = 30000L
private const val RECONNECT_DELAY_MS     = 3000L
private const val MAX_RECONNECT_ATTEMPTS = 5

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

// ══════════════════════════════════════════════════════════════════════════════
//  Composable principal
// ══════════════════════════════════════════════════════════════════════════════
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(
    settings: SettingsManager,
    onClose: () -> Unit,
    onOpenMenu: () -> Unit,
    showNavigationIcon: Boolean = true
) {
    val scope          = rememberCoroutineScope()
    val context        = LocalContext.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }

    val ghost = "\u200B"

    fun forceShowKeyboard() {
        focusRequester.requestFocus()
        keyboardController?.show()
    }

    val initialStatus = stringResource(R.string.status_connecting)
    var rawInput    by remember { mutableStateOf(TextFieldValue(ghost)) }
    var session     by remember { mutableStateOf<ShellSession?>(null) }
    var status      by remember { mutableStateOf(initialStatus) }
    var isConnected by remember { mutableStateOf(false) }

    var userClosedManually by remember { mutableStateOf(false) }
    var reconnectAttempt   by remember { mutableIntStateOf(0) }
    var isReconnecting     by remember { mutableStateOf(false) }

    var ctrlActive  by remember { mutableStateOf(false) }
    var altActive   by remember { mutableStateOf(false) }
    var isLandscape by remember { mutableStateOf(false) }
    var showBars    by remember { mutableStateOf(true) }
    var fontSize    by remember { mutableFloatStateOf(13f) }

    val emulator   = remember { TerminalEmulator(80, 24) }
    var renderTick by remember { mutableIntStateOf(0) }
    var termCols   by remember { mutableIntStateOf(80) }
    var termRows   by remember { mutableIntStateOf(24) }

    // ── Nouveautés ─────────────────────────────────────────────────────────────
    val commandHistory   = remember { CommandHistory() }
    val localShortcuts   = remember { settings.sshShortcuts.toMutableStateList() }

    // Dialog état
    val showAddSnippet       = remember { mutableStateOf(false) }
    val editSnippetIndex     = remember { mutableStateOf<Int?>(null) }

    // Suggestions d'autocomplétion
    var suggestions          by remember { mutableStateOf<List<String>>(emptyList()) }
    // ── Fin nouveautés ─────────────────────────────────────────────────────────

    var cursorVisible by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) { while (true) { delay(530.milliseconds); cursorVisible = !cursorVisible } }

    val screenLines        = remember(renderTick) { emulator.toScreenLines() }
    val screenLinesRaw     = remember(renderTick) { emulator.toScreenLinesRaw() }
    val cursorRow          = emulator.cursorRow
    val cursorCol          = emulator.cursorCol
    val scrollbackSnapshot = remember(renderTick) { emulator.scrollback.toList() }
    val totalLines         = scrollbackSnapshot.size + screenLines.size
    val listState          = rememberLazyListState()

    LaunchedEffect(totalLines) {
        if (totalLines > 0) try { listState.scrollToItem(totalLines - 1) } catch (_: Exception) {}
    }

    LaunchedEffect(termCols, termRows) {
        emulator.resize(termCols, termRows)
        session?.setWindowSize(termCols, termRows)
        renderTick++
    }

    var typedLine   by remember { mutableStateOf("") }

    // Recalcule les suggestions à chaque changement de typedLine
    LaunchedEffect(typedLine) {
        suggestions = computeSuggestions(typedLine, commandHistory.entries)
    }

    val statusConnected    = stringResource(R.string.status_connected_terminal)
    val statusReconnecting = stringResource(R.string.status_reconnecting, reconnectAttempt, MAX_RECONNECT_ATTEMPTS)
    val statusDisconnected = stringResource(R.string.status_disconnected)
    val statusError        = stringResource(R.string.status_error)
    val statusConnecting   = stringResource(R.string.status_connecting)

    val msgConnLostRetry   = stringResource(R.string.msg_connection_lost_retry, reconnectAttempt, MAX_RECONNECT_ATTEMPTS, RECONNECT_DELAY_MS / 1000)
    val msgReconnFailed    = stringResource(R.string.msg_reconnect_failed, MAX_RECONNECT_ATTEMPTS)
    val msgManualReconn    = stringResource(R.string.msg_manual_reconnect)
    val msgCheckSettings   = stringResource(R.string.msg_check_ssh_settings)
    val msgConnectingTo    = stringResource(R.string.msg_connecting_to, settings.host, settings.port)

    @SuppressLint("SourceLockedOrientationActivity")
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
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val item = clipboard.primaryClip?.getItemAt(0)
        item?.text?.toString()?.let { text ->
            scope.launch { session?.sendRaw(text) }
        }
    }

    fun sendRaw(s: String) {
        val sess = session
        if (sess != null && isConnected) {
            scope.launch { sess.sendRaw(s) }
        }
    }

    fun sendCommand(cmd: String) {
        commandHistory.add(cmd)
        sendRaw(cmd + "\r")
        typedLine = ""
    }

    fun runShortcut(shortcut: SshShortcut) {
        if (!isConnected) return
        shortcut.commands.forEach { cmd ->
            if (cmd.contains("{{input}}")) {
                sendCommand(cmd.replace("{{input}}", ""))
            } else {
                sendCommand(cmd)
            }
        }
    }

    fun applySuggestion(s: String) {
        typedLine = s
        rawInput = TextFieldValue(ghost + s, TextRange(ghost.length + s.length))
        suggestions = emptyList()
    }

    suspend fun connectSsh() {
        val result = SshClient.openShell(
            host     = settings.host,
            port     = settings.port,
            user     = settings.username,
            password = settings.password
        )
        result.onSuccess { sh ->
            session          = sh
            status           = statusConnected
            isConnected      = true
            isReconnecting   = false
            reconnectAttempt = 0
            
            scope.launch {
                delay(500.milliseconds)
                sh.setWindowSize(termCols, termRows)
            }

            scope.launch(Dispatchers.IO) {
                val inputStream = java.io.BufferedInputStream(sh.inputStream, 32768)
                val buffer = ByteArray(16384)
                val batch = java.io.ByteArrayOutputStream()
                var lastUpdate = System.currentTimeMillis()

                while (sh.isConnected) {
                    val n = try { inputStream.read(buffer) } catch (_: Exception) { -1 }
                    if (n < 0) break
                    if (n > 0) {
                        batch.write(buffer, 0, n)
                        val now = System.currentTimeMillis()
                        // Batching: 30ms ou buffer plein
                        if (now - lastUpdate > 30 || batch.size() > 4096) {
                            val text = batch.toString("UTF-8")
                            batch.reset()
                            lastUpdate = now
                            withContext(Dispatchers.Main) {
                                emulator.process(text)
                                renderTick++
                            }
                        }
                    } else {
                        kotlinx.coroutines.yield()
                    }
                }
                if (batch.size() > 0) {
                    val text = batch.toString("UTF-8")
                    withContext(Dispatchers.Main) {
                        emulator.process(text)
                        renderTick++
                    }
                }

                withContext(Dispatchers.Main) {
                    isConnected = false
                    session     = null
                    // ... (rest of the disconnection logic)

                    if (!userClosedManually) {
                        if (reconnectAttempt < MAX_RECONNECT_ATTEMPTS) {
                            reconnectAttempt++
                            isReconnecting = true
                            status = statusReconnecting
                            emulator.process("\r\n\u001B[33m$msgConnLostRetry\u001B[0m\r\n")
                            renderTick++
                            scope.launch {
                                delay(RECONNECT_DELAY_MS.milliseconds)
                                if (!userClosedManually) connectSsh()
                            }
                        } else {
                            isReconnecting = false
                            status = statusDisconnected
                            emulator.process("\r\n\u001B[31m$msgReconnFailed\u001B[0m\r\n")
                            renderTick++
                        }
                    } else {
                        status = statusDisconnected
                        onClose()
                    }
                }
            }
        }.onFailure { err ->
            isConnected = false
            session     = null

            if (!userClosedManually && reconnectAttempt < MAX_RECONNECT_ATTEMPTS) {
                reconnectAttempt++
                isReconnecting = true
                status = statusReconnecting
                emulator.process("\r\n\u001B[33m⚠ Échec — ${SshClient.parseError(context, err)}\u001B[0m\r\n")
                emulator.process("\u001B[33m  $msgManualReconn\u001B[0m\r\n")
                renderTick++
                delay(RECONNECT_DELAY_MS.milliseconds)
                if (!userClosedManually) connectSsh()
            } else if (!userClosedManually) {
                isReconnecting = false
                status = statusError
                emulator.process(SshClient.parseError(context, err) + "\r\n")
                emulator.process("$msgCheckSettings\r\n")
                renderTick++
            }
        }
    }

    LaunchedEffect(Unit) {
        emulator.process("$msgConnectingTo\r\n")
        renderTick++
        connectSsh()
    }

    BackHandler {
        userClosedManually = true
        session?.close()
        onClose()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            settings.profiles.find { it.id == settings.currentProfileId }?.name ?: "Terminal",
                            fontWeight = FontWeight.Bold,
                            fontSize   = 16.sp,
                            color      = Color.White
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val dotColor = when {
                                isConnected                                || status == statusConnected -> TerminalGreen
                                isReconnecting                             || status.startsWith(statusConnecting.dropLast(3)) -> Color.Yellow
                                else                                       -> Color.Red
                            }
                            Box(modifier = Modifier.size(6.dp).background(dotColor, RoundedCornerShape(50)))
                            Spacer(Modifier.width(6.dp))
                            Text(status, fontSize = 10.sp, color = Color.Gray, fontFamily = FontFamily.Monospace)
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
                    IconButton(onClick = { emulator.fullReset(); renderTick++ }) {
                        Icon(Icons.Default.DeleteSweep, contentDescription = "Clear", tint = TerminalGreen.copy(0.75f))
                    }
                    IconButton(
                        onClick = { if (isConnected) pasteFromClipboard() },
                        enabled = isConnected
                    ) {
                        Icon(
                            Icons.Default.ContentPaste,
                            contentDescription = stringResource(R.string.action_paste),
                            tint = if (isConnected) TerminalGreen.copy(0.75f) else Color(0xFF3A3A3A)
                        )
                    }
                    if (!isConnected && !isReconnecting) {
                        IconButton(onClick = {
                            reconnectAttempt   = 0
                            userClosedManually = false
                            status             = statusConnecting
                            isReconnecting     = true
                            emulator.process("\r\n\u001B[33m$msgManualReconn\u001B[0m\r\n")
                            renderTick++
                            scope.launch { connectSsh() }
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
                        userClosedManually = true
                        session?.close()
                        onClose()
                    }) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.action_close), tint = TerminalGreen)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF1A1A1A))
            )
        },
        containerColor = TerminalBg
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .background(TerminalBg)
                .imePadding()
        ) {
            if (isReconnecting) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF2A1F00))
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Row(
                        verticalAlignment     = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 2.dp, color = Color(0xFFFFD93D))
                        Text(text = status, color = Color(0xFFFFD93D), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    }
                }
            }

            // ── Zone terminal ─────────────────────────────────────────────
            Box(modifier = Modifier.weight(1f).fillMaxWidth().background(TerminalBg)) {
                val density = LocalDensity.current
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .onSizeChanged { size ->
                            if (size.width > 0 && size.height > 0) {
                                val charW = fontSize * 0.6f
                                val charH = fontSize * 1.27f
                                val boxW = size.width / density.density
                                val boxH = size.height / density.density
                                val newCols = (boxW / charW).toInt().coerceIn(20, 250)
                                val newRows = (boxH / charH).toInt().coerceIn(5, 80)
                                if (newCols != termCols || newRows != termRows) {
                                    termCols = newCols
                                    termRows = newRows
                                }
                            }
                        }
                ) {
                    BasicTextField(
                        value         = rawInput,
                        onValueChange = { nv ->
                            val old = rawInput.text
                            val new = nv.text
                            
                            if (new == ghost && old != ghost) {
                                rawInput = nv
                            } else {
                                when {
                                    new.length > old.length -> {
                                        val added = new.replace(ghost, "")
                                        if (added.isNotEmpty()) {
                                            when {
                                                ctrlActive -> {
                                                    val ch   = added.last().lowercaseChar()
                                                    val code = ch.code - 'a'.code + 1
                                                    sendRaw(if (code in 1..26) code.toChar().toString() else ch.toString())
                                                    ctrlActive = false
                                                }
                                                altActive -> {
                                                    sendRaw("\u001B${added.last()}")
                                                    altActive = false
                                                }
                                                else -> {
                                                    if (added.length > 1) {
                                                        typedLine += added
                                                    }
                                                    sendRaw(added)
                                                }
                                            }
                                        }
                                        rawInput = TextFieldValue(ghost, selection = TextRange(ghost.length))
                                    }
                                    new.length < old.length -> {
                                        val count = (old.length - new.length).coerceAtLeast(1)
                                        repeat(count) { sendRaw("\u0008") }
                                        rawInput = TextFieldValue(ghost, selection = TextRange(ghost.length))
                                    }
                                    else -> {
                                        rawInput = nv
                                    }
                                }
                            }
                        },
                        textStyle       = TextStyle(color = Color.Transparent, fontSize = 1.sp),
                        cursorBrush     = SolidColor(Color.Transparent),
                        modifier        = Modifier
                            .size(1.dp)
                            .align(Alignment.BottomStart)
                            .alpha(0f)
                            .focusRequester(focusRequester),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = {
                            sendRaw("\r")
                            rawInput = TextFieldValue(ghost, selection = TextRange(ghost.length))
                        }),
                        singleLine = false
                    )

                    val hScroll = rememberScrollState()
                    SelectionContainer(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) {
                                awaitPointerEventScope {
                                    while (true) {
                                        val event       = awaitPointerEvent()
                                        val isUp        = event.changes.all { !it.pressed }
                                        val isLongPress = event.changes.any {
                                            it.uptimeMillis - it.previousUptimeMillis > 400
                                        }
                                        if (isUp && !isLongPress) forceShowKeyboard()
                                    }
                                }
                            }
                    ) {
                        LazyColumn(
                            state    = listState,
                            modifier = Modifier
                                .fillMaxSize()
                                .horizontalScroll(hScroll)
                                .padding(horizontal = 4.dp, vertical = 2.dp)
                        ) {
                            items(scrollbackSnapshot.size, key = { "sb_$it" }) { idx ->
                                val line = scrollbackSnapshot[idx]
                                Text(
                                    text       = line,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize   = fontSize.sp,
                                    lineHeight = (fontSize * 1.27f).sp,
                                    softWrap   = false
                                )
                            }

    items(screenLines.size, key = { "sr_$it" }) { idx ->
                                val line = screenLines[idx]
                                val displayLine = if (isConnected && idx == cursorRow && cursorVisible && emulator.getCursorVisible()) {
                                    buildAnnotatedString {
                                        append(line)
                                        if (line.isNotEmpty()) {
                                            val ci = cursorCol.coerceIn(0, line.length - 1)
                                            addStyle(SpanStyle(color = TerminalBg, background = TERM_DEFAULT_FG), ci, ci + 1)
                                        } else {
                                            withStyle(SpanStyle(color = TerminalBg, background = TERM_DEFAULT_FG)) { append(" ") }
                                        }
                                    }
                                } else {
                                    val rawText = screenLinesRaw.getOrElse(idx) { "" }
                                    val tint = if (idx != cursorRow) syntaxTintForLine(rawText) else null
                                    if (tint != null) {
                                        buildAnnotatedString {
                                            withStyle(SpanStyle(color = tint.copy(alpha = 0.85f))) { append(line.text) }
                                        }
                                    } else line
                                }

                                Text(
                                    text       = displayLine,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize   = fontSize.sp,
                                    lineHeight = (fontSize * 1.27f).sp,
                                    softWrap   = false
                                )
                            }
                        }
                    }
                }
            }

            // ── Barres d'outils (Shortcuts / Ctrl / Alt) ────────────────────
            if (showBars) {
                Column(modifier = Modifier.background(Color(0xFF121212))) {
                    // Barre Suggestions
                    if (suggestions.isNotEmpty()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(vertical = 4.dp, horizontal = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            suggestions.forEach { s ->
                                AssistChip(
                                    onClick = { applySuggestion(s) },
                                    label   = { Text(s, color = TerminalGreen, fontSize = 12.sp) },
                                    colors  = AssistChipDefaults.assistChipColors(containerColor = Color(0xFF252525))
                                )
                            }
                        }
                    }

                    // Barre Ctrl / Alt / Special
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment     = Alignment.CenterVertically
                    ) {
                        FilterChip(
                            selected = ctrlActive,
                            onClick  = { ctrlActive = !ctrlActive; forceShowKeyboard() },
                            label    = { Text("CTRL", fontSize = 11.sp) },
                            colors   = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = TerminalGreen,
                                selectedLabelColor     = Color.Black,
                                containerColor         = Color(0xFF252525),
                                labelColor             = Color.LightGray
                            )
                        )
                        FilterChip(
                            selected = altActive,
                            onClick  = { altActive = !altActive; forceShowKeyboard() },
                            label    = { Text("ALT", fontSize = 11.sp) },
                            colors   = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = TerminalGreen,
                                selectedLabelColor     = Color.Black,
                                containerColor         = Color(0xFF252525),
                                labelColor             = Color.LightGray
                            )
                        )
                        
                        VerticalDivider(modifier = Modifier.height(24.dp), color = Color.DarkGray)

                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            SPECIAL_KEYS.forEach { (label, code) ->
                                AssistChip(
                                    onClick = { sendRaw(code); forceShowKeyboard() },
                                    label   = { Text(label, fontSize = 11.sp, fontWeight = FontWeight.Bold) },
                                    colors  = AssistChipDefaults.assistChipColors(
                                        containerColor = Color(0xFF252525),
                                        labelColor     = Color.White
                                    )
                                )
                            }
                        }
                    }

                    // Barre Snippets
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 8.dp, end = 8.dp, bottom = 8.dp, top = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment     = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick  = { showAddSnippet.value = true },
                            modifier = Modifier.size(32.dp).background(Color(0xFF252525), RoundedCornerShape(8.dp))
                        ) {
                            Icon(Icons.Default.Add, null, tint = TerminalGreen, modifier = Modifier.size(18.dp))
                        }

                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            localShortcuts.forEachIndexed { idx, s ->
                                val color = shortcutColor(idx)
                                AssistChip(
                                    modifier = Modifier.combinedClickable(
                                        onClick = { runShortcut(s) },
                                        onLongClick = { editSnippetIndex.value = idx }
                                    ),
                                    onClick     = { runShortcut(s) },
                                    label       = { Text(s.label, fontSize = 11.sp, fontWeight = FontWeight.Medium) },
                                    colors      = AssistChipDefaults.assistChipColors(
                                        containerColor = color.copy(alpha = 0.15f),
                                        labelColor     = color
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Dialogs
    if (showAddSnippet.value) {
        SnippetDialog(
            initialLabel   = "",
            initialCommand = "",
            title          = "Add Snippet",
            onConfirm      = { l, c ->
                val ns = SshShortcut(label = l, commands = listOf(c))
                localShortcuts.add(ns)
                settings.sshShortcuts = localShortcuts.toList()
                showAddSnippet.value = false
            },
            onDismiss = { showAddSnippet.value = false }
        )
    }

    editSnippetIndex.value?.let { idx ->
        val s = localShortcuts[idx]
        SnippetDialog(
            initialLabel   = s.label,
            initialCommand = s.commands.firstOrNull() ?: "",
            title          = "Edit Snippet",
            onConfirm      = { l, c ->
                localShortcuts[idx] = s.copy(label = l, commands = listOf(c))
                settings.sshShortcuts = localShortcuts.toList()
                editSnippetIndex.value = null
            },
            onDelete = {
                localShortcuts.removeAt(idx)
                settings.sshShortcuts = localShortcuts.toList()
                editSnippetIndex.value = null
            },
            onDismiss = { editSnippetIndex.value = null }
        )
    }
}
