package dev.kogler.a7iiiconnect.develop.engine

/**
 * The Rust engine (native/, libdevelop.so): full-size demosaic and the export render. Every
 * call returns false when the library is missing or rejects its input, and callers then use
 * the Kotlin engine, which stays the reference.
 */
object Native {
    /** Set false to force the Kotlin engine (tests compare the two). */
    @Volatile var enabled = true

    private val loaded: Boolean = try {
        System.loadLibrary("develop")
        true
    } catch (e: Throwable) {
        false
    }

    val available get() = enabled && loaded

    @JvmStatic external fun demosaic(
        data: ShortArray, rawWidth: Int, cfa: IntArray, left: Int, top: Int, width: Int, height: Int,
        black: IntArray, scale: FloatArray, x0: Int, y0: Int, x1: Int, y1: Int, out: ShortArray, outOffset: Int,
    ): Boolean

    @JvmStatic external fun render(
        params: FloatArray, tone: FloatArray, curves: FloatArray?,
        guideSize: IntArray?, base: FloatArray?, medium: FloatArray?, dark: FloatArray?, air: FloatArray?,
        src: ShortArray, srcInfo: DoubleArray, grid: FloatArray, gridInfo: FloatArray, vig: FloatArray?,
        frame: DoubleArray, y0: Int, y1: Int, out: IntArray,
    ): Boolean
}
