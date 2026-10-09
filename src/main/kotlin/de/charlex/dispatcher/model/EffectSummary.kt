package de.charlex.dispatcher.model

/** Why a summary contains work at more than one dispatcher. */
enum class PathRelation {
    BRANCH_ALTERNATIVES,
    CONTEXT_SWITCH,
}

/** A display-ready dispatcher entry. Unknown is represented by a null dispatcher. */
data class BadgeSegment(
    val dispatcher: Dispatcher?,
    val label: String,
    val partial: Boolean,
)

class EffectSummary(
    val dispatchers: DispatcherSet,
    pathRelations: Set<PathRelation> = emptySet(),
    /** Explicit synchronous selections and unresolved work inside their regions. */
    val selectedDispatchers: DispatcherSet = DispatcherSet.EMPTY,
) {
    val setsDispatcher: Boolean
        get() = !selectedDispatchers.isEmpty

    val pathRelations: Set<PathRelation> = java.util.Collections.unmodifiableSet(
        LinkedHashSet(pathRelations),
    )

    fun join(other: EffectSummary): EffectSummary = EffectSummary(
        dispatchers.join(other.dispatchers),
        pathRelations + other.pathRelations,
        selectedDispatchers.join(other.selectedDispatchers),
    )

    fun substitute(inheritedContexts: DispatcherSet): EffectSummary = EffectSummary(
        dispatchers.substitute(inheritedContexts),
        pathRelations,
        selectedDispatchers,
    )

    /** Known dispatchers are partial only when another distinct known identity is proven. */
    fun badgeSegments(): List<BadgeSegment> {
        val concrete = dispatchers.known.filter { it != Dispatcher.Inherited }
        val partial = concrete.size > 1
        val segments = concrete.sortedWith(dispatcherOrder).map { dispatcher ->
            BadgeSegment(dispatcher, dispatcher.displayLabel(), partial)
        }.toMutableList()

        if (Dispatcher.Inherited in dispatchers.known) {
            segments += BadgeSegment(Dispatcher.Inherited, "Inherited", partial = false)
        }
        if (dispatchers.hasUnknown) {
            segments += BadgeSegment(dispatcher = null, label = "Unknown", partial = false)
        }
        return segments
    }

    override fun equals(other: Any?): Boolean =
        other is EffectSummary && dispatchers == other.dispatchers && pathRelations == other.pathRelations &&
            selectedDispatchers == other.selectedDispatchers

    override fun hashCode(): Int = 31 * (31 * dispatchers.hashCode() + pathRelations.hashCode()) + selectedDispatchers.hashCode()

    override fun toString(): String =
        "EffectSummary(dispatchers=$dispatchers, pathRelations=$pathRelations, selectedDispatchers=$selectedDispatchers)"

    companion object {
        val EMPTY = EffectSummary(DispatcherSet.EMPTY)
    }
}

private val dispatcherOrder = compareBy<Dispatcher>({ it.orderRank() }, { it.sortLabel() }, { it.sortIdentity() })

private fun Dispatcher.orderRank(): Int = when (this) {
    Dispatcher.Main -> 0
    Dispatcher.IO -> 1
    Dispatcher.Default -> 2
    Dispatcher.Unconfined -> 3
    Dispatcher.Inherited -> 4
    is Dispatcher.Custom -> 5
}

private fun Dispatcher.sortLabel(): String = when (this) {
    Dispatcher.Main -> "Main"
    Dispatcher.IO -> "IO"
    Dispatcher.Default -> "Default"
    Dispatcher.Unconfined -> "Unconfined"
    Dispatcher.Inherited -> "Inherited"
    is Dispatcher.Custom -> label
}

private fun Dispatcher.sortIdentity(): String = when (this) {
    is Dispatcher.Custom -> identity
    else -> ""
}

private fun Dispatcher.displayLabel(): String = when (this) {
    Dispatcher.Main -> "Main"
    Dispatcher.IO -> "IO"
    Dispatcher.Default -> "Default"
    Dispatcher.Unconfined -> "Unconfined"
    Dispatcher.Inherited -> "Inherited"
    is Dispatcher.Custom -> label
}
