package com.zkkm.pocketssh.term

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/**
 * A compact but real VT100/xterm-256color emulator.
 *
 * Implements the control sequences that interactive Unix programs actually use:
 * cursor movement, erase/insert/delete, scroll regions, SGR (including 256 colour
 * and true colour), alternate screen, save/restore, DSR/DA replies, bracketed
 * paste, tab stops, UTF-8 (with CJK double-width cells) and scrollback.
 *
 * Feed raw bytes from the SSH channel with [feed]; render the visible rows with
 * [lineAt]. All public members are safe to call from both the network thread and
 * the UI thread.
 */
class VtEmulator(
    initialCols: Int = 80,
    initialRows: Int = 24,
    private val scrollbackLimit: Int = 3000
) {

    var cols: Int = initialCols
        private set
    var rows: Int = initialRows
        private set

    /** Sink for replies the terminal owes the host (DA, DSR, ...). */
    @Volatile
    var onResponse: ((String) -> Unit)? = null
    @Volatile
    var onTitleChanged: ((String) -> Unit)? = null
    @Volatile
    var onBell: (() -> Unit)? = null

    private val decoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)

    private var primaryLines = ArrayList<TermLine>()
    private var altBuffer: ArrayList<TermLine>? = null
    private var usingAlt = false
    private val scrollback = RingBuffer<TermLine>(scrollbackLimit)

    private var cx = 0
    private var cy = 0
    private var savedPrimaryCx = 0
    private var savedPrimaryCy = 0
    private var savedAltCx = 0
    private var savedAltCy = 0

    private var topMargin = 0
    private var bottomMargin = initialRows - 1

    private var curFg = TermColor.DEFAULT
    private var curBg = TermColor.DEFAULT
    private var curFlags = TermFlags.NONE
    private var lastPrinted = ' '.code

    private var scrollOffsetValue = 0

    var autowrap = true
        private set
    var originMode = false
        private set
    var cursorVisible = true
        private set
    var applicationCursorKeys = false
        private set
    var bracketedPaste = false
        private set
    private var insertMode = false
    private var wrapPending = false

    // Parser state machine ------------------------------------------------
    private var state = ST_GROUND
    private val csiBuffer = StringBuilder()
    private val oscBuffer = StringBuilder()

    init {
        buildBuffers(cols, rows)
    }

    // ---------------------------------------------------------------------
    // Public surface
    // ---------------------------------------------------------------------

    /** Decode and apply a chunk of terminal output. */
    @Synchronized
    fun feed(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size) {
        if (length <= 0) return
        val decoded = decoder.decode(ByteBuffer.wrap(bytes, offset, length))
        while (decoded.hasRemaining()) {
            handleCodepoint(decoded.get().code)
        }
    }

    @get:Synchronized
    val historySize: Int
        get() = scrollback.size

    @Synchronized
    fun getScrollOffset(): Int = scrollOffsetValue

    @Synchronized
    fun setScrollOffset(value: Int) {
        scrollOffsetValue = value.coerceIn(0, scrollback.size)
    }

    @Synchronized
    fun scrollBy(delta: Int) {
        setScrollOffset(scrollOffsetValue + delta)
    }

    @get:Synchronized
    val isAltScreen: Boolean
        get() = usingAlt

    @get:Synchronized
    val cursorRow: Int
        get() = cy

    @get:Synchronized
    val cursorCol: Int
        get() = cx

    @get:Synchronized
    val showCursor: Boolean
        get() = cursorVisible && scrollOffsetValue == 0

    /** Visible row [viewRow] (0 = top of the viewport), honouring scrollback. */
    @Synchronized
    fun lineAt(viewRow: Int): TermLine? {
        if (viewRow < 0 || viewRow >= rows) return null
        val active = buffer()
        if (usingAlt || scrollOffsetValue == 0) {
            return active.getOrNull(viewRow)
        }
        val historyTop = scrollback.size - scrollOffsetValue
        val absolute = historyTop + viewRow
        return if (absolute < scrollback.size) {
            scrollback[absolute]
        } else {
            active.getOrNull(absolute - scrollback.size)
        }
    }

    @Synchronized
    fun clearScreen() {
        val active = buffer()
        for (row in 0 until rows) {
            active[row].clear(curFg, curBg, curFlags)
        }
        cx = 0
        cy = 0
        wrapPending = false
        scrollOffsetValue = 0
    }

    @Synchronized
    fun clearScrollback() {
        scrollback.clear()
        scrollOffsetValue = 0
    }

    @Synchronized
    fun reset() {
        primaryLines = ArrayList()
        altBuffer = null
        usingAlt = false
        scrollback.clear()
        curFg = TermColor.DEFAULT
        curBg = TermColor.DEFAULT
        curFlags = TermFlags.NONE
        autowrap = true
        originMode = false
        cursorVisible = true
        applicationCursorKeys = false
        bracketedPaste = false
        insertMode = false
        wrapPending = false
        state = ST_GROUND
        csiBuffer.setLength(0)
        oscBuffer.setLength(0)
        scrollOffsetValue = 0
        buildBuffers(cols, rows)
    }

    /** Change the terminal size, preserving as much content as possible. */
    @Synchronized
    fun resize(newCols: Int, newRows: Int) {
        val targetCols = newCols.coerceIn(2, 500)
        val targetRows = newRows.coerceIn(2, 400)
        if (targetCols == cols && targetRows == rows) return

        primaryLines = reflowBuffer(primaryLines, targetCols, targetRows)
        altBuffer = altBuffer?.let { reflowBuffer(it, targetCols, targetRows) }

        cols = targetCols
        rows = targetRows
        topMargin = 0
        bottomMargin = rows - 1
        cx = cx.coerceIn(0, cols - 1)
        cy = cy.coerceIn(0, rows - 1)
        scrollOffsetValue = scrollOffsetValue.coerceAtMost(scrollback.size)
        wrapPending = false
    }

    /** Visible text of the whole screen, used for whole-screen copy. */
    @Synchronized
    fun snapshotText(): String {
        val sb = StringBuilder()
        for (row in 0 until rows) {
            val line = buffer().getOrNull(row) ?: continue
            var last = line.cols - 1
            while (last >= 0 && (line.text[last] == ' '.code || line.flags[last] and TermFlags.CONTINUATION != 0)) last--
            if (last < 0) {
                sb.append('\n')
                continue
            }
            for (col in 0..last) {
                if (line.flags[col] and TermFlags.CONTINUATION != 0) continue
                sb.appendCodePoint(line.text[col])
            }
            sb.append('\n')
        }
        return sb.toString()
    }

    // ---------------------------------------------------------------------
    // Buffer plumbing
    // ---------------------------------------------------------------------

    private fun buffer(): ArrayList<TermLine> =
        if (usingAlt) (altBuffer ?: primaryLines) else primaryLines

    private fun newLine(): TermLine = TermLine(cols)

    private fun buildBuffers(c: Int, r: Int) {
        cols = c
        rows = r
        primaryLines = ArrayList(r)
        repeat(r) { primaryLines.add(newLine()) }
        topMargin = 0
        bottomMargin = r - 1
        cx = 0
        cy = 0
    }

    private fun reflowBuffer(source: ArrayList<TermLine>, newCols: Int, newRows: Int): ArrayList<TermLine> {
        val out = ArrayList<TermLine>(newRows)
        for (row in 0 until newRows) {
            val existing = source.getOrNull(row)
            out.add(existing?.resized(newCols) ?: TermLine(newCols))
        }
        return out
    }

    private fun lineAtRow(row: Int): TermLine {
        val active = buffer()
        return active[row.coerceIn(0, rows - 1)]
    }

    // ---------------------------------------------------------------------
    // Parser
    // ---------------------------------------------------------------------

    private fun handleCodepoint(cp: Int) {
        when (state) {
            ST_GROUND -> when {
                cp == 0x1B -> state = ST_ESCAPE
                cp == 0x9B -> {
                    csiBuffer.setLength(0)
                    state = ST_CSI
                }
                cp < 0x20 -> control(cp)
                cp == 0x7F -> Unit
                else -> putChar(cp)
            }
            ST_ESCAPE -> escapeDispatch(cp)
            ST_CSI -> csiInput(cp)
            ST_OSC -> oscInput(cp)
            ST_STRING -> if (cp == 0x1B) state = ST_STRING_ESC
            ST_STRING_ESC -> state = if (cp.toChar() == '\\') ST_GROUND else ST_STRING
            ST_CHARSET -> state = ST_GROUND
            ST_OSC_ESC -> {
                if (cp.toChar() == '\\') {
                    finishOsc()
                } else {
                    oscBuffer.append('\\')
                    oscBuffer.append(cp.toChar())
                    state = ST_OSC
                }
            }
        }
    }

    private fun control(cp: Int) {
        when (cp) {
            0x07 -> onBell?.invoke()
            0x08 -> {
                if (cx > 0) cx--
                wrapPending = false
            }
            0x09 -> tabForward(1)
            0x0A, 0x0B, 0x0C -> {
                indexDown()
                wrapPending = false
            }
            0x0D -> {
                cx = 0
                wrapPending = false
            }
            0x0E, 0x0F -> Unit
            else -> Unit
        }
    }

    private fun escapeDispatch(cp: Int) {
        state = ST_GROUND
        when (cp.toChar()) {
            '[' -> {
                csiBuffer.setLength(0)
                state = ST_CSI
            }
            ']' -> {
                oscBuffer.setLength(0)
                state = ST_OSC
            }
            'P', 'X', '^', '_' -> state = ST_STRING
            '(', ')', '*', '+', '-', '.', '/' -> state = ST_CHARSET
            '#' -> state = ST_CHARSET
            '7' -> saveCursor()
            '8' -> restoreCursor()
            'D' -> indexDown()
            'E' -> {
                indexDown()
                cx = 0
            }
            'M' -> reverseIndex()
            'H' -> setTabStop()
            'c' -> reset()
            '=' , '>' -> Unit
            '\\' -> Unit
            else -> Unit
        }
    }

    private fun csiInput(cp: Int) {
        when {
            cp in 0x40..0x7E -> {
                dispatchCsi(cp.toChar(), csiBuffer.toString())
                csiBuffer.setLength(0)
                state = ST_GROUND
            }
            cp in 0x20..0x3F -> csiBuffer.append(cp.toChar())
            cp < 0x20 -> control(cp)
            else -> state = ST_GROUND
        }
    }

    private fun oscInput(cp: Int) {
        when {
            cp == 0x07 -> finishOsc()
            cp == 0x1B -> state = ST_OSC_ESC
            cp < 0x20 -> Unit
            else -> if (oscBuffer.length < 4096) oscBuffer.append(cp.toChar())
        }
    }

    private fun finishOsc() {
        val payload = oscBuffer.toString()
        oscBuffer.setLength(0)
        state = ST_GROUND
        val sep = payload.indexOf(';')
        if (sep < 0) return
        val command = payload.substring(0, sep)
        val value = payload.substring(sep + 1)
        when (command) {
            "0", "1", "2" -> onTitleChanged?.invoke(value)
            else -> Unit
        }
    }

    // ---------------------------------------------------------------------
    // Printing
    // ---------------------------------------------------------------------

    private fun putChar(cp: Int) {
        val width = charWidth(cp)
        if (width == 0) return

        if (wrapPending) {
            wrapPending = false
            cx = 0
            indexDown()
        }

        if (cx + width > cols) {
            if (autowrap) {
                cx = 0
                indexDown()
            } else {
                cx = (cols - width).coerceAtLeast(0)
            }
        }

        val line = lineAtRow(cy)
        if (insertMode) line.shiftRight(cx, width)

        line.text[cx] = cp
        line.fg[cx] = curFg
        line.bg[cx] = curBg
        line.flags[cx] = curFlags

        if (width == 2 && cx + 1 < cols) {
            line.text[cx + 1] = ' '.code
            line.fg[cx + 1] = curFg
            line.bg[cx + 1] = curBg
            line.flags[cx + 1] = curFlags or TermFlags.CONTINUATION
        }

        lastPrinted = cp
        cx += width
        if (cx >= cols) {
            cx = cols - 1
            wrapPending = autowrap
        }
    }

    // ---------------------------------------------------------------------
    // Cursor / scrolling primitives
    // ---------------------------------------------------------------------

    private fun indexDown() {
        when {
            cy == bottomMargin -> scrollUp(1)
            cy < rows - 1 -> cy++
        }
    }

    private fun reverseIndex() {
        when {
            cy == topMargin -> scrollDown(1)
            cy > 0 -> cy--
        }
    }

    private fun scrollUp(count: Int) {
        val active = buffer()
        val regionHeight = bottomMargin - topMargin + 1
        val n = count.coerceIn(1, regionHeight)
        repeat(n) {
            val removed = active.removeAt(topMargin)
            if (!usingAlt && topMargin == 0) {
                scrollback.add(removed)
            }
            active.add(bottomMargin, newLine())
        }
        if (!usingAlt && topMargin == 0 && scrollOffsetValue > 0) {
            scrollOffsetValue = scrollOffsetValue.coerceAtMost(scrollback.size)
        }
    }

    private fun scrollDown(count: Int) {
        val active = buffer()
        val regionHeight = bottomMargin - topMargin + 1
        val n = count.coerceIn(1, regionHeight)
        repeat(n) {
            active.removeAt(bottomMargin)
            active.add(topMargin, newLine())
        }
    }

    private fun saveCursor() {
        if (usingAlt) {
            savedAltCx = cx
            savedAltCy = cy
        } else {
            savedPrimaryCx = cx
            savedPrimaryCy = cy
        }
    }

    private fun restoreCursor() {
        if (usingAlt) {
            cx = savedAltCx.coerceIn(0, cols - 1)
            cy = savedAltCy.coerceIn(0, rows - 1)
        } else {
            cx = savedPrimaryCx.coerceIn(0, cols - 1)
            cy = savedPrimaryCy.coerceIn(0, rows - 1)
        }
        wrapPending = false
    }

    private fun tabForward(count: Int) {
        repeat(count) {
            cx = ((cx / 8) + 1).times(8).coerceIn(0, cols - 1)
        }
        wrapPending = false
    }

    private fun tabBackward(count: Int) {
        repeat(count) {
            cx = if (cx % 8 == 0) (cx - 8).coerceAtLeast(0) else (cx - (cx % 8))
        }
    }

    private fun setTabStop() = Unit

    private fun moveCursor(dx: Int, dy: Int) {
        val newCy = cy + dy
        val lower = if (cy in topMargin..bottomMargin) topMargin else 0
        val upper = if (cy in topMargin..bottomMargin) bottomMargin else rows - 1
        cy = newCy.coerceIn(lower, upper)
        cx = (cx + dx).coerceIn(0, cols - 1)
        wrapPending = false
    }

    private fun clampCol(col: Int): Int = col.coerceIn(0, cols - 1)

    // ---------------------------------------------------------------------
    // CSI dispatch
    // ---------------------------------------------------------------------

    private fun dispatchCsi(finalChar: Char, raw: String) {
        var body = raw
        val isPrivate = body.startsWith("?")
        if (isPrivate) body = body.substring(1)
        val cleaned = StringBuilder(body.length)
        for (ch in body) {
            if (ch.isDigit() || ch == ';' || ch == ':') cleaned.append(ch)
        }
        val params = parseInts(cleaned.toString())

        fun arg(index: Int, fallback: Int): Int {
            val value = params.getOrNull(index) ?: 0
            return if (value <= 0) fallback else value
        }

        when (finalChar) {
            'A' -> moveCursor(0, -arg(0, 1))
            'B' -> moveCursor(0, arg(0, 1))
            'C' -> moveCursor(arg(0, 1), 0)
            'D' -> moveCursor(-arg(0, 1), 0)
            'E' -> {
                moveCursor(0, arg(0, 1))
                cx = 0
            }
            'F' -> {
                moveCursor(0, -arg(0, 1))
                cx = 0
            }
            'G', '`' -> cx = clampCol(arg(0, 1) - 1)
            'H', 'f' -> {
                val row = arg(0, 1) - 1
                val col = arg(1, 1) - 1
                cy = (if (originMode) topMargin + row else row).coerceIn(0, rows - 1)
                cx = clampCol(col)
                wrapPending = false
            }
            'I' -> tabForward(arg(0, 1))
            'J' -> eraseInDisplay(arg(0, 0))
            'K' -> eraseInLine(arg(0, 0))
            'L' -> insertLines(arg(0, 1))
            'M' -> deleteLines(arg(0, 1))
            'P' -> lineAtRow(cy).shiftLeft(cx, arg(0, 1))
            '@' -> lineAtRow(cy).shiftRight(cx, arg(0, 1))
            'S' -> scrollUp(arg(0, 1))
            'T' -> scrollDown(arg(0, 1))
            'X' -> lineAtRow(cy).clearRange(cx, cx + arg(0, 1), curFg, curBg, curFlags)
            'Z' -> tabBackward(arg(0, 1))
            'b' -> {
                repeat(arg(0, 1).coerceAtMost(cols)) { putChar(lastPrinted) }
            }
            'c' -> {
                if (params.getOrElse(0) { 0 } == 0) onResponse?.invoke("\u001b[?1;2c")
            }
            'd' -> cy = (arg(0, 1) - 1).coerceIn(0, rows - 1)
            'e' -> moveCursor(0, arg(0, 1))
            'g' -> Unit
            'h' -> setModes(params, isPrivate, true)
            'l' -> setModes(params, isPrivate, false)
            'm' -> applySgr(params)
            'n' -> {
                when (params.getOrElse(0) { 0 }) {
                    5 -> onResponse?.invoke("\u001b[0n")
                    6 -> onResponse?.invoke("\u001b[${cy + 1};${cx + 1}R")
                    else -> Unit
                }
            }
            'r' -> {
                val top = arg(0, 1) - 1
                val bottom = arg(1, rows) - 1
                if (top in 0 until bottom && bottom < rows) {
                    topMargin = top
                    bottomMargin = bottom
                    cx = 0
                    cy = if (originMode) topMargin else 0
                }
            }
            's' -> saveCursor()
            'u' -> restoreCursor()
            else -> Unit
        }
    }

    private fun parseInts(spec: String): List<Int> {
        if (spec.isEmpty()) return emptyList()
        val parts = spec.split(';')
        val out = ArrayList<Int>(parts.size)
        for (part in parts) {
            val first = part.substringBefore(':')
            out.add(first.toIntOrNull() ?: 0)
        }
        return out
    }

    private fun setModes(params: List<Int>, privateMode: Boolean, enable: Boolean) {
        for (value in params) {
            if (privateMode) {
                when (value) {
                    1 -> applicationCursorKeys = enable
                    6 -> {
                        originMode = enable
                        cx = 0
                        cy = if (enable) topMargin else 0
                    }
                    7 -> {
                        autowrap = enable
                        if (!enable) wrapPending = false
                    }
                    25 -> cursorVisible = enable
                    47, 1047 -> setAltScreen(enable)
                    1048 -> if (enable) saveCursor() else restoreCursor()
                    1049 -> {
                        if (enable) {
                            saveCursor()
                            setAltScreen(true)
                        } else {
                            setAltScreen(false)
                            restoreCursor()
                        }
                    }
                    2004 -> bracketedPaste = enable
                    else -> Unit
                }
            } else {
                when (value) {
                    4 -> insertMode = enable
                    else -> Unit
                }
            }
        }
    }

    private fun setAltScreen(enable: Boolean) {
        if (enable == usingAlt) return
        if (enable) {
            altBuffer = ArrayList<TermLine>(rows).apply {
                repeat(rows) { add(TermLine(cols)) }
            }
            usingAlt = true
            cx = 0
            cy = 0
        } else {
            usingAlt = false
            altBuffer = null
        }
        scrollOffsetValue = 0
        wrapPending = false
    }

    private fun eraseInLine(mode: Int) {
        val line = lineAtRow(cy)
        when (mode) {
            0 -> line.clearRange(cx, cols, curFg, curBg, curFlags)
            1 -> line.clearRange(0, cx + 1, curFg, curBg, curFlags)
            2 -> line.clearRange(0, cols, curFg, curBg, curFlags)
            else -> Unit
        }
    }

    private fun eraseInDisplay(mode: Int) {
        val active = buffer()
        when (mode) {
            0 -> {
                eraseInLine(0)
                for (row in cy + 1 until rows) {
                    active[row].clear(curFg, curBg, curFlags)
                }
            }
            1 -> {
                for (row in 0 until cy) {
                    active[row].clear(curFg, curBg, curFlags)
                }
                eraseInLine(1)
            }
            2 -> {
                for (row in 0 until rows) {
                    active[row].clear(curFg, curBg, curFlags)
                }
            }
            3 -> {
                scrollback.clear()
                scrollOffsetValue = 0
            }
            else -> Unit
        }
    }

    private fun insertLines(count: Int) {
        if (cy !in topMargin..bottomMargin) return
        val active = buffer()
        val n = count.coerceAtMost(bottomMargin - cy + 1)
        repeat(n) {
            active.removeAt(bottomMargin)
            active.add(cy, newLine())
        }
    }

    private fun deleteLines(count: Int) {
        if (cy !in topMargin..bottomMargin) return
        val active = buffer()
        val n = count.coerceAtMost(bottomMargin - cy + 1)
        repeat(n) {
            active.removeAt(cy)
            active.add(bottomMargin, newLine())
        }
    }

    // ---------------------------------------------------------------------
    // SGR
    // ---------------------------------------------------------------------

    private fun applySgr(params: List<Int>) {
        if (params.isEmpty()) {
            resetAttributes()
            return
        }
        var i = 0
        while (i < params.size) {
            when (val code = params[i]) {
                0 -> resetAttributes()
                1 -> curFlags = curFlags or TermFlags.BOLD
                2 -> curFlags = curFlags or TermFlags.DIM
                3 -> curFlags = curFlags or TermFlags.ITALIC
                4 -> curFlags = curFlags or TermFlags.UNDERLINE
                5, 6 -> curFlags = curFlags or TermFlags.BLINK
                7 -> curFlags = curFlags or TermFlags.INVERSE
                8 -> curFlags = curFlags or TermFlags.HIDDEN
                9 -> curFlags = curFlags or TermFlags.STRIKE
                21, 22 -> curFlags = curFlags and TermFlags.BOLD.inv() and TermFlags.DIM.inv()
                23 -> curFlags = curFlags and TermFlags.ITALIC.inv()
                24 -> curFlags = curFlags and TermFlags.UNDERLINE.inv()
                25 -> curFlags = curFlags and TermFlags.BLINK.inv()
                27 -> curFlags = curFlags and TermFlags.INVERSE.inv()
                28 -> curFlags = curFlags and TermFlags.HIDDEN.inv()
                29 -> curFlags = curFlags and TermFlags.STRIKE.inv()
                in 30..37 -> curFg = TermColor.palette(code - 30)
                39 -> curFg = TermColor.DEFAULT
                in 40..47 -> curBg = TermColor.palette(code - 40)
                49 -> curBg = TermColor.DEFAULT
                in 90..97 -> curFg = TermColor.palette(code - 90 + 8)
                in 100..107 -> curBg = TermColor.palette(code - 100 + 8)
                38, 48 -> {
                    val kind = params.getOrNull(i + 1) ?: return
                    when (kind) {
                        5 -> {
                            val index = params.getOrNull(i + 2) ?: return
                            setExtendedColor(code, TermColor.palette(index))
                            i += 2
                        }
                        2 -> {
                            val r = params.getOrNull(i + 2) ?: return
                            val g = params.getOrNull(i + 3) ?: return
                            val b = params.getOrNull(i + 4) ?: return
                            val rgb = ((r and 0xFF) shl 16) or ((g and 0xFF) shl 8) or (b and 0xFF)
                            setExtendedColor(code, TermColor.rgb(rgb))
                            i += 4
                        }
                        else -> i += 1
                    }
                }
                else -> Unit
            }
            i++
        }
    }

    private fun setExtendedColor(code: Int, color: Int) {
        if (code == 38) curFg = color else curBg = color
    }

    private fun resetAttributes() {
        curFg = TermColor.DEFAULT
        curBg = TermColor.DEFAULT
        curFlags = TermFlags.NONE
    }

    // ---------------------------------------------------------------------
    // Width handling
    // ---------------------------------------------------------------------

    private fun charWidth(cp: Int): Int {
        if (cp == 0) return 0
        if (cp < 0x20) return 0
        if (cp in 0x7F..0x9F) return 0
        if (cp == 0x200B || cp == 0x200C || cp == 0x200D || cp == 0xFEFF) return 0
        if (cp in 0x0300..0x036F) return 0
        return if (isWide(cp)) 2 else 1
    }

    private fun isWide(cp: Int): Boolean = when (cp) {
        in 0x1100..0x115F -> true
        in 0x2E80..0x303E -> true
        in 0x3041..0x33FF -> true
        in 0x3400..0x4DBF -> true
        in 0x4E00..0x9FFF -> true
        in 0xA000..0xA4CF -> true
        in 0xA960..0xA97F -> true
        in 0xAC00..0xD7A3 -> true
        in 0xF900..0xFAFF -> true
        in 0xFE10..0xFE19 -> true
        in 0xFE30..0xFE6F -> true
        in 0xFF00..0xFF60 -> true
        in 0xFFE0..0xFFE6 -> true
        in 0x1F300..0x1F64F -> true
        in 0x1F900..0x1F9FF -> true
        in 0x20000..0x2FFFD -> true
        in 0x30000..0x3FFFD -> true
        else -> false
    }

    companion object {
        private const val ST_GROUND = 0
        private const val ST_ESCAPE = 1
        private const val ST_CSI = 2
        private const val ST_OSC = 3
        private const val ST_OSC_ESC = 4
        private const val ST_STRING = 5
        private const val ST_STRING_ESC = 6
        private const val ST_CHARSET = 7
    }
}
