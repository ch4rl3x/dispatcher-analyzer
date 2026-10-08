package de.charlex.dispatcher.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DispatcherOriginTest {
    @Test
    fun constructorMakesDeepImmutableCopiesAndDropsOriginsWithoutKnownConcreteDispatchers() {
        val dispatcher = Dispatcher.IO
        val known = linkedSetOf<Dispatcher>(dispatcher, Dispatcher.Inherited)
        val origin = origin("src/Worker.kt", 12, 2, "withContext(IO)")
        val originsForDispatcher = linkedSetOf(origin)
        val origins = linkedMapOf<Dispatcher, Set<DispatcherOrigin>>(
            dispatcher to originsForDispatcher,
            Dispatcher.Main to setOf(origin("src/Main.kt", 4, 1, "launch")),
            Dispatcher.Inherited to setOf(origin("src/Base.kt", 0, 1, "inherited")),
        )

        val set = DispatcherSet(known = known, origins = origins)
        known += Dispatcher.Main
        originsForDispatcher.clear()
        origins.clear()

        assertEquals(mapOf(dispatcher to setOf(origin)), set.origins)
        assertThrows(UnsupportedOperationException::class.java) {
            (set.origins as MutableMap)[Dispatcher.Main] = setOf(origin)
        }
        assertThrows(UnsupportedOperationException::class.java) {
            (set.origins.getValue(dispatcher) as MutableSet).add(origin("src/Other.kt", 1, 1, "call"))
        }
    }

    @Test
    fun joinsOriginsByDispatcherAndRetainMultipleEvidenceLocations() {
        val first = origin("src/A.kt", 2, 1, "launch")
        val second = origin("src/B.kt", 9, 3, "withContext(IO)")

        val combined = DispatcherSet(known = setOf(Dispatcher.IO), origins = mapOf(Dispatcher.IO to setOf(first)))
            .join(DispatcherSet(known = setOf(Dispatcher.IO), origins = mapOf(Dispatcher.IO to setOf(second))))

        assertEquals(setOf(first, second), combined.origins[Dispatcher.IO])
    }

    @Test
    fun substitutionTransfersCallerOriginsAndPreservesSummaryOrigins() {
        val callerOrigin = origin("src/Caller.kt", 17, 4, "launch")
        val effectOrigin = origin("src/Worker.kt", 23, 6, "withContext(IO)")
        val summary = DispatcherSet(
            known = setOf(Dispatcher.Inherited, Dispatcher.IO),
            origins = mapOf(Dispatcher.IO to setOf(effectOrigin)),
        )
        val caller = DispatcherSet(
            known = setOf(Dispatcher.Main),
            origins = mapOf(Dispatcher.Main to setOf(callerOrigin)),
        )

        val substituted = summary.substitute(caller)

        assertEquals(setOf(Dispatcher.Main, Dispatcher.IO), substituted.known)
        assertEquals(setOf(callerOrigin), substituted.origins[Dispatcher.Main])
        assertEquals(setOf(effectOrigin), substituted.origins[Dispatcher.IO])
        assertFalse(Dispatcher.Inherited in substituted.origins)
    }

    @Test
    fun originDifferencesParticipateInEqualityAndHashing() {
        val first = DispatcherSet(
            known = setOf(Dispatcher.IO),
            origins = mapOf(Dispatcher.IO to setOf(origin("src/A.kt", 2, 1, "first"))),
        )
        val second = DispatcherSet(
            known = setOf(Dispatcher.IO),
            origins = mapOf(Dispatcher.IO to setOf(origin("src/B.kt", 2, 1, "second"))),
        )

        assertNotEquals(first, second)
        assertNotEquals(first.hashCode(), second.hashCode())
        assertEquals(first, first.join(first))
        assertTrue(first.join(second).origins[Dispatcher.IO]?.size == 2)
    }

    @Test
    fun originCoordinatesAndDescriptionAreValidated() {
        assertThrows(IllegalArgumentException::class.java) { origin(" ", 0, 1, "call") }
        assertThrows(IllegalArgumentException::class.java) { origin("src/A.kt", -1, 1, "call") }
        assertThrows(IllegalArgumentException::class.java) { origin("src/A.kt", 0, 0, "call") }
        assertThrows(IllegalArgumentException::class.java) { origin("src/A.kt", 0, 1, " ") }
    }

    private fun origin(fileUrl: String, offset: Int, line: Int, description: String) =
        DispatcherOrigin(fileUrl, offset, line, description)
}
