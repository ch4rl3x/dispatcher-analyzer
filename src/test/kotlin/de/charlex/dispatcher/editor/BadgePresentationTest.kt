package de.charlex.dispatcher.editor

import de.charlex.dispatcher.model.Dispatcher
import de.charlex.dispatcher.model.DispatcherSet
import de.charlex.dispatcher.model.EffectSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.Color
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

class BadgePresentationTest {
    @Test
    fun colorsFollowDispatcherIdentity() {
        assertEquals(BadgeColor.MAIN, BadgeColor.forDispatcher(Dispatcher.Main))
        assertEquals(BadgeColor.DEFAULT, BadgeColor.forDispatcher(Dispatcher.Default))
        assertEquals(BadgeColor.IO, BadgeColor.forDispatcher(Dispatcher.IO))
        assertEquals(BadgeColor.UNKNOWN, BadgeColor.forDispatcher(null))
        assertEquals(BadgeColor.UNKNOWN, BadgeColor.forDispatcher(Dispatcher.Inherited))
        assertEquals(BadgeColor.CUSTOM, BadgeColor.forDispatcher(Dispatcher.Unconfined))
        assertEquals(BadgeColor.CUSTOM, BadgeColor.forDispatcher(Dispatcher.Custom("test.Custom", "Room")))
    }

    @Test
    fun allPaletteColorsHaveReadableLightAndDarkContrast() {
        BadgeColor.entries.forEach { color ->
            assertTrue("${color.name} light contrast", contrast(color.color(false), Color(0xF0F0F0)) >= 4.5)
            assertTrue("${color.name} dark contrast", contrast(color.color(true), Color(0x3B3B3B)) >= 4.5)
        }
    }

    @Test
    fun incomingBadgesHaveNoPartialLabels() {
        val summary = EffectSummary(DispatcherSet.of(Dispatcher.Main, Dispatcher.IO))
        assertFalse(BadgePresentation.segments(summary, true).any { it.partial })
        assertTrue(BadgePresentation.segments(summary, false).all { it.partial })
    }

    @Test
    fun emptyOrSymbolicResultsDisplayUnknown() {
        assertEquals(listOf("Unknown"), BadgePresentation.segments(EffectSummary.EMPTY, false).map { it.label })
        val inherited = EffectSummary(DispatcherSet.of(Dispatcher.Inherited).join(DispatcherSet.unknown("unresolved")))
        assertEquals(listOf("Unknown"), BadgePresentation.segments(inherited, false).map { it.label })
    }

    private fun contrast(foreground: Color, background: Color): Double {
        fun luminance(color: Color): Double {
            fun channel(value: Int): Double {
                val normalized = value / 255.0
                return if (normalized <= 0.04045) normalized / 12.92 else ((normalized + 0.055) / 1.055).pow(2.4)
            }
            return 0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)
        }
        val first = luminance(foreground)
        val second = luminance(background)
        return (max(first, second) + 0.05) / (min(first, second) + 0.05)
    }
}
