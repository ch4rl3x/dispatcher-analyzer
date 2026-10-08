package de.charlex.dispatcher.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DispatcherSelectionTest {
    @Test
    fun setsDispatcherDefaultsToFalse() {
        assertFalse(EffectSummary(DispatcherSet.of(Dispatcher.IO)).setsDispatcher)
        assertFalse(EffectSummary.EMPTY.setsDispatcher)
    }

    @Test
    fun joinCombinesDispatcherSelectionEvidenceWithOr() {
        val ordinary = EffectSummary(DispatcherSet.of(Dispatcher.Main))
        val selection = EffectSummary(DispatcherSet.of(Dispatcher.IO), setsDispatcher = true)

        assertFalse(ordinary.join(ordinary).setsDispatcher)
        assertTrue(ordinary.join(selection).setsDispatcher)
        assertTrue(selection.join(ordinary).setsDispatcher)
    }

    @Test
    fun substitutionPreservesSelectionEvidenceWithoutInferringItFromCallerContext() {
        val inherited = DispatcherSet.of(Dispatcher.Inherited)
        val caller = DispatcherSet.of(Dispatcher.Main)

        val ordinary = EffectSummary(inherited).substitute(caller)
        val selecting = EffectSummary(inherited, setsDispatcher = true).substitute(caller)

        assertFalse(ordinary.setsDispatcher)
        assertTrue(selecting.setsDispatcher)
    }

    @Test
    fun selectionEvidenceParticipatesInEqualityAndHashing() {
        val dispatchers = DispatcherSet.of(Dispatcher.IO)
        val ordinary = EffectSummary(dispatchers)
        val selecting = EffectSummary(dispatchers, setsDispatcher = true)

        assertNotEquals(ordinary, selecting)
        assertNotEquals(ordinary.hashCode(), selecting.hashCode())
    }

    @Test
    fun joiningEmptySummaryPreservesSelectionEvidence() {
        val selecting = EffectSummary(DispatcherSet.of(Dispatcher.IO), setsDispatcher = true)

        assertTrue(EffectSummary.EMPTY.join(selecting).setsDispatcher)
        assertTrue(selecting.join(EffectSummary.EMPTY).setsDispatcher)
        assertEquals(selecting, EffectSummary.EMPTY.join(selecting))
    }
}
