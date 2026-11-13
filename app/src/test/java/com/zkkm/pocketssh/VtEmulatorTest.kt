package com.zkkm.pocketssh

import com.zkkm.pocketssh.term.VtEmulator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the VT parser. These run without an Android device and
 * cover the escape sequences interactive shells depend on.
 */
class VtEmulatorTest {

    private fun emulator(cols: Int = 20, rows: Int = 5): VtEmulator {
        val emu = VtEmulator(cols, rows, 100)
        return emu
    }

    private fun VtEmulator.feedText(text: String) {
        feed(text.toByteArray(Charsets.UTF_8), 0, text.toByteArray(Charsets.UTF_8).size)
    }

    private fun VtEmulator.rowText(row: Int): String {
        val line = lineAt(row) ?: return ""
        val sb = StringBuilder()
        for (col in 0 until line.cols) {
            if (line.flags[col] and com.zkkm.pocketssh.term.TermFlags.CONTINUATION != 0) continue
            sb.appendCodePoint(line.text[col])
        }
        return sb.toString()
    }

    @Test
    fun plainTextIsPrinted() {
        val emu = emulator()
        emu.feedText("hello")
        assertTrue(emu.rowText(0).startsWith("hello"))
        assertEquals(5, emu.cursorCol)
    }

    @Test
    fun carriageReturnAndLineFeedWork() {
        val emu = emulator()
        emu.feedText("abc\r\ndef")
        assertEquals("abc", emu.rowText(0).trimEnd())
        assertTrue(emu.rowText(1).startsWith("def"))
    }

    @Test
    fun cursorPositionIsApplied() {
        val emu = emulator()
        // ESC[3;5H -> row 3, column 5
        emu.feedText("\u001b[3;5HX")
        assertTrue(emu.rowText(2).startsWith("    X"))
    }

    @Test
    fun eraseInLineClearsToEnd() {
        val emu = emulator()
        emu.feedText("abcdef\u001b[1;4H\u001b[K")
        assertEquals("abc", emu.rowText(0).trimEnd())
    }

    @Test
    fun alternateScreenRestoresPrimary() {
        val emu = emulator()
        emu.feedText("primary")
        emu.feedText("\u001b[?1049h")
        emu.feedText("alternate")
        assertEquals(true, emu.isAltScreen)
        emu.feedText("\u001b[?1049l")
        assertEquals(false, emu.isAltScreen)
        assertTrue(emu.rowText(0).startsWith("primary"))
    }

    @Test
    fun sgrColoursAreRecorded() {
        val emu = emulator()
        emu.feedText("\u001b[31mR\u001b[0mN")
        val line = emu.lineAt(0)!!
        assertEquals(com.zkkm.pocketssh.term.TermColor.palette(1), line.fg[0])
        assertEquals(com.zkkm.pocketssh.term.TermColor.DEFAULT, line.fg[1])
    }

    @Test
    fun trueColourIsRecorded() {
        val emu = emulator()
        emu.feedText("\u001b[38;2;10;20;30mX")
        val line = emu.lineAt(0)!!
        assertEquals(com.zkkm.pocketssh.term.TermColor.rgb(0x0A141E), line.fg[0])
    }

    @Test
    fun scrollingPushesLinesIntoScrollback() {
        val emu = emulator(cols = 10, rows = 3)
        emu.feedText("one\r\ntwo\r\nthree\r\nfour")
        assertTrue(emu.historySize >= 1)
        assertEquals(3, emu.rows)
    }

    @Test
    fun cjkCharactersOccupyTwoCells() {
        val emu = emulator()
        emu.feedText("中文")
        val line = emu.lineAt(0)!!
        assertEquals(0x4E2D, line.text[0])
        assertEquals(com.zkkm.pocketssh.term.TermFlags.CONTINUATION, line.flags[1] and com.zkkm.pocketssh.term.TermFlags.CONTINUATION)
        assertEquals(4, emu.cursorCol)
    }

    @Test
    fun deviceStatusReportRepliesWithCursor() {
        val emu = emulator()
        val replies = StringBuilder()
        emu.onResponse = { replies.append(it) }
        emu.feedText("\u001b[2;7H\u001b[6n")
        assertEquals("\u001b[2;7R", replies.toString())
    }

    @Test
    fun resizeKeepsContent() {
        val emu = emulator(cols = 20, rows = 5)
        emu.feedText("keep me")
        emu.resize(40, 8)
        assertEquals(40, emu.cols)
        assertEquals(8, emu.rows)
        assertTrue(emu.rowText(0).startsWith("keep me"))
    }

    @Test
    fun insertLinesShiftsContentDown() {
        val emu = emulator(cols = 10, rows = 4)
        emu.feedText("a\r\nb\r\nc")
        emu.feedText("\u001b[1;1H\u001b[L")
        assertTrue(emu.rowText(0).isBlank())
        assertTrue(emu.rowText(1).startsWith("a"))
    }
}
