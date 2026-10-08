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
                val result = file.project.service<DispatcherAnalysisService>().requestAnalysis(file)
                BadgePresentation.render(factory, result, editor, service<DispatcherSettings>().getState(), sink)
                return false
            }
        }
    }
}
