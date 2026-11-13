package com.zkkm.pocketssh.term

/**
 * Pure-Kotlin translation between what an IME / hardware keyboard produces and
 * the bytes a POSIX PTY expects.
 *
 * No Android framework types are referenced here on purpose: all the rules can
 * be exercised by plain JVM unit tests, which is important because input bugs
 * are otherwise only reproducible on a physical device.
 *
 * Key codes are inlined as numeric constants (they are part of the Android API
 * contract) so local tests never need to load `android.view.KeyEvent`.
 */
object InputTranscoder {

    // android.view.KeyEvent constants, inlined.
    private const val KEYCODE_0 = 7            // .. KEYCODE_9 = 16
    private const val KEYCODE_9 = 16
    private const val KEYCODE_A = 29           // .. KEYCODE_Z = 54
    private const val KEYCODE_Z = 54
    private const val KEYCODE_NUMPAD_0 = 144  // .. KEYCODE_NUMPAD_9 = 153
    private const val KEYCODE_NUMPAD_9 = 153
    private const val KEYCODE_COMMA = 55
    private const val KEYCODE_PERIOD = 56
    private const val KEYCODE_SPACE = 62
    private const val KEYCODE_GRAVE = 68
    private const val KEYCODE_MINUS = 69
    private const val KEYCODE_EQUALS = 70
    private const val KEYCODE_LEFT_BRACKET = 71
    private const val KEYCODE_RIGHT_BRACKET = 72
    private const val KEYCODE_BACKSLASH = 73
    private const val KEYCODE_SEMICOLON = 74
    private const val KEYCODE_APOSTROPHE = 75
    private const val KEYCODE_SLASH = 76
    private const val KEYCODE_AT = 77
    private const val KEYCODE_STAR = 17
    private const val KEYCODE_POUND = 18
    private const val KEYCODE_PLUS = 81
    private const val KEYCODE_NUMPAD_DOT = 158

    /**
     * Convert IME-committed text into PTY bytes.
     *
     * A PTY in canonical mode needs CR (0x0D) to submit a line. IMEs are
     * inconsistent: they commit "\n", "\r", "\r\n" or send an editor action.
     * Dropping CR here is what makes the Enter key look dead, so every newline
     * form maps to exactly one CR.
     */
    fun normalizeText(text: String, ctrl: Boolean = false, alt: Boolean = false): String {
        val out = StringBuilder(text.length)
        for (i in text.indices) {
            val ch = text[i]
            when {
                ctrl -> out.append(controlCodeFor(ch).toChar())
                alt -> out.append('\u001b').append(ch)
                ch == '\r' -> out.append('\r')
                ch == '\n' -> {
                    // CRLF is one Enter, not two.
                    if (i == 0 || text[i - 1] != '\r') out.append('\r')
                }
                else -> out.append(ch)
            }
        }
        return out.toString()
    }

    /**
     * Map a single printable key character to terminal bytes. ALT is applied
     * by the caller so that escape sequences are not wrapped twice.
     */
    fun mapKeyCharacter(codePoint: Int, ctrl: Boolean): String {
        val text = String(Character.toChars(codePoint))
        return if (ctrl && text.length == 1) {
            controlCodeFor(text[0]).toChar().toString()
        } else {
            text
        }
    }

    /**
     * Fallback for IMEs that send raw key events without a `unicodeChar`
     * (common on "password-style" fields and some Chinese keyboards).
     * Returns the code point the key stands for, or null for non-printable keys.
     */
    fun printableForKey(keyCode: Int, shift: Boolean): Int? {
        val plain: Char = when (keyCode) {
            in KEYCODE_A..KEYCODE_Z -> ('a'.code + keyCode - KEYCODE_A).toChar()
            in KEYCODE_0..KEYCODE_9 -> ('0'.code + keyCode - KEYCODE_0).toChar()
            in KEYCODE_NUMPAD_0..KEYCODE_NUMPAD_9 -> ('0'.code + keyCode - KEYCODE_NUMPAD_0).toChar()
            KEYCODE_COMMA -> ','
            KEYCODE_PERIOD -> '.'
            KEYCODE_SPACE -> ' '
            KEYCODE_GRAVE -> '`'
            KEYCODE_MINUS -> '-'
            KEYCODE_EQUALS -> '='
            KEYCODE_LEFT_BRACKET -> '['
            KEYCODE_RIGHT_BRACKET -> ']'
            KEYCODE_BACKSLASH -> '\\'
            KEYCODE_SEMICOLON -> ';'
            KEYCODE_APOSTROPHE -> '\''
            KEYCODE_SLASH -> '/'
            KEYCODE_AT -> '@'
            KEYCODE_STAR -> '*'
            KEYCODE_POUND -> '#'
            KEYCODE_PLUS -> '+'
            KEYCODE_NUMPAD_DOT -> '.'
            else -> return null
        }
        return (if (shift) shiftOf(plain) else plain).code
    }

    /** Ctrl+<key> produces the matching ASCII control code. */
    fun controlCodeFor(ch: Char): Int = when (ch) {
        in 'a'..'z' -> ch.code - 'a'.code + 1
        in 'A'..'Z' -> ch.code - 'A'.code + 1
        ' ', '@' -> 0
        '\\' -> 28
        ']' -> 29
        '^' -> 30
        '_' -> 31
        '?' -> 127
        else -> ch.code
    }

    private fun shiftOf(ch: Char): Char = when (ch) {
        in 'a'..'z' -> (ch.code - 'a'.code + 'A'.code).toChar()
        '0' -> ')'
        '1' -> '!'
        '2' -> '@'
        '3' -> '#'
        '4' -> '$'
        '5' -> '%'
        '6' -> '^'
        '7' -> '&'
        '8' -> '*'
        '9' -> '('
        ',' -> '<'
        '.' -> '>'
        '`' -> '~'
        '-' -> '_'
        '=' -> '+'
        '[' -> '{'
        ']' -> '}'
        '\\' -> '|'
        ';' -> ':'
        '\'' -> '"'
        '/' -> '?'
        else -> ch
    }
}
