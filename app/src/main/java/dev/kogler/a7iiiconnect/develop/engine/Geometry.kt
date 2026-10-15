package dev.kogler.a7iiiconnect.develop.engine

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Where each output pixel comes from: crop, 90-degree turns and flips, straighten, Geometry
 * (perspective, rotate, scale, offset) and lens corrections, all composed into one inverse map
 * from output position to source position, sampled once. Units are pixels of the full-size,
 * upright source ([srcW] x [srcH]), so the same map serves the preview and the export.
 */
class Geometry(
    s: Settings,
    val srcW: Double,
    val srcH: Double,
    lens: LensData?,
    /** True while cropping: show the whole frame so the crop box can be moved. */
    ignoreCrop: Boolean = false,
) {
    private val hd = hypot(srcW / 2, srcH / 2)
    private val rot90 = s[P.ROTATE_90].toInt().mod(4)
    private val flipH = s.on(P.FLIP_H)
    private val flipV = s.on(P.FLIP_V)
    /** Frame = the source after 90-degree turns. */
    val frameW = if (rot90 % 2 == 1) srcH else srcW
    val frameH = if (rot90 % 2 == 1) srcW else srcH
    private val cropL = if (ignoreCrop) 0.0 else s[P.CROP_LEFT].toDouble()
    private val cropT = if (ignoreCrop) 0.0 else s[P.CROP_TOP].toDouble()
    private val cropR = if (ignoreCrop) 1.0 else s[P.CROP_RIGHT].toDouble()
    private val cropB = if (ignoreCrop) 1.0 else s[P.CROP_BOTTOM].toDouble()
    val outW = ((cropR - cropL) * frameW).coerceAtLeast(16.0)
    val outH = ((cropB - cropT) * frameH).coerceAtLeast(16.0)

    private val angle = (s[P.STRAIGHTEN] + s[P.ROTATE]) * PI / 180
    private val cosA = cos(-angle)
    private val sinA = sin(-angle)
    private val vert = s[P.VERTICAL] / 100.0 * 0.35
    private val horiz = s[P.HORIZONTAL] / 100.0 * 0.35
    private val offX = s[P.OFFSET_X] / 100.0 * 0.2
    private val offY = s[P.OFFSET_Y] / 100.0 * 0.2
    private val manualK = -s[P.MANUAL_DISTORTION] / 100.0 * 0.2

    // Lens profile splines (darktable's reading of Sony's data): knots over radius 0..1.
    private val useLens = lens != null && s.on(P.LENS_PROFILE)
    private val n = lens?.n ?: 0
    private val knots = DoubleArray(n) { (it + 0.5) / (n - 1) }
    private val distG = DoubleArray(n)
    private val distR = DoubleArray(n)
    private val distB = DoubleArray(n)
    private val vig = DoubleArray(n)
    private val manualVig = s[P.MANUAL_VIGNETTING] / 100.0
    private val manualVigMid = 0.25 + s[P.MANUAL_VIGNETTING_MIDPOINT] / 100.0 * 0.6

    init {
        if (useLens && lens != null) {
            val dAmt = s[P.LENS_DISTORTION] / 100.0
            val vAmt = s[P.LENS_VIGNETTING] / 100.0
            val ca = s.on(P.REMOVE_CA)
            for (i in 0 until n) {
                distG[i] = dAmt * lens.distortion[i] * 2.0.pow(-14) + 1
                distR[i] = distG[i] * (if (ca) lens.caRed[i] * 2.0.pow(-21) + 1 else 1.0)
                distB[i] = distG[i] * (if (ca) lens.caBlue[i] * 2.0.pow(-21) + 1 else 1.0)
                vig[i] = 2.0.pow(0.5 - 2.0.pow(vAmt * lens.vignetting[i] * 2.0.pow(-13) - 1))
            }
        }
    }

    /** Scale that keeps the corners filled after straightening and lens correction. */
    private val autoScale: Double = run {
        val a = abs(angle)
        val rot = maxOf((srcW * cos(a) + srcH * sin(a)) / srcW, (srcW * sin(a) + srcH * cos(a)) / srcH)
        var lensScale = 1.0
        if (useLens || manualK != 0.0) {
            // Largest radial stretch along the frame edge (corners and edge midpoints).
            for (r in listOf(1.0, srcW / 2 / hd, srcH / 2 / hd)) lensScale = maxOf(lensScale, radial(r, distG))
        }
        rot * lensScale
    }
    private val scale = s[P.SCALE] / 100.0 * autoScale

    val isIdentity = rot90 == 0 && !flipH && !flipV && cropL == 0.0 && cropT == 0.0 && cropR == 1.0 && cropB == 1.0 &&
        angle == 0.0 && vert == 0.0 && horiz == 0.0 && offX == 0.0 && offY == 0.0 && manualK == 0.0 && !useLens &&
        s[P.SCALE] == 100f

    val hasVignetteCorrection = useLens || manualVig != 0.0
    val hasChromaticShift = useLens && s.on(P.REMOVE_CA)

    private fun spline(table: DoubleArray, r: Double): Double {
        if (n == 0) return 1.0
        if (r <= knots[0]) return table[0]
        for (i in 1 until n) if (r <= knots[i]) {
            val t = (r - knots[i - 1]) / (knots[i] - knots[i - 1])
            return table[i - 1] + (table[i] - table[i - 1]) * t
        }
        return table[n - 1]
    }

    private fun radial(r: Double, table: DoubleArray): Double =
        (if (useLens) spline(table, r) else 1.0) * (1 + manualK * r * r)

    /**
     * Maps output pixel ([ox], [oy]) to source pixel positions for red, green and blue
     * (they differ only with chromatic-aberration correction): out = [rx, ry, gx, gy, bx, by].
     */
    fun map(ox: Double, oy: Double, out: DoubleArray) {
        // Output -> frame (undo crop) -> unflipped -> unrotated source frame.
        var fx = cropL * frameW + ox
        var fy = cropT * frameH + oy
        if (flipH) fx = frameW - fx
        if (flipV) fy = frameH - fy
        val x: Double; val y: Double
        when (rot90) {
            1 -> { x = fy; y = srcH - fx }
            2 -> { x = srcW - fx; y = srcH - fy }
            3 -> { x = srcW - fy; y = fx }
            else -> { x = fx; y = fy }
        }
        // Centred, half-diagonal units.
        var qx = (x - srcW / 2) / hd
        var qy = (y - srcH / 2) / hd
        // Undo straighten/rotate, scale and offset.
        val rx = qx * cosA - qy * sinA
        val ry = qx * sinA + qy * cosA
        qx = rx / scale - offX
        qy = ry / scale - offY
        // Undo perspective (a keystone homography).
        val w = 1 - vert * qy - horiz * qx
        qx /= w; qy /= w
        // Lens: corrected -> distorted position, per channel.
        val r = sqrt(qx * qx + qy * qy)
        val m = 1 + manualK * r * r
        if (useLens) {
            val g = spline(distG, r) * m
            out[2] = qx * g * hd + srcW / 2; out[3] = qy * g * hd + srcH / 2
            if (hasChromaticShift) {
                val rr = spline(distR, r) * m
                val bb = spline(distB, r) * m
                out[0] = qx * rr * hd + srcW / 2; out[1] = qy * rr * hd + srcH / 2
                out[4] = qx * bb * hd + srcW / 2; out[5] = qy * bb * hd + srcH / 2
            } else {
                out[0] = out[2]; out[1] = out[3]; out[4] = out[2]; out[5] = out[3]
            }
        } else {
            out[2] = qx * m * hd + srcW / 2; out[3] = qy * m * hd + srcH / 2
            out[0] = out[2]; out[1] = out[3]; out[4] = out[2]; out[5] = out[3]
        }
    }

    /** Brightness gain that undoes lens vignetting (and manual Optics vignetting) at a source pixel. */
    fun vignetteGain(sx: Double, sy: Double): Float {
        val r = hypot(sx - srcW / 2, sy - srcH / 2) / hd
        var g = 1.0
        if (useLens) g /= maxOf(1e-4, spline(vig, r))
        if (manualVig != 0.0) {
            val t = (r / manualVigMid).coerceAtMost(1.6)
            g *= 1 + manualVig * 0.8 * t * t * t * t / 4
        }
        return g.toFloat()
    }
}
