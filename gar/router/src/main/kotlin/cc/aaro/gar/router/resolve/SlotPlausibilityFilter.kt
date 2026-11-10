package cc.aaro.gar.router.resolve

import cc.aaro.gar.router.CallFilter
import cc.aaro.gar.router.CallVerdict
import cc.aaro.gar.router.RouterOutput
import cc.aaro.gar.router.tools.SlotKind
import cc.aaro.gar.router.tools.SlotSpec
import cc.aaro.gar.router.tools.ToolSpec

/**
 * Guard A: drops a call when the model filled a typed slot (duration, time, date, datetime,
 * number) with words that aren't a value of that kind: "what's the capital of france" ->
 * `date_query(day: "france")`, "ring sarah" -> `alarm_set(time: "ring sarah")`, "turn off my
 * alarm" -> `alarm_set(time: "alarm")`. Such a call is a misroute, and asking "What day?" about
 * it would only confuse.
 *
 * A value GAR can't resolve keeps the call (GAR asks about it, as before the filter) when it is
 * vague but of the right kind: it has a word of a value of that kind ([TypedValueWords]: "later",
 * "after lunch", "a few minutes", "this evening", "the minimum"), or it is a bare number in a
 * duration, time or date slot ("a timer for twelve": the value without its unit). It drops the
 * call when the value is a complete value of another kind, which belongs to another tool: a
 * duration as an alarm's time ("one hour": a timer), a time of day as a day ("tomorrow morning"
 * in `date_query`: the weather), a time of day or a day as a duration. Missing slots and
 * ambiguous values ("seven": am or pm? "december": which day?) keep the call. Whether a time of
 * day is a day depends on the tool through [rewrite]: `calendar_query` takes the day of it
 * ([TypedSlotRewrites.dayOfTimeOfDay]: "tomorrow morning" is tomorrow's events), `date_query`
 * doesn't.
 *
 * The slot kinds come from the called tool (`tools.yml`), and the values are resolved exactly as
 * GAR's call runner resolves them in the app: the same [SlotResolvers], after the same [rewrite].
 * A slot that the rewrite empties counts as missing. In a language without number and date
 * support every call is kept.
 *
 * @param resolvers the resolvers for the current language, looked up for each call
 * @param rewrite what the app does to a raw slot before resolving it (`GarSlotRewrites`, which
 * applies [TypedSlotRewrites] to these slots)
 */
class SlotPlausibilityFilter(
    private val resolvers: () -> SlotResolvers,
    private val rewrite: (ToolSpec, SlotSpec, String) -> String = TypedSlotRewrites::rewrite,
) : CallFilter {

    override fun check(call: RouterOutput.Call): CallVerdict {
        val typed = call.tool.slots.filter { it.kind in TYPED_KINDS && it.name in call.rawSlots }
        if (typed.isEmpty()) {
            return CallVerdict.Keep
        }
        val resolvers = resolvers()
        if (!resolvers.supportsLanguage) {
            return CallVerdict.Keep
        }
        for (slot in typed) {
            val raw = rewrite(call.tool, slot, call.rawSlots.getValue(slot.name))
                .takeIf { it.isNotBlank() } ?: continue
            val resolution = when (slot.kind) {
                SlotKind.DURATION -> resolvers.duration(raw)
                SlotKind.TIME -> resolvers.time(raw)
                SlotKind.DATE -> resolvers.date(raw)
                SlotKind.DATETIME -> resolvers.dateTime(raw)
                SlotKind.NUMBER -> resolvers.number(raw)
                else -> continue
            }
            if (resolution !is Resolution.Unresolvable || isBareNumber(slot, raw, resolvers)) {
                continue
            }
            val otherKind = otherKind(slot, raw, resolvers)
            if (otherKind != null || !TypedValueWords.mentionsValue(slot.kind, raw)) {
                return CallVerdict.Drop(
                    "${call.tool.name}.${slot.name} '$raw' is no ${slot.kind.yamlName}: " +
                        (otherKind?.let { "it is $it" } ?: resolution.reason)
                )
            }
        }
        return CallVerdict.Keep
    }

    /**
     * What else [raw] is when it is a complete value of a kind that belongs to other tools than
     * [slot]'s, or null.
     */
    private fun otherKind(slot: SlotSpec, raw: String, resolvers: SlotResolvers): String? =
        when (slot.kind) {
            SlotKind.TIME ->
                "a duration".takeIf { resolvers.duration(raw) is Resolution.Resolved }
            SlotKind.DATE -> when (val r = resolvers.dateTime(raw)) {
                is Resolution.Resolved -> "a time of day".takeIf { r.value.timeSpoken }
                is Resolution.Ambiguous -> "a time of day".takeIf { r.options.any { it.timeSpoken } }
                is Resolution.Unresolvable -> null
            }
            SlotKind.DURATION -> when {
                resolvers.time(raw) !is Resolution.Unresolvable -> "a time of day"
                resolvers.date(raw) is Resolution.Resolved -> "a day"
                else -> null
            }
            else -> null
        }

    /**
     * A number alone where a duration, time or date was expected ("twelve" from "a timer for
     * twelve minutes" when the model left out the unit): words of the right kind, only incomplete.
     */
    private fun isBareNumber(slot: SlotSpec, raw: String, resolvers: SlotResolvers): Boolean =
        slot.kind != SlotKind.NUMBER && resolvers.number(raw) is Resolution.Resolved

    companion object {
        /** The slot kinds GAR resolves into values, so it can tell a value from other words. */
        val TYPED_KINDS = setOf(
            SlotKind.DURATION, SlotKind.TIME, SlotKind.DATE, SlotKind.DATETIME, SlotKind.NUMBER,
        )
    }
}
