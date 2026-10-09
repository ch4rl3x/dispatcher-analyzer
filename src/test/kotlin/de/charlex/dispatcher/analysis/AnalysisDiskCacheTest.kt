package de.charlex.dispatcher.analysis

import de.charlex.dispatcher.model.Dispatcher
import de.charlex.dispatcher.model.DispatcherOrigin
import de.charlex.dispatcher.model.DispatcherSet
import de.charlex.dispatcher.model.EffectSummary
import de.charlex.dispatcher.model.PathRelation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.file.Files
import java.security.MessageDigest

class AnalysisDiskCacheTest {
    @Test
    fun roundTripsImmutableAnalysisGraphAndPresentationData() = runBlocking {
        val projectDirectory = Files.createTempDirectory("dispatcher-cache-roundtrip")
        try {
            val cache = AnalysisDiskCache(projectDirectory)
            val expected = sampleAnalysis()

            cache.save(expected)

            assertEquals(expected, cache.load(expected.environmentFingerprint))
            assertNull(cache.load("different environment"))
            assertEquals(projectDirectory.resolve("build/dispatcher-analyzer/analysis-cache.bin"), cache.path)
            Files.list(cache.path.parent).use { files ->
                assertEquals(listOf(cache.path), files.toList())
            }
        } finally {
            projectDirectory.toFile().deleteRecursively()
        }
    }

    @Test
    fun cachesWithoutNonSuspendIncomingContextsAreDiscarded() = runBlocking {
        val projectDirectory = Files.createTempDirectory("dispatcher-cache-legacy-functions")
        try {
            val cache = AnalysisDiskCache(projectDirectory)
            val expected = sampleAnalysis()
            cache.save(expected)
            val payload = Files.readAllBytes(cache.path).dropLast(32).toByteArray()
            ByteBuffer.wrap(payload).putInt(4, 5)
            Files.write(cache.path, payload + MessageDigest.getInstance("SHA-256").digest(payload))
            assertNull(cache.load(expected.environmentFingerprint))
        } finally {
            projectDirectory.toFile().deleteRecursively()
        }
    }

    @Test
    fun missingTruncatedAndTrailingDataAreCacheMisses() = runBlocking {
        val projectDirectory = Files.createTempDirectory("dispatcher-cache-corrupt")
        try {
            val cache = AnalysisDiskCache(projectDirectory)
            assertNull(cache.load("fingerprint"))

            cache.save(sampleAnalysis())
            Files.write(cache.path, byteArrayOf(0x44, 0x41, 0x4e))
            assertNull(cache.load("environment-v1"))

            cache.save(sampleAnalysis())
            Files.write(cache.path, Files.readAllBytes(cache.path) + byteArrayOf(0x01))
            assertNull(cache.load("environment-v1"))
        } finally {
            projectDirectory.toFile().deleteRecursively()
        }
    }

    @Test
    fun schemaMismatchAndValidTagCorruptionAreCacheMisses() = runBlocking {
        val projectDirectory = Files.createTempDirectory("dispatcher-cache-integrity")
        try {
            val cache = AnalysisDiskCache(projectDirectory)
            val key = FunctionKey("file:///project/src/Worker.kt", 0)
            cache.save(CachedProjectAnalysis(
                environmentFingerprint = "environment-v1",
                files = emptyMap(),
                summaries = mapOf(key to EffectSummary(DispatcherSet.of(Dispatcher.IO))),
                incoming = emptyMap(),
                results = emptyMap(),
            ))
            val bytes = Files.readAllBytes(cache.path)
            val fingerprintLength = "environment-v1".toByteArray(Charsets.UTF_8).size
            val fileLength = key.file.toByteArray(Charsets.UTF_8).size
            val dispatcherTagOffset = 8 + 4 + fingerprintLength + 4 + 4 + 4 + fileLength + 4 + 4

            assertEquals(1, bytes[dispatcherTagOffset].toInt())
            bytes[dispatcherTagOffset] = 0 // IO remains a valid tag after changing to Main.
            Files.write(cache.path, bytes)
            assertNull(cache.load("environment-v1"))

            cache.save(sampleAnalysis())
            val wrongSchema = Files.readAllBytes(cache.path)
            wrongSchema[7] = 99
            Files.write(cache.path, wrongSchema)
            assertNull(cache.load("environment-v1"))
        } finally {
            projectDirectory.toFile().deleteRecursively()
        }
    }

    @Test
    fun cachesWithoutDispatcherSelectionIdentitiesAreDiscarded() = runBlocking {
        val projectDirectory = Files.createTempDirectory("dispatcher-cache-legacy-selections")
        try {
            val cache = AnalysisDiskCache(projectDirectory)
            val expected = sampleAnalysis()
            cache.save(expected)
            val payload = Files.readAllBytes(cache.path).dropLast(32).toByteArray()
            ByteBuffer.wrap(payload).putInt(4, 2)
            Files.write(cache.path, payload + MessageDigest.getInstance("SHA-256").digest(payload))

            assertNull(cache.load(expected.environmentFingerprint))
        } finally {
            projectDirectory.toFile().deleteRecursively()
        }
    }

    @Test
    fun cachesWithVisibilityBasedIncomingUncertaintyAreDiscarded() = runBlocking {
        val projectDirectory = Files.createTempDirectory("dispatcher-cache-legacy-visibility")
        try {
            val cache = AnalysisDiskCache(projectDirectory)
            val expected = sampleAnalysis()
            cache.save(expected)
            val payload = Files.readAllBytes(cache.path).dropLast(32).toByteArray()
            ByteBuffer.wrap(payload).putInt(4, 3)
            Files.write(cache.path, payload + MessageDigest.getInstance("SHA-256").digest(payload))

            assertNull(cache.load(expected.environmentFingerprint))
        } finally {
            projectDirectory.toFile().deleteRecursively()
        }
    }

