package de.charlex.dispatcher.analysis

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

internal object ClasspathFingerprint {
    data class Result(val digest: String?, val watchedPaths: Set<String>, val watchedLinks: Set<String>)

    suspend fun calculate(physicalRootPaths: List<String>): String? = inspect(physicalRootPaths).digest

    suspend fun inspect(physicalRootPaths: List<String>, onWatchPath: (String, Boolean) -> Unit = { _, _ -> }): Result = withContext(Dispatchers.IO) {
        val context = currentCoroutineContext()
        val watched = linkedSetOf<String>()
        val links = linkedSetOf<String>()
        fun watch(path: Path, subtree: Boolean = true) {
            context.ensureActive()
            val value = path.toAbsolutePath().normalize().toString().replace('\\', '/')
            if ((if (subtree) watched else links).add(value)) onWatchPath(value, subtree)
        }
        val resolvingLinks = HashSet<Path>()
        fun watchResolved(path: Path) {
            watch(path)
            var prefix = path.root
            for (part in path) {
                context.ensureActive()
                prefix = prefix.resolve(part)
                if (Files.isSymbolicLink(prefix)) {
                    watch(prefix, false)
                    if (!resolvingLinks.add(prefix)) throw IllegalStateException("Classpath symlink cycle")
                    try {
                        val target = prefix.parent.resolve(Files.readSymbolicLink(prefix)).normalize()
                        watchResolved(target.resolve(prefix.relativize(path)))
                    } finally {
                        resolvingLinks.remove(prefix)
                    }
                    return
                }
            }
            watch(path.toRealPath())
        }
        val value = try {
            val digest = MessageDigest.getInstance("SHA-256")
            digest.putString("dispatcher-classpath-v2")
            digest.putInt(physicalRootPaths.size)
            val activeDirectories = HashSet<Path>()
            val activeLinks = HashSet<Path>()
            physicalRootPaths.forEachIndexed { index, rootValue ->
                context.ensureActive()
                digest.putInt(index)
                val root = Path.of(rootValue).toAbsolutePath().normalize()
                watchResolved(root)
                digest.putString(root.toString())
                hashNode(root, "", digest, activeDirectories, activeLinks, ::watchResolved) { context.ensureActive() }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            context.ensureActive()
            null
        }
        Result(value, java.util.Collections.unmodifiableSet(watched), java.util.Collections.unmodifiableSet(links))
    }

    private fun hashNode(
        path: Path,
        relativePath: String,
        digest: MessageDigest,
        activeDirectories: MutableSet<Path>,
        activeLinks: MutableSet<Path>,
        watch: (Path) -> Unit,
        checkCancellation: () -> Unit,
    ) {
        checkCancellation()
        digest.putString(relativePath)
        val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        when {
            attributes.isSymbolicLink -> {
                if (!activeLinks.add(path)) throw IllegalStateException("Classpath symlink cycle")
                try {
                    digest.update(TYPE_SYMLINK)
                    val target = path.parent.resolve(Files.readSymbolicLink(path)).normalize()
                    watch(target)
                    digest.putString(target.toString())
                    hashNode(target, relativePath, digest, activeDirectories, activeLinks, watch, checkCancellation)
                } finally {
                    activeLinks.remove(path)
                }
            }
            attributes.isDirectory -> {
                val realPath = path.toRealPath()
                if (!activeDirectories.add(realPath)) throw IllegalStateException("Classpath symlink cycle")
                try {
                    digest.update(TYPE_DIRECTORY)
                    val children = Files.newDirectoryStream(path).use { entries ->
                        entries.toList().sortedBy { it.fileName.toString() }
                    }
                    digest.putInt(children.size)
                    for (child in children) {
                        checkCancellation()
                        val name = child.fileName.toString()
                        hashNode(child, if (relativePath.isEmpty()) name else "$relativePath/$name", digest,
                            activeDirectories, activeLinks, watch, checkCancellation)
                    }
                } finally {
                    activeDirectories.remove(realPath)
                }
            }
            attributes.isRegularFile -> {
                digest.update(TYPE_FILE)
                digest.putLong(attributes.size())
                Files.newInputStream(path).use { input -> input.hashInto(digest, checkCancellation) }
            }
            else -> throw IllegalStateException("Unsupported classpath entry")
        }
    }

    private fun InputStream.hashInto(digest: MessageDigest, checkCancellation: () -> Unit) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            checkCancellation()
            val count = read(buffer)
            if (count < 0) return
            if (count > 0) digest.update(buffer, 0, count)
        }
    }

    private fun MessageDigest.putString(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        putInt(bytes.size)
        update(bytes)
    }

    private fun MessageDigest.putInt(value: Int) {
        update((value ushr 24).toByte())
        update((value ushr 16).toByte())
        update((value ushr 8).toByte())
        update(value.toByte())
    }

    private fun MessageDigest.putLong(value: Long) {
        for (shift in 56 downTo 0 step 8) update((value ushr shift).toByte())
    }

    private val TYPE_DIRECTORY = byteArrayOf(1)
    private val TYPE_FILE = byteArrayOf(2)
    private val TYPE_SYMLINK = byteArrayOf(3)
}
