package dev.kogler.a7iiiconnect.develop

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.Closeable
import java.nio.FloatBuffer
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Looks at one shot on the device: Google's ML Kit finds faces (with how open the eyes are), names
 * what's in the picture and reads any text; [Sharpness] measures focus; [FaceEmbedder] tells the
 * faces apart. ML Kit's models come with Google Play services, so nothing leaves the phone.
 */
class Analyzer(private val context: Context) : Closeable {
    private val faces = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .setMinFaceSize(0.04f)
            .build(),
    )
    private val labeler = ImageLabeling.getClient(ImageLabelerOptions.Builder().setConfidenceThreshold(LABEL_CONFIDENCE).build())
    private val reader = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val embedder: FaceEmbedder? = runCatching { FaceEmbedder(context) }
        .onFailure { Log.w(TAG, "face recognition unavailable", it) }.getOrNull()

    /** Null when it couldn't look (a model still downloading, say): it's tried again later. */
    fun analyse(shot: Shot): ShotInsight? {
        val full = Photos.cullImage(context, shot, EDGE) ?: return null
        val scale = minOf(1f, EDGE.toFloat() / maxOf(full.width, full.height))
        val small = if (scale < 1f) Bitmap.createScaledBitmap(full, (full.width * scale).toInt(), (full.height * scale).toInt(), true) else full
        try {
            val image = InputImage.fromBitmap(small, 0)
            val found = Tasks.await(faces.process(image), 1, TimeUnit.MINUTES)
            val labels = Tasks.await(labeler.process(image), 1, TimeUnit.MINUTES).map { it.text }
            val text = Tasks.await(reader.process(image), 1, TimeUnit.MINUTES).text
                .replace(Regex("\\s+"), " ").trim().take(MAX_TEXT)

            val w = small.width; val h = small.height
            val px = IntArray(w * h).also { small.getPixels(it, 0, w, 0, 0, w, h) }
            val overall = Sharpness.of(Sharpness.grey(px), w, h)

            val infos = found.map { f ->
                val b = f.boundingBox
                val box = floatArrayOf(
                    (b.left / w.toFloat()).coerceIn(0f, 1f), (b.top / h.toFloat()).coerceIn(0f, 1f),
                    (b.right / w.toFloat()).coerceIn(0f, 1f), (b.bottom / h.toFloat()).coerceIn(0f, 1f),
                )
                val turned = abs(f.headEulerAngleY) > TURNED
                val l = f.leftEyeOpenProbability
                val r = f.rightEyeOpenProbability
                val eyes = if (turned || l == null || r == null) null else minOf(l, r)
                // The face's focus, from the full-size picture: a face is small in a scaled one.
                val sharp = faceSharpness(full, box)
                val points = listOf(FaceLandmark.RIGHT_EYE, FaceLandmark.LEFT_EYE, FaceLandmark.NOSE_BASE, FaceLandmark.MOUTH_RIGHT, FaceLandmark.MOUTH_LEFT)
                    .map { f.getLandmark(it)?.position }
                val big = (box[2] - box[0]) * full.width >= MIN_FACE_PX
                val embedding = if (embedder != null && big && !turned && points.all { it != null }) runCatching {
                    embedder.embed(full, points.map { PointF(it!!.x / scale, it.y / scale) })
                }.onFailure { Log.w(TAG, "face embedding failed", it) }.getOrNull() else null
                FaceInfo(box, eyes, f.smilingProbability, sharp, embedding)
            }
            val main = infos.maxByOrNull { it.width }
            return ShotInsight(infos, main?.sharp ?: overall, labels, text)
        } catch (e: Exception) {
            Log.w(TAG, "couldn't look at ${shot.key}", e)
            return null
        } finally {
            if (small !== full) small.recycle()
            full.recycle()
        }
    }

    /** [Sharpness] of the middle of a face (the edges are often hair and background). */
    private fun faceSharpness(bmp: Bitmap, box: FloatArray): Float {
        val w = bmp.width; val h = bmp.height
        val bw = (box[2] - box[0]) * w; val bh = (box[3] - box[1]) * h
        val l = (box[0] * w + bw * 0.15f).toInt().coerceIn(0, w - 1)
        val t = (box[1] * h + bh * 0.15f).toInt().coerceIn(0, h - 1)
        val r = (box[2] * w - bw * 0.15f).toInt().coerceIn(l + 1, w)
        val b = (box[3] * h - bh * 0.15f).toInt().coerceIn(t + 1, h)
        val cw = r - l; val ch = b - t
        if (cw < 8 || ch < 8) return 0f
        val px = IntArray(cw * ch).also { bmp.getPixels(it, 0, cw, l, t, cw, ch) }
        return Sharpness.of(Sharpness.grey(px), cw, ch)
    }

    override fun close() {
        faces.close(); labeler.close(); reader.close(); embedder?.close()
    }

    companion object {
        private const val TAG = "Analyzer"
        /** Long edge of the picture the models see. */
        private const val EDGE = 1600
        private const val LABEL_CONFIDENCE = 0.65f
        private const val MAX_TEXT = 400
        /** Faces turned further than this (degrees) are profiles: no eye check, no recognising. */
        private const val TURNED = 35f
        /** Faces narrower than this many pixels are too small to recognise. */
        private const val MIN_FACE_PX = 56
    }
}

/**
 * Tells faces apart: SFace (a MobileFaceNet from OpenCV's model zoo, Apache 2.0) turns a face,
 * lined up on its eyes, nose and mouth, into 128 numbers. Two pictures of the same person point
 * the same way; see [People].
 */
class FaceEmbedder(context: Context) : Closeable {
    private val env = OrtEnvironment.getEnvironment()
    private val session = env.createSession(context.assets.open("sface_int8.onnx").use { it.readBytes() }, OrtSession.SessionOptions())

    /** [points]: both eyes, the nose, then both mouth corners (each pair in either order). */
    fun embed(bmp: Bitmap, points: List<PointF>): FloatArray {
        // The template goes left to right on the picture.
        val ordered = points.subList(0, 2).sortedBy { it.x } + points[2] + points.subList(3, 5).sortedBy { it.x }
        val a = FaceAlign.similarity(ordered.flatMap { listOf(it.x, it.y) }.toFloatArray(), FaceAlign.TEMPLATE)
        val side = FaceAlign.SIDE
        val crop = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        val m = Matrix().apply { setValues(floatArrayOf(a[0], a[1], a[2], a[3], a[4], a[5], 0f, 0f, 1f)) }
        Canvas(crop).drawBitmap(bmp, m, Paint(Paint.FILTER_BITMAP_FLAG))
        val px = IntArray(side * side).also { crop.getPixels(it, 0, side, 0, 0, side, side) }
        crop.recycle()
        // Planes of red, green and blue, 0..255, as the model was trained.
        val n = side * side
        val input = FloatBuffer.allocate(3 * n)
        for (i in 0 until n) input.put(i, (px[i] shr 16 and 0xFF).toFloat())
        for (i in 0 until n) input.put(n + i, (px[i] shr 8 and 0xFF).toFloat())
        for (i in 0 until n) input.put(2 * n + i, (px[i] and 0xFF).toFloat())
        OnnxTensor.createTensor(env, input, longArrayOf(1, 3, side.toLong(), side.toLong())).use { t ->
            session.run(mapOf("data" to t)).use { out ->
                @Suppress("UNCHECKED_CAST")
                val v = (out[0].value as Array<FloatArray>)[0]
                return ShotInsight.normalize(v.copyOf())
            }
        }
    }

    override fun close() = session.close()
}
