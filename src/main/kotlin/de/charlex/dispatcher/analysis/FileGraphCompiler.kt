package de.charlex.dispatcher.analysis

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiRecursiveElementWalkingVisitor
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.psi.*
import java.security.MessageDigest

internal class FileGraphCompiler {
    fun compile(file: KtFile, contentHash: String): FileGraph {
        val progress = AnalysisProgress()
        val functions = mutableListOf<KtNamedFunction>()
        val initializers = mutableListOf<PsiElement>()
        val properties = mutableListOf<KtProperty>()
        val omitted = mutableListOf<com.intellij.openapi.util.TextRange>()
        file.accept(object : PsiRecursiveElementWalkingVisitor() {
            override fun visitElement(element: PsiElement) {
                progress.consume()
                when (element) {
                    is KtNamedFunction -> {
                        functions += element
                        element.bodyExpression?.textRange?.let(omitted::add)
                    }
                    is KtProperty -> {
                        properties += element
                        element.initializer?.textRange?.let(omitted::add)
                        if (PsiTreeUtil.getParentOfType(element, KtNamedFunction::class.java) == null) initializers += element
                    }
                    is KtAnonymousInitializer, is KtCallableReferenceExpression -> initializers += element
                    is KtPropertyAccessor -> element.bodyExpression?.let(initializers::add)
                    is KtParameter -> element.defaultValue?.let(initializers::add)
                    is KtSecondaryConstructor, is KtSuperTypeCallEntry,
                    is KtConstructorDelegationCall, is KtPropertyDelegate -> initializers += element
                }
                super.visitElement(element)
            }
        })
        val api = KotlinAnalysisAdapter()
        val graph = SourceGraph(api, progress)
        val bodies = functions.map { graph.body(it) }.filterIndexed { index, _ -> SourceGraph.isSupported(functions[index]) }
        initializers.forEach(graph::topLevel)
        val text = file.text
        val structure = StringBuilder()
        var offset = 0
        omitted.sortedBy { it.startOffset }.forEach { range ->
            if (range.startOffset >= offset) {
                structure.append(text, offset, range.startOffset).append("<body>")
                offset = range.endOffset
            }
        }
        structure.append(text, offset, file.textLength)
        (functions + properties).forEach { declaration ->
            progress.consume()
            structure.append('|').append(declaration.name).append(':').append(api.declarationType(declaration))
            if (declaration is KtNamedFunction) {
                val end = declaration.bodyExpression?.textRange?.startOffset ?: declaration.textRange.endOffset
                structure.append(text, declaration.textRange.startOffset, end)
            }
        }
        val path = file.virtualFile?.url ?: file.name
        return FileGraph(path, contentHash, sha256(structure.toString().toByteArray()),
            api.dependencies.filterTo(linkedSetOf()) { it != path }, bodies.toList(), graph.calls.toList(),
            graph.unresolvedNames.toSet(), graph.escapingTargets.toSet())
    }
}

internal fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
    .joinToString("") { "%02x".format(it) }
