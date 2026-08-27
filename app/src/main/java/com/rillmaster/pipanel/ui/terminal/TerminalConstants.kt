package com.rillmaster.pipanel.ui.terminal

import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf

data class TerminalTheme(
    val name: String,
    val background: Color,
    val foreground: Color,
    val cursor: Color,
    val ansiColors: Array<Color>
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as TerminalTheme
        if (name != other.name) return false
        return true
    }

    override fun hashCode(): Int = name.hashCode()
}

val DraculaTheme = TerminalTheme(
    name = "Dracula",
    background = Color(0xFF282A36),
    foreground = Color(0xFFF8F8F2),
    cursor = Color(0xFF50FA7B),
    ansiColors = arrayOf(
        Color(0xFF000000), Color(0xFFFF5555), Color(0xFF50FA7B), Color(0xFFF1FA8C),
        Color(0xFFBD93F9), Color(0xFFFF79C6), Color(0xFF8BE9FD), Color(0xFFBFBFBF),
        Color(0xFF4D4D4D), Color(0xFFFF6E67), Color(0xFF5AF78E), Color(0xFFF4F99D),
        Color(0xFFCAA9FA), Color(0xFFFF92D0), Color(0xFF9AEDFE), Color(0xFFFFFFFF)
    )
)

val SolarizedDarkTheme = TerminalTheme(
    name = "Solarized Dark",
    background = Color(0xFF002B36),
    foreground = Color(0xFF839496),
    cursor = Color(0xFF93A1A1),
    ansiColors = arrayOf(
        Color(0xFF073642), Color(0xFFDC322F), Color(0xFF859900), Color(0xFFB58900),
        Color(0xFF268BD2), Color(0xFFD33682), Color(0xFF2AA198), Color(0xFFEEE8D5),
        Color(0xFF002B36), Color(0xFFCB4B16), Color(0xFF586E75), Color(0xFF657B83),
        Color(0xFF839496), Color(0xFF6C71C4), Color(0xFF93A1A1), Color(0xFFFDF6E3)
    )
)

val MonokaiTheme = TerminalTheme(
    name = "Monokai",
    background = Color(0xFF272822),
    foreground = Color(0xFFF8F8F2),
    cursor = Color(0xFFF8F8F0),
    ansiColors = arrayOf(
        Color(0xFF272822), Color(0xFFF92672), Color(0xFFA6E22E), Color(0xFFF4BF75),
        Color(0xFF66D9EF), Color(0xFFAE81FF), Color(0xFFA1EFE4), Color(0xFFF8F8F2),
        Color(0xFF75715E), Color(0xFFF92672), Color(0xFFA6E22E), Color(0xFFE6DB74),
        Color(0xFF66D9EF), Color(0xFFAE81FF), Color(0xFFA1EFE4), Color(0xFFF9F8F5)
    )
)

val TerminalThemes = listOf(DraculaTheme, SolarizedDarkTheme, MonokaiTheme)

var CurrentTerminalTheme by mutableStateOf(DraculaTheme)

val TerminalGreen get() = CurrentTerminalTheme.cursor
val TerminalBg    get() = CurrentTerminalTheme.background
val TERM_DEFAULT_FG get() = CurrentTerminalTheme.foreground

fun ansiIndex(i: Int) = CurrentTerminalTheme.ansiColors.getOrElse(i) { TERM_DEFAULT_FG }

fun ansi256(i: Int): Color = when {
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

val ERROR_REGEX   = Regex("(?i)error|fail|failed|fatal|critical|severe")
val SUCCESS_REGEX = Regex("(?i)success|succeeded|done|complete")
val WARNING_REGEX = Regex("(?i)warning|warn|caution|alert")

val OUTPUT_ERROR_COLOR   = Color(0xFFFF5555)
val OUTPUT_SUCCESS_COLOR = Color(0xFF50FA7B)
val OUTPUT_WARNING_COLOR = Color(0xFFFFD93D)

fun syntaxTintForLine(rawText: String): Color? = when {
    ERROR_REGEX.containsMatchIn(rawText)   -> OUTPUT_ERROR_COLOR
    SUCCESS_REGEX.containsMatchIn(rawText) -> OUTPUT_SUCCESS_COLOR
    WARNING_REGEX.containsMatchIn(rawText) -> OUTPUT_WARNING_COLOR
    else                                   -> null
}

val SHORTCUT_COLORS = listOf(
    Color(0xFF50FA7B), Color(0xFF8BE9FD), Color(0xFFBD93F9),
    Color(0xFFFF79C6), Color(0xFFFFD93D), Color(0xFFFFFF55)
)
fun shortcutColor(idx: Int) = SHORTCUT_COLORS[idx % SHORTCUT_COLORS.size]

class CommandHistory(val maxSize: Int = 50) {
    private val _entries = androidx.compose.runtime.mutableStateListOf<String>()
    val entries: List<String> get() = _entries
    fun add(cmd: String) {
        if (cmd.isNotBlank()) {
            _entries.remove(cmd)
            _entries.add(cmd)
            if (_entries.size > maxSize) _entries.removeAt(0)
        }
    }
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
    
    val parts = input.split(" ")
    val lastPart = parts.last()
    
    // Si l'entrée finit par un espace, on propose des commandes communes ou des flags
    if (input.endsWith(" ")) {
        return listOf("-h", "--help", "sudo ", "&& ", "| grep ")
    }

    val all = (history.asReversed() + COMMON_COMMANDS).distinct()
    
    // Suggestions basées sur le début de la commande complète
    val cmdSuggestions = all.filter { it.startsWith(input, ignoreCase = true) && it != input }
    
    // Suggestions basées sur le dernier mot (utile pour les chemins ou sous-commandes)
    val wordSuggestions = if (lastPart.length >= 2) {
         COMMON_COMMANDS.map { it.trim() }.filter { it.startsWith(lastPart, ignoreCase = true) }
    } else emptyList()

    return (cmdSuggestions + wordSuggestions).distinct().take(6)
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
