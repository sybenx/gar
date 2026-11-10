package org.stypox.dicio.gar.conversation

import org.stypox.dicio.gar.ui.GarSpeaking

/**
 * Which utterances the text to speech still has queued, and what runs once all of them are over
 * (gar/DESIGN.md, "Continued conversation"), for `AndroidTtsSpeechDevice`. Android's
 * `TextToSpeech.isSpeaking` is often still false right after `speak` queued an utterance, so
 * "finished speaking" also needs every queued id to be done, failed or stopped. Thread-safe: the
 * utterance callbacks come on a binder thread. The runnables run outside the lock. Whether
 * anything is queued feeds [GarSpeaking], which the microphone's state reads.
 */
class GarUtteranceTracker {
    private val lock = Any()
    private val queued = LinkedHashSet<String>()
    private val runnables = ArrayList<Runnable>()

    /** `speak` queued the utterance [id]. */
    fun queued(id: String) {
        synchronized(lock) {
            queued.add(id)
            GarSpeaking.update(this, pending = true)
        }
    }

    /**
     * The utterance [id] is over (`onDone`, `onError`, `onStop`): once none is left, runs what
     * waited for the end of the speech.
     */
    fun finished(id: String) {
        val toRun = synchronized(lock) {
            queued.remove(id)
            GarSpeaking.update(this, pending = queued.isNotEmpty())
            if (queued.isEmpty()) {
                ArrayList(runnables).also { runnables.clear() }
            } else {
                emptyList()
            }
        }
        toRun.forEach { it.run() }
    }

    /**
     * Runs [runnable] now if nothing is queued and the engine says it isn't [speaking], else once
     * the last queued utterance is over.
     */
    fun runWhenFinished(speaking: Boolean, runnable: Runnable) {
        val now = synchronized(lock) {
            if (speaking || queued.isNotEmpty()) {
                runnables.add(runnable)
                false
            } else {
                true
            }
        }
        if (now) {
            runnable.run()
        }
    }

    /** `stopSpeaking`: everything is over, and what waited for the end never runs. */
    fun stopped() {
        synchronized(lock) {
            queued.clear()
            runnables.clear()
            GarSpeaking.update(this, pending = false)
        }
    }

    /** Whether an utterance is still queued. */
    val pending: Boolean
        get() = synchronized(lock) { queued.isNotEmpty() }
}
