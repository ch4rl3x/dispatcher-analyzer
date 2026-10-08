package de.charlex.dispatcher.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DispatcherModelTest {
    @Test
    fun inheritedDispatcherIsSubstitutedAndUnknownIsPreserved() {
        val summary = DispatcherSet.of(Dispatcher.Inherited, Dispatcher.IO)
            .join(DispatcherSet.unknown("unresolved call"))

        val result = summary.substitute(DispatcherSet.of(Dispatcher.Main))

        assertEquals(setOf(Dispatcher.Main, Dispatcher.IO), result.known)
        assertEquals(setOf("unresolved call"), result.unknownReasons)
    }

    @Test
    fun emptyCallerContextDoesNotEraseSymbolicInheritance() {
        assertEquals(
            DispatcherSet.of(Dispatcher.Inherited),
            DispatcherSet.of(Dispatcher.Inherited).substitute(DispatcherSet.EMPTY),
        )
    }

    @Test
    fun bottomIsDifferentFromUnknownAndJoinIsAUnion() {
        assertTrue(DispatcherSet.EMPTY.isEmpty)
        assertFalse(DispatcherSet.unknown("missing evidence").isEmpty)
        assertEquals(
            DispatcherSet.of(Dispatcher.Main, Dispatcher.IO),
            DispatcherSet.of(Dispatcher.Main).join(DispatcherSet.of(Dispatcher.IO)),
        )
    }

    @Test
    fun customDispatchersKeepIdentityAndUseStableBadgeOrdering() {
        val sameLabelA = Dispatcher.Custom("lib:a", "Room")
        val sameLabelB = Dispatcher.Custom("lib:b", "Room")
        val summary = EffectSummary(
            DispatcherSet.of(
                Dispatcher.Custom("lib:z", "Zeta"),
                sameLabelB,
                Dispatcher.Unconfined,
                Dispatcher.Default,
                Dispatcher.IO,
                Dispatcher.Main,
                sameLabelA,
            ),
        )

        assertEquals(
            listOf("Main", "IO", "Default", "Unconfined", "Room", "Room", "Zeta"),
            summary.badgeSegments().map(BadgeSegment::label),
        )
        assertEquals(7, summary.dispatchers.known.size)
        assertEquals(
            listOf("lib:a", "lib:b"),
            summary.badgeSegments().mapNotNull { (it.dispatcher as? Dispatcher.Custom)?.identity }.take(2),
        )
    }

    @Test
    fun knownDispatcherIsPartialOnlyWhenAnotherKnownIdentityIsPresent() {
        val uncertainOnly = EffectSummary(
            DispatcherSet.of(Dispatcher.IO).join(DispatcherSet.unknown("incomplete branch")),
        ).badgeSegments()
        assertEquals(listOf(BadgeSegment(Dispatcher.IO, "IO", false), BadgeSegment(null, "Unknown", false)), uncertainOnly)

        val mixed = EffectSummary(
            DispatcherSet.of(Dispatcher.Main, Dispatcher.IO).join(DispatcherSet.unknown("unresolved call")),
            setOf(PathRelation.CONTEXT_SWITCH),
        )

        assertEquals(
            listOf(
                BadgeSegment(Dispatcher.Main, "Main", true),
                BadgeSegment(Dispatcher.IO, "IO", true),
                BadgeSegment(null, "Unknown", false),
            ),
            mixed.badgeSegments(),
        )
        assertTrue(PathRelation.CONTEXT_SWITCH in mixed.pathRelations)
    }

    @Test
    fun joinsKeepBranchAndSwitchExplanations() {
        val combined = EffectSummary(DispatcherSet.of(Dispatcher.Main), setOf(PathRelation.BRANCH_ALTERNATIVES))
            .join(EffectSummary(DispatcherSet.of(Dispatcher.IO), setOf(PathRelation.CONTEXT_SWITCH)))

        assertEquals(
            setOf(PathRelation.BRANCH_ALTERNATIVES, PathRelation.CONTEXT_SWITCH),
            combined.pathRelations,
        )
    }
}
