package id.basokanggito.app.data

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class BackupCryptoTest {
    private val password get() = "Baso keluarga aman 🔐".toCharArray()
    private fun encrypted(value: ByteArray = "Bon keluarga: Rp100.000 — pelanggan 日本語".toByteArray()): ByteArray = ByteArrayOutputStream().also {
        BackupCrypto.encrypt(it, password, BackupCrypto.newHeader("2.1-test")) { payload -> payload.write(value) }
    }.toByteArray()
    private fun decrypt(value: ByteArray, pass: CharArray = password): ByteArray = ByteArrayOutputStream().also {
        BackupCrypto.decrypt(ByteArrayInputStream(value), it, pass)
    }.toByteArray()
    private fun failure(code: String, action: () -> Unit) {
        try { action(); fail("Expected $code") } catch (failure: BackupException) { assertEquals(code, failure.code) }
    }

    @Test fun unicodeRoundtripPreservesPayloadAndHeaderWithoutChangingPassword() {
        val value = "Bahasa Indonesia — 日本語 😀".toByteArray(Charsets.UTF_8)
        val pass = password; val original = pass.copyOf(); val header = BackupCrypto.newHeader("versi-α")
        val encrypted = ByteArrayOutputStream()
        BackupCrypto.encrypt(encrypted, pass, header) { it.write(value) }
        val output = ByteArrayOutputStream()
        val restored = BackupCrypto.decrypt(ByteArrayInputStream(encrypted.toByteArray()), output, pass)
        assertArrayEquals(value, output.toByteArray()); assertArrayEquals(original, pass)
        assertEquals(header.appVersion, restored.appVersion); assertEquals(header.createdAt, restored.createdAt)
        assertEquals(1, restored.format); assertEquals(2, restored.schema); assertEquals(600000, restored.iterations)
        assertArrayEquals(header.salt, restored.salt); assertArrayEquals(header.nonce, restored.nonce)
    }

    @Test fun emptyPayloadAuthenticatesAndRandomHeadersNeverReuseNonceOrSalt() {
        val first = BackupCrypto.newHeader("test"); val second = BackupCrypto.newHeader("test")
        assertFalse(first.salt.contentEquals(second.salt)); assertFalse(first.nonce.contentEquals(second.nonce))
        assertArrayEquals(ByteArray(0), decrypt(encrypted(ByteArray(0))))
    }

    @Test fun wrongPasswordAndDamagedCiphertextOrTagShareAuthenticationError() {
        val bytes = encrypted()
        failure("backup_password") { decrypt(bytes, "different long password".toCharArray()) }
        for (index in listOf(bytes.lastIndex - 16, bytes.lastIndex)) {
            val corrupt = bytes.copyOf(); corrupt[index] = (corrupt[index].toInt() xor 1).toByte()
            failure("backup_password") { decrypt(corrupt) }
        }
    }

    @Test fun validHeaderTamperingIsAuthenticatedIncludingLengthPrefix() {
        val bytes = encrypted()
        // Salt stays structurally valid but belongs to the authenticated header.
        val length = ByteBuffer.wrap(bytes).int; val corrupt = bytes.copyOf()
        corrupt[4 + length - 28] = (corrupt[4 + length - 28].toInt() xor 1).toByte()
        failure("backup_password") { decrypt(corrupt) }
        val versionCorrupt = bytes.copyOf(); versionCorrupt[32] = '9'.code.toByte()
        failure("backup_password") { decrypt(versionCorrupt) }
    }

    @Test fun truncatedEnvelopeOrMissingTagIsInvalidAndTruncatedPayloadFailsAuthentication() {
        val bytes = encrypted(); val payloadStart = 4 + ByteBuffer.wrap(bytes).int
        for (size in listOf(0, 3, 20, payloadStart, payloadStart + 15)) {
            failure("backup_invalid") { decrypt(bytes.copyOf(size)) }
        }
        failure("backup_password") { decrypt(bytes.copyOf(bytes.size - 1)) }
    }

    @Test fun unsupportedFormatsSchemasKdfsAndWorkFactorsAreRejectedBeforeKeyDerivation() {
        val bytes = encrypted(); val versionLength = ByteBuffer.wrap(bytes, 28, 4).int
        for ((offset, value) in listOf(12 to 2, 16 to 3, (32 + versionLength) to 2,
            (36 + versionLength) to 599999, (36 + versionLength) to 1000001)) {
            val corrupt = bytes.copyOf(); ByteBuffer.wrap(corrupt).putInt(offset, value)
            failure("backup_incompatible") { decrypt(corrupt) }
        }
    }

    @Test fun malformedLengthsTimestampsMagicAndTrailingHeaderBytesAreRejected() {
        val bytes = encrypted()
        for (length in listOf(-1, 0, 63, 1025, Int.MAX_VALUE)) {
            val corrupt = bytes.copyOf(); ByteBuffer.wrap(corrupt).putInt(0, length)
            failure("backup_invalid") { decrypt(corrupt) }
        }
        for (length in listOf(-1, 101)) {
            val corrupt = bytes.copyOf(); ByteBuffer.wrap(corrupt).putInt(28, length)
            failure("backup_invalid") { decrypt(corrupt) }
        }
        val timestamp = bytes.copyOf(); ByteBuffer.wrap(timestamp).putLong(20, Long.MAX_VALUE)
        failure("backup_invalid") { decrypt(timestamp) }
        val magic = bytes.copyOf(); magic[4] = 0
        failure("backup_invalid") { decrypt(magic) }
        val headerLength = ByteBuffer.wrap(bytes).int
        val extra = bytes.copyOfRange(0, 4 + headerLength) + byteArrayOf(0) + bytes.copyOfRange(4 + headerLength, bytes.size)
        ByteBuffer.wrap(extra).putInt(0, headerLength + 1)
        failure("backup_invalid") { decrypt(extra) }
    }

    @Test fun passwordBoundsAndInvalidWriterHeadersAreRejected() {
        val output = ByteArrayOutputStream(); val header = BackupCrypto.newHeader("test")
        for (pass in listOf(CharArray(0), "short".toCharArray(), CharArray(513) { 'a' })) {
            failure("backup_password") { BackupCrypto.encrypt(output, pass, header) {} }
        }
        for (pass in listOf(CharArray(0), CharArray(513) { 'a' })) {
            failure("backup_password") { decrypt(ByteArray(0), pass) }
        }
        failure("backup_invalid") { BackupCrypto.encrypt(output, password, header.copy(appVersion = "a".repeat(101))) {} }
        failure("backup_incompatible") { BackupCrypto.encrypt(output, password, header.copy(iterations = 1000000)) {} }
    }

    @Test fun streamFailuresPropagateAndCallerStreamsStayOpen() {
        val expected = IOException("Synthetic IO failure")
        val brokenOutput = object : OutputStream() { override fun write(value: Int) { throw expected } }
        try { BackupCrypto.encrypt(brokenOutput, password, BackupCrypto.newHeader("test")) {}; fail() }
        catch (failure: IOException) { assertSame(expected, failure) }
        val brokenInput = object : InputStream() { override fun read(): Int = throw expected }
        try { BackupCrypto.decrypt(brokenInput, ByteArrayOutputStream(), password); fail() }
        catch (failure: IOException) { assertSame(expected, failure) }
        val bytes = encrypted()
        try { BackupCrypto.decrypt(ByteArrayInputStream(bytes), brokenOutput, password); fail() }
        catch (failure: IOException) { assertSame(expected, failure) }
        var closed = false
        val output = object : ByteArrayOutputStream() { override fun close() { closed = true; super.close() } }
        BackupCrypto.encrypt(output, password, BackupCrypto.newHeader("test")) { it.write(1); it.close() }
        assertFalse(closed); assertArrayEquals(byteArrayOf(1), decrypt(output.toByteArray()))
    }

    @Test fun oversizedPayloadAndCiphertextAreBoundedWithoutAllocatingOversizedInput() {
        val sink = object : OutputStream() { override fun write(value: Int) {}; override fun write(bytes: ByteArray, offset: Int, length: Int) {} }
        failure("backup_limit") {
            BackupCrypto.encrypt(sink, password, BackupCrypto.newHeader("test")) { output ->
                val chunk = ByteArray(32768)
                repeat((BackupCrypto.MaxPayloadBytes / chunk.size).toInt() + 1) { output.write(chunk) }
            }
        }
        val envelope = encrypted(ByteArray(0)); val headerSize = 4 + ByteBuffer.wrap(envelope).int
        val source = object : InputStream() {
            var count = 0L
            override fun read(): Int = if (count < headerSize) envelope[(count++).toInt()].toInt() and 255 else { count++; 0 }
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                if (count < headerSize) { val size = minOf(length, headerSize - count.toInt()); envelope.copyInto(bytes, offset, count.toInt(), count.toInt() + size); count += size; return size }
                bytes.fill(0, offset, offset + length); count += length; return length
            }
        }
        failure("backup_limit") { BackupCrypto.decrypt(source, sink, password) }
    }
}
