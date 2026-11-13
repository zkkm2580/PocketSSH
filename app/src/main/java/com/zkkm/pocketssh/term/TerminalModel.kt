package com.zkkm.pocketssh.term

/**
 * Colour encoding used inside the terminal buffer.
 *
 * 0                     -> terminal default (theme decides the actual colour)
 * 0x01_000000 | index   -> xterm 256-colour palette entry
 * 0x02_000000 | RGB     -> 24-bit direct colour
 */
object TermColor {
    const val DEFAULT = 0

    fun palette(index: Int): Int = 0x01000000 or (index and 0xFF)
    fun rgb(rgb: Int): Int = 0x02000000 or (rgb and 0xFFFFFF)

    fun isDefault(spec: Int): Boolean = spec == DEFAULT
    fun isPalette(spec: Int): Boolean = (spec ushr 24) == 1
    fun paletteIndex(spec: Int): Int = spec and 0xFF
    fun rgbValue(spec: Int): Int = spec and 0xFFFFFF
}

/** Per-cell attribute flags. */
object TermFlags {
    const val NONE = 0
    const val BOLD = 1
    const val DIM = 1 shl 1
    const val ITALIC = 1 shl 2
    const val UNDERLINE = 1 shl 3
    const val BLINK = 1 shl 4
    const val INVERSE = 1 shl 5
    const val HIDDEN = 1 shl 6
    const val STRIKE = 1 shl 7
    /** Marks the right half of a double-width character. */
    const val CONTINUATION = 1 shl 8
}

/** One screen row: character codepoints plus per-cell colour/attribute data. */
class TermLine(val cols: Int) {

    val text = IntArray(cols) { ' '.code }
    val fg = IntArray(cols) { TermColor.DEFAULT }
    val bg = IntArray(cols) { TermColor.DEFAULT }
    val flags = IntArray(cols) { TermFlags.NONE }

    fun clear(fgColor: Int, bgColor: Int, attr: Int) {
        clearRange(0, cols, fgColor, bgColor, attr)
    }

    fun clearRange(from: Int, to: Int, fgColor: Int, bgColor: Int, attr: Int) {
        val start = from.coerceIn(0, cols)
        val end = to.coerceIn(0, cols)
        if (start >= end) return
        java.util.Arrays.fill(text, start, end, ' '.code)
        java.util.Arrays.fill(fg, start, end, fgColor)
        java.util.Arrays.fill(bg, start, end, bgColor)
        java.util.Arrays.fill(flags, start, end, attr)
    }

    fun shiftLeft(from: Int, count: Int) {
        if (count <= 0 || from >= cols) return
        val n = count.coerceAtMost(cols - from)
        val span = cols - from - n
        if (span > 0) {
            System.arraycopy(text, from + n, text, from, span)
            System.arraycopy(fg, from + n, fg, from, span)
            System.arraycopy(bg, from + n, bg, from, span)
            System.arraycopy(flags, from + n, flags, from, span)
        }
        clearRange(cols - n, cols, TermColor.DEFAULT, TermColor.DEFAULT, TermFlags.NONE)
    }

    fun shiftRight(from: Int, count: Int) {
        if (count <= 0 || from >= cols) return
        val n = count.coerceAtMost(cols - from)
        val span = cols - from - n
        if (span > 0) {
            System.arraycopy(text, from, text, from + n, span)
            System.arraycopy(fg, from, fg, from + n, span)
            System.arraycopy(bg, from, bg, from + n, span)
            System.arraycopy(flags, from, flags, from + n, span)
        }
        clearRange(from, from + n, TermColor.DEFAULT, TermColor.DEFAULT, TermFlags.NONE)
    }

    fun copyInto(target: TermLine) {
        val n = minOf(cols, target.cols)
        System.arraycopy(text, 0, target.text, 0, n)
        System.arraycopy(fg, 0, target.fg, 0, n)
        System.arraycopy(bg, 0, target.bg, 0, n)
        System.arraycopy(flags, 0, target.flags, 0, n)
        if (target.cols > n) {
            target.clearRange(n, target.cols, TermColor.DEFAULT, TermColor.DEFAULT, TermFlags.NONE)
        }
    }

    fun resized(newCols: Int): TermLine {
        val line = TermLine(newCols)
        copyInto(line)
        return line
    }
}

/** Fixed-capacity FIFO used for the scrollback buffer. */
internal class RingBuffer<T>(private val capacity: Int) {
    private val items = arrayOfNulls<Any?>(if (capacity > 0) capacity else 1)
    var size: Int = 0
        private set
    private var start = 0

    fun add(item: T) {
        if (capacity <= 0) return
        if (size < capacity) {
            items[(start + size) % capacity] = item
            size++
        } else {
            items[start] = item
            start = (start + 1) % capacity
        }
    }

    @Suppress("UNCHECKED_CAST")
    operator fun get(index: Int): T = items[(start + index) % capacity] as T

    fun clear() {
        items.fill(null)
        size = 0
        start = 0
    }
}
