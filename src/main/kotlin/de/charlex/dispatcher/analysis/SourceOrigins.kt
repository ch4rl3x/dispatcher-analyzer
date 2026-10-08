package de.charlex.dispatcher.analysis

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.psi.PsiDocumentManager
import de.charlex.dispatcher.model.Dispatcher
import de.charlex.dispatcher.model.DispatcherOrigin
import de.charlex.dispatcher.model.DispatcherSet
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile

internal object SourceOrigins {
    fun dispatchers(dispatcher: Dispatcher, expression: KtExpression): DispatcherSet {
        ProgressManager.checkCanceled()
        val file = expression.containingFile as? KtFile ?: return DispatcherSet.of(dispatcher)
        val virtualFile = file.virtualFile ?: return DispatcherSet.of(dispatcher)
        if (file.isCompiled || !ProjectFileIndex.getInstance(file.project).isInContent(virtualFile)) {
            return DispatcherSet.of(dispatcher)
        }
        val document = PsiDocumentManager.getInstance(file.project).getDocument(file)
            ?: return DispatcherSet.of(dispatcher)
        val offset = expression.textRange.startOffset
        val end = minOf(expression.textRange.endOffset, offset + 120)
        val description = document.immutableCharSequence.subSequence(offset, end).toString()
            .replace(Regex("\\s+"), " ").trim()
        val origin = DispatcherOrigin(virtualFile.url, offset, document.getLineNumber(offset) + 1, description)
        return DispatcherSet(setOf(dispatcher), origins = mapOf(dispatcher to setOf(origin)))
    }
}
