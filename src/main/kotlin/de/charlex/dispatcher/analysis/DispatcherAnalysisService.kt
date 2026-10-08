package de.charlex.dispatcher.analysis

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ReadConstraint
import com.intellij.openapi.application.constrainedReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootModificationTracker
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiRecursiveElementWalkingVisitor
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.SmartPsiElementPointer
import com.intellij.ProjectTopics
import com.intellij.openapi.roots.ModuleRootEvent
import com.intellij.openapi.roots.ModuleRootListener
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.util.Disposer
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.psi.util.PsiTreeUtil
import de.charlex.dispatcher.model.Dispatcher
import de.charlex.dispatcher.model.DispatcherSet
import de.charlex.dispatcher.model.EffectSummary
import de.charlex.dispatcher.model.PathRelation
import de.charlex.dispatcher.editor.DispatcherSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtAnonymousInitializer
import org.jetbrains.kotlin.psi.KtCallableReferenceExpression
import org.jetbrains.kotlin.psi.KtPropertyAccessor
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtSecondaryConstructor
import org.jetbrains.kotlin.psi.KtSuperTypeCallEntry
import org.jetbrains.kotlin.psi.KtConstructorDelegationCall
import org.jetbrains.kotlin.psi.KtPropertyDelegate

@Service(Service.Level.PROJECT)
class DispatcherAnalysisService(private val project: Project, private val coroutineScope: CoroutineScope) {
    @Volatile private var cached: Snapshot? = null
    private val requestLock = Any()
    private var pendingStamp: Stamp? = null
    private var pendingJob: Job? = null
    private var latestRequest: SmartPsiElementPointer<KtFile>? = null
    @Volatile private var requestGeneration = 0L
    @Volatile private var documentRevision = 0L
    @Volatile private var currentStatus = "Idle"
    private var explicitRequestId = 0L

    val analysisStatus: String
        get() = currentStatus

