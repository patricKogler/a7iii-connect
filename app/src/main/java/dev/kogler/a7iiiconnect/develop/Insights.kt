package dev.kogler.a7iiiconnect.develop

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Base64

/** A face in a shot. [box] is left, top, right, bottom as fractions of the upright picture. */
class FaceInfo(
    val box: FloatArray,
    /** How open the more closed eye is, 0..1; null when it couldn't tell (a face in profile). */
    val eyesOpen: Float?,
    val smile: Float?,
    /** [Sharpness] of the face. */
    val sharp: Float,
    /** Who it is, as a unit vector ([FaceEmbedder]); null for faces too small or too turned. */
    val embedding: FloatArray?,
) {
    val width get() = box[2] - box[0]
}

/**
 * What looking at a shot found: its faces, how sharp it is, what's in it ([labels], from ML Kit's
 * image labeller) and any [text] in it. Worked out once per shot and kept in develop/insights.txt.
 */
class ShotInsight(
    val faces: List<FaceInfo>,
    /** The sharpness that matters: the biggest face's when there are faces, else the sharpest part. */
    val sharp: Float,
    val labels: List<String>,
    val text: String,
    val version: Int = VERSION,
) {
    fun encode(): String = JSONObject().apply {
        put("v", version)
        put("s", sharp.toDouble())
        if (labels.isNotEmpty()) put("l", JSONArray(labels))
        if (text.isNotEmpty()) put("t", text)
        if (faces.isNotEmpty()) put("f", JSONArray(faces.map { f ->
            JSONObject().apply {
                put("b", JSONArray(f.box.map { "%.4f".format(java.util.Locale.US, it).toDouble() }))
                f.eyesOpen?.let { put("e", it.toDouble()) }
                f.smile?.let { put("m", it.toDouble()) }
                put("s", f.sharp.toDouble())
                f.embedding?.let { put("id", packEmbedding(it)) }
            }
        }))
    }.toString()

    companion object {
        /** Bump to look at every shot again after the analysis changes. */
        const val VERSION = 1

        fun decode(s: String): ShotInsight? = runCatching {
            val o = JSONObject(s)
            val faces = o.optJSONArray("f")?.let { a ->
                (0 until a.length()).map { i ->
                    val f = a.getJSONObject(i)
                    val b = f.getJSONArray("b")
                    FaceInfo(
                        FloatArray(4) { b.getDouble(it).toFloat() },
                        if (f.has("e")) f.getDouble("e").toFloat() else null,
                        if (f.has("m")) f.getDouble("m").toFloat() else null,
                        f.optDouble("s", 0.0).toFloat(),
                        f.optString("id").takeIf { it.isNotEmpty() }?.let(::unpackEmbedding),
                    )
                }
            } ?: emptyList()
            val labels = o.optJSONArray("l")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
            ShotInsight(faces, o.optDouble("s", 0.0).toFloat(), labels, o.optString("t"), o.optInt("v"))
        }.getOrNull()

        /** A unit vector in a byte per value (plenty for telling faces apart), base64. */
        fun packEmbedding(e: FloatArray): String =
            Base64.getEncoder().encodeToString(ByteArray(e.size) { (e[it] * 127f).toInt().coerceIn(-127, 127).toByte() })

        fun unpackEmbedding(s: String): FloatArray? = runCatching {
            val b = Base64.getDecoder().decode(s)
            normalize(FloatArray(b.size) { b[it] / 127f })
        }.getOrNull()

        fun normalize(v: FloatArray): FloatArray {
            var n = 0f
            for (x in v) n += x * x
            n = kotlin.math.sqrt(n)
            if (n > 0f) for (i in v.indices) v[i] /= n
            return v
        }
    }
}

/**
 * Every shot's [ShotInsight], looked at in the background as photos arrive (newest first) and when
 * the review deck needs them. Only one shot is analysed at a time, whoever asks.
 */
object Insights {
    private var cache: MutableMap<String, ShotInsight>? = null
    private val analysing = Any()

    @Synchronized
    fun known(context: Context): Map<String, ShotInsight> = HashMap(load(context))

    /** Looks at the shots not done yet, reporting what's known after every [batch]. */
    fun index(context: Context, shots: List<Shot>, batch: Int = 10, keepGoing: () -> Boolean = { true }, onProgress: (Map<String, ShotInsight>) -> Unit) {
        val missing = missing(context, shots.sortedByDescending { it.takenMillis })
        if (missing.isEmpty()) return
        Analyzer(context).use { analyzer ->
            for (chunk in missing.chunked(batch)) {
                if (!keepGoing()) return
                for (shot in chunk) {
                    if (!keepGoing()) return
                    analyse(context, analyzer, shot)
                }
                onProgress(known(context))
            }
        }
    }

    /** Looks at [shots] in the order given (blocking), reporting after each: the review deck's next cards first. */
    fun ensure(context: Context, shots: List<Shot>, keepGoing: () -> Boolean = { true }, onEach: (Map<String, ShotInsight>) -> Unit = {}) {
        val missing = missing(context, shots)
        if (missing.isEmpty()) return
        Analyzer(context).use { analyzer ->
            for (shot in missing) {
                if (!keepGoing()) return
                analyse(context, analyzer, shot)
                onEach(known(context))
            }
        }
    }

    private fun missing(context: Context, shots: List<Shot>): List<Shot> = synchronized(this) {
        val have = load(context)
        shots.filter { have[it.key]?.version != ShotInsight.VERSION }
    }

    private fun analyse(context: Context, analyzer: Analyzer, shot: Shot) {
        synchronized(analysing) { analyseLocked(context, analyzer, shot) }
    }

    private fun analyseLocked(context: Context, analyzer: Analyzer, shot: Shot) {
        // The other caller may have done it while this one waited.
        if (synchronized(this) { load(context)[shot.key]?.version == ShotInsight.VERSION }) return
        val found = runCatching { analyzer.analyse(shot) }.getOrNull() ?: return
        synchronized(this) {
            load(context)[shot.key] = found
            runCatching {
                file(context).apply { parentFile?.mkdirs() }.appendText("${shot.key}\t${found.encode()}\n")
            }
        }
    }

    private fun file(context: Context) = File(context.filesDir, "develop/insights.txt")

    /** Lines are only ever added; a later line for the same shot wins. Rewritten when mostly stale. */
    private fun load(context: Context): MutableMap<String, ShotInsight> = cache ?: run {
        val map = HashMap<String, ShotInsight>()
        var lines = 0
        runCatching {
            file(context).forEachLine { line ->
                lines++
                val tab = line.indexOf('\t')
                if (tab > 0) ShotInsight.decode(line.substring(tab + 1))?.let { map[line.substring(0, tab)] = it }
            }
        }
        if (lines > map.size * 3 / 2 + 50) runCatching {
            file(context).writeText(map.entries.joinToString("") { "${it.key}\t${it.value.encode()}\n" })
        }
        map
    }.also { cache = it }
}
