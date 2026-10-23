package dev.kogler.a7iiiconnect.develop

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.net.Uri
import android.provider.OpenableColumns
import dev.kogler.a7iiiconnect.develop.engine.Lut
import dev.kogler.a7iiiconnect.develop.engine.Luts
import java.io.File
import java.util.zip.ZipInputStream

/**
 * The film LUTs imported on this phone: .cube files, Hald CLUT PNGs, or a .zip of either (such as
 * RawTherapee's film simulation pack). Each is resampled to [Lut.SIZE] and kept in
 * develop/luts as <id>.lut; luts.txt lists id, name and the folder it came from, which groups
 * them in the Looks panel. Edits name a LUT by id, and [Luts] reads it on first use.
 */
object LutLibrary {
    data class Entry(val id: String, val name: String, val folder: String)

    @Volatile
    private var entries: List<Entry>? = null

    private fun dir(context: Context) = File(context.filesDir, "develop/luts").apply { mkdirs() }
    private fun index(context: Context) = File(context.filesDir, "develop/luts.txt")

    /** Reads the list and registers every LUT with [Luts] (once per process). */
    fun ensure(context: Context): List<Entry> = entries ?: synchronized(this) {
        entries ?: run {
            val app = context.applicationContext
            val list = runCatching {
                index(app).readLines().mapNotNull { line ->
                    line.split('\t').takeIf { it.size >= 3 }?.let { Entry(it[0], it[1], it[2]) }
                }
            }.getOrDefault(emptyList())
            for (e in list) register(app, e)
            entries = list
            list
        }
    }

    private fun register(context: Context, e: Entry) {
        val file = File(dir(context), "${e.id}.lut")
        Luts.register(e.id) { file.inputStream().use { Lut.read(it) } }
    }

    fun name(context: Context, id: String?): String? = id?.let { i -> ensure(context).firstOrNull { it.id == i }?.name }

    /** What an import did: how many looks it added and how many files it couldn't use. */
    data class Result(val added: List<Entry>, val skipped: Int)

    /**
     * Imports the LUTs in [uri] (one file or a zip of many); [progress] gets the count so far.
     * Slow for a big pack: run it off the main thread.
     */
    fun import(context: Context, uri: Uri, progress: (Int) -> Unit = {}): Result {
        val app = context.applicationContext
        val name = displayName(app, uri) ?: uri.lastPathSegment ?: "LUT"
        val added = ArrayList<Entry>()
        var skipped = 0
        fun take(path: String, bytes: ByteArray, folder: String) {
            val lut = runCatching { decode(path, bytes) }.getOrNull()
            if (lut == null) { skipped++; return }
            val (table, title) = lut
            val stem = path.substringAfterLast('/').substringBeforeLast('.')
            added += store(app, table.resized(Lut.SIZE), (title ?: stem).trim().ifEmpty { "LUT" }, folder)
            progress(added.size)
        }
        app.contentResolver.openInputStream(uri)?.use { input ->
            if (name.endsWith(".zip", ignoreCase = true)) {
                val zip = ZipInputStream(input.buffered())
                while (true) {
                    val e = zip.nextEntry ?: break
                    val path = e.name
                    if (e.isDirectory || !isLut(path) || path.substringAfterLast('/').startsWith(".") || "__MACOSX" in path) continue
                    // The folder right above the file: a pack's brand ("Kodak", "Fujifilm") or its name.
                    val folder = path.substringBeforeLast('/', "").substringAfterLast('/')
                        .ifEmpty { name.substringBeforeLast('.') }
                    take(path, zip.readBytes(), folder)
                }
            } else if (isLut(name)) {
                take(name, input.readBytes(), "Imported")
            } else {
                skipped++
            }
        } ?: skipped++
        if (added.isNotEmpty()) save(app, ensure(app) + added)
        return Result(added, skipped)
    }

    fun delete(context: Context, id: String) {
        val app = context.applicationContext
        File(dir(app), "$id.lut").delete()
        File(dir(app), "$id.jpg").delete()
        Luts.unregister(id)
        save(app, ensure(app).filter { it.id != id })
    }

