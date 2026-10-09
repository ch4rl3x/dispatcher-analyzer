package de.charlex.dispatcher.editor

import com.intellij.openapi.editor.colors.ColorKey
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.ui.ColorUtil
import de.charlex.dispatcher.model.Dispatcher
import java.awt.Color

internal enum class BadgeColor(val light: Int, val dark: Int) {
    MAIN(0xA12622, 0xF28B82),
    DEFAULT(0x267331, 0x81C995),
    IO(0x87610C, 0xFDD663),
    UNKNOWN(0x666666, 0xAAAAAA),
    CUSTOM(0x7352A5, 0xC0A0EB),
    ;

    val key: ColorKey = ColorKey.createColorKey("DISPATCHER_ANALYZER_$name")

    fun color(darkTheme: Boolean): Color = Color(if (darkTheme) dark else light)

    fun color(scheme: EditorColorsScheme): Color =
        scheme.getColor(key) ?: color(ColorUtil.isDark(scheme.defaultBackground))

    companion object {
        fun forDispatcher(dispatcher: Dispatcher?): BadgeColor = when (dispatcher) {
            Dispatcher.Main -> MAIN
            Dispatcher.Default -> DEFAULT
            Dispatcher.IO -> IO
            null, Dispatcher.Inherited -> UNKNOWN
            Dispatcher.Unconfined, is Dispatcher.Custom -> CUSTOM
        }
    }
}
