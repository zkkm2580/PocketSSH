package com.zkkm.pocketssh.term

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max

/**
 * Canvas-based terminal renderer.
 *
 * The view owns no terminal state of its own: it paints whatever [emulator]
 * exposes and forwards user input to [listener]. Soft-keyboard input is hosted
 * by a hidden [TerminalInputView] instead of a hand-rolled InputConnection,
 * which is far more reliable across IMEs.
 */
class TerminalView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    interface Listener {
        /** The view wants a grid of this size to fill itself. */
        fun onTerminalResize(cols: Int, rows: Int)

        /** Bytes the user produced that must be written to the SSH channel. */
        fun onTerminalInput(bytes: ByteArray)
    }

    var listener: Listener? = null

    var emulator: VtEmulator? = null
        set(value) {
            field = value
            requestLayout()
            invalidate()
        }

    var colorBackground: Int = 0xFF1E2127.toInt()
        set(value) {
            field = value
            invalidate()
        }

    var colorForeground: Int = 0xFFD7DAE0.toInt()
        set(value) {
            field = value
            invalidate()
        }

    var colorCursor: Int = 0xFF61AFEF.toInt()

    /** Sticky modifiers driven by the on-screen key bar. */
    var ctrlActive: Boolean = false
    var altActive: Boolean = false
    var onModifierChanged: (() -> Unit)? = null

    var textSizeSp: Float = 14f
        set(value) {
            val clamped = value.coerceIn(7f, 30f)
            if (clamped != field) {
                field = clamped
                updateTypeMetrics()
                requestLayout()
                invalidate()
            }
        }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
    private val fillPaint = Paint()
    private val cursorPaint = Paint()

    private var cellWidth = 1f
    private var cellHeight = 1f
    private var baselineOffset = 0f

    private var lastCols = 0
    private var lastRows = 0

    private val handler = Handler(Looper.getMainLooper())
    private var blinkOn = true
    private var blinkEnabled = false
    private val blinkRunnable = object : Runnable {
        override fun run() {
            blinkOn = !blinkOn
            invalidate()
            handler.postDelayed(this, 520)
        }
    }

    private var scrollAccumulator = 0f
    private var dragging = false
    private var downY = 0f
    private val touchSlop = 8f

    init {
        // Input focus belongs to the hidden TerminalInputView, never to this view.
        isFocusable = false
        isFocusableInTouchMode = false
        setBackgroundColor(colorBackground)
        updateTypeMetrics()
    }

    // ---------------------------------------------------------------------
    // Geometry
    // ---------------------------------------------------------------------

    private fun updateTypeMetrics() {
        textPaint.typeface = Typeface.MONOSPACE
        textPaint.textSize = textSizeSp * resources.displayMetrics.scaledDensity
        val metrics = textPaint.fontMetrics
        cellHeight = max(1f, ceil(metrics.descent - metrics.ascent))
        baselineOffset = -metrics.ascent
        val mWidth = textPaint.measureText("M")
        val wWidth = textPaint.measureText("W")
        cellWidth = max(1f, max(mWidth, wWidth))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        requestGridSize()
    }

    private fun requestGridSize() {
        if (width <= 0 || height <= 0 || cellWidth <= 0f || cellHeight <= 0f) return
        val cols = max(2, (width / cellWidth).toInt())
        val rows = max(2, (height / cellHeight).toInt())
        if (cols != lastCols || rows != lastRows) {
            lastCols = cols
            lastRows = rows
            listener?.onTerminalResize(cols, rows)
        }
    }

    fun invalidateTerminal() {
        postInvalidateOnAnimation()
    }

    /** Recompute the character grid, e.g. after a font size change. */
    fun refreshGridSize() {
        requestGridSize()
    }

    fun setConnected(connected: Boolean) {
        blinkEnabled = connected
        handler.removeCallbacks(blinkRunnable)
        blinkOn = true
        if (connected) handler.postDelayed(blinkRunnable, 520)
        invalidate()
    }

    // ---------------------------------------------------------------------
    // Drawing
    // ---------------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(colorBackground)
        val emu = emulator ?: return

        synchronized(emu) {
            for (row in 0 until emu.rows) {
                val line = emu.lineAt(row) ?: continue
                drawLine(canvas, line, row)
            }
            if (blinkOn && emu.showCursor && blinkEnabled) {
                drawCursor(canvas, emu)
            }
            val offset = emu.getScrollOffset()
            if (offset > 0) {
                drawScrollBadge(canvas, offset, emu.historySize)
            }
        }
    }

    private fun drawLine(canvas: Canvas, line: TermLine, rowIndex: Int) {
        val top = rowIndex * cellHeight
        var col = 0
        val run = StringBuilder()

        while (col < line.cols) {
            val flags = line.flags[col]
            if (flags and TermFlags.CONTINUATION != 0) {
                col++
                continue
            }
            val cp = line.text[col]
            val width = cellWidthOf(cp)
            val fgSpec = line.fg[col]
            val bgSpec = line.bg[col]
            val style = flags and STYLE_MASK

            run.setLength(0)
            run.appendCodePoint(cp)
            var end = col + width
            if (width == 1) {
                while (end < line.cols) {
                    val nextFlags = line.flags[end]
                    if (nextFlags and TermFlags.CONTINUATION != 0) break
                    if ((nextFlags and STYLE_MASK) != style) break
                    if (line.fg[end] != fgSpec || line.bg[end] != bgSpec) break
                    if (cellWidthOf(line.text[end]) != 1) break
                    run.appendCodePoint(line.text[end])
                    end++
                }
            }

            paintCellsBackground(canvas, col, end, top, bgSpec, style)
            paintRunText(canvas, run, col, top, fgSpec, bgSpec, style)
            col = end
        }
    }

    private fun paintCellsBackground(
        canvas: Canvas,
        startCol: Int,
        endCol: Int,
        top: Float,
        bgSpec: Int,
        style: Int
    ) {
        val inverse = style and TermFlags.INVERSE != 0
        if (TermColor.isDefault(bgSpec) && !inverse) return

        val color = if (inverse && TermColor.isDefault(bgSpec)) {
            colorForeground
        } else {
            AnsiPalette.resolve(bgSpec, if (inverse) colorForeground else colorBackground, false)
        }
        if (color == colorBackground) return

        fillPaint.color = color
        val left = startCol * cellWidth
        val right = endCol * cellWidth
        canvas.drawRect(left, top, right, top + cellHeight, fillPaint)
    }

    private fun paintRunText(
        canvas: Canvas,
        run: StringBuilder,
        startCol: Int,
        top: Float,
        fgSpec: Int,
        bgSpec: Int,
        style: Int
    ) {
        if (run.isEmpty()) return
        var blank = true
        for (i in 0 until run.length) {
            if (run[i] != ' ') {
                blank = false
                break
            }
        }
        if (blank) return

        val inverse = style and TermFlags.INVERSE != 0
        val bold = style and TermFlags.BOLD != 0
        val dim = style and TermFlags.DIM != 0
        val hidden = style and TermFlags.HIDDEN != 0

        var color = AnsiPalette.resolve(fgSpec, if (inverse) colorBackground else colorForeground, bold)
        if (hidden) {
            color = if (TermColor.isDefault(bgSpec) && !inverse) colorBackground
            else AnsiPalette.resolve(bgSpec, colorBackground, false)
        }

        if (dim) {
            val alpha = (android.graphics.Color.alpha(color) * 0.6f).toInt().coerceIn(40, 255)
            color = (alpha shl 24) or (color and 0x00FFFFFF)
        }
        textPaint.color = color
        textPaint.isFakeBoldText = bold
        textPaint.isUnderlineText = style and TermFlags.UNDERLINE != 0
        textPaint.isStrikeThruText = style and TermFlags.STRIKE != 0
        textPaint.textSkewX = if (style and TermFlags.ITALIC != 0) -0.25f else 0f

        canvas.drawText(run, 0, run.length, startCol * cellWidth, top + baselineOffset, textPaint)

        textPaint.isFakeBoldText = false
        textPaint.isUnderlineText = false
        textPaint.isStrikeThruText = false
        textPaint.textSkewX = 0f
    }

    private fun drawCursor(canvas: Canvas, emu: VtEmulator) {
        val col = emu.cursorCol
        val row = emu.cursorRow
        if (row < 0 || row >= emu.rows || col < 0 || col >= emu.cols) return
        val left = col * cellWidth
        val top = row * cellHeight
        cursorPaint.color = colorCursor
        canvas.drawRect(left, top, left + cellWidth, top + cellHeight, cursorPaint)

        val line = emu.lineAt(row) ?: return
        if (col < line.cols) {
            val flags = line.flags[col]
            if (flags and TermFlags.CONTINUATION == 0 && line.text[col] != ' '.code) {
                textPaint.color = colorBackground
                textPaint.isFakeBoldText = false
                textPaint.isUnderlineText = false
                textPaint.isStrikeThruText = false
                textPaint.textSkewX = 0f
                val text = String(Character.toChars(line.text[col]))
                canvas.drawText(text, left, top + baselineOffset, textPaint)
            }
        }
    }

    private fun drawScrollBadge(canvas: Canvas, offset: Int, history: Int) {
        val text = "↑ $offset / $history"
        cursorPaint.color = 0xAA000000.toInt()
        val x = width - 170f
        canvas.drawRect(x - 12f, 8f, width - 8f, 12f + cellHeight, cursorPaint)
        textPaint.color = 0xFF9AA4B2.toInt()
        textPaint.isFakeBoldText = false
        textPaint.isUnderlineText = false
        textPaint.isStrikeThruText = false
        textPaint.textSkewX = 0f
        canvas.drawText(text, x, 8f + baselineOffset, textPaint)
    }

    private fun cellWidthOf(cp: Int): Int = if (isWideForDraw(cp)) 2 else 1

    private fun isWideForDraw(cp: Int): Boolean = when (cp) {
        in 0x1100..0x115F -> true
        in 0x2E80..0x303E -> true
        in 0x3041..0x33FF -> true
        in 0x3400..0x4DBF -> true
        in 0x4E00..0x9FFF -> true
        in 0xA000..0xA4CF -> true
        in 0xAC00..0xD7A3 -> true
        in 0xF900..0xFAFF -> true
        in 0xFE30..0xFE6F -> true
        in 0xFF00..0xFF60 -> true
        in 0xFFE0..0xFFE6 -> true
        in 0x1F300..0x1F64F -> true
        in 0x1F900..0x1F9FF -> true
        in 0x20000..0x2FFFD -> true
        else -> false
    }

    // ---------------------------------------------------------------------
    // Touch handling
    // ---------------------------------------------------------------------

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val emu = emulator ?: return super.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downY = event.y
                scrollAccumulator = 0f
                dragging = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dy = event.y - downY
                downY = event.y
                if (!dragging && abs(dy) > touchSlop) dragging = true
                if (dragging) {
                    scrollAccumulator += dy
                    val steps = (scrollAccumulator / cellHeight).toInt()
                    if (steps != 0) {
                        emu.scrollBy(steps)
                        scrollAccumulator -= steps * cellHeight
                        invalidate()
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!dragging) {
                    if (emu.getScrollOffset() > 0) {
                        emu.setScrollOffset(0)
                        invalidate()
                    }
                    requestIme()
                }
                dragging = false
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    /** Invoked when the user taps the terminal and expects the keyboard. */
    var onRequestImeFocus: (() -> Unit)? = null

    fun requestIme() {
        onRequestImeFocus?.invoke()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        handler.removeCallbacks(blinkRunnable)
    }

    // ---------------------------------------------------------------------
    // Input
    //
    // The IME session lives in TerminalInputView; this view only reports the
    // intent to type and translates non-text keys (hardware keyboard, key bar).
    // ---------------------------------------------------------------------

    /**
     * Translates a physical-keyboard or IME key event into terminal bytes.
     * Returns true when the event was consumed.
     */
    fun handleHardwareKey(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN && event.action != KeyEvent.ACTION_MULTIPLE) return false
        val ctrl = event.isCtrlPressed || ctrlActive
        val alt = event.isAltPressed || altActive

        val sequence: String? = when (event.keyCode) {
            KeyEvent.KEYCODE_DEL -> if (ctrl) "\u0008" else "\u007F"
            KeyEvent.KEYCODE_FORWARD_DEL -> "\u001b[3~"
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> "\r"
            KeyEvent.KEYCODE_TAB -> if (event.isShiftPressed) "\u001b[Z" else "\t"
            KeyEvent.KEYCODE_ESCAPE -> "\u001b"
            KeyEvent.KEYCODE_MOVE_HOME -> cursorSequence('H')
            KeyEvent.KEYCODE_MOVE_END -> cursorSequence('F')
            KeyEvent.KEYCODE_DPAD_UP -> arrowSequence('A')
            KeyEvent.KEYCODE_DPAD_DOWN -> arrowSequence('B')
            KeyEvent.KEYCODE_DPAD_RIGHT -> arrowSequence('C')
            KeyEvent.KEYCODE_DPAD_LEFT -> arrowSequence('D')
            KeyEvent.KEYCODE_PAGE_UP -> "\u001b[5~"
            KeyEvent.KEYCODE_PAGE_DOWN -> "\u001b[6~"
            KeyEvent.KEYCODE_INSERT -> "\u001b[2~"
            else -> {
                // IMEs that send raw key events often leave unicodeChar at 0
                // (very common with password-style fields). Fall back to the
                // key code so those characters are not silently lost.
                val codePoint = if (event.unicodeChar != 0) {
                    event.unicodeChar
                } else {
                    InputTranscoder.printableForKey(event.keyCode, event.isShiftPressed)
                        ?: return false
                }
                InputTranscoder.mapKeyCharacter(codePoint, ctrl)
            }
        }

        if (sequence == null) return false
        val payload = if (alt && sequence.length == 1) "\u001b$sequence" else sequence
        if (ctrl) clearModifiers()
        sendBytes(payload.toByteArray(Charsets.UTF_8))
        return true
    }

    private fun arrowSequence(direction: Char): String =
        if (emulator?.applicationCursorKeys == true) "\u001bO$direction" else "\u001b[$direction"

    private fun cursorSequence(finalChar: Char): String =
        if (emulator?.applicationCursorKeys == true) "\u001bO$finalChar" else "\u001b[$finalChar"

    /** Public entry point for the on-screen key bar. */
    fun sendSequence(sequence: String) {
        if (sequence.isEmpty()) return
        sendBytes(sequence.toByteArray(Charsets.UTF_8))
    }

    fun sendArrow(direction: Char) = sendSequence(arrowSequence(direction))

    fun sendBackspace(ctrl: Boolean = false) {
        sendBytes(byteArrayOf(if (ctrl) 0x08 else 0x7F))
    }

    fun sendEnter() = sendSequence("\r")

    /**
     * Send IME-committed text to the PTY. LF, CR and CRLF all become one CR,
     * which is what a canonical-mode shell needs to accept a command.
     */
    fun sendText(text: String) {
        if (text.isEmpty()) return
        val bracketed = emulator?.bracketedPaste == true && text.length > 20 &&
            (text.contains('\n') || text.contains('\r'))
        val normalized = InputTranscoder.normalizeText(text, ctrlActive, altActive)
        val hadModifier = ctrlActive || altActive
        if (hadModifier) clearModifiers()
        val payload = if (bracketed) "\u001b[200~$normalized\u001b[201~" else normalized
        sendBytes(payload.toByteArray(Charsets.UTF_8))
    }

    fun toggleCtrl() {
        ctrlActive = !ctrlActive
        if (ctrlActive) altActive = false
        onModifierChanged?.invoke()
        invalidate()
    }

    fun toggleAlt() {
        altActive = !altActive
        if (altActive) ctrlActive = false
        onModifierChanged?.invoke()
        invalidate()
    }

    private fun clearModifiers() {
        ctrlActive = false
        altActive = false
        onModifierChanged?.invoke()
    }

    private fun sendBytes(bytes: ByteArray) {
        val emu = emulator
        if (emu != null && emu.getScrollOffset() > 0) {
            emu.setScrollOffset(0)
            invalidate()
        }
        listener?.onTerminalInput(bytes)
    }

    companion object {
        private const val STYLE_MASK = TermFlags.BOLD or TermFlags.DIM or TermFlags.ITALIC or
            TermFlags.UNDERLINE or TermFlags.BLINK or TermFlags.INVERSE or
            TermFlags.HIDDEN or TermFlags.STRIKE
    }
}
