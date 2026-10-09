package de.charlex.dispatcher.editor

import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.impl.EditorColorsSchemeImpl
import com.intellij.openapi.options.colors.ColorDescriptor
import com.intellij.openapi.options.colors.ColorSettingsPage
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.awt.Color

class DispatcherColorSettingsTest : BasePlatformTestCase() {
    fun testRegisteredPageOffersOnlyForegroundColorsWithPreview() {
        val page = ColorSettingsPage.EP_NAME.extensionList.filterIsInstance<DispatcherColorSettingsPage>().single()
        assertEquals("Dispatcher Analyzer", page.displayName)
        assertEmpty(page.attributeDescriptors.toList())
        assertEquals(BadgeColor.entries.map { it.key }.toSet(), page.colorDescriptors.map { it.key }.toSet())
        assertTrue(page.colorDescriptors.all { it.kind == ColorDescriptor.Kind.FOREGROUND })
        for ((tag, key) in page.additionalHighlightingTagToColorKeyMap) {
            assertTrue(page.demoText.contains("<$tag>"))
            assertTrue(page.colorDescriptors.any { it.key == key })
        }
    }

    fun testBundledSchemesPreserveLightAndDarkDefaults() {
        for ((name, dark) in listOf("Default" to false, "Darcula" to true)) {
            val scheme = requireNotNull(EditorColorsManager.getInstance().getScheme(name))
            BadgeColor.entries.forEach { color ->
                assertEquals("$name ${color.name}", color.color(dark), scheme.getColor(color.key))
            }
        }
    }

    fun testOverridesPersistInTheSchemeWithoutChangingOtherSchemes() {
        val manager = EditorColorsManager.getInstance()
        val light = requireNotNull(manager.getScheme("Default"))
        val dark = requireNotNull(manager.getScheme("Darcula"))
        val custom = light.clone() as EditorColorsSchemeImpl
        custom.name = "Custom dispatcher colors"
        val overrides = BadgeColor.entries.associateWith { Color(0x123450 + it.ordinal) }
        overrides.forEach { (color, value) -> custom.setColor(color.key, value) }
        val restored = EditorColorsSchemeImpl(light).apply { readExternal(custom.writeScheme()) }
        overrides.forEach { (color, value) ->
            assertEquals(value, color.color(restored))
            assertEquals(color.color(false), color.color(light))
            assertEquals(color.color(true), color.color(dark))
        }
        restored.setColor(BadgeColor.IO.key, null)
        assertEquals(BadgeColor.IO.color(false), BadgeColor.IO.color(restored))
    }
}
