package de.charlex.dispatcher.editor

import com.intellij.codeInsight.hints.InlayHintsSink
import com.intellij.codeInsight.hints.presentation.InlayPresentation
import com.intellij.codeInsight.hints.presentation.MouseButton
import com.intellij.codeInsight.hints.presentation.PresentationFactory
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.ui.ColorUtil
import de.charlex.dispatcher.analysis.FileAnalysis
import de.charlex.dispatcher.model.BadgeSegment
import de.charlex.dispatcher.model.Dispatcher
import de.charlex.dispatcher.model.DispatcherOrigin
import de.charlex.dispatcher.model.EffectSummary
import java.awt.Cursor
import java.util.EnumSet

internal object BadgePresentation {
    fun segments(summary: EffectSummary, declaration: Boolean): List<BadgeSegment> {
        val segments = summary.badgeSegments().map { segment ->
            if (segment.dispatcher == Dispatcher.Inherited) {
                BadgeSegment(null, "Unknown", false)
            } else {
                segment.copy(partial = !declaration && segment.partial)
            }
        }.distinct()
        return segments.ifEmpty { listOf(BadgeSegment(null, "Unknown", false)) }
    }

    fun create(
        factory: PresentationFactory,
        editor: Editor,
        summary: EffectSummary,
        tooltip: String,
        declaration: Boolean,
        isCurrentAnalysis: () -> Boolean = { false },
        navigateDispatcher: ((Dispatcher, List<DispatcherOrigin>, () -> Boolean) -> Unit)? = null,
    ): InlayPresentation {
        val dark = ColorUtil.isDark(editor.colorsScheme.defaultBackground)
        val prefix = if (declaration) "called within Dispatcher " else "Dispatcher "
        val parts = mutableListOf(factory.smallTextWithoutBackground(prefix))
        segments(summary, declaration).forEachIndexed { index, segment ->
            if (index > 0) parts += factory.smallTextWithoutBackground(" | ")
            val color = BadgeColor.forDispatcher(segment.dispatcher)
            val name = ColoredTextPresentation(factory.smallTextWithoutBackground(segment.label), color.color(dark))
            val dispatcher = segment.dispatcher
            val origins = dispatcher?.let(summary.dispatchers.origins::get).orEmpty().sortedWith(originOrder)
            if (dispatcher != null && origins.isNotEmpty()) {
                val navigate: (Dispatcher, List<DispatcherOrigin>, () -> Boolean) -> Unit =
                    navigateDispatcher ?: { _, targetOrigins, current ->
                        DispatcherOriginNavigation.navigate(editor, targetOrigins, current)
                    }
                parts += factory.withCursorOnHover(
                    factory.onClick(
                        name,
                        EnumSet.of(MouseButton.Left, MouseButton.Middle),
                        { _, _ ->
                            if (isCurrentAnalysis()) navigate(dispatcher, origins, isCurrentAnalysis)
                        },
                    ),
                    Cursor.getPredefinedCursor(Cursor.HAND_CURSOR),
                )
            } else {
                parts += name
            }
            if (segment.partial) {
                parts += ColoredTextPresentation(
                    factory.smallTextWithoutBackground(" (partial)"),
                    color.color(dark),
                )
            }
        }
        val badge = factory.roundWithBackgroundAndSmallInset(factory.seq(*parts.toTypedArray()))
        return factory.withTooltip(tooltip, badge)
    }

