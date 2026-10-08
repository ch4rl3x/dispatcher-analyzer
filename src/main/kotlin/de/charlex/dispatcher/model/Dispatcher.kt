package de.charlex.dispatcher.model

sealed class Dispatcher {
    object Main : Dispatcher()
    object IO : Dispatcher()
    object Default : Dispatcher()
    object Unconfined : Dispatcher()

    /** The identity must distinguish dispatchers that happen to share a label. */
    data class Custom(val identity: String, val label: String) : Dispatcher() {
        init {
            require(identity.isNotBlank()) { "Custom dispatcher identity must not be blank" }
            require(label.isNotBlank()) { "Custom dispatcher label must not be blank" }
        }
    }

    /** A symbolic dispatcher supplied by the caller when a summary is applied. */
    object Inherited : Dispatcher()
}

class DispatcherSet(
    known: Set<Dispatcher> = emptySet(),
    unknownReasons: Set<String> = emptySet(),
) {
    init {
        require(unknownReasons.none(String::isBlank)) { "Unknown reasons must not be blank" }
    }

    val known: Set<Dispatcher> = immutableCopy(known)
    val unknownReasons: Set<String> = immutableCopy(unknownReasons)

    fun join(other: DispatcherSet): DispatcherSet = DispatcherSet(
        known + other.known,
        unknownReasons + other.unknownReasons,
    )

    /** Replaces symbolic inherited entries with the supplied caller contexts. */
    fun substitute(inheritedContexts: DispatcherSet): DispatcherSet {
        if (Dispatcher.Inherited !in known || inheritedContexts.isEmpty) return this
        val replaced = (known - Dispatcher.Inherited) + inheritedContexts.known
        return DispatcherSet(replaced, unknownReasons + inheritedContexts.unknownReasons)
    }

    val isEmpty: Boolean
        get() = known.isEmpty() && unknownReasons.isEmpty()

    val hasUnknown: Boolean
        get() = unknownReasons.isNotEmpty()

    override fun equals(other: Any?): Boolean =
        other is DispatcherSet && known == other.known && unknownReasons == other.unknownReasons

    override fun hashCode(): Int = 31 * known.hashCode() + unknownReasons.hashCode()

    override fun toString(): String = "DispatcherSet(known=$known, unknownReasons=$unknownReasons)"

    companion object {
        val EMPTY = DispatcherSet()

        fun of(vararg dispatchers: Dispatcher): DispatcherSet = DispatcherSet(dispatchers.toSet())

        fun unknown(reason: String): DispatcherSet {
            require(reason.isNotBlank()) { "Unknown reason must not be blank" }
            return DispatcherSet(unknownReasons = setOf(reason))
        }
    }
}

private fun <T> immutableCopy(values: Set<T>): Set<T> =
    java.util.Collections.unmodifiableSet(LinkedHashSet(values))
