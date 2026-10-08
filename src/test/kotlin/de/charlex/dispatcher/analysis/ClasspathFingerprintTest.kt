package de.charlex.dispatcher.analysis

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Test
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ClasspathFingerprintTest {
    @Test
    fun archiveContentChangeIsDetectedWhenSizeAndTimestampMatch() = runBlocking {
        val root = Files.createTempDirectory("classpath-fingerprint-archive")
        try {
            val archive = root.resolve("library.jar")
            val timestamp = FileTime.fromMillis(1_700_000_000_000)
            writeArchive(archive, "class-data-A")
            Files.setLastModifiedTime(archive, timestamp)
            val before = ClasspathFingerprint.calculate(listOf(archive.toString()))
            val originalSize = Files.size(archive)

            writeArchive(archive, "class-data-B")
            Files.setLastModifiedTime(archive, timestamp)

            assertEquals(originalSize, Files.size(archive))
            assertEquals(timestamp, Files.getLastModifiedTime(archive))
            assertNotEquals(before, ClasspathFingerprint.calculate(listOf(archive.toString())))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun nestedDirectoryContentAndRootOrderingAreIncluded() = runBlocking {
        val root = Files.createTempDirectory("classpath-fingerprint-directory")
        try {
            val first = root.resolve("first")
            val second = root.resolve("second")
            Files.createDirectories(first.resolve("nested"))
            Files.createDirectories(second)
            val nestedFile = first.resolve("nested/Library.class")
            Files.write(nestedFile, byteArrayOf(1, 2, 3))
            Files.write(second.resolve("Library.class"), byteArrayOf(4, 5, 6))

            val before = ClasspathFingerprint.calculate(listOf(first.toString(), second.toString()))
            assertNotEquals(before, ClasspathFingerprint.calculate(listOf(second.toString(), first.toString())))

            Files.write(nestedFile, byteArrayOf(1, 2, 4))
            assertNotEquals(before, ClasspathFingerprint.calculate(listOf(first.toString(), second.toString())))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun missingOrCyclicRootsAreNotFingerprintable() = runBlocking {
        val root = Files.createTempDirectory("classpath-fingerprint-invalid")
        try {
            assertNull(ClasspathFingerprint.calculate(listOf(root.resolve("missing").toString())))
            val loop = root.resolve("loop")
            try {
                Files.createSymbolicLink(loop, loop)
                assertNull(ClasspathFingerprint.calculate(listOf(loop.toString())))
            } catch (_: UnsupportedOperationException) {
                // Symlinks are unavailable on this filesystem.
            } catch (_: java.nio.file.FileSystemException) {
                // Symlink creation may be restricted by the host.
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun canceledFingerprintDoesNotReturnAValue() = runBlocking {
        val root = Files.createTempDirectory("classpath-fingerprint-cancel")
        try {
            Files.write(root.resolve("Library.class"), ByteArray(1024 * 1024))
            val canceledJob = Job().apply { cancel() }
            try {
                withContext(canceledJob) {
                    ClasspathFingerprint.calculate(listOf(root.toString()))
                }
                throw AssertionError("Expected cancellation")
            } catch (_: CancellationException) {
                assertTrue(canceledJob.isCancelled)
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun symlinkRootsAndDescendantsRegisterExternalTargetsBeforeHashing() = runBlocking {
        val root = Files.createTempDirectory("classpath-fingerprint-links").toRealPath()
        try {
            val classes = Files.createDirectories(root.resolve("classes"))
            val external = Files.createDirectories(root.resolve("external"))
            val target = Files.write(external.resolve("Library.class"), byteArrayOf(1, 2, 3))
            val intermediate = external.resolve("linked.class")
            val alias = root.resolve("alias")
            try {
                Files.createSymbolicLink(intermediate, target)
                Files.createSymbolicLink(classes.resolve("Library.class"), intermediate)
                Files.createSymbolicLink(alias, classes)
            } catch (unavailable: UnsupportedOperationException) {
                assumeNoException(unavailable)
                return@runBlocking
            } catch (unavailable: java.nio.file.FileSystemException) {
                assumeNoException(unavailable)
                return@runBlocking
            }
            val observed = linkedSetOf<String>()
            val before = ClasspathFingerprint.inspect(listOf(alias.toString())) { path, _ -> observed += path }
            assertEquals(before.watchedPaths + before.watchedLinks, observed)
            assertTrue(observed.containsAll(listOf(alias, classes, intermediate, target).map { it.toString() }))

            Files.write(target, byteArrayOf(1, 2, 4))
            assertNotEquals(before.digest, ClasspathFingerprint.calculate(listOf(alias.toString())))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun classpathBelowLinkedParentWatchesIntermediateDirectoryTargets() = runBlocking {
        val root = Files.createTempDirectory("classpath-fingerprint-parent-link").toRealPath()
        try {
            val external = Files.createDirectories(root.resolve("external"))
            val classes = Files.createDirectories(external.resolve("classes"))
            Files.write(classes.resolve("Library.class"), byteArrayOf(1))
            val intermediate = root.resolve("intermediate")
            val alias = root.resolve("alias")
            try {
                Files.createSymbolicLink(intermediate, external)
                Files.createSymbolicLink(alias, intermediate)
            } catch (unavailable: UnsupportedOperationException) {
                assumeNoException(unavailable)
                return@runBlocking
            } catch (unavailable: java.nio.file.FileSystemException) {
                assumeNoException(unavailable)
                return@runBlocking
            }
            val inspected = ClasspathFingerprint.inspect(listOf(alias.resolve("classes").toString()))
            assertTrue(inspected.watchedLinks.containsAll(listOf(alias, intermediate).map { it.toString() }))
            assertTrue(classes.toString() in inspected.watchedPaths)
            assertTrue("A parent alias must not watch unrelated sibling files", external.toString() !in inspected.watchedPaths)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun writeArchive(path: java.nio.file.Path, content: String) {
        ZipOutputStream(Files.newOutputStream(path)).use { zip ->
            zip.putNextEntry(ZipEntry("META-INF/library.kotlin_module").apply { time = 1_700_000_000_000 })
            zip.write(content.toByteArray())
            zip.closeEntry()
        }
    }
}
