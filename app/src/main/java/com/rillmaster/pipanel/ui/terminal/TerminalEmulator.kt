package com.rillmaster.pipanel.ui.terminal

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle

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
