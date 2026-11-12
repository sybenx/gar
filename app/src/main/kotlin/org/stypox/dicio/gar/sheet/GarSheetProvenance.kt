package org.stypox.dicio.gar.sheet

import androidx.compose.runtime.Immutable
import org.dicio.skill.skill.SkillOutput
import java.util.IdentityHashMap

/** How an answer on the sheet was found: right away by a skill, or by the on-device model. */
@Immutable
sealed interface GarSheetRoute {
    data object Instant : GarSheetRoute

    /** The model fallback picked the skill, in [millis]. */
    data class Model(val millis: Long) : GarSheetRoute
}

/** The provenance chips under an answer: the [route] and the [skillId] that answered. */
@Immutable
data class GarSheetProvenance(
    val route: GarSheetRoute,
    val skillId: String?,
)

/**
 * Tells which answers came through the model, from what the sheet sees while it is open: the
 * model's routing (GarThinking.routing) between a question and its answer. One per show of the
 * sheet; pure apart from its own state (tested in GarSheetProvenanceTest).
 */
class GarSheetProvenanceTracker {
    private var routeStartedAt: Long? = null
    private var routeEndedAt: Long? = null
    private var hadPending = false
    private val known = IdentityHashMap<SkillOutput, GarSheetRoute>()

    /** The model's routing now: [routingStartedAt] while it runs, null otherwise. */
    fun onRouting(routingStartedAt: Long?, now: Long) {
        if (routingStartedAt != null) {
            routeStartedAt = routingStartedAt
            routeEndedAt = null
        } else if (routeStartedAt != null && routeEndedAt == null) {
            routeEndedAt = now
        }
    }

    /** A new question began: a route that ended before it isn't its route. */
    fun onPending(pending: Boolean, routing: Boolean) {
        if (pending && !hadPending && !routing) {
            routeStartedAt = null
            routeEndedAt = null
        }
        hadPending = pending
    }

    /** The route of [answer]; decided the first time it is asked for, then kept. */
    fun routeFor(answer: SkillOutput, now: Long): GarSheetRoute {
        known[answer]?.let { return it }
        val started = routeStartedAt
        val route = if (started == null) {
            GarSheetRoute.Instant
        } else {
            GarSheetRoute.Model(((routeEndedAt ?: now) - started).coerceAtLeast(0))
        }
        routeStartedAt = null
        routeEndedAt = null
        if (known.size >= MAX_KNOWN) {
            known.clear()
        }
        known[answer] = route
        return route
    }

    private companion object {
        const val MAX_KNOWN = 32
    }
}
