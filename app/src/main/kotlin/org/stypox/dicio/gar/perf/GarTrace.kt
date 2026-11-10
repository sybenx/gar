package org.stypox.dicio.gar.perf

import android.os.Build
import android.os.Trace
import java.util.concurrent.atomic.AtomicInteger

/*
 * Trace sections named "Gar:<what>" for Perfetto (gar/DESIGN.md, "Frame rate"). They cost a flag
 * check while no trace runs, and show only in debuggable or profileable builds.
 */

/** Runs [block] inside the trace section [name]; begin and end on the same thread. */
inline fun <T> garTrace(name: String, block: () -> T): T {
    Trace.beginSection(name)
    try {
        return block()
    } finally {
        Trace.endSection()
    }
}

private val asyncCookies = AtomicInteger()

/**
 * Begins the async trace section [name] (API 29+, nothing before), which may end on another
 * thread; pass the result to [garTraceAsyncEnd].
 */
fun garTraceAsyncBegin(name: String): Int {
    val cookie = asyncCookies.incrementAndGet()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        Trace.beginAsyncSection(name, cookie)
    }
    return cookie
}

fun garTraceAsyncEnd(name: String, cookie: Int) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        Trace.endAsyncSection(name, cookie)
    }
}

/** Runs [block], which may suspend and change threads, inside the async section [name]. */
suspend inline fun <T> garTraceSuspending(name: String, crossinline block: suspend () -> T): T {
    val cookie = garTraceAsyncBegin(name)
    try {
        return block()
    } finally {
        garTraceAsyncEnd(name, cookie)
    }
}
