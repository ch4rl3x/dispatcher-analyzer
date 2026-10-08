package de.charlex.dispatcher.editor

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.CaretListener
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
import java.util.IdentityHashMap

internal object CaretFunctionScope {
    fun functionRanges(file: KtFile): List<TextRange> =
        PsiTreeUtil.collectElementsOfType(file, KtNamedFunction::class.java).map { it.textRange }

    fun selectedRange(ranges: List<TextRange>, caretOffset: Int): TextRange? =
        ranges.asSequence().filter { it.startOffset <= caretOffset && caretOffset < it.endOffset }
            .minByOrNull { it.length }

    fun eligibleCallOffsets(file: KtFile, callOffsets: Set<Int>, selectedFunctionStart: Int?): Set<Int> {
        if (selectedFunctionStart == null || file.textLength == 0) return emptySet()
        return callOffsets.filterTo(linkedSetOf()) { callOffset ->
            val elementOffset = (callOffset - 1).coerceIn(0, file.textLength - 1)
            val element = file.findElementAt(elementOffset)
            PsiTreeUtil.getParentOfType(element, KtNamedFunction::class.java, false)
                ?.textRange?.startOffset == selectedFunctionStart
        }
    }
}

@Service(Service.Level.PROJECT)
internal class DispatcherCaretRefreshService(private val project: Project) : Disposable {
    private val states = IdentityHashMap<Editor, EditorState>()
    @Volatile private var disposed = false

    init {
        EditorFactory.getInstance().addEditorFactoryListener(object : EditorFactoryListener {
            override fun editorReleased(event: EditorFactoryEvent) {
                val state = synchronized(states) { states.remove(event.editor) } ?: return
                event.editor.caretModel.removeCaretListener(state.listener)
            }
        }, this)
    }

    fun track(editor: Editor, file: PsiFile, ranges: List<TextRange>, caretOffset: Int) {
        if (disposed || project.isDisposed || editor.isDisposed) return
        val application = ApplicationManager.getApplication()
        if (!application.isDispatchThread) {
            application.invokeLater({
                if (!disposed && !project.isDisposed && !editor.isDisposed) {
                    trackOnUi(editor, file, ranges, caretOffset)
                }
            }, ModalityState.any())
            return
        }
        trackOnUi(editor, file, ranges, caretOffset)
    }

    private fun trackOnUi(editor: Editor, file: PsiFile, ranges: List<TextRange>, caretOffset: Int) {
        if (disposed || project.isDisposed || editor.isDisposed) return
        val renderedSelection = CaretFunctionScope.selectedRange(ranges, caretOffset)
        val currentSelection = CaretFunctionScope.selectedRange(ranges, editor.caretModel.primaryCaret.offset)
        val state = synchronized(states) {
            if (disposed) return
            states[editor] ?: createState(editor).also { states[editor] = it }
        }
        synchronized(state) {
            state.file = file
            state.ranges = ranges
            state.selected = currentSelection
        }
        val settings = service<DispatcherSettings>()
        if (renderedSelection != currentSelection && settings.onlyInFunctionContainingCaret &&
            settings.callSiteMode != CallSiteBadgeMode.NONE) {
            InlayRefresh.restartProject(project, "Caret moved while dispatcher hints were rendered")
        }
    }

    private fun createState(editor: Editor): EditorState {
        val listener = object : CaretListener {
            override fun caretPositionChanged(event: CaretEvent) {
                if (event.editor !== editor || project.isDisposed || editor.isDisposed) return
                val current = synchronized(states) { states[editor] } ?: return
                val next: TextRange?
                val file: PsiFile?
                synchronized(current) {
                    next = CaretFunctionScope.selectedRange(current.ranges, editor.caretModel.primaryCaret.offset)
                    if (next == current.selected) return
                    current.selected = next
                    file = current.file
                }
                val settings = service<DispatcherSettings>()
                if (settings.onlyInFunctionContainingCaret && settings.callSiteMode != CallSiteBadgeMode.NONE) {
                    if (file != null) InlayRefresh.restartProject(project, "Caret moved to another function")
                }
            }
        }
        val state = EditorState(listener)
        editor.caretModel.addCaretListener(listener)
        return state
    }

    private class EditorState(val listener: CaretListener) {
        var file: PsiFile? = null
        var ranges: List<TextRange> = emptyList()
        var selected: TextRange? = null
    }

    override fun dispose() {
        disposed = true
        synchronized(states) {
            states.forEach { (editor, state) ->
                if (!editor.isDisposed) editor.caretModel.removeCaretListener(state.listener)
            }
            states.clear()
        }
    }
}