    /** Deletes every LUT that came from [folder]. */
    fun deleteFolder(context: Context, folder: String) {
        val app = context.applicationContext
        val (gone, kept) = ensure(app).partition { it.folder == folder }
        for (e in gone) { File(dir(app), "${e.id}.lut").delete(); File(dir(app), "${e.id}.jpg").delete(); Luts.unregister(e.id) }
        save(app, kept)
    }

    /** A look's preview picture, when its pack came with one (the G'MIC pack does). */
    fun thumbnail(context: Context, id: String): File? = File(dir(context), "$id.jpg").takeIf { it.exists() }

    /**
     * Downloads the G'MIC film pack (github.com/YahiaAngelo/Film-Luts, 296 film stocks) onto this
     * phone when asked. It isn't shipped in the app: the LUTs' origin and licence are unclear.
     * Its film_luts.json gives each look its name, category and thumbnail. Ids are fixed per
     * file ("gmic-…"), so two phones that both have the pack name the same look the same way, and
     * running it again only fetches what's missing. [progress] gets (done, total).
     */
    fun downloadGmicPack(context: Context, progress: (Int, Int) -> Unit = { _, _ -> }): Result {
        val app = context.applicationContext
        val list = GmicPack.parse(fetch(GmicPack.BASE + GmicPack.INDEX).decodeToString())
        val have = ensure(app).mapTo(HashSet()) { it.id }
        val added = ArrayList<Entry>()
        var skipped = 0
        var done = 0
        val pool = java.util.concurrent.Executors.newFixedThreadPool(4)
        try {
            val jobs = list.map { item ->
                pool.submit<Entry?> {
                    if (item.id in have) return@submit null
                    val lut = Lut.parseCube(fetch(GmicPack.BASE + item.file).decodeToString()).first
                    item.thumbnail?.let { t -> runCatching { File(dir(app), "${item.id}.jpg").writeBytes(fetch(GmicPack.BASE + t)) } }
                    synchronized(this) { store(app, lut.resized(Lut.SIZE), item.name, item.category, item.id) }
                }
            }
            for (j in jobs) {
                val e = runCatching { j.get() }.getOrElse { skipped++; null }
                if (e != null) added += e
                progress(++done, jobs.size)
            }
        } finally {
            pool.shutdown()
        }
        if (added.isNotEmpty()) save(app, ensure(app) + added)
        if (added.isEmpty() && skipped == list.size && list.isNotEmpty()) error("couldn't download the LUTs")
        return Result(added, skipped)
    }

    private fun fetch(url: String): ByteArray {
        val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        try {
            if (conn.responseCode != 200) error("HTTP ${conn.responseCode} for $url")
            return conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }

    private fun isLut(path: String) = path.endsWith(".cube", ignoreCase = true) || path.endsWith(".png", ignoreCase = true)

    private fun decode(path: String, bytes: ByteArray): Pair<Lut, String?> {
        if (path.endsWith(".cube", ignoreCase = true)) return Lut.parseCube(bytes.decodeToString())
        val opts = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
            inScaled = false
        }
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: error("not an image")
        try {
            val px = IntArray(bmp.width * bmp.height)
            bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
            return Lut.fromHald(bmp.width, bmp.height, px) to null
        } finally {
            bmp.recycle()
        }
    }

    private fun store(context: Context, lut: Lut, name: String, folder: String, fixedId: String? = null): Entry {
        val id = fixedId ?: run {
            val taken = ensure(context).mapTo(HashSet()) { it.id }
            val base = "${folder}-$name".lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(60).ifEmpty { "lut" }
            var id = base
            var n = 2
            while (id in taken || File(dir(context), "$id.lut").exists()) id = "$base-${n++}"
            id
        }
        File(dir(context), "$id.lut").outputStream().use { lut.write(it) }
        val e = Entry(id, name.replace('\t', ' '), folder.replace('\t', ' '))
        register(context, e)
        return e
    }

    private fun save(context: Context, list: List<Entry>) = synchronized(this) {
        index(context).writeText(list.joinToString("") { "${it.id}\t${it.name}\t${it.folder}\n" })
        entries = list
    }

    private fun displayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()
}
