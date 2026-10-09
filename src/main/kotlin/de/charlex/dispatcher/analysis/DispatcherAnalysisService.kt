package de.charlex.dispatcher.analysis

import com.intellij.ide.plugins.cl.PluginAwareClassLoader
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ReadConstraint
import com.intellij.openapi.application.constrainedReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileDocumentManagerListener
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootEvent
import com.intellij.openapi.roots.ModuleRootListener
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.LanguageLevelModuleExtension
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.roots.ExportableOrderEntry
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.roots.OrderEnumerator
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.roots.ProjectRootModificationTracker
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.VirtualFileWithId
import com.intellij.openapi.vfs.JarFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFileDeleteEvent
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import de.charlex.dispatcher.model.DispatcherSet
import de.charlex.dispatcher.model.EffectSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.kotlin.psi.KtFile
import java.nio.file.Path
import java.nio.file.Files
import java.security.MessageDigest

@Service(Service.Level.PROJECT)
class DispatcherAnalysisService(private val project: Project, private val coroutineScope: CoroutineScope) {
    @Volatile private var cached: Snapshot? = null
    @Volatile private var graphState: CachedProjectAnalysis? = null
    @Volatile private var generation = 0L
    @Volatile private var sourceRevision = 0L
    @Volatile private var documentRevision = 0L
    @Volatile private var status = "Waiting for saved sources"
    @Volatile private var started = false
    @Volatile private var forceFullRebuild = false
    @Volatile private var classRootPaths: Set<String> = emptySet()
    @Volatile private var classRootLinks: Set<String> = emptySet()
    @Volatile private var classpathRevision = 0L
    @Volatile private var classpathDigest: ClasspathDigest? = null
    @Volatile private var observedHashes: Map<String, ObservedHash> = emptyMap()
    private val requestLock = Any()
    private var pendingJob: Job? = null
    private val forcedFiles = linkedSetOf<String>()
    internal var cacheDirectoryOverride: Path? = null
    @Volatile internal var lastResolvedFiles: Set<String> = emptySet()
        private set

    val analysisStatus: String get() = status