    fun render(
        factory: PresentationFactory,
        result: FileAnalysis,
        editor: Editor,
        settings: DispatcherHintSettings,
        sink: InlayHintsSink,
        sourceFileUrl: String = "",
        isCurrentAnalysis: () -> Boolean = { false },
        eligibleCallOffsets: Set<Int>? = null,
    ) {
        val currentResult = { sourceFileUrl.isNotBlank() && isCurrentAnalysis() }
        val length = editor.document.textLength
        result.declarations.forEach { (offset, badge) ->
            ProgressManager.checkCanceled()
            if (offset !in 0..length) return@forEach
            val presentation = create(
                factory, editor, badge.summary, badge.tooltip, true,
                isCurrentAnalysis = currentResult,
            )
            val indent = editor.offsetToLogicalPosition(offset).column
            sink.addBlockElement(
                offset,
                relatesToPrecedingText = false,
                showAbove = true,
                priority = 0,
                presentation = factory.seq(factory.textSpacePlaceholder(indent, false), presentation),
            )
        }
        val callSiteMode = settings.resolvedCallSiteMode()
        if (callSiteMode != CallSiteBadgeMode.NONE) {
            result.calls.forEach { (offset, badge) ->
                ProgressManager.checkCanceled()
                if (offset !in 0..length) return@forEach
                if (eligibleCallOffsets != null && offset !in eligibleCallOffsets) return@forEach
                if (callSiteMode == CallSiteBadgeMode.DISPATCHER_CHANGES && !badge.summary.setsDispatcher) {
                    return@forEach
                }
                val presentation = create(
                    factory, editor, badge.summary, badge.tooltip, false,
                    isCurrentAnalysis = currentResult,
                )
                sink.addInlineElement(offset, true, factory.inset(presentation, left = 4), false)
            }
        }
    }

    private val originOrder = compareBy<DispatcherOrigin>({ it.fileUrl }, { it.line }, { it.offset }, { it.description })
}

internal object DispatcherOriginNavigation {
    fun navigate(
        editor: Editor,
        origins: List<DispatcherOrigin>,
        isCurrentAnalysis: () -> Boolean,
        openOrigin: ((DispatcherOrigin) -> Unit)? = null,
        showChooser: ((List<DispatcherOrigin>, (DispatcherOrigin) -> Unit) -> Unit)? = null,
    ) {
        val current = isCurrentAnalysis()
        if (editor.isDisposed || editor.project?.isDisposed != false || !current) return
        val choices = origins.distinct().sortedWith(compareBy({ it.fileUrl }, { it.line }, { it.offset }, { it.description }))
        if (choices.isEmpty()) return
        val open: (DispatcherOrigin) -> Unit = openOrigin ?: { origin -> openSourceOrigin(editor, origin) }
        val choose: (List<DispatcherOrigin>, (DispatcherOrigin) -> Unit) -> Unit =
            showChooser ?: { values, onChosen -> showOriginChooser(editor, values, onChosen) }
        fun select(origin: DispatcherOrigin) {
            if (!editor.isDisposed && editor.project?.isDisposed == false && isCurrentAnalysis()) open(origin)
        }
        if (choices.size == 1) select(choices.single()) else choose(choices, ::select)
    }

    private fun showOriginChooser(
        editor: Editor,
        origins: List<DispatcherOrigin>,
        onChosen: (DispatcherOrigin) -> Unit,
    ) {
        val choices = origins.map { origin ->
            val path = VfsUtilCore.urlToPath(origin.fileUrl)
            val basePath = editor.project?.basePath
            val displayPath = if (basePath != null && path.startsWith("$basePath/")) {
                path.removePrefix("$basePath/")
            } else {
                path
            }
            OriginChoice(
                origin,
                "$displayPath:${origin.line} — ${origin.description}",
            )
        }
        JBPopupFactory.getInstance().createPopupChooserBuilder(choices)
            .setTitle("Dispatcher origin")
            .setItemChosenCallback { onChosen(it.origin) }
            .createPopup()
            .show(JBPopupFactory.getInstance().guessBestPopupLocation(editor))
    }

    private fun openSourceOrigin(editor: Editor, origin: DispatcherOrigin) {
        val project = editor.project ?: return
        val file = VirtualFileManager.getInstance().findFileByUrl(origin.fileUrl) ?: return
        if (project.isDisposed || editor.isDisposed || !file.isValid || file.isDirectory) return
        val document = FileDocumentManager.getInstance().getDocument(file) ?: return
        if (origin.offset > document.textLength) return
        OpenFileDescriptor(project, file, origin.offset).navigate(true)
    }

    private data class OriginChoice(val origin: DispatcherOrigin, val label: String) {
        override fun toString(): String = label
    }
}
