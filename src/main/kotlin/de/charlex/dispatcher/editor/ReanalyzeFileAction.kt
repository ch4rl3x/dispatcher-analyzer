package de.charlex.dispatcher.editor

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import de.charlex.dispatcher.analysis.DispatcherAnalysisService
import org.jetbrains.kotlin.psi.KtFile

class ReanalyzeFileAction : AnAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        val project = event.project
        val file = project?.let { currentFile(event, it) }
        event.presentation.isEnabledAndVisible =
            project != null && file != null && isSupportedFile(project, file)
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        if (project.isDisposed) return
        val file = currentFile(event, project) ?: return
        if (!file.isValid) return

        val fileDocumentManager = FileDocumentManager.getInstance()
        fileDocumentManager.getCachedDocument(file)?.let(fileDocumentManager::saveDocument)
        project.service<DispatcherAnalysisService>().reanalyzeFile(file)
    }

    internal fun isSupportedFile(project: Project, file: VirtualFile): Boolean {
        if (project.isDisposed || !file.isValid || file.isDirectory || file.extension != "kt") return false
        if (!ProjectFileIndex.getInstance(project).isInContent(file)) return false
        val psiFile = PsiManager.getInstance(project).findFile(file) as? KtFile ?: return false
        return !psiFile.isCompiled
    }

    private fun currentFile(event: AnActionEvent, project: Project): VirtualFile? {
        return event.getData(CommonDataKeys.VIRTUAL_FILE)
            ?: event.getData(CommonDataKeys.EDITOR)
            ?.document
            ?.let(FileDocumentManager.getInstance()::getFile)
            ?: FileEditorManager.getInstance(project).selectedFiles.firstOrNull()
    }
}
