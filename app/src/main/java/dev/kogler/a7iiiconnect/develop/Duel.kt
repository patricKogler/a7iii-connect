package dev.kogler.a7iiiconnect.develop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Head to head: the best shot so far meets each of the others in turn, and the better one stays
 * on. "Keep both" lets the challenger through as well. With n shots it takes n - 1 choices, each
 * between just two pictures. Every step can be undone.
 */
class Duel<T>(val items: List<T>) {
    private data class State(val best: Int, val next: Int, val alsoKept: List<Int>)

    private var state by mutableStateOf(State(0, 1, emptyList()))
    private val history = mutableStateListOf<State>()

    val best: T get() = items[state.best]
    /** The shot up against [best], or null once everyone has had a go. */
    val challenger: T? get() = items.getOrNull(state.next)
    val done: Boolean get() = state.next >= items.size
    /** Choices made so far, out of [rounds]. */
    val round: Int get() = (state.next - 1).coerceAtLeast(0)
    val rounds: Int get() = (items.size - 1).coerceAtLeast(0)
    val canUndo: Boolean get() = history.isNotEmpty()

    /** The best shot so far wins ([bestWins]) or the challenger takes its place. */
    fun choose(bestWins: Boolean) {
        if (done) return
        val s = state
        history += s
        state = s.copy(best = if (bestWins) s.best else s.next, next = s.next + 1)
    }

    /** Both stay: the challenger is kept, and the best so far meets the next one. */
    fun keepBoth() {
        if (done) return
        val s = state
        history += s
        state = s.copy(next = s.next + 1, alsoKept = s.alsoKept + s.next)
    }

    fun undo() {
        state = history.removeLastOrNull() ?: return
    }

    /** The shots to keep, the winner first; meaningful once [done]. */
    val kept: List<T> get() = (listOf(state.best) + state.alsoKept).distinct().map { items[it] }
    val dropped: List<T> get() = items.filterIndexed { i, _ -> i != state.best && i !in state.alsoKept }
}