    init {
        val lifetime = Disposer.newDisposable("Dispatcher analysis listeners")
        coroutineScope.coroutineContext[Job]?.invokeOnCompletion { Disposer.dispose(lifetime) }
        val connection = project.messageBus.connect(lifetime)
        connection.subscribe(PsiModificationTracker.TOPIC, PsiModificationTracker.Listener {
            sourceChanged()
        })
        connection.subscribe(ProjectTopics.PROJECT_ROOTS, object : ModuleRootListener {
            override fun rootsChanged(event: ModuleRootEvent) {
                sourceChanged()
            }
        })
        EditorFactory.getInstance().eventMulticaster.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                if (project.isDisposed) return
                val file = FileDocumentManager.getInstance().getFile(event.document) ?: return
                if (file.extension == "kt" && ProjectFileIndex.getInstance(project).isInContent(file)) sourceChanged(documentChanged = true)
            }
        }, lifetime)
    }

    fun requestAnalysis(file: KtFile): FileAnalysis {
        if (project.isDisposed || !file.isValid) return FileAnalysis()
        val requestedStamp = stamp()
        val path = file.virtualFile?.url ?: file.name
        cached?.takeIf { it.stamp == requestedStamp }?.files?.get(path)?.let { return it }
        synchronized(requestLock) {
            latestRequest = SmartPointerManager.getInstance(project).createSmartPsiElementPointer(file)
            if (service<DispatcherSettings>().automaticAnalysis && (pendingStamp != requestedStamp || pendingJob?.isActive != true)) {
                schedule(latestRequest, requestedStamp, debounce = true)
            }
        }
        val old = cached
        val callOffsets = old?.takeIf { it.fileStamps[path] == file.modificationStamp }?.files?.get(path)?.calls?.keys.orEmpty()
        val reason = if (service<DispatcherSettings>().automaticAnalysis || pendingJob?.isActive == true) {
            "Dispatcher analysis is pending"
        } else "Run Analyze project to refresh dispatcher analysis"
        return unavailable(file, reason, callOffsets)
    }

    fun runAnalysisNow() {
        if (project.isDisposed) return
        val request = synchronized(requestLock) {
            pendingJob?.cancel()
            pendingStamp = null
            requestGeneration++
            updateStatus("Queued", requestGeneration)
            ++explicitRequestId
        }
        coroutineScope.launch(Dispatchers.EDT) {
            if (project.isDisposed) return@launch
            PsiDocumentManager.getInstance(project).performWhenAllCommitted {
                if (!project.isDisposed && coroutineScope.isActive) synchronized(requestLock) {
                    if (request == explicitRequestId) schedule(latestRequest, stamp(), debounce = false)
                }
            }
        }
    }

    fun analysisModeChanged() {
        if (project.isDisposed) return
        synchronized(requestLock) {
            explicitRequestId++
            pendingJob?.cancel()
            pendingStamp = null
            requestGeneration++
            if (service<DispatcherSettings>().automaticAnalysis && latestRequest != null) {
                schedule(latestRequest, stamp(), debounce = true)
            } else {
                updateStatus(if (cached?.stamp == stamp()) "Up to date" else if (cached == null) "Idle" else "Out of date", requestGeneration)
            }
        }
    }

    private fun sourceChanged(documentChanged: Boolean = false) {
        if (project.isDisposed) return
        synchronized(requestLock) {
            if (documentChanged) {
                documentRevision++
                explicitRequestId++
            }
            pendingJob?.cancel()
            pendingStamp = null
            requestGeneration++
            if (service<DispatcherSettings>().automaticAnalysis && latestRequest != null) {
                schedule(latestRequest, stamp(), debounce = true)
            } else {
                updateStatus(if (cached == null) "Idle" else "Out of date", requestGeneration)
            }
        }
    }

    private fun schedule(pointer: SmartPsiElementPointer<KtFile>?, requestedStamp: Stamp, debounce: Boolean) {
        pendingJob?.cancel()
        pendingStamp = requestedStamp
        val generation = ++requestGeneration
        updateStatus(if (debounce) "Queued" else "Analyzing", generation)
        pendingJob = coroutineScope.launch {
            if (debounce) delay(750)
            updateStatus("Analyzing", generation)
            val snapshot = constrainedReadAction(ReadConstraint.inSmartMode(project), ReadConstraint.withDocumentsCommitted(project)) {
                if (project.isDisposed || stamp() != requestedStamp) return@constrainedReadAction null
                val requested = pointer?.element ?: FilenameIndex.getAllFilesByExt(project, "kt", GlobalSearchScope.projectScope(project))
                    .asSequence().mapNotNull { PsiManager.getInstance(project).findFile(it) as? KtFile }.firstOrNull()
                if (requested == null) Snapshot(requestedStamp, emptyMap(), emptyMap()) else computeSafely(requested, requestedStamp)
            } ?: return@launch
            ensureActive()
            withContext(Dispatchers.EDT) {
                if (project.isDisposed || stamp() != snapshot.stamp || generation != requestGeneration) return@withContext
                cached = snapshot
                currentStatus = "Up to date"
                project.messageBus.syncPublisher(DispatcherAnalysisListener.TOPIC).analysisUpdated()
            }
        }
    }

    private fun updateStatus(status: String, generation: Long) {
        if (generation != requestGeneration || currentStatus == status) return
        currentStatus = status
        coroutineScope.launch(Dispatchers.EDT) {
            if (!project.isDisposed && generation == requestGeneration) {
                project.messageBus.syncPublisher(DispatcherAnalysisListener.TOPIC).analysisUpdated()
            }
        }
    }

    internal fun hasCurrentAnalysis(file: KtFile): Boolean =
        !project.isDisposed && cached?.let { it.stamp == stamp() && (file.virtualFile?.url ?: file.name) in it.files } == true

    fun analyze(file: KtFile): FileAnalysis {
        if (project.isDisposed || !file.isValid) return FileAnalysis()
        if (DumbService.isDumb(project)) return unavailable(file, "Project indexing is in progress")
        val application = ApplicationManager.getApplication()
        if (application.isDispatchThread && !application.isUnitTestMode) {
            return unavailable(file, "Analysis is waiting for background execution")
        }
        application.assertReadAccessAllowed()
        val stamp = stamp()
        val fileKey = file.virtualFile?.url ?: file.name
        cached?.takeIf { it.stamp == stamp }?.files?.get(fileKey)?.let { return it }
        val snapshot = computeSafely(file, stamp)
        if (stamp() == stamp && !project.isDisposed) {
            cached = snapshot
            currentStatus = "Up to date"
        }
        return snapshot.files[fileKey] ?: unavailable(file, "File is no longer available")
    }

    private fun computeSafely(file: KtFile, stamp: Stamp): Snapshot = try {
        compute(file, stamp)
    } catch (cancelled: ProcessCanceledException) {
        throw cancelled
    } catch (cancelled: java.util.concurrent.CancellationException) {
        throw cancelled
    } catch (failure: RuntimeException) {
        LOG.debug("Dispatcher analysis unavailable", failure)
        val path = file.virtualFile?.url ?: file.name
        Snapshot(stamp, mapOf(path to unavailable(file, "Kotlin analysis is unavailable for this file")),
            mapOf(path to file.modificationStamp))
    }

    private fun stamp() = Stamp(
        PsiModificationTracker.getInstance(project).modificationCount,
        ProjectRootModificationTracker.getInstance(project).modificationCount,
        documentRevision,
    )

    private fun compute(requested: KtFile, stamp: Stamp): Snapshot {
        val sourceFiles = FilenameIndex.getAllFilesByExt(project, "kt", GlobalSearchScope.projectScope(project))
        val files = listOf(requested) + sourceFiles.asSequence()
            .filter { it != requested.virtualFile }
            .sortedBy { it.url }
            .mapNotNull { PsiManager.getInstance(project).findFile(it) as? KtFile }.toList()
        val progress = AnalysisProgress()
        val functions = mutableListOf<KtNamedFunction>()
        val initializers = mutableListOf<PsiElement>()
        files.forEach { file ->
            file.accept(object : PsiRecursiveElementWalkingVisitor() {
                override fun visitElement(element: PsiElement) {
                    progress.consume()
                    when (element) {
                        is KtNamedFunction -> {
                            functions += element
                        }
                        is KtProperty -> if (PsiTreeUtil.getParentOfType(element, KtNamedFunction::class.java) == null) {
                            initializers += element
                        }
                        is KtAnonymousInitializer -> initializers += element
                        is KtCallableReferenceExpression -> initializers += element
                        is KtPropertyAccessor -> element.bodyExpression?.let(initializers::add)
                        is KtParameter -> element.defaultValue?.let(initializers::add)
                        is KtSecondaryConstructor, is KtSuperTypeCallEntry,
                        is KtConstructorDelegationCall, is KtPropertyDelegate -> initializers += element
                    }
                    super.visitElement(element)
                }
            })
        }
        val suspendFunctions = functions.filter(SourceGraph::isSupported)
        val keys = suspendFunctions.map(SourceGraph::key).toSet()
        val graph = SourceGraph(KotlinAnalysisAdapter(), keys, progress)
        val allBodies = functions.map(graph::body)
        initializers.forEach(graph::topLevel)
        val bodies = allBodies.filter { it.key in keys }

        var summaries = keys.associateWith { EffectSummary.EMPTY }
        while (true) {
            ProgressManager.checkCanceled()
            val next = bodies.associate { body ->
                body.key to summaries.getValue(body.key).join(evaluate(body.effect, summaries))
            }
            if (next == summaries) {
                break
            }
            summaries = next
        }
        summaries = summaries.mapValues { (_, summary) ->
            when {
                summary.dispatchers.isEmpty -> unknown("No executable dispatcher evidence was found")
                else -> summary
            }
        }
        while (true) {
            ProgressManager.checkCanceled()
            val next = bodies.associate { body ->
                body.key to summaries.getValue(body.key).join(evaluate(body.effect, summaries))
            }
            if (next == summaries) break
            summaries = next
        }
        var incoming = bodies.associate { body ->
            val reasons = mutableSetOf<String>()
            if (body.openEntry) reasons += "External callers may use another dispatcher"
            if (body.declaration.name in graph.unresolvedNames) reasons += "An unresolved call may target this declaration"
            if (body.key in graph.escapingTargets) reasons += "A callable reference may escape the analyzed call graph"
            body.key to DispatcherSet(unknownReasons = reasons)
        }
        while (true) {
            ProgressManager.checkCanceled()
            val next = incoming.toMutableMap()
            graph.calls.forEach { call ->
                ProgressManager.checkCanceled()
                val target = call.target ?: return@forEach
                if (target !in keys) return@forEach
                val ownerContext = if (call.owner == null) DispatcherSet.EMPTY else incoming[call.owner] ?: DispatcherSet.EMPTY
                val supplied = substitute(call.context, ownerContext)
                next[target] = next.getValue(target).join(supplied)
            }
            if (next == incoming) {
                break
            }
            incoming = next
        }
        incoming = incoming.mapValues { (_, contexts) ->
            when {
                contexts.isEmpty -> DispatcherSet.unknown("No proven callers were found")
                else -> contexts
            }
        }
        while (true) {
            ProgressManager.checkCanceled()
            val next = incoming.toMutableMap()
            graph.calls.forEach { call ->
                ProgressManager.checkCanceled()
                val target = call.target ?: return@forEach
                if (target !in keys) return@forEach
                val ownerContext = call.owner?.let(incoming::get) ?: DispatcherSet.EMPTY
                next[target] = next.getValue(target).join(substitute(call.context, ownerContext))
            }
            if (next == incoming) break
            incoming = next
        }

        val result = files.associate { file ->
            val path = file.virtualFile?.url ?: file.name
            val declarations = bodies.filter { it.key.file == path }.associate { body ->
                val summary = EffectSummary(incoming.getValue(body.key))
                body.key.offset to BadgeResult(summary, tooltip(summary, declaration = true))
            }
            val calls = graph.calls.filter { it.file == path }.associate { call ->
                val inherited = call.owner?.let(incoming::get) ?: DispatcherSet.EMPTY
                val summary = evaluate(call.effect, summaries).substitute(inherited).let { result ->
                    if (Dispatcher.Inherited in result.dispatchers.known) {
                        result.substitute(DispatcherSet.unknown("Caller's context is unknown"))
                    } else result
                }
                call.offset to BadgeResult(summary, tooltip(summary, declaration = false))
            }
            path to FileAnalysis(immutable(declarations), immutable(calls))
        }
        return Snapshot(stamp, immutable(result), immutable(files.associate { (it.virtualFile?.url ?: it.name) to it.modificationStamp }))
    }

    private fun evaluate(effect: Effect, summaries: Map<FunctionKey, EffectSummary>): EffectSummary {
        ProgressManager.checkCanceled()
        return when (effect) {
            is Effect.Work -> effect.summary
            is Effect.Invoke -> summaries[effect.target]?.let { summary ->
                if (summary.dispatchers.isEmpty) summary else summary.substitute(effect.context)
            } ?: unknown("Callee was outside the analyzed graph")
            is Effect.Group -> {
                val children = effect.effects.map { evaluate(it, summaries) }.filterNot { it.dispatchers.isEmpty }
                val joined = children.fold(EffectSummary.EMPTY, EffectSummary::join)
                if (joined.dispatchers.known.size > 1 && (effect.branch || children.size > 1)) EffectSummary(joined.dispatchers,
                    joined.pathRelations + if (effect.branch) PathRelation.BRANCH_ALTERNATIVES else PathRelation.CONTEXT_SWITCH)
                else joined
            }
        }
    }

    private fun unavailable(file: KtFile, reason: String, callOffsets: Set<Int> = emptySet()): FileAnalysis {
        val badge = BadgeResult(unknown(reason), reason)
        val declarations = mutableMapOf<Int, BadgeResult>()
        file.accept(object : PsiRecursiveElementWalkingVisitor() {
            override fun visitElement(element: PsiElement) {
                ProgressManager.checkCanceled()
                if (element is KtNamedFunction && SourceGraph.isSupported(element)) declarations[element.textRange.startOffset] = badge
                super.visitElement(element)
            }
        })
        return FileAnalysis(immutable(declarations), immutable(callOffsets.associateWith { badge }))
    }

    private data class Stamp(val source: Long, val roots: Long, val documents: Long)
    private data class Snapshot(val stamp: Stamp, val files: Map<String, FileAnalysis>, val fileStamps: Map<String, Long>)

    companion object {
        private val LOG = Logger.getInstance(DispatcherAnalysisService::class.java)

        private fun unknown(reason: String) = EffectSummary(DispatcherSet.unknown(reason))
        private fun substitute(context: DispatcherSet, incoming: DispatcherSet): DispatcherSet {
            if (Dispatcher.Inherited !in context.known) return context
            return DispatcherSet(context.known - Dispatcher.Inherited, context.unknownReasons).join(incoming)
        }
        private fun <K, V> immutable(map: Map<K, V>): Map<K, V> = java.util.Collections.unmodifiableMap(LinkedHashMap(map))
        private fun tooltip(summary: EffectSummary, declaration: Boolean): String = buildString {
            append(if (declaration) "Possible incoming dispatcher contexts. " else "Dispatcher contexts for callee execution. ")
            if (!declaration) {
                if (PathRelation.CONTEXT_SWITCH in summary.pathRelations) append("Includes dispatcher switches within a path. ")
                if (PathRelation.BRANCH_ALTERNATIVES in summary.pathRelations) append("Includes alternative execution paths. ")
                if (summary.dispatchers.hasUnknown) append("Coverage is unknown. ")
            }
            append(summary.dispatchers.unknownReasons.sorted().joinToString(". "))
            append(" A dispatcher badge is not a thread-safety guarantee.")
        }
    }
}
