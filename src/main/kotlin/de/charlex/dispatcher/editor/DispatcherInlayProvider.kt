package de.charlex.dispatcher.editor

import com.intellij.codeInsight.hints.ChangeListener
import com.intellij.codeInsight.hints.FactoryInlayHintsCollector
import com.intellij.codeInsight.hints.ImmediateConfigurable
import com.intellij.codeInsight.hints.InlayHintsCollector
import com.intellij.codeInsight.hints.InlayHintsProvider
import com.intellij.codeInsight.hints.InlayHintsSink
import com.intellij.codeInsight.hints.NoSettings
import com.intellij.codeInsight.hints.SettingsKey
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import de.charlex.dispatcher.analysis.DispatcherAnalysisService
import org.jetbrains.kotlin.psi.KtFile
import javax.swing.JComponent
import javax.swing.JPanel

class DispatcherInlayProvider : InlayHintsProvider<NoSettings> {
    override val key = SettingsKey<NoSettings>("dispatcher.analyzer.badges")
    override val name = "Coroutine dispatchers"
    override val previewText = """
        import kotlinx.coroutines.Dispatchers
        import kotlinx.coroutines.withContext

        suspend fun loadData() = withContext(Dispatchers.IO) {
            readData()
        }

        suspend fun refresh() {
            loadData()
        }
    """.trimIndent()

    override val isVisibleInSettings = false

    override fun createSettings() = NoSettings()

    override fun createConfigurable(settings: NoSettings): ImmediateConfigurable =
        object : ImmediateConfigurable {
            override fun createComponent(listener: ChangeListener): JComponent = JPanel()
        }

    override fun getCollectorFor(
        file: PsiFile,
        editor: Editor,
        settings: NoSettings,
        sink: InlayHintsSink,
    ): InlayHintsCollector? {
        if (file !is KtFile) return null
        return object : FactoryInlayHintsCollector(editor) {
            private var collected = false

            override fun collect(element: PsiElement, editor: Editor, sink: InlayHintsSink): Boolean {
                if (collected || file.project.isDisposed) return false
                collected = true
                ProgressManager.checkCanceled()
                val analysis = file.project.service<DispatcherAnalysisService>()
                val fileUrl = file.virtualFile?.url ?: return false
                val result = analysis.requestAnalysis(file)
                val badgeSettings = service<DispatcherSettings>().getState()
                val eligibleCallOffsets = if (
                    badgeSettings.onlyInFunctionContainingCaret &&
                    badgeSettings.resolvedCallSiteMode() != CallSiteBadgeMode.NONE
                ) {
                    val ranges = CaretFunctionScope.functionRanges(file)
                    val caretOffset = editor.caretModel.primaryCaret.offset
                    val selected = CaretFunctionScope.selectedRange(ranges, caretOffset)
                    file.project.service<DispatcherCaretRefreshService>().track(editor, file, ranges, caretOffset)
                    CaretFunctionScope.eligibleCallOffsets(file, result.calls.keys, selected?.startOffset)
                } else null
                BadgePresentation.render(
                    factory,
                    result,
                    editor,
                    badgeSettings,
                    sink,
                    fileUrl,
                    isCurrentAnalysis = { analysis.isCurrentAnalysis(fileUrl, result) },
                    eligibleCallOffsets = eligibleCallOffsets,
                )
                return false
            }
        }
    }
}
