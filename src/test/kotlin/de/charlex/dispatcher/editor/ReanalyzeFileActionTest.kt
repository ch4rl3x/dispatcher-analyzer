package de.charlex.dispatcher.editor

import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class ReanalyzeFileActionTest : BasePlatformTestCase() {
    fun testActionIsVisibleOnlyForProjectKotlinFiles() {
        val kotlinFile = myFixture.addFileToProject("src/Worker.kt", "suspend fun work() {}")
        val javaFile = myFixture.addFileToProject("src/Worker.java", "class Worker {}")
        val action = ReanalyzeFileAction()

        assertEquals(ActionUpdateThread.BGT, action.actionUpdateThread)
        assertTrue(eventFor(action, kotlinFile.virtualFile).also(action::update).presentation.isEnabledAndVisible)
        assertFalse(eventFor(action, javaFile.virtualFile).also(action::update).presentation.isEnabledAndVisible)
    }

    fun testInvokingActionSavesActiveDocumentBeforeRequestingAnalysis() {
        val psiFile = myFixture.addFileToProject("src/Worker.kt", "suspend fun work() {}")
        myFixture.configureFromExistingVirtualFile(psiFile.virtualFile)
        val editor = myFixture.editor
        val file = FileDocumentManager.getInstance().getFile(editor.document)!!
        WriteCommandAction.runWriteCommandAction(project) {
            editor.document.insertString(0, "// saved before analysis\n")
        }
        assertTrue(FileDocumentManager.getInstance().isDocumentUnsaved(editor.document))

        ReanalyzeFileAction().actionPerformed(
            eventFor(ReanalyzeFileAction(), file, editor),
        )

        assertFalse(FileDocumentManager.getInstance().isDocumentUnsaved(editor.document))
        assertTrue(String(file.contentsToByteArray()).startsWith("// saved before analysis\n"))
    }

    private fun eventFor(
        action: ReanalyzeFileAction,
        file: com.intellij.openapi.vfs.VirtualFile,
        editor: com.intellij.openapi.editor.Editor? = null,
    ): AnActionEvent {
        val context = SimpleDataContext.builder()
            .add(CommonDataKeys.PROJECT, project)
            .add(CommonDataKeys.VIRTUAL_FILE, file)
            .apply { if (editor != null) add(CommonDataKeys.EDITOR, editor) }
            .build()
        return AnActionEvent.createFromAnAction(action, null, ActionPlaces.UNKNOWN, context)
    }
}
