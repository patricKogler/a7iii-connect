package dev.kogler.a7iiiconnect.develop.engine

import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ThreadFactory

private val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 8)

private val pool = Executors.newFixedThreadPool(threads, ThreadFactory { r ->
    Thread(r, "develop").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1 }
})

/** Runs [body] for every row in [0, rows), split into bands across the CPU cores. */
fun parallelRows(rows: Int, body: (Int) -> Unit) {
    if (rows <= 0) return
    val bands = minOf(rows, threads * 4)
    val futures = ArrayList<Future<*>>(bands)
    for (b in 0 until bands) {
        val from = rows * b / bands
        val to = rows * (b + 1) / bands
        futures += pool.submit { for (r in from until to) body(r) }
    }
    futures.forEach { it.get() }
}
