package com.zkkm.pocketssh.term

/** xterm-256 palette plus the One-Dark-flavoured 16 base colours used by the app. */
object AnsiPalette {

    private const val RGB_BLACK = 0xFF282C34.toInt()
    private const val RGB_RED = 0xFFE06C75.toInt()
    private const val RGB_GREEN = 0xFF98C379.toInt()
    private const val RGB_YELLOW = 0xFFE5C07B.toInt()
    private const val RGB_BLUE = 0xFF61AFEF.toInt()
    private const val RGB_MAGENTA = 0xFFC678DD.toInt()
    private const val RGB_CYAN = 0xFF56B6C2.toInt()
    private const val RGB_WHITE = 0xFFABB2BF.toInt()

    private const val RGB_BRIGHT_BLACK = 0xFF5C6370.toInt()
    private const val RGB_BRIGHT_RED = 0xFFEF7A83.toInt()
    private const val RGB_BRIGHT_GREEN = 0xFFA8E08A.toInt()
    private const val RGB_BRIGHT_YELLOW = 0xFFFFD68A.toInt()
    private const val RGB_BRIGHT_BLUE = 0xFF8CC7FF.toInt()
    private const val RGB_BRIGHT_MAGENTA = 0xFFE09BEF.toInt()
    private const val RGB_BRIGHT_CYAN = 0xFF7FDCE7.toInt()
    private const val RGB_BRIGHT_WHITE = 0xFFFFFFFF.toInt()

    private val COLORS = IntArray(256)

    init {
        intArrayOf(
            RGB_BLACK, RGB_RED, RGB_GREEN, RGB_YELLOW,
            RGB_BLUE, RGB_MAGENTA, RGB_CYAN, RGB_WHITE,
            RGB_BRIGHT_BLACK, RGB_BRIGHT_RED, RGB_BRIGHT_GREEN, RGB_BRIGHT_YELLOW,
            RGB_BRIGHT_BLUE, RGB_BRIGHT_MAGENTA, RGB_BRIGHT_CYAN, RGB_BRIGHT_WHITE
        ).copyInto(COLORS, 0)

        // 6x6x6 colour cube.
        val levels = intArrayOf(0, 95, 135, 175, 215, 255)
        var index = 16
        for (r in 0..5) {
            for (g in 0..5) {
                for (b in 0..5) {
                    COLORS[index++] = 0xFF000000.toInt() or
                        (levels[r] shl 16) or (levels[g] shl 8) or levels[b]
                }
            }
        }
        // Greyscale ramp.
        for (i in 0..23) {
            val v = 8 + i * 10
            COLORS[index++] = 0xFF000000.toInt() or (v shl 16) or (v shl 8) or v
        }
    }

    fun base(index: Int): Int = COLORS[index and 0xFF]

    /**
     * Turn a buffer colour spec into a concrete ARGB colour.
     *
     * @param bold when true, base palette entries 0..7 are promoted to their bright variant.
     */
    fun resolve(spec: Int, defaultColor: Int, bold: Boolean = false): Int {
        if (spec == TermColor.DEFAULT) return defaultColor
        if (TermColor.isPalette(spec)) {
            var index = TermColor.paletteIndex(spec)
            if (bold && index < 8) index += 8
            return COLORS[index and 0xFF]
        }
        return 0xFF000000.toInt() or TermColor.rgbValue(spec)
    }
}
