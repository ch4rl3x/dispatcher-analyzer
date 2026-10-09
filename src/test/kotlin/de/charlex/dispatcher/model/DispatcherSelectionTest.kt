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
    fun joinCombinesDispatcherSelectionWithoutSelectingInheritedWork() {
        val ordinary = EffectSummary(DispatcherSet.of(Dispatcher.Main))
        val io = DispatcherSet.of(Dispatcher.IO)
        val selection = EffectSummary(io, selectedDispatchers = io)

        assertFalse(ordinary.join(ordinary).setsDispatcher)
        assertTrue(ordinary.join(selection).setsDispatcher)
        assertTrue(selection.join(ordinary).setsDispatcher)
        assertEquals(io, ordinary.join(selection).selectedDispatchers)
        assertEquals(io, selection.join(ordinary).selectedDispatchers)
    }

    @Test
    fun substitutionPreservesSelectionEvidenceWithoutInferringItFromCallerContext() {
        val inherited = DispatcherSet.of(Dispatcher.Inherited)
        val caller = DispatcherSet.of(Dispatcher.Main)

        val ordinary = EffectSummary(inherited).substitute(caller)
        val io = DispatcherSet.of(Dispatcher.IO)
        val selecting = EffectSummary(inherited.join(io), selectedDispatchers = io).substitute(caller)

        assertFalse(ordinary.setsDispatcher)
        assertTrue(selecting.setsDispatcher)
        assertEquals(setOf(Dispatcher.Main, Dispatcher.IO), selecting.dispatchers.known)
        assertEquals(io, selecting.selectedDispatchers)
        assertTrue(selecting.badgeSegments().all { it.partial })
    }

    @Test
    fun selectionEvidenceParticipatesInEqualityAndHashing() {
        val dispatchers = DispatcherSet.of(Dispatcher.IO)
        val ordinary = EffectSummary(dispatchers)
        val selecting = EffectSummary(dispatchers, selectedDispatchers = dispatchers)

        assertNotEquals(ordinary, selecting)
        assertNotEquals(ordinary.hashCode(), selecting.hashCode())
    }

    @Test
    fun joiningEmptySummaryPreservesSelectionEvidence() {
        val io = DispatcherSet.of(Dispatcher.IO)
        val selecting = EffectSummary(io, selectedDispatchers = io)

        assertTrue(EffectSummary.EMPTY.join(selecting).setsDispatcher)
        assertTrue(selecting.join(EffectSummary.EMPTY).setsDispatcher)
        assertEquals(selecting, EffectSummary.EMPTY.join(selecting))
    }

    @Test
    fun selectedOriginsRemainDistinctFromCallerOriginsOfSameDispatcher() {
        val callerOrigin = DispatcherOrigin("file:///Caller.kt", 1, 1, "Dispatchers.IO")
        val selectedOrigin = DispatcherOrigin("file:///Worker.kt", 2, 1, "Dispatchers.IO")
        val caller = DispatcherSet(setOf(Dispatcher.IO), origins = mapOf(Dispatcher.IO to setOf(callerOrigin)))
        val selected = DispatcherSet(setOf(Dispatcher.IO), origins = mapOf(Dispatcher.IO to setOf(selectedOrigin)))
        val summary = EffectSummary(
            DispatcherSet.of(Dispatcher.Inherited).join(selected),
            selectedDispatchers = selected,
        ).substitute(caller)

        assertEquals(setOf(callerOrigin, selectedOrigin), summary.dispatchers.origins[Dispatcher.IO])
        assertEquals(setOf(selectedOrigin), summary.selectedDispatchers.origins[Dispatcher.IO])
        assertFalse(summary.badgeSegments().single().partial)
    }

    @Test
    fun unresolvedSelectionRemainsUnknownWithoutSelectingCallerContext() {
        val unknown = DispatcherSet.unknown("Unresolved dispatcher")
        val summary = EffectSummary(
            DispatcherSet.of(Dispatcher.Inherited).join(unknown),
            selectedDispatchers = unknown,
        ).substitute(DispatcherSet.of(Dispatcher.Main))

        assertTrue(summary.setsDispatcher)
        assertEquals(setOf(Dispatcher.Main), summary.dispatchers.known)
        assertTrue(summary.selectedDispatchers.known.isEmpty())
        assertEquals(unknown, summary.selectedDispatchers)
    }

    @Test
    fun joiningSelectionsPreservesBothIdentities() {
        val io = DispatcherSet.of(Dispatcher.IO)
        val default = DispatcherSet.of(Dispatcher.Default)
        val summary = EffectSummary(io, selectedDispatchers = io)
            .join(EffectSummary(default, selectedDispatchers = default))

        assertEquals(setOf(Dispatcher.IO, Dispatcher.Default), summary.selectedDispatchers.known)
    }
}
