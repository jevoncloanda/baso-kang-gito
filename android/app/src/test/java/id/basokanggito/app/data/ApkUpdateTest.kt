package id.basokanggito.app.data

import id.basokanggito.app.BuildConfig
import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.net.URI
import java.nio.file.Files
import java.security.MessageDigest
import java.security.cert.Certificate
import java.util.concurrent.CancellationException
import javax.net.ssl.HttpsURLConnection

class ApkUpdateTest {
    private val bytes = ByteArray(150_000) { (it % 251).toByte() }
    private fun hash(data: ByteArray) = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
    private fun release() = ApkRelease(3, "0.0.3", "$AppDownloadPage/releases/baso-kang-gito-v0.0.3-3.apk", hash(bytes))
    private fun error(reason: ApkError, action: () -> Unit) {
        try { action(); fail("Expected $reason") } catch (failure: ApkFailure) { assertEquals(reason, failure.reason) }
    }
    private fun temporary(action: (File) -> Unit) {
        val directory = Files.createTempDirectory("bkg-apk-test").toFile()
        try { action(File(directory, "update.apk")) } finally { directory.listFiles()?.forEach { it.delete() }; directory.delete() }
    }
    private class Connection(uri: URI, val data: ByteArray, val status: Int = 200, val length: Long = data.size.toLong(), val location: String? = null) : HttpsURLConnection(uri.toURL()) {
        override fun getResponseCode() = status
        override fun getContentLengthLong() = length
        override fun getContentType() = ApkMime
        override fun getHeaderField(name: String?) = if (name == "Location") location else null
        override fun getInputStream(): InputStream = ByteArrayInputStream(data)
        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy() = false
        override fun getCipherSuite() = "test-only"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
    }

    @Test fun discoveryAndProductionVariantIsolation() {
        val artifact = release()
        val policy = AppVersionPolicy(3, 1, 1, "0.0.3", "", artifact)
        assertTrue(policy.updateAvailable(2)); assertFalse(policy.required(2)); assertEquals(artifact, policy.artifact)
        assertTrue(productionUpdaterEnabled(ProductionPackage, "release"))
        for (variant in listOf("debug", "validation")) assertFalse(productionUpdaterEnabled(ProductionPackage, variant))
        for (id in listOf("id.basokanggito.app.debug", "id.basokanggito.app.access.validation")) assertFalse(productionUpdaterEnabled(id, "release"))
    }

    @Test fun malformedUntrustedAndDowngradedMetadataRejected() {
        for (url in listOf("http://basokanggito.jevoncl.com/a.apk", "https://evil.example/a.apk", "https://basokanggito.jevoncl.com@evil.example/a.apk", release().url + "?token=x", release().url + "#x")) {
            error(ApkError.METADATA) { validateApkRelease(release().copy(url = url), 2) }
        }
        error(ApkError.METADATA) { validateApkRelease(release().copy(sha256 = "not-a-hash"), 2) }
        error(ApkError.VERSION) { validateApkRelease(release(), 3) }
        error(ApkError.VERSION) { validateApkRelease(release(), 4) }
        assertFalse(trustedApkUri(URI("https://basokanggito.jevoncl.com")))
    }

    @Test fun downloadSuccessReportsProgressAndWritesOnlyItsOwnFile() = temporary { file ->
        val marker = File(file.parentFile, "pending-operation.json").apply { writeText("unchanged operation ID and body") }
        val progress = mutableListOf<Int?>()
        ApkDownload { Connection(it, bytes) }.download(release(), 2, file) { progress += it }
        assertArrayEquals(bytes, file.readBytes()); assertEquals(0, progress.first()); assertEquals(100, progress.last())
        assertEquals("unchanged operation ID and body", marker.readText())
    }