    @Test
    fun malformedDeclaredLengthCannotExceedActualFileBytes() = runBlocking {
        val projectDirectory = Files.createTempDirectory("dispatcher-cache-length")
        try {
            val cache = AnalysisDiskCache(projectDirectory)
            cache.save(sampleAnalysis())
            val version = ByteBuffer.wrap(Files.readAllBytes(cache.path)).getInt(4)
            DataOutputStream(Files.newOutputStream(cache.path)).use { output ->
                output.writeInt(0x44414E41)
                output.writeInt(version)
                output.writeInt(Int.MAX_VALUE)
                output.write(ByteArray(32))
            }

            assertNull(cache.load("fingerprint"))
        } finally {
            projectDirectory.toFile().deleteRecursively()
        }
    }

    @Test
    fun canceledSaveKeepsPreviousCacheAndLeavesNoTemporaryFile() = runBlocking {
        val projectDirectory = Files.createTempDirectory("dispatcher-cache-cancel")
        try {
            val cache = AnalysisDiskCache(projectDirectory)
            val previous = sampleAnalysis()
            cache.save(previous)
            val previousBytes = Files.readAllBytes(cache.path)
            val cancelledJob = kotlinx.coroutines.Job().apply { cancel() }

            try {
                kotlinx.coroutines.withContext(cancelledJob) { cache.save(sampleAnalysis().copy(environmentFingerprint = "new")) }
                throw AssertionError("Expected canceled cache write")
            } catch (_: CancellationException) {
                // Expected: cancellation before IO begins must preserve the published snapshot.
            }

            assertTrue(previousBytes.contentEquals(Files.readAllBytes(cache.path)))
            Files.list(cache.path.parent).use { files -> assertEquals(listOf(cache.path), files.toList()) }
        } finally {
            projectDirectory.toFile().deleteRecursively()
        }
    }

    @Test
    fun codecRetainsOriginProvenanceAndDispatcherSelections() = runBlocking {
        val projectDirectory = Files.createTempDirectory("dispatcher-cache-provenance")
        try {
            val cache = AnalysisDiskCache(projectDirectory)
            val expected = sampleAnalysis()
            cache.save(expected)
            val loaded = requireNotNull(cache.load(expected.environmentFingerprint))

            val cachedIo = loaded.summaries.values.first().dispatchers.origins.getValue(Dispatcher.IO)
            assertEquals(setOf(DispatcherOrigin("file:///project/src/Worker.kt", 23, 2, "withContext(Dispatchers.IO)")), cachedIo)
            assertTrue(loaded.summaries.values.first().setsDispatcher)
            assertEquals(setOf(Dispatcher.IO), loaded.summaries.values.first().selectedDispatchers.known)
            assertEquals(cachedIo, loaded.summaries.values.first().selectedDispatchers.origins[Dispatcher.IO])
            assertTrue(loaded.incoming.values.first().origins.containsKey(Dispatcher.Main))
            assertFalse(loaded.summaries.values.first().pathRelations.isEmpty())
        } finally {
            projectDirectory.toFile().deleteRecursively()
        }
    }

    private fun sampleAnalysis(): CachedProjectAnalysis {
        val worker = FunctionKey("file:///project/src/Worker.kt", 11)
        val mainOrigin = DispatcherOrigin("file:///project/src/Entry.kt", 4, 1, "launch")
        val ioOrigin = DispatcherOrigin("file:///project/src/Worker.kt", 23, 2, "withContext(Dispatchers.IO)")
        val incoming = DispatcherSet(
            known = setOf(Dispatcher.Main),
            origins = mapOf(Dispatcher.Main to setOf(mainOrigin)),
        )
        val selectedDispatchers = DispatcherSet(
            known = setOf(Dispatcher.IO),
            origins = mapOf(Dispatcher.IO to setOf(ioOrigin)),
        )
        val selected = EffectSummary(
            incoming.join(selectedDispatchers),
            setOf(PathRelation.CONTEXT_SWITCH),
            selectedDispatchers = selectedDispatchers,
        )
        val effect = Effect.Group(
            listOf(
                Effect.Work(EffectSummary(incoming)),
                Effect.ContextSelection(
                    Effect.Group(
                        listOf(Effect.Invoke(worker, incoming), Effect.Work(selected)),
                        branch = true,
                    ),
                    selectedDispatchers,
                ),
            ),
        )
        val graph = FileGraph(
            fileUrl = worker.file,
            contentHash = "source-content-hash",
            structureHash = "declaration-shape-hash",
            dependencies = setOf("library:coroutines:1", "module:app"),
            functions = listOf(FunctionBody(worker, "worker", effect, isSuspend = false)),
            calls = listOf(SourceCall(worker.file, 42, worker, worker, incoming, effect, showBadge = false)),
            unresolvedNames = setOf("customScope"),
            escapingTargets = setOf(worker),
        )
        val result = FileAnalysis(
            declarations = mapOf(11 to BadgeResult(selected, "Verified dispatcher selection")),
            calls = mapOf(42 to BadgeResult(EffectSummary(incoming), "Incoming context")),
            nonSuspendDeclarations = mapOf(50 to BadgeResult(EffectSummary(incoming), "Helper context")),
        )
        return CachedProjectAnalysis(
            environmentFingerprint = "environment-v1",
            files = mapOf(worker.file to graph),
            summaries = mapOf(worker to selected),
            incoming = mapOf(worker to incoming),
            results = mapOf(worker.file to result),
        )
    }
}
