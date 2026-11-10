package org.stypox.dicio.gar.model

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Keeps at most one loaded model in memory. Users [acquire] it while the assistant UI is visible
 * and [release] it when hidden; the model is unloaded [graceMillis] after the last release, or
 * right away by [unloadIfIdle] (memory pressure). Loading, using and unloading are serialized,
 * so a model is never closed while [use] runs.
 *
 * A key that failed to load is not loaded again (neither by [preload] nor by [use], which throw
 * [PreviousLoadFailedException]) until [retry], so a broken file costs one failed load, not one
 * per utterance or resume.
 *
 * @param K identifies what to load (file and prompt family); a different key replaces the
 * loaded model
 * @param V the loaded model
 * @param shortGraceMillis after the last release, the model stays [graceMillis] only if
 * [keepLonger] says so after this shorter time; otherwise it is unloaded then. A process that may
 * be frozen soon (cached, Android's freezer kicks in after about 10 s) can't run a long timer, and
 * would keep the model's memory until it is killed.
 * @param onCloseError reports a failure of [close]; the model counts as unloaded anyway
 */
class GarEngineHolder<K : Any, V : Any>(
    private val scope: CoroutineScope,
    private val graceMillis: Long,
    private val load: suspend (K) -> V,
    private val close: suspend (V) -> Unit,
    private val onCloseError: (Throwable) -> Unit = {},
    private val shortGraceMillis: Long = graceMillis,
    private val keepLonger: () -> Boolean = { true },
) {
    sealed interface State<out K> {
        data object NotLoaded : State<Nothing>
        data class Loading<K>(val key: K) : State<K>
        data class Loaded<K>(val key: K) : State<K>
        data class Failed<K>(val key: K, val throwable: Throwable) : State<K>
    }

    private val _state = MutableStateFlow<State<K>>(State.NotLoaded)
    val state: StateFlow<State<K>> = _state

    private val mutex = Mutex()
    private var loaded: Pair<K, V>? = null // guarded by mutex

    private val refLock = Any()
    private var refs = 0 // guarded by refLock
    private var unloadJob: Job? = null // guarded by refLock
    private var preloadJob: Pair<K, Job>? = null // guarded by refLock

    val isAcquired: Boolean
        get() = synchronized(refLock) { refs > 0 }

    fun acquire() {
        synchronized(refLock) {
            refs += 1
            unloadJob?.cancel()
            unloadJob = null
        }
    }

    fun release() {
        synchronized(refLock) {
            check(refs > 0) { "release() without acquire()" }
            refs -= 1
            if (refs == 0) {
                unloadJob?.cancel()
                unloadJob = scope.launch {
                    val short = minOf(shortGraceMillis, graceMillis)
                    delay(short)
                    if (short < graceMillis && keepLonger()) {
                        delay(graceMillis - short)
                    }
                    unloadIfIdle()
                }
            }
        }
    }

    /**
     * Starts loading [key] in the background, if it isn't loaded already. Calls while a preload of
     * the same key is still running return that one.
     */
    fun preload(key: K): Job = synchronized(refLock) {
        preloadJob?.let { (runningKey, job) ->
            if (runningKey == key && job.isActive) {
                return job
            }
        }
        val job = scope.launch {
            try {
                mutex.withLock { ensureLoaded(key) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                // already reported through state
            }
        }
        preloadJob = Pair(key, job)
        job
    }

    /**
     * Runs [block] with the model for [key], loading it first if needed. Callers that don't hold
     * a reference should [acquire] before and [release] after, so the model is kept for a while.
     */
    suspend fun <R> use(key: K, block: suspend (V) -> R): R = mutex.withLock {
        block(ensureLoaded(key))
    }

    /** Unloads the model now, unless someone holds a reference. */
    suspend fun unloadIfIdle() = mutex.withLock {
        if (!isAcquired) {
            closeLoaded()
        }
    }

    /** Unloads the model now, e.g. because its file is going to be deleted. */
    suspend fun unload() = mutex.withLock {
        closeLoaded()
    }

    /** Forgets a failed load, so the next [preload] or [use] tries again. */
    suspend fun retry() = mutex.withLock {
        if (_state.value is State.Failed) {
            _state.value = State.NotLoaded
        }
    }

    private suspend fun ensureLoaded(key: K): V {
        loaded?.let { (loadedKey, value) ->
            if (loadedKey == key) {
                return value
            }
        }
        (_state.value as? State.Failed)?.let { failed ->
            if (failed.key == key) {
                throw PreviousLoadFailedException(failed.throwable)
            }
        }
        closeLoaded()

        _state.value = State.Loading(key)
        val value = try {
            load(key)
        } catch (e: CancellationException) {
            _state.value = State.NotLoaded
            throw e
        } catch (t: Throwable) {
            _state.value = State.Failed(key, t)
            throw t
        }
        loaded = Pair(key, value)
        _state.value = State.Loaded(key)
        return value
    }

    private suspend fun closeLoaded() {
        // a Failed state stays visible until the next load attempt
        val value = loaded?.second ?: return
        loaded = null
        _state.value = State.NotLoaded
        try {
            close(value)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            // don't fail the load, unload or delete that wanted the old model gone
            onCloseError(t)
        }
    }
}

/** [GarEngineHolder.use] of a key whose last load failed, see [GarEngineHolder.retry]. */
class PreviousLoadFailedException(cause: Throwable) :
    Exception("The model could not be loaded: ${cause.message}", cause)
