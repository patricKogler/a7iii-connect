package dev.kogler.a7iiiconnect.develop.engine

/**
 * The Rust engine (native/, libdevelop.so): the full-size demosaic. Every call returns false
 * when the library is missing or rejects its input, and callers then use the Kotlin demosaic,
 * which stays the reference.
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
}
