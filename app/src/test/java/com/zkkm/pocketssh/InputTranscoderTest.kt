package com.zkkm.pocketssh

import com.zkkm.pocketssh.term.InputTranscoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure-JVM regression tests for the input path. These cover the exact failures
 * that made typing and Enter appear dead on a phone: CR being dropped, LF not
 * translated, and IME key events arriving without a unicodeChar.
 */
class InputTranscoderTest {

    @Test
    fun lfBecomesCarriageReturn() {
        assertEquals("ls -la\r", InputTranscoder.normalizeText("ls -la\n"))
    }

    @Test
    fun carriageReturnIsPreserved() {
        // 1.0.1 dropped '\r' entirely, so IMEs committing CR sent nothing.
        assertEquals("ls\r", InputTranscoder.normalizeText("ls\r"))
    }

    @Test
    fun crlfIsExactlyOneCarriageReturn() {
        assertEquals("pwd\r", InputTranscoder.normalizeText("pwd\r\n"))
        assertEquals("a\rb\r", InputTranscoder.normalizeText("a\nb\n"))
    }

    @Test
    fun plainTextIsUnchanged() {
        assertEquals("echo hello", InputTranscoder.normalizeText("echo hello"))
    }

    @Test
    fun cjkAndSupplementaryCodePointsSurvive() {
        assertEquals("中文\uD83D\uDE80\r", InputTranscoder.normalizeText("中文\uD83D\uDE80\n"))
    }

    @Test
    fun ctrlProducesControlCodes() {
        assertEquals("\u0001\u0002\u0003", InputTranscoder.normalizeText("abc", ctrl = true))
        assertEquals("\u0000", InputTranscoder.normalizeText(" ", ctrl = true))
    }

    @Test
    fun altPrefixesEscape() {
        assertEquals("\u001bx", InputTranscoder.normalizeText("x", alt = true))
    }

    @Test
    fun keyCodeFallbackMapsLetters() {
        // KEYCODE_A = 29, KEYCODE_C = 31
        assertEquals('a'.code, InputTranscoder.printableForKey(29, shift = false))
        assertEquals('C'.code, InputTranscoder.printableForKey(31, shift = true))
    }

    @Test
    fun keyCodeFallbackMapsDigitsAndShiftedSymbols() {
        // KEYCODE_1 = 8, KEYCODE_SLASH = 76, KEYCODE_MINUS = 69
        assertEquals('1'.code, InputTranscoder.printableForKey(8, shift = false))
        assertEquals('!'.code, InputTranscoder.printableForKey(8, shift = true))
        assertEquals('?'.code, InputTranscoder.printableForKey(76, shift = true))
        assertEquals('_'.code, InputTranscoder.printableForKey(69, shift = true))
    }

    @Test
    fun keyCodeFallbackMapsNumpad() {
        // KEYCODE_NUMPAD_0 = 144, KEYCODE_NUMPAD_9 = 153
        assertEquals('0'.code, InputTranscoder.printableForKey(144, shift = false))
        assertEquals('9'.code, InputTranscoder.printableForKey(153, shift = false))
    }

    @Test
    fun unknownKeyCodeHasNoPrintableCharacter() {
        assertNull(InputTranscoder.printableForKey(0, shift = false))
        assertNull(InputTranscoder.printableForKey(66, shift = false)) // KEYCODE_ENTER
    }

    @Test
    fun ctrlCFromKeyCodeIsEtx() {
        assertEquals("\u0003", InputTranscoder.mapKeyCharacter('c'.code, ctrl = true))
        assertEquals("c", InputTranscoder.mapKeyCharacter('c'.code, ctrl = false))
    }

    @Test
    fun supplementaryCharacterFromKeyCodeIsIntact() {
        assertEquals("\uD83D\uDE80", InputTranscoder.mapKeyCharacter(0x1F680, ctrl = false))
    }
}
