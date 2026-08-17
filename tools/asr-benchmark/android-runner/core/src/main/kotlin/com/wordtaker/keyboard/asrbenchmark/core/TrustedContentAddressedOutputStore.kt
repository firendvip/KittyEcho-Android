package com.wordtaker.keyboard.asrbenchmark.core

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID

data class StoredContentAddressedJson(
    val fileName: String,
    val sha256: String,
)

/**
 * Publishes immutable JSON below a fixed app-private root.
 *
 * The caller supplies the trusted Android Context root, never an arbitrary
 * output path. Both runner-owned child components are validated without
 * following links. Android's public Java API does not guarantee openat or
 * renameat, so the store holds the validated run-directory FD and revalidates
 * every fixed path component before and after each path-based leaf operation.
 * This is engineering hardening, not same-UID adversarial attestation.
 */
class TrustedContentAddressedOutputStore private constructor(
    private val trustedRootBinding: DirectoryBinding,
    private val storeRootBinding: DirectoryBinding,
    private val runDirectoryBinding: DirectoryBinding,
    private val heldDirectoryChannel: FileChannel,
    private val publishedFilePermissions: Set<PosixFilePermission>,
    private val publicationCheckpoint: ((Path) -> Unit)?,
) : AutoCloseable {
    val runDirectory: Path
        get() = runDirectoryBinding.path

    private val publishedBindings =
        mutableMapOf<String, PublishedFileBinding>()
    private var closed = false

    @Synchronized
    fun publish(
        kind: String,
        document: Map<String, Any?>,
    ): StoredContentAddressedJson {
        ensureOpen()
        if (!ARTIFACT_KIND.matches(kind)) {
            throw BenchmarkContractException(
                "content-addressed output kind is invalid",
            )
        }
        val encoded = (
            CanonicalJson.encode(document) + "\n"
            ).encodeToByteArray()
        val digest = Hashing.sha256(encoded)
        val fileName = "$kind-$digest.json"
        val target = relativeLeaf(fileName)
        verifyLayout()

        val existing = measureIfPresent(target, digest, encoded)
        if (existing != null) {
            bindPublished(fileName, existing)
            verifyLayout()
            verifyPublished(fileName, digest)
            return StoredContentAddressedJson(fileName, digest)
        }

        val temporary = relativeLeaf(
            ".$kind-${UUID.randomUUID()}.tmp",
        )
        writePrivateTemporary(temporary, encoded)
        measure(temporary, digest, encoded)
        moveRelative(temporary, target)
        val binding = measure(target, digest, encoded)
        bindPublished(fileName, binding)
        publicationCheckpoint?.invoke(runDirectory)
        verifyLayout()
        verifyPublished(fileName, digest)
        return StoredContentAddressedJson(fileName, digest)
    }

    @Synchronized
    fun verifyPublished(
        fileName: String,
        expectedSha256: String,
    ): Boolean {
        ensureOpen()
        Hashing.requireSha256(expectedSha256, "published output sha256")
        val relativeName = relativeLeaf(fileName)
        val expected = publishedBindings[fileName]
            ?: throw BenchmarkContractException(
                "published output lacks its held-directory identity binding",
            )
        verifyLayout()
        val measured = measure(
            relativeName,
            expectedSha256,
            expected.bytes,
        )
        if (!expected.sameIdentityAndContent(measured)) {
            throw BenchmarkContractException(
                "published output inode or content was replaced",
            )
        }
        verifyLayout()
        return true
    }

    @Synchronized
    override fun close() {
        if (!closed) {
            closed = true
            heldDirectoryChannel.close()
        }
    }

    private fun bindPublished(
        fileName: String,
        binding: PublishedFileBinding,
    ) {
        val prior = publishedBindings.putIfAbsent(fileName, binding)
        if (
            prior != null &&
            !prior.sameIdentityAndContent(binding)
        ) {
            throw BenchmarkContractException(
                "content-addressed output identity changed within one store",
            )
        }
    }

    private fun writePrivateTemporary(
        name: Path,
        encoded: ByteArray,
    ) {
        val options = linkedSetOf<OpenOption>(
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE,
            LinkOption.NOFOLLOW_LINKS,
        )
        val permissions = PosixFilePermissions.asFileAttribute(
            setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
            ),
        )
        val channel = try {
            openFileChannel(name, options, permissions)
        } catch (error: Exception) {
            throw BenchmarkContractException(
                "private content-addressed temp cannot be created safely: " +
                    error.javaClass.simpleName,
            )
        }
        channel.use { fileChannel ->
            val bytes = ByteBuffer.wrap(encoded)
            while (bytes.hasRemaining()) {
                if (fileChannel.write(bytes) <= 0) {
                    throw BenchmarkContractException(
                        "secure output channel made no write progress",
                    )
                }
            }
            fileChannel.force(true)
        }
        setLeafPermissions(name, publishedFilePermissions)
    }

    private fun measureIfPresent(
        name: Path,
        expectedSha256: String,
        expectedBytes: ByteArray,
    ): PublishedFileBinding? = try {
        measure(name, expectedSha256, expectedBytes)
    } catch (_: NoSuchFileException) {
        null
    }

    private fun measure(
        name: Path,
        expectedSha256: String,
        expectedBytes: ByteArray,
    ): PublishedFileBinding {
        val before = readLeafAttributes(name)
        val options = linkedSetOf<OpenOption>(
            StandardOpenOption.READ,
            LinkOption.NOFOLLOW_LINKS,
        )
        val bytes = try {
            openFileChannel(name, options).use { fileChannel ->
                if (fileChannel.size() > MAX_JSON_BYTES) {
                    throw BenchmarkContractException(
                        "content-addressed output exceeds the local limit",
                    )
                }
                val result = ByteArray(fileChannel.size().toInt())
                val buffer = ByteBuffer.wrap(result)
                while (buffer.hasRemaining()) {
                    val count = fileChannel.read(buffer)
                    if (count < 0) {
                        throw BenchmarkContractException(
                            "content-addressed output is truncated",
                        )
                    }
                    if (count == 0) {
                        throw BenchmarkContractException(
                            "content-addressed output made no read progress",
                        )
                    }
                }
                result
            }
        } catch (error: BenchmarkContractException) {
            throw error
        } catch (error: Exception) {
            throw BenchmarkContractException(
                "content-addressed output cannot be opened safely: " +
                    error.javaClass.simpleName,
            )
        }
        val after = readLeafAttributes(name)
        if (
            before != after ||
            !bytes.contentEquals(expectedBytes) ||
            Hashing.sha256(bytes) != expectedSha256
        ) {
            throw BenchmarkContractException(
                "content-addressed output identity or digest differs",
            )
        }
        return PublishedFileBinding(
            fileKey = before.fileKey,
            sizeBytes = before.sizeBytes,
            sha256 = expectedSha256,
            bytes = bytes,
        )
    }

    private fun readLeafAttributes(name: Path): LeafIdentity {
        val attributes = readBasicAttributes(name)
        if (
            !attributes.isRegularFile ||
            attributes.isSymbolicLink ||
            attributes.size() <= 0L ||
            attributes.size() > MAX_JSON_BYTES
        ) {
            throw BenchmarkContractException(
                "content-addressed output must be one bounded regular file",
            )
        }
        val fileKey = attributes.fileKey()
            ?: throw BenchmarkContractException(
                "content-addressed output inode identity is unavailable",
            )
        if (readLeafPermissions(name) != publishedFilePermissions) {
            throw BenchmarkContractException(
                "content-addressed output permissions differ from policy",
            )
        }
        return LeafIdentity(
            fileKey = fileKey,
            sizeBytes = attributes.size(),
        )
    }

    private fun verifyLayout() {
        if (!heldDirectoryChannel.isOpen) {
            throw BenchmarkContractException(
                "trusted output directory FD is no longer held",
            )
        }
        trustedRootBinding.verify()
        storeRootBinding.verify()
        runDirectoryBinding.verify()
    }

    private fun openFileChannel(
        name: Path,
        options: Set<OpenOption>,
        vararg attributes: java.nio.file.attribute.FileAttribute<*>,
    ): FileChannel {
        verifyLayout()
        val channel = FileChannel.open(
            runDirectory.resolve(name.toString()),
            options,
            *attributes,
        )
        return try {
            verifyLayout()
            channel
        } catch (error: Exception) {
            channel.close()
            throw error
        }
    }

    private fun moveRelative(source: Path, target: Path) {
        verifyLayout()
        Files.move(
            runDirectory.resolve(source.toString()),
            runDirectory.resolve(target.toString()),
            StandardCopyOption.ATOMIC_MOVE,
        )
        verifyLayout()
    }

    private fun readBasicAttributes(name: Path): BasicFileAttributes {
        verifyLayout()
        val attributes = Files.readAttributes(
            runDirectory.resolve(name.toString()),
            BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS,
        )
        verifyLayout()
        return attributes
    }

    private fun readLeafPermissions(
        name: Path,
    ): Set<PosixFilePermission> {
        verifyLayout()
        val permissions = Files.getPosixFilePermissions(
            runDirectory.resolve(name.toString()),
            LinkOption.NOFOLLOW_LINKS,
        )
        verifyLayout()
        return permissions
    }

    private fun setLeafPermissions(
        name: Path,
        permissions: Set<PosixFilePermission>,
    ) {
        verifyLayout()
        Files.setPosixFilePermissions(
            runDirectory.resolve(name.toString()),
            permissions,
        )
        verifyLayout()
    }

    private fun relativeLeaf(fileName: String): Path {
        if (
            fileName.isBlank() ||
            fileName.contains('/') ||
            fileName.contains('\\') ||
            fileName == "." ||
            fileName == ".."
        ) {
            throw BenchmarkContractException(
                "content-addressed output filename is not one relative leaf",
            )
        }
        return runDirectory.fileSystem.getPath(fileName)
    }

    private fun ensureOpen() {
        if (closed) {
            throw BenchmarkContractException(
                "content-addressed output store is closed",
            )
        }
    }

    companion object {
        const val ROOT_NAME = "kittyecho-asr-benchmark-output-v1"

        private val RUN_ID = Regex("^run_[0-9a-f]{12}$")
        private val ARTIFACT_KIND = Regex("^[a-z][a-z0-9-]{1,63}$")
        private val DIRECTORY_PERMISSIONS = setOf(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE,
        )
        private val READ_ONLY_FILE_PERMISSIONS = setOf(
            PosixFilePermission.OWNER_READ,
        )
        private val READ_WRITE_FILE_PERMISSIONS = setOf(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
        )
        private const val MAX_JSON_BYTES = 64L * 1024L * 1024L

        fun open(
            trustedAppPrivateRoot: Path,
            runId: String,
        ): TrustedContentAddressedOutputStore = openInternal(
            trustedAppPrivateRoot,
            runId,
            publishedFilePermissions = READ_ONLY_FILE_PERMISSIONS,
            publicationCheckpoint = null,
        )

        /**
         * Development-only output mode. Formal callers continue to use
         * [open], whose immutable publications remain owner-read-only.
         */
        fun openDevelopment(
            trustedAppPrivateRoot: Path,
            runId: String,
        ): TrustedContentAddressedOutputStore = openInternal(
            trustedAppPrivateRoot,
            runId,
            publishedFilePermissions = READ_WRITE_FILE_PERMISSIONS,
            publicationCheckpoint = null,
        )

        internal fun openForTesting(
            trustedAppPrivateRoot: Path,
            runId: String,
            publicationCheckpoint: (Path) -> Unit,
        ): TrustedContentAddressedOutputStore = openInternal(
            trustedAppPrivateRoot,
            runId,
            publishedFilePermissions = READ_ONLY_FILE_PERMISSIONS,
            publicationCheckpoint = publicationCheckpoint,
        )

        private fun openInternal(
            trustedAppPrivateRoot: Path,
            runId: String,
            publishedFilePermissions: Set<PosixFilePermission>,
            publicationCheckpoint: ((Path) -> Unit)?,
        ): TrustedContentAddressedOutputStore {
            if (!RUN_ID.matches(runId)) {
                throw BenchmarkContractException(
                    "content-addressed output run_id is invalid",
                )
            }
            val trustedRoot = trustedAppPrivateRoot.toAbsolutePath().normalize()
            val trustedBinding = DirectoryBinding.capture(
                trustedRoot,
                requireOwnerOnly = true,
            )
            val storeRoot = createPrivateDirectory(
                trustedRoot.resolve(ROOT_NAME),
            )
            trustedBinding.verify()
            val storeBinding = DirectoryBinding.capture(
                storeRoot,
                requireOwnerOnly = true,
            )
            val runDirectory = createPrivateDirectory(
                storeRoot.resolve(runId),
            )
            trustedBinding.verify()
            storeBinding.verify()
            val runBinding = DirectoryBinding.capture(
                runDirectory,
                requireOwnerOnly = true,
            )
            val heldDirectoryChannel = try {
                FileChannel.open(
                    runDirectory,
                    StandardOpenOption.READ,
                    LinkOption.NOFOLLOW_LINKS,
                )
            } catch (error: Exception) {
                throw BenchmarkContractException(
                    "trusted output run directory FD cannot be held: " +
                        error.javaClass.simpleName,
                )
            }
            return try {
                TrustedContentAddressedOutputStore(
                    trustedRootBinding = trustedBinding,
                    storeRootBinding = storeBinding,
                    runDirectoryBinding = runBinding,
                    heldDirectoryChannel = heldDirectoryChannel,
                    publishedFilePermissions = publishedFilePermissions,
                    publicationCheckpoint = publicationCheckpoint,
                ).also { it.verifyLayout() }
            } catch (error: Exception) {
                heldDirectoryChannel.close()
                throw error
            }
        }

        private fun createPrivateDirectory(path: Path): Path {
            try {
                Files.createDirectory(
                    path,
                    PosixFilePermissions.asFileAttribute(
                        DIRECTORY_PERMISSIONS,
                    ),
                )
            } catch (_: FileAlreadyExistsException) {
                // Existing components are accepted only after no-follow checks.
            } catch (error: Exception) {
                throw BenchmarkContractException(
                    "trusted output directory cannot be created: " +
                        error.javaClass.simpleName,
                )
            }
            DirectoryBinding.capture(path, requireOwnerOnly = true)
            return path
        }
    }

    private data class DirectoryBinding(
        val path: Path,
        val fileKey: Any,
        val permissions: Set<PosixFilePermission>,
    ) {
        fun verify() {
            val current = capture(path, requireOwnerOnly = true)
            if (current != this) {
                throw BenchmarkContractException(
                    "trusted output directory identity was replaced",
                )
            }
        }

        companion object {
            fun capture(
                path: Path,
                requireOwnerOnly: Boolean,
            ): DirectoryBinding {
                val attributes = try {
                    Files.readAttributes(
                        path,
                        BasicFileAttributes::class.java,
                        LinkOption.NOFOLLOW_LINKS,
                    )
                } catch (error: Exception) {
                    throw BenchmarkContractException(
                        "trusted output directory cannot be inspected: " +
                            error.javaClass.simpleName,
                    )
                }
                if (!attributes.isDirectory || attributes.isSymbolicLink) {
                    throw BenchmarkContractException(
                        "trusted output path component must be a non-symlink directory",
                    )
                }
                val fileKey = attributes.fileKey()
                    ?: throw BenchmarkContractException(
                        "trusted output directory inode identity is unavailable",
                    )
                val permissions = try {
                    Files.getPosixFilePermissions(
                        path,
                        LinkOption.NOFOLLOW_LINKS,
                    )
                } catch (error: Exception) {
                    throw BenchmarkContractException(
                        "trusted output directory POSIX permissions are unavailable",
                    )
                }
                if (
                    requireOwnerOnly &&
                    permissions.any {
                        it !in DIRECTORY_PERMISSIONS
                    }
                ) {
                    throw BenchmarkContractException(
                        "trusted output directory grants group or other access",
                    )
                }
                return DirectoryBinding(
                    path = path,
                    fileKey = fileKey,
                    permissions = permissions,
                )
            }
        }
    }

    private data class LeafIdentity(
        val fileKey: Any,
        val sizeBytes: Long,
    )

    private data class PublishedFileBinding(
        val fileKey: Any,
        val sizeBytes: Long,
        val sha256: String,
        val bytes: ByteArray,
    ) {
        fun sameIdentityAndContent(other: PublishedFileBinding): Boolean =
            fileKey == other.fileKey &&
                sizeBytes == other.sizeBytes &&
                sha256 == other.sha256 &&
                bytes.contentEquals(other.bytes)
    }
}
