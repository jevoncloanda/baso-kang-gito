package id.basokanggito.app.data

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.SecureRandom
import java.time.Instant
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class BackupException(val code: String) : Exception(code)

object BackupCrypto {
    const val MinPasswordLength = 12
    const val MaxPayloadBytes = 64L * 1024 * 1024
    private const val Iterations = 600_000
    private val magic = "BKGBAK01".toByteArray(Charsets.US_ASCII)
    data class Header(val format: Int = 1, val schema: Int = 2, val createdAt: Long, val appVersion: String,
        val iterations: Int, val salt: ByteArray, val nonce: ByteArray)

    fun newHeader(appVersion: String): Header {
        val random = SecureRandom()
        return Header(createdAt = Instant.now().toEpochMilli(), appVersion = appVersion, iterations = Iterations,
            salt = ByteArray(16).also(random::nextBytes), nonce = ByteArray(12).also(random::nextBytes))
    }

    fun encrypt(output: OutputStream, password: CharArray, header: Header, writePayload: (OutputStream) -> Unit) {
        checkPassword(password, MinPasswordLength)
        if (header.iterations != Iterations) throw BackupException("backup_incompatible")
        val bytes = encodeHeader(header)
        val prefix = prefix(bytes.size)
        val cipher = cipher(Cipher.ENCRYPT_MODE, password, header, prefix, bytes)
        output.write(prefix); output.write(bytes)
        var count = 0L
        val payload = object : OutputStream() {
            override fun write(value: Int) = write(byteArrayOf(value.toByte()), 0, 1)
            override fun write(buffer: ByteArray, offset: Int, length: Int) {
                if (offset < 0 || length < 0 || offset > buffer.size - length) throw IndexOutOfBoundsException()
                if (length.toLong() > MaxPayloadBytes - count) throw BackupException("backup_limit")
                count += length
                var start = offset
                while (start < offset + length) {
                    val size = minOf(32768, offset + length - start)
                    cipher.update(buffer, start, size)?.let(output::write)
                    start += size
                }
            }
            override fun flush() = output.flush()
            override fun close() = flush()
        }
        writePayload(payload)
        output.write(cipher.doFinal())
    }

    // Output must be private staging: no plaintext is trusted until authentication returns successfully.
    fun decrypt(input: InputStream, output: OutputStream, password: CharArray): Header {
        checkPassword(password, 1)
        val data = DataInputStream(input)
        val length = try { data.readInt() } catch (_: EOFException) { throw BackupException("backup_invalid") }
        if (length !in 64..1024) throw BackupException("backup_invalid")
        val bytes = ByteArray(length)
        try { data.readFully(bytes) } catch (_: EOFException) { throw BackupException("backup_invalid") }
        val header = decodeHeader(bytes)
        val cipher = cipher(Cipher.DECRYPT_MODE, password, header, prefix(length), bytes)
        val buffer = ByteArray(32768)
        var ciphertext = 0L; var plaintext = 0L
        fun emit(value: ByteArray?) {
            if (value == null) return
            if (value.size.toLong() > MaxPayloadBytes - plaintext) throw BackupException("backup_limit")
            plaintext += value.size; output.write(value)
        }
        while (true) {
            val size = input.read(buffer)
            if (size == -1) break
            if (size == 0) continue
            ciphertext += size
            if (ciphertext > MaxPayloadBytes + 16) throw BackupException("backup_limit")
            emit(cipher.update(buffer, 0, size))
        }
        if (ciphertext < 16) throw BackupException("backup_invalid")
        try { emit(cipher.doFinal()) } catch (_: AEADBadTagException) { throw BackupException("backup_password") }
        return header
    }

    private fun checkPassword(password: CharArray, minimum: Int) {
        if (password.size !in minimum..512) throw BackupException("backup_password")
    }

    private fun prefix(length: Int): ByteArray = ByteArrayOutputStream().also { DataOutputStream(it).writeInt(length) }.toByteArray()

    private fun encodeHeader(header: Header): ByteArray {
        validate(header)
        val version = header.appVersion.toByteArray(Charsets.UTF_8)
        return ByteArrayOutputStream().also { buffer ->
            val out = DataOutputStream(buffer)
            out.write(magic); out.writeInt(header.format); out.writeInt(header.schema); out.writeLong(header.createdAt)
            out.writeInt(version.size); out.write(version); out.writeInt(1); out.writeInt(header.iterations)
            out.write(header.salt); out.write(header.nonce)
        }.toByteArray()
    }

    private fun decodeHeader(bytes: ByteArray): Header {
        try {
            val input = DataInputStream(ByteArrayInputStream(bytes))
            if (!ByteArray(8).also(input::readFully).contentEquals(magic)) throw BackupException("backup_invalid")
            val format = input.readInt(); val schema = input.readInt(); val created = input.readLong()
            if (format != 1 || schema != 2) throw BackupException("backup_incompatible")
            val length = input.readInt()
            if (length !in 0..100) throw BackupException("backup_invalid")
            val version = ByteArray(length).also(input::readFully)
            val appVersion = try {
                Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(version)).toString()
            } catch (_: java.nio.charset.CharacterCodingException) { throw BackupException("backup_invalid") }
            if (input.readInt() != 1) throw BackupException("backup_incompatible")
            val header = Header(format, schema, created, appVersion, input.readInt(), ByteArray(16).also(input::readFully), ByteArray(12).also(input::readFully))
            if (input.available() != 0) throw BackupException("backup_invalid")
            validate(header)
            return header
        } catch (_: EOFException) { throw BackupException("backup_invalid") }
    }

    private fun validate(header: Header) {
        if (header.format != 1 || header.schema != 2 || header.iterations !in Iterations..1_000_000) throw BackupException("backup_incompatible")
        if (header.createdAt <= 0 || header.createdAt > Instant.now().toEpochMilli() + 86_400_000L ||
            header.appVersion.toByteArray(Charsets.UTF_8).size > 100 || header.salt.size != 16 || header.nonce.size != 12) throw BackupException("backup_invalid")
    }

    private fun cipher(mode: Int, password: CharArray, header: Header, prefix: ByteArray, bytes: ByteArray): Cipher {
        val specification = PBEKeySpec(password, header.salt, header.iterations, 256)
        val key = try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(specification).encoded }
        finally { specification.clearPassword() }
        return try {
            Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, header.nonce))
                updateAAD(prefix); updateAAD(bytes)
            }
        } finally { key.fill(0) }
    }
}
