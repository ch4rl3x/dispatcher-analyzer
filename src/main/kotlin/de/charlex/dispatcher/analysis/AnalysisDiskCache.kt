package de.charlex.dispatcher.analysis

import de.charlex.dispatcher.model.Dispatcher
import de.charlex.dispatcher.model.DispatcherOrigin
import de.charlex.dispatcher.model.DispatcherSet
import de.charlex.dispatcher.model.EffectSummary
import de.charlex.dispatcher.model.PathRelation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.security.DigestInputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

internal class AnalysisDiskCache(projectDirectory: Path) {
    val path: Path = projectDirectory.resolve("build/dispatcher-analyzer/analysis-cache.bin")

    suspend fun load(environmentFingerprint: String): CachedProjectAnalysis? = withContext(Dispatchers.IO) {
        val context = currentCoroutineContext()
        context.ensureActive()
        if (!Files.isRegularFile(path)) return@withContext null
        val fileSize = try {
            Files.size(path)
        } catch (_: IOException) {
            return@withContext null
        }
        if (fileSize < DIGEST_LENGTH) return@withContext null
        try {
            Files.newInputStream(path).use { raw ->
                val buffered = BufferedInputStream(raw)
                val payloadSize = fileSize - DIGEST_LENGTH
                val limited = LimitedInputStream(buffered, payloadSize)
                val counter = CountingInputStream(limited)
                val digest = MessageDigest.getInstance("SHA-256")
                val input = DataInputStream(DigestInputStream(counter, digest))
                val cache = CacheReader(input, counter, payloadSize) { context.ensureActive() }
                    .read(environmentFingerprint) ?: return@use null
                context.ensureActive()
                val storedDigest = ByteArray(DIGEST_LENGTH)
                DataInputStream(buffered).readFully(storedDigest)
                require(buffered.read() == -1) { "Trailing cache bytes" }
                if (!MessageDigest.isEqual(storedDigest, digest.digest())) null else cache
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }

    suspend fun save(value: CachedProjectAnalysis) = withContext(Dispatchers.IO) {
        val context = currentCoroutineContext()
        context.ensureActive()
        Files.createDirectories(path.parent)
        val temporary = Files.createTempFile(path.parent, "analysis-cache-", ".tmp")
        try {
            Files.newOutputStream(temporary).use { raw ->
                val buffered = BufferedOutputStream(raw)
                val digest = MessageDigest.getInstance("SHA-256")
                val digestOutput = DigestOutputStream(buffered, digest)
                val output = DataOutputStream(digestOutput)
                CacheWriter(output) { context.ensureActive() }.write(value)
                output.flush()
                digestOutput.on(false)
                buffered.write(digest.digest())
                buffered.flush()
            }
            context.ensureActive()
            try {
                Files.move(temporary, path, ATOMIC_MOVE, REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, path, REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private class CacheWriter(
        private val output: DataOutputStream,
        private val checkCancellation: () -> Unit,
    ) {
        fun write(value: CachedProjectAnalysis) {
            output.writeInt(MAGIC)
            output.writeInt(VERSION)
            writeString(value.environmentFingerprint)
            writeMap(value.files, compareBy { it }) { key, graph ->
                writeString(key)
                writeFileGraph(graph)
            }
            writeMap(value.summaries, compareBy<FunctionKey>({ it.file }, { it.offset })) { key, summary ->
                writeFunctionKey(key)
                writeSummary(summary)
            }
            writeMap(value.incoming, compareBy<FunctionKey>({ it.file }, { it.offset })) { key, dispatchers ->
                writeFunctionKey(key)
                writeDispatcherSet(dispatchers)
            }
            writeMap(value.results, compareBy { it }) { key, result ->
                writeString(key)
                writeFileAnalysis(result)
            }
            output.flush()
        }

        private fun writeFileGraph(graph: FileGraph) {
            writeString(graph.fileUrl)
            writeString(graph.contentHash)
            writeString(graph.structureHash)
            writeStrings(graph.dependencies)
            writeList(graph.functions) { writeFunctionBody(it) }
            writeList(graph.calls) { writeSourceCall(it) }
            writeStrings(graph.unresolvedNames)
            writeSorted(graph.escapingTargets, compareBy<FunctionKey>({ it.file }, { it.offset })) { writeFunctionKey(it) }
        }

        private fun writeFunctionBody(body: FunctionBody) {
            writeFunctionKey(body.key)
            writeNullableString(body.name)
            writeEffect(body.effect)
        }

        private fun writeSourceCall(call: SourceCall) {
            writeString(call.file)
            output.writeInt(call.offset)
            writeNullableFunctionKey(call.owner)
            writeNullableFunctionKey(call.target)
            writeDispatcherSet(call.context)
            writeEffect(call.effect)
        }

        private fun writeEffect(effect: Effect) {
            val pending = ArrayDeque<Effect>()
            pending.addLast(effect)
            while (pending.isNotEmpty()) {
                checkCancellation()
                when (val node = pending.removeLast()) {
                    is Effect.Work -> {
                        output.writeByte(EFFECT_WORK)
                        writeSummary(node.summary)
                    }
                    is Effect.Invoke -> {
                        output.writeByte(EFFECT_INVOKE)
                        writeFunctionKey(node.target)
                        writeDispatcherSet(node.context)
                    }
                    is Effect.ContextSelection -> {
                        output.writeByte(EFFECT_CONTEXT_SELECTION)
                        writeDispatcherSet(node.selectedDispatchers)
                        pending.addLast(node.effect)
                    }
                    is Effect.Group -> {
                        output.writeByte(EFFECT_GROUP)
                        output.writeBoolean(node.branch)
                        output.writeInt(node.effects.size)
                        node.effects.asReversed().forEach(pending::addLast)
                    }
                }
            }
        }

        private fun writeSummary(summary: EffectSummary) {
            writeDispatcherSet(summary.dispatchers)
            writeSorted(summary.pathRelations, compareBy { it.ordinal }) { output.writeByte(it.ordinal) }
            writeDispatcherSet(summary.selectedDispatchers)
        }

        private fun writeDispatcherSet(dispatcherSet: DispatcherSet) {
            writeSorted(dispatcherSet.known, dispatcherComparator) { writeDispatcher(it) }
            writeStrings(dispatcherSet.unknownReasons)
            writeMap(dispatcherSet.origins, dispatcherComparator) { dispatcher, origins ->
                writeDispatcher(dispatcher)
                writeSorted(origins, compareBy<DispatcherOrigin>({ it.fileUrl }, { it.offset }, { it.line }, { it.description })) {
                    writeString(it.fileUrl)
                    output.writeInt(it.offset)
                    output.writeInt(it.line)
                    writeString(it.description)
                }
            }
        }

        private fun writeDispatcher(dispatcher: Dispatcher) {
            output.writeByte(dispatcherTag(dispatcher))
            if (dispatcher is Dispatcher.Custom) {
                writeString(dispatcher.identity)
                writeString(dispatcher.label)
            }
        }

        private fun writeFileAnalysis(result: FileAnalysis) {
            writeMap(result.declarations, compareBy { it }) { offset, badge ->
                output.writeInt(offset)
                writeBadgeResult(badge)
            }
            writeMap(result.calls, compareBy { it }) { offset, badge ->
                output.writeInt(offset)
                writeBadgeResult(badge)
            }
        }

        private fun writeBadgeResult(result: BadgeResult) {
            writeSummary(result.summary)
            writeString(result.tooltip)
        }

        private fun writeFunctionKey(key: FunctionKey) {
            writeString(key.file)
            output.writeInt(key.offset)
        }

        private fun writeNullableFunctionKey(key: FunctionKey?) {
            output.writeBoolean(key != null)
            if (key != null) writeFunctionKey(key)
        }

        private fun writeNullableString(value: String?) {
            output.writeBoolean(value != null)
            if (value != null) writeString(value)
        }

        private fun writeStrings(values: Set<String>) = writeSorted(values, compareBy { it }) { writeString(it) }

        private fun <T> writeList(values: List<T>, write: (T) -> Unit) {
            output.writeInt(values.size)
            values.forEach { checkCancellation(); write(it) }
        }

        private fun <T> writeSorted(values: Collection<T>, comparator: Comparator<T>, write: (T) -> Unit) {
            output.writeInt(values.size)
            values.sortedWith(comparator).forEach { checkCancellation(); write(it) }
        }

        private fun <K, V> writeMap(values: Map<K, V>, comparator: Comparator<K>, write: (K, V) -> Unit) {
            output.writeInt(values.size)
            values.entries.sortedWith { first, second -> comparator.compare(first.key, second.key) }
                .forEach { checkCancellation(); write(it.key, it.value) }
        }

        private fun writeString(value: String) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            output.writeInt(bytes.size)
            var offset = 0
            while (offset < bytes.size) {
                checkCancellation()
                val length = minOf(IO_CHUNK_SIZE, bytes.size - offset)
                output.write(bytes, offset, length)
                offset += length
            }
        }
    }

    private class CacheReader(
        private val input: DataInputStream,
        private val counter: CountingInputStream,
        private val fileSize: Long,
        private val checkCancellation: () -> Unit,
    ) {
        fun read(expectedEnvironmentFingerprint: String): CachedProjectAnalysis? {
            require(input.readInt() == MAGIC) { "Invalid dispatcher analysis cache" }
            require(input.readInt() == VERSION) { "Unsupported dispatcher analysis cache version" }
            val fingerprint = readString()
            if (fingerprint != expectedEnvironmentFingerprint) return null
            val files = readMap { readString() to readFileGraph() }
            val summaries = readMap { readFunctionKey() to readSummary() }
            val incoming = readMap { readFunctionKey() to readDispatcherSet() }
            val results = readMap { readString() to readFileAnalysis() }
            require(counter.bytesRead == fileSize) { "Trailing or unread cache bytes" }
            return CachedProjectAnalysis(fingerprint, files, summaries, incoming, results)
        }

        private fun readFileGraph(): FileGraph = FileGraph(
            fileUrl = readString(),
            contentHash = readString(),
            structureHash = readString(),
            dependencies = readStringSet(),
            functions = readList { readFunctionBody() },
            calls = readList { readSourceCall() },
            unresolvedNames = readStringSet(),
            escapingTargets = readSet { readFunctionKey() },
        )

        private fun readFunctionBody() = FunctionBody(
            key = readFunctionKey(),
            name = readNullableString(),
            effect = readEffect(),
        )

        private fun readSourceCall() = SourceCall(
            file = readString(),
            offset = readNonNegativeInt(),
            owner = readNullableFunctionKey(),
            target = readNullableFunctionKey(),
            context = readDispatcherSet(),
            effect = readEffect(),
        )

        private fun readEffect(): Effect {
            val frames = ArrayDeque<EffectFrame>()
            var completed: Effect? = null
            while (true) {
                checkCancellation()
                if (completed == null) {
                    when (input.readUnsignedByte()) {
                        EFFECT_WORK -> completed = Effect.Work(readSummary())
                        EFFECT_INVOKE -> completed = Effect.Invoke(readFunctionKey(), readDispatcherSet())
                        EFFECT_CONTEXT_SELECTION -> frames.addLast(EffectFrame.Selection(readDispatcherSet()))
                        EFFECT_GROUP -> {
                            val branch = input.readBoolean()
                            val childCount = readCount(minimumBytesPerItem = 1)
                            if (childCount == 0) completed = Effect.Group(emptyList(), branch)
                            else frames.addLast(EffectFrame.Group(branch, childCount))
                        }
                        else -> throw IOException("Invalid effect tag")
                    }
                }
                while (completed != null) {
                    val frame = frames.lastOrNull() ?: return completed
                    when (frame) {
                        is EffectFrame.Selection -> {
                            frames.removeLast()
                            completed = Effect.ContextSelection(completed, frame.selectedDispatchers)
                        }
                        is EffectFrame.Group -> {
                            frame.children += completed
                            frame.remaining--
                            if (frame.remaining == 0) {
                                frames.removeLast()
                                completed = Effect.Group(frame.children, frame.branch)
                            } else {
                                completed = null
                            }
                        }
                    }
                }
            }
        }

        private fun readSummary(): EffectSummary {
            val dispatchers = readDispatcherSet()
            val relations = readSet {
                when (val ordinal = input.readUnsignedByte()) {
                    PathRelation.BRANCH_ALTERNATIVES.ordinal -> PathRelation.BRANCH_ALTERNATIVES
                    PathRelation.CONTEXT_SWITCH.ordinal -> PathRelation.CONTEXT_SWITCH
                    else -> throw IOException("Invalid path relation tag: $ordinal")
                }
            }
            return EffectSummary(dispatchers, relations, readDispatcherSet())
        }

        private fun readDispatcherSet(): DispatcherSet {
            val known = readSet { readDispatcher() }
            val unknownReasons = readStringSet()
            val origins = readMap { readDispatcher() to readSet {
                DispatcherOrigin(readString(), readNonNegativeInt(), readPositiveInt(), readString())
            } }
            return DispatcherSet(known, unknownReasons, origins).also {
                require(it.origins == origins) { "Invalid dispatcher origins" }
            }
        }

        private fun readDispatcher(): Dispatcher = when (input.readUnsignedByte()) {
            TAG_MAIN -> Dispatcher.Main
            TAG_IO -> Dispatcher.IO
            TAG_DEFAULT -> Dispatcher.Default
            TAG_UNCONFINED -> Dispatcher.Unconfined
            TAG_CUSTOM -> Dispatcher.Custom(readString(), readString())
            TAG_INHERITED -> Dispatcher.Inherited
            else -> throw IOException("Invalid dispatcher tag")
        }

        private fun readFileAnalysis() = FileAnalysis(
            declarations = readMap { readNonNegativeInt() to readBadgeResult() },
            calls = readMap { readNonNegativeInt() to readBadgeResult() },
        )

        private fun readBadgeResult() = BadgeResult(readSummary(), readString())

        private fun readFunctionKey() = FunctionKey(readString(), readNonNegativeInt())

        private fun readNullableFunctionKey(): FunctionKey? = if (input.readBoolean()) readFunctionKey() else null

        private fun readNullableString(): String? = if (input.readBoolean()) readString() else null

        private fun readStringSet() = readSet { readString() }

        private fun <T> readList(read: () -> T): List<T> {
            val count = readCount(minimumBytesPerItem = 1)
            val result = ArrayList<T>()
            repeat(count) { checkCancellation(); result += read() }
            return result
        }

        private fun <T> readSet(read: () -> T): Set<T> {
            val count = readCount(minimumBytesPerItem = 1)
            val result = LinkedHashSet<T>()
            repeat(count) { checkCancellation(); result += read() }
            return result
        }

        private fun <K, V> readMap(read: () -> Pair<K, V>): Map<K, V> {
            val count = readCount(minimumBytesPerItem = 2)
            val result = LinkedHashMap<K, V>()
            repeat(count) {
                checkCancellation()
                val (key, value) = read()
                require(key !in result) { "Duplicate cache key" }
                result[key] = value
            }
            return result
        }

        private fun readString(): String {
            val length = readCount(minimumBytesPerItem = 1)
            val bytes = ByteArray(length)
            var offset = 0
            while (offset < length) {
                checkCancellation()
                val chunk = minOf(IO_CHUNK_SIZE, length - offset)
                input.readFully(bytes, offset, chunk)
                offset += chunk
            }
            return Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        }

        private fun readNonNegativeInt(): Int = input.readInt().also { require(it >= 0) { "Negative cache offset" } }

        private fun readPositiveInt(): Int = input.readInt().also { require(it >= 1) { "Invalid cache line" } }

        private fun readCount(minimumBytesPerItem: Int): Int {
            val count = input.readInt()
            require(count >= 0) { "Negative cache record length" }
            require(count.toLong() * minimumBytesPerItem <= remainingBytes()) { "Cache record exceeds file size" }
            return count
        }

        private fun remainingBytes(): Long = fileSize - counter.bytesRead
    }

    private sealed interface EffectFrame {
        data class Selection(val selectedDispatchers: DispatcherSet) : EffectFrame
        class Group(val branch: Boolean, var remaining: Int, val children: MutableList<Effect> = mutableListOf()) : EffectFrame
    }

    private class CountingInputStream(input: InputStream) : FilterInputStream(input) {
        var bytesRead = 0L
            private set

        override fun read(): Int = super.read().also { if (it >= 0) bytesRead++ }

        override fun read(bytes: ByteArray, offset: Int, length: Int): Int = super.read(bytes, offset, length).also {
            if (it > 0) bytesRead += it
        }
    }

    private class LimitedInputStream(input: InputStream, private var remaining: Long) : FilterInputStream(input) {
        override fun read(): Int {
            if (remaining == 0L) return -1
            return super.read().also { if (it >= 0) remaining-- }
        }

        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
            if (remaining == 0L) return -1
            val allowed = minOf(length.toLong(), remaining).toInt()
            return super.read(bytes, offset, allowed).also { if (it > 0) remaining -= it }
        }
    }

    companion object {
        private const val MAGIC = 0x44414E41
        private const val VERSION = 5
        private const val IO_CHUNK_SIZE = 16 * 1024
        private const val DIGEST_LENGTH = 32

        private const val EFFECT_WORK = 1
        private const val EFFECT_CONTEXT_SELECTION = 2
        private const val EFFECT_INVOKE = 3
        private const val EFFECT_GROUP = 4

        private const val TAG_MAIN = 0
        private const val TAG_IO = 1
        private const val TAG_DEFAULT = 2
        private const val TAG_UNCONFINED = 3
        private const val TAG_CUSTOM = 4
        private const val TAG_INHERITED = 5

        private fun dispatcherTag(dispatcher: Dispatcher): Int = when (dispatcher) {
            Dispatcher.Main -> TAG_MAIN
            Dispatcher.IO -> TAG_IO
            Dispatcher.Default -> TAG_DEFAULT
            Dispatcher.Unconfined -> TAG_UNCONFINED
            is Dispatcher.Custom -> TAG_CUSTOM
            Dispatcher.Inherited -> TAG_INHERITED
        }

        private val dispatcherComparator = compareBy<Dispatcher>(
            { dispatcherTag(it) },
            { (it as? Dispatcher.Custom)?.identity.orEmpty() },
            { (it as? Dispatcher.Custom)?.label.orEmpty() },
        )
    }
}