    init {
        val lifetime = Disposer.newDisposable("Dispatcher analysis listeners")
        coroutineScope.coroutineContext[Job]?.invokeOnCompletion { Disposer.dispose(lifetime) }
        val connection = project.messageBus.connect(lifetime)
        connection.subscribe(ModuleRootListener.TOPIC, object : ModuleRootListener {
            override fun rootsChanged(event: ModuleRootEvent) {
                forceFullRebuild = true
                classpathRevision++
                invalidate()
                if (started) schedule()
            }
        })
        val applicationConnection = ApplicationManager.getApplication().messageBus.connect(lifetime)
        applicationConnection.subscribe(FileDocumentManagerListener.TOPIC, object : FileDocumentManagerListener {
            override fun afterDocumentSaved(document: com.intellij.openapi.editor.Document) {
                val file = FileDocumentManager.getInstance().getFile(document) ?: return
                if (relevant(file)) savedFilesChanged(setOf(file.url))
            }
        })
        applicationConnection.subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) {
                if (project.isDisposed) return
                if (events.any(::cacheEvent)) coroutineScope.launch(Dispatchers.IO) {
                    val cache = diskCache() ?: return@launch
                    if (!Files.isRegularFile(cache.path) && !project.isDisposed) {
                        forceFullRebuild = true
                        invalidate()
                        if (started) schedule()
                    }
                }
                val classpathChanged = events.any(::classRootEvent)
                if (classpathChanged) classpathRevision++
                val structural = classpathChanged || events.any(::structuralEvent)
                val files = events.mapNotNull { it.file }.filter { relevant(it) || graphState?.files?.containsKey(it.url) == true }
                if (structural || files.isNotEmpty()) {
                    if (structural) forceFullRebuild = true
                    invalidate()
                    if (started) schedule()
                }
            }
        })
        EditorFactory.getInstance().eventMulticaster.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                if (project.isDisposed) return
                val file = FileDocumentManager.getInstance().getFile(event.document) ?: return
                if (relevant(file)) invalidate(documentChanged = true)
            }
        }, lifetime)
    }

    fun startAnalysis() {
        if (project.isDisposed) return
        started = true
        schedule()
    }

    fun reanalyzeFile(file: VirtualFile) {
        if (project.isDisposed || file.extension != "kt" || !relevant(file)) return
        synchronized(requestLock) { forcedFiles += file.url }
        invalidate()
        savedFilesChanged(setOf(file.url))
    }

    internal fun savedFilesChanged(fileUrls: Set<String>) {
        if (project.isDisposed || fileUrls.isEmpty()) return
        started = true
        schedule()
    }

    fun requestAnalysis(file: KtFile): FileAnalysis {
        if (project.isDisposed || !file.isValid) return FileAnalysis()
        val path = file.virtualFile?.url ?: file.name
        cached?.takeIf { it.stamp == stamp() }?.analysis?.results?.get(path)?.let { return it }
        return unavailable(file, if (pendingJob?.isActive == true) "Dispatcher analysis is pending" else "Save the file to refresh dispatcher analysis")
    }

    internal fun hasCurrentAnalysis(file: KtFile): Boolean =
        !project.isDisposed && cached?.let { it.stamp == stamp() && (file.virtualFile?.url ?: file.name) in it.analysis.results } == true

    internal fun isCurrentAnalysis(fileUrl: String, result: FileAnalysis): Boolean =
        !project.isDisposed && cached?.let { it.stamp == stamp() && it.analysis.results[fileUrl] === result } == true

    private fun invalidate(documentChanged: Boolean = false) {
        if (project.isDisposed) return
        synchronized(requestLock) {
            sourceRevision++
            if (documentChanged) documentRevision++
            generation++
            pendingJob?.cancel()
            updateStatus(if (cached == null) "Waiting for saved sources" else "Out of date", generation)
        }
    }

    private fun schedule() {
        if (project.isDisposed) return
        synchronized(requestLock) {
            pendingJob?.cancel()
            val requestedGeneration = ++generation
            updateStatus("Queued", requestedGeneration)
            pendingJob = coroutineScope.launch {
                try { runScheduled(requestedGeneration) }
                catch (cancelled: ProcessCanceledException) { throw cancelled }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: java.io.IOException) {
                    LOG.debug("Dispatcher source could not be read", failure)
                    updateStatus("Analysis unavailable", requestedGeneration)
                }
                catch (failure: RuntimeException) {
                    LOG.debug("Dispatcher analysis is unavailable", failure)
                    updateStatus("Analysis unavailable", requestedGeneration)
                }
            }
        }
    }

    private suspend fun runScheduled(requestedGeneration: Long) {
        delay(100)
        val input = constrainedReadAction(ReadConstraint.inSmartMode(project), ReadConstraint.withDocumentsCommitted(project)) {
            if (project.isDisposed || hasUnsavedSources()) return@constrainedReadAction null
            collectInput()
        }
        if (input == null) {
            updateStatus("Waiting for saved sources", requestedGeneration)
            return
        }
        updateStatus("Analyzing", requestedGeneration)
        val hashes = withContext(Dispatchers.IO) { hashFiles(input.files) }
        val previousDigest = classpathDigest?.takeIf { it.paths == input.classpathPaths && it.revision == input.classpathRevision }
        val fingerprint = previousDigest?.let { ClasspathFingerprint.Result(it.value, it.watchedPaths, it.watchedLinks) }
            ?: ClasspathFingerprint.inspect(input.classpathPaths) { path, subtree ->
                synchronized(requestLock) {
                    if (generation == requestedGeneration) {
                        if (subtree) classRootPaths = classRootPaths + path else classRootLinks = classRootLinks + path
                    }
                }
            }
        val digest = fingerprint.digest
        synchronized(requestLock) {
            if (digest != null && generation == requestedGeneration && input.classpathRevision == classpathRevision) {
                classpathDigest = ClasspathDigest(input.classpathPaths, input.classpathRevision, digest, fingerprint.watchedPaths, fingerprint.watchedLinks)
                classRootPaths = fingerprint.watchedPaths
                classRootLinks = fingerprint.watchedLinks
            }
        }
        val environment = environmentFingerprint(input.environment + "|" + digest, input.files, hashes)
        val disk = diskCache()
        val cacheMissing = disk != null && withContext(Dispatchers.IO) { !Files.isRegularFile(disk.path) }
        val rebuild = forceFullRebuild || digest == null || cacheMissing && graphState != null
        val prior = if (rebuild) null else graphState?.takeIf { it.environmentFingerprint == environment } ?: disk?.load(environment)
        kotlin.coroutines.coroutineContext.ensureActive()
        val forced = synchronized(requestLock) { forcedFiles.toSet() }
        val snapshot = constrainedReadAction(ReadConstraint.inSmartMode(project), ReadConstraint.withDocumentsCommitted(project)) {
            if (project.isDisposed || input.stamp != stamp() || hasUnsavedSources()) return@constrainedReadAction null
            val files = input.files.filter { it.extension == "kt" }.mapNotNull { PsiManager.getInstance(project).findFile(it) as? KtFile }
            compute(files, hashes.mapValues { it.value.hash }, environment, prior, forced, input.stamp)
        } ?: return
        kotlin.coroutines.coroutineContext.ensureActive()
        withContext(Dispatchers.EDT) {
            if (project.isDisposed || generation != requestedGeneration || snapshot.stamp != stamp()) return@withContext
            graphState = snapshot.analysis
            cached = snapshot
            observedHashes = hashes
            synchronized(requestLock) { forcedFiles.removeAll(forced) }
            forceFullRebuild = false
            status = "Up to date"
            project.messageBus.syncPublisher(DispatcherAnalysisListener.TOPIC).analysisUpdated()
        }
        kotlin.coroutines.coroutineContext.ensureActive()
        if (digest != null && generation == requestedGeneration && snapshot.stamp == stamp()) {
            try { disk?.save(snapshot.analysis) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { LOG.debug("Dispatcher cache could not be saved", failure) }
        }
    }

    fun analyze(file: KtFile): FileAnalysis {
        if (project.isDisposed || !file.isValid) return FileAnalysis()
        if (DumbService.isDumb(project)) return unavailable(file, "Project indexing is in progress")
        val application = ApplicationManager.getApplication()
        if (application.isDispatchThread && !application.isUnitTestMode) return unavailable(file, "Analysis is waiting for background execution")
        application.assertReadAccessAllowed()
        val stamp = stamp()
        val path = file.virtualFile?.url ?: file.name
        cached?.takeIf { it.stamp == stamp }?.analysis?.results?.get(path)?.let { return it }
        val input = collectInput()
        val sources = listOf(file) + input.files.filter { it.extension == "kt" && it != file.virtualFile }
            .mapNotNull { PsiManager.getInstance(project).findFile(it) as? KtFile }
        val hashes = sources.associate { (it.virtualFile?.url ?: it.name) to sha256(it.text.toByteArray()) }
        val previous = graphState?.takeIf { it.environmentFingerprint == input.environment }
        val snapshot = compute(sources, hashes, input.environment, previous, emptySet(), stamp)
        if (stamp() == stamp && !project.isDisposed) {
            graphState = snapshot.analysis
            cached = snapshot
            status = "Up to date"
        }
        return snapshot.analysis.results[path] ?: FileAnalysis()
    }

    private fun compute(
        files: List<KtFile>, hashes: Map<String, String>, environment: String,
        previous: CachedProjectAnalysis?, forced: Set<String>, stamp: Stamp,
    ): Snapshot {
        val sources = files.associateBy { it.virtualFile?.url ?: it.name }
        val changed = sources.keys.filterTo(linkedSetOf()) { it in forced || previous?.files?.get(it)?.contentHash != hashes[it] }
        val removed = previous?.files.orEmpty().keys - sources.keys
        val graphs = previous?.files.orEmpty().filterKeys { it in sources }.toMutableMap()
        val resolved = linkedSetOf<String>()
        fun resolve(path: String) {
            ProgressManager.checkCanceled()
            graphs[path] = FileGraphCompiler().compile(sources.getValue(path), hashes.getValue(path))
            resolved += path
        }
        changed.forEach(::resolve)
        val structural = previous == null || removed.isNotEmpty() || sources.keys != previous.files.keys || changed.any {
            graphs[it]?.structureHash != previous.files[it]?.structureHash
        }
        if (structural) sources.keys.filterNot { it in resolved }.forEach(::resolve)
        else {
            val queue = ArrayDeque(changed)
            val dependents = previous!!.files.values.flatMap { graph -> graph.dependencies.map { it to graph.fileUrl } }
                .groupBy({ it.first }, { it.second })
            while (queue.isNotEmpty()) {
                ProgressManager.checkCanceled()
                dependents[queue.removeFirst()].orEmpty().forEach { path ->
                    if (path !in resolved) { resolve(path); queue.addLast(path) }
                }
            }
        }
        val analysis = ProjectGraphSolver().solve(environment, graphs, if (structural) null else previous, resolved + removed)
        lastResolvedFiles = resolved.toSet()
        val ancestry = linkedSetOf<String>()
        sources.keys.forEach { url ->
            ProgressManager.checkCanceled()
            var path = VfsUtilCore.urlToPath(url)
            while (path.isNotEmpty() && ancestry.add(path)) path = path.substringBeforeLast('/', "")
        }
        return Snapshot(stamp, analysis, immutable(sources.mapValues { fileStamp(it.value) }), ancestry)
    }

    private fun collectInput(): Input {
        val scope = GlobalSearchScope.projectScope(project)
        val files = (SOURCE_EXTENSIONS + CONFIG_EXTENSIONS).flatMap { extension -> FilenameIndex.getAllFilesByExt(project, extension, scope) }
            .distinctBy { it.url }.filter { relevant(it) }.sortedBy { it.url }
        val roots = OrderEnumerator.orderEntries(project).recursively().classes().roots.sortedBy { it.url }
        val physicalRoots = roots.map { JarFileSystem.getInstance().getVirtualFileForJar(it) ?: it }
        classRootPaths = classpathDigest?.watchedPaths.orEmpty() + physicalRoots.map { it.path.substringBefore("!/") }
        val classpathPaths = physicalRoots.flatMap { root ->
            if (root.fileSystem.protocol == "jrt") {
                val home = Path.of(root.path.substringBefore("!/"))
                listOf(home.resolve("lib/modules").toString(), home.resolve("release").toString())
            } else listOf(root.path)
        }.distinct()
        val environment = sha256(buildString {
            append("dispatcher-graph-1|").append(ApplicationInfo.getInstance().build.asString())
            append('|').append(pluginVersion(KtFile::class.java))
            append('|').append(pluginVersion(DispatcherAnalysisService::class.java))
            append('|').append(project.basePath)
            roots.zip(physicalRoots).forEach { (root, physical) ->
                append('|').append(root.url).append(':').append(physical.timeStamp).append(':').append(physical.length)
            }
            ModuleManager.getInstance(project).modules.sortedBy { it.name }.forEach { module ->
                ProgressManager.checkCanceled()
                val model = ModuleRootManager.getInstance(module)
                append("|module:").append(module.name).append(':').append(model.sdk?.name).append(':').append(model.sdk?.versionString)
                append(':').append(model.getModuleExtension(LanguageLevelModuleExtension::class.java)?.languageLevel)
                model.contentEntries.sortedBy { it.url }.forEach { content ->
                    append("|content:").append(content.url)
                    content.sourceFolders.sortedBy { it.url }.forEach { source ->
                        append("|source:").append(source.url).append(':').append(source.isTestSource).append(':').append(source.packagePrefix)
                    }
                    content.excludeFolderUrls.sorted().forEach { append("|exclude:").append(it) }
                }
                model.orderEntries.forEach { entry ->
                    append("|entry:").append(entry.javaClass.name).append(':').append(entry.presentableName)
                    if (entry is ExportableOrderEntry) append(':').append(entry.scope).append(':').append(entry.isExported)
                    entry.getFiles(OrderRootType.CLASSES).forEach { append(':').append(it.url) }
                }
            }
        }.toByteArray())
        return Input(stamp(), files, environment, classpathPaths, classpathRevision)
    }

    private fun pluginVersion(type: Class<*>): String? =
        (type.classLoader as? PluginAwareClassLoader)?.pluginDescriptor?.version ?: type.`package`.implementationVersion

    private suspend fun hashFiles(files: List<VirtualFile>): Map<String, ObservedHash> = buildMap {
        files.forEach { file ->
            kotlin.coroutines.coroutineContext.ensureActive()
            val previous = observedHashes[file.url]
            val stamp = file.modificationStamp
            val length = file.length
            val fileId = (file as? VirtualFileWithId)?.id
            if (previous != null && previous.stamp == stamp && previous.length == length && previous.fileId == fileId) put(file.url, previous)
            else {
                val digest = MessageDigest.getInstance("SHA-256")
                file.inputStream.use { stream ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        kotlin.coroutines.coroutineContext.ensureActive()
                        val count = stream.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                    }
                }
                put(file.url, ObservedHash(stamp, length, fileId, digest.digest().joinToString("") { "%02x".format(it) }))
            }
        }
    }

    private fun environmentFingerprint(base: String, files: List<VirtualFile>, hashes: Map<String, ObservedHash>): String = sha256(buildString {
        append(base)
        files.filter { it.extension != "kt" }.forEach { append('|').append(it.url).append(':').append(hashes.getValue(it.url).hash) }
    }.toByteArray())

    private fun relevant(file: VirtualFile): Boolean = !project.isDisposed && !file.path.contains("/build/dispatcher-analyzer/") &&
        file.extension in SOURCE_EXTENSIONS + CONFIG_EXTENSIONS && ProjectFileIndex.getInstance(project).isInContent(file)

    private fun structuralEvent(event: VFileEvent): Boolean {
        if (cacheEvent(event)) return false
        val file = event.file
        val cachePath = diskCache()?.path?.toString()?.replace('\\', '/')
        if (file?.isDirectory == true && ProjectFileIndex.getInstance(project).isInContent(file) &&
            (cachePath == null || !cachePath.startsWith(file.path + "/"))) return true
        return eventPaths(event).any { it in cached?.sourceAncestry.orEmpty() } &&
            (event is VFileDeleteEvent || event is VFileMoveEvent || event is VFilePropertyChangeEvent)
    }

    private fun classRootEvent(event: VFileEvent): Boolean = eventPaths(event).any { path ->
        classRootLinks.any { link -> path == link || event.file?.isDirectory == true && link.startsWith("$path/") } ||
        classRootPaths.any { root -> path == root || path.startsWith("$root/") ||
            event.file?.isDirectory == true && root.startsWith("$path/") }
    }

    private fun cacheEvent(event: VFileEvent): Boolean {
        if (event !is VFileDeleteEvent && event !is VFileMoveEvent && event !is VFilePropertyChangeEvent) return false
        val cachePath = diskCache()?.path?.toString()?.replace('\\', '/') ?: return false
        return eventPaths(event).any { cachePath == it || cachePath.startsWith("$it/") }
    }

    private fun eventPaths(event: VFileEvent): List<String> {
        val paths = mutableListOf(event.path)
        if (event is VFileMoveEvent) paths += event.oldParent.path + "/" + event.file.name
        if (event is VFilePropertyChangeEvent && event.propertyName == VirtualFile.PROP_NAME) {
            paths += event.file.parent.path + "/" + event.oldValue
        }
        return paths
    }

    private fun hasUnsavedSources(): Boolean = FileDocumentManager.getInstance().unsavedDocuments.any { document ->
        FileDocumentManager.getInstance().getFile(document)?.let(::relevant) == true
    }

    private fun diskCache(): AnalysisDiskCache? = (cacheDirectoryOverride ?: project.basePath?.let(Path::of))?.let(::AnalysisDiskCache)

    private fun unavailable(file: KtFile, reason: String): FileAnalysis {
        val path = file.virtualFile?.url ?: file.name
        val old = cached?.takeIf { it.fileStamps[path] == fileStamp(file) }?.analysis?.results?.get(path) ?: return FileAnalysis()
        val document = file.virtualFile?.let { FileDocumentManager.getInstance().getCachedDocument(it) }
        if (document != null && !PsiDocumentManager.getInstance(project).isCommitted(document)) return FileAnalysis()
        val badge = BadgeResult(EffectSummary(DispatcherSet.unknown(reason)), reason)
        return FileAnalysis(
            immutable(old.declarations.keys.associateWith { badge }),
            immutable(old.calls.keys.associateWith { badge }),
            immutable(old.nonSuspendDeclarations.keys.associateWith { badge }),
        )
    }

    private fun updateStatus(value: String, requestedGeneration: Long) {
        if (requestedGeneration != generation) return
        status = value
        coroutineScope.launch(Dispatchers.EDT) {
            if (!project.isDisposed && requestedGeneration == generation) project.messageBus.syncPublisher(DispatcherAnalysisListener.TOPIC).analysisUpdated()
        }
    }

    private fun stamp() = Stamp(sourceRevision, ProjectRootModificationTracker.getInstance(project).modificationCount, documentRevision)
    private fun fileStamp(file: KtFile) = FileStamp(file.modificationStamp, file.virtualFile?.modificationStamp, (file.virtualFile as? VirtualFileWithId)?.id)
    private data class Input(val stamp: Stamp, val files: List<VirtualFile>, val environment: String,
        val classpathPaths: List<String>, val classpathRevision: Long)
    private data class ClasspathDigest(val paths: List<String>, val revision: Long, val value: String,
        val watchedPaths: Set<String>, val watchedLinks: Set<String>)
    private data class Stamp(val sources: Long, val roots: Long, val documents: Long)
    private data class FileStamp(val psi: Long, val vfs: Long?, val fileId: Int?)
    private data class Snapshot(val stamp: Stamp, val analysis: CachedProjectAnalysis, val fileStamps: Map<String, FileStamp>, val sourceAncestry: Set<String>)
    private data class ObservedHash(val stamp: Long, val length: Long, val fileId: Int?, val hash: String)

    companion object {
        private val LOG = Logger.getInstance(DispatcherAnalysisService::class.java)
        private val SOURCE_EXTENSIONS = setOf("kt", "java")
        private val CONFIG_EXTENSIONS = setOf("kts", "gradle", "properties", "toml", "iml")
    }
}