    @Test fun failuresAndIncompleteTransfersDeleteOnlyTheCandidate() = temporary { file ->
        val marker = File(file.parentFile, "draft.json").apply { writeText("original draft") }
        error(ApkError.NETWORK) { ApkDownload { Connection(it, bytes, status = 503) }.download(release(), 2, file) {} }
        error(ApkError.NETWORK) { ApkDownload { throw IOException("offline") }.download(release(), 2, file) {} }
        assertFalse(file.exists())
        error(ApkError.INTEGRITY) { ApkDownload { Connection(it, bytes, length = bytes.size.toLong() + 1) }.download(release(), 2, file) {} }
        assertFalse(file.exists())
        error(ApkError.INTEGRITY) { ApkDownload { Connection(it, bytes) }.download(release().copy(sha256 = "0".repeat(64)), 2, file) {} }
        assertFalse(file.exists()); assertEquals("original draft", marker.readText())
    }

    @Test fun insufficientStorageDoesNotStartWriting() = temporary { file ->
        val noSpace = object : File(file.path) {
            override fun getParentFile(): File = object : File(file.parent!!) { override fun getUsableSpace() = 0L }
        }
        error(ApkError.STORAGE) { ApkDownload { Connection(it, bytes) }.download(release(), 2, noSpace) {} }
        assertFalse(file.exists())
    }

    @Test fun emptyAndOversizedDownloadsAreRejected() = temporary { file ->
        for (length in listOf(0L, 129L * 1024 * 1024)) {
            error(ApkError.INTEGRITY) { ApkDownload { Connection(it, bytes, length = length) }.download(release(), 2, file) {} }
            assertFalse(file.exists())
        }
    }

    @Test fun redirectOutsideTrustedHostRejectedBeforeConnecting() = temporary { file ->
        var calls = 0
        error(ApkError.METADATA) {
            ApkDownload { calls++; Connection(it, bytes, status = 302, location = "https://evil.example/update.apk") }.download(release(), 2, file) {}
        }
        assertEquals(1, calls); assertFalse(file.exists())
    }

    @Test fun sameHostRedirectWorksAndCancellationRemovesPartialFile() = temporary { file ->
        var calls = 0
        ApkDownload { calls++; if (calls == 1) Connection(it, bytes, status = 307, location = release().url) else Connection(it, bytes) }.download(release(), 2, file) {}
        assertArrayEquals(bytes, file.readBytes())
        val download = ApkDownload { Connection(it, bytes) }
        try {
            download.download(release(), 2, file) { if ((it ?: 0) > 0) download.cancel() }
            fail("Expected cancellation")
        } catch (_: CancellationException) { assertFalse(file.exists()) }
    }

    @Test fun packageDebugFlagAndArchiveVersionMustMatch() {
        val identity = ApkIdentity(ProductionPackage, 3, "0.0.3", false)
        verifyApkIdentity(identity, release(), 2)
        error(ApkError.PACKAGE) { verifyApkIdentity(identity.copy(packageId = "$ProductionPackage.debug"), release(), 2) }
        error(ApkError.PACKAGE) { verifyApkIdentity(identity.copy(debuggable = true), release(), 2) }
        error(ApkError.VERSION) { verifyApkIdentity(identity.copy(versionCode = 2), release(), 2) }
        error(ApkError.VERSION) { verifyApkIdentity(identity.copy(versionName = "0.0.2"), release(), 2) }
    }

    @Test fun realProductionSignatureAndWrongIdentity() {
        val path = System.getProperty("bkg.apkFixture"); assumeNotNull(path)
        val file = File(path!!)
        verifyApkSignature(file, BuildConfig.UPDATE_CERTIFICATE_SHA256)
        error(ApkError.SIGNATURE) { verifyApkSignature(file, "0".repeat(64)) }
    }

    @Test fun tamperedApkRejectedEvenWhenItsNewHashIsKnown() = temporary { file ->
        val path = System.getProperty("bkg.apkFixture"); assumeNotNull(path)
        File(path!!).copyTo(file, overwrite = true)
        RandomAccessFile(file, "rw").use { apk -> val offset = apk.length() / 3; apk.seek(offset); val value = apk.read(); apk.seek(offset); apk.write(value xor 1) }
        assertEquals(64, apkSha256(file).length)
        error(ApkError.SIGNATURE) { verifyApkSignature(file, BuildConfig.UPDATE_CERTIFICATE_SHA256) }
    }
}
