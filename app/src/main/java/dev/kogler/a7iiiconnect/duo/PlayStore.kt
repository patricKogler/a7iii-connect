package dev.kogler.a7iiiconnect.duo

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import java.io.File

/**
 * This phone's [Fact]s, for every shared album, in develop/play.txt beside the albums. Screens
 * read [version] so they redraw when facts change, here or from the other phone.
 */
object PlayStore {
    /** Bumped on every change. */
    var version by mutableIntStateOf(0); private set

    private var cache: List<Fact>? = null

    private fun file(context: Context) = File(File(context.filesDir, "develop").apply { mkdirs() }, "play.txt")

    @Synchronized
    fun facts(context: Context): List<Fact> = cache ?: runCatching { Facts.decode(file(context).readText()) }
        .getOrDefault(emptyList()).also { cache = it }

    fun forAlbum(context: Context, album: String): List<Fact> = facts(context).filter { it.album == album }

    /**
     * Facts made on this phone: kept, and sent straight to the other phone when it's connected.
     * Call on the main thread (the file is small; writing it is quick).
     */
    fun add(context: Context, new: List<Fact>) {
        if (new.isEmpty()) return
        put(context, new)
        Duo.sendFacts(new)
    }

    fun add(context: Context, vararg new: Fact) = add(context, new.toList())

    /** Facts from the other phone, already under this phone's keys. Returns what was new. */
    fun receive(context: Context, incoming: List<Fact>): List<Fact> {
        val news = synchronized(this) { Facts.news(facts(context), incoming) }
        if (news.isNotEmpty()) put(context, news)
        return news
    }

    /** Re-keys every fact to this phone's shot keys (after shots from the other phone landed). */
    fun rekey(context: Context, resolve: (String) -> String?) {
        synchronized(this) {
            val all = facts(context)
            val moved = Facts.rekey(all, resolve)
            if (moved === all) return
            write(context, moved)
        }
        version++
    }

    private fun put(context: Context, new: List<Fact>) {
        synchronized(this) { write(context, Facts.merge(facts(context), new)) }
        version++
    }

    private fun write(context: Context, all: List<Fact>) {
        cache = all
        val f = file(context)
        // Written beside and then moved over, so a crash mid-write can't lose everything.
        val tmp = File(f.parentFile, "play.txt.tmp")
        tmp.writeText(Facts.encode(all))
        if (!tmp.renameTo(f)) { f.writeText(tmp.readText()); tmp.delete() }
    }
}
