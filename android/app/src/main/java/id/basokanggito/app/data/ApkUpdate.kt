package id.basokanggito.app.data

import com.android.apksig.ApkVerifier
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import javax.net.ssl.HttpsURLConnection

internal const val ProductionPackage = "id.basokanggito.app"
internal const val ApkMime = "application/vnd.android.package-archive"
private const val MaximumApkBytes = 128L * 1024 * 1024
internal enum class ApkError { METADATA, NETWORK, STORAGE, INTEGRITY, SIGNATURE, PACKAGE, VERSION, INSTALLER }
internal class ApkFailure(val reason: ApkError) : IOException(reason.name)
data class ApkRelease(val versionCode: Int, val versionName: String, val url: String, val sha256: String)
internal data class ApkIdentity(val packageId: String, val versionCode: Long, val versionName: String?, val debuggable: Boolean)

internal fun productionUpdaterEnabled(packageId: String, buildType: String) = packageId == ProductionPackage && buildType == "release"

internal fun trustedApkUri(uri: URI): Boolean = uri.scheme == "https" && uri.host.equals("basokanggito.jevoncl.com", ignoreCase = true) &&
    uri.port in listOf(-1, 443) && uri.rawUserInfo == null && uri.rawFragment == null && uri.rawQuery == null &&
    uri.rawPath?.matches(Regex("/releases/baso-kang-gito-v[0-9]+(?:\\.[0-9]+){1,3}-[1-9][0-9]*\\.apk")) == true

internal fun validateApkRelease(release: ApkRelease, installed: Long) {
    if (release.versionCode < 1 || !release.versionName.matches(Regex("[0-9]+(?:\\.[0-9]+){1,3}")) ||
        !release.sha256.matches(Regex("[a-f0-9]{64}")) ||
        release.url != "$AppDownloadPage/releases/baso-kang-gito-v${release.versionName}-${release.versionCode}.apk" ||
        !runCatching { trustedApkUri(URI(release.url)) }.getOrDefault(false)) throw ApkFailure(ApkError.METADATA)
    if (release.versionCode <= installed) throw ApkFailure(ApkError.VERSION)
}

internal fun apkSha256(file: File): String {
    val hash = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input -> val buffer = ByteArray(64 * 1024); while (true) { val count = input.read(buffer); if (count < 0) break; hash.update(buffer, 0, count) } }
    return hash.digest().joinToString("") { "%02x".format(it) }
}

internal fun verifyApkSignature(file: File, certificate: String) {
    val result = try { ApkVerifier.Builder(file).setMinCheckedPlatformVersion(26).setMaxCheckedPlatformVersion(36).build().verify() }
        catch (_: Exception) { throw ApkFailure(ApkError.SIGNATURE) }
    val certificates = result.signerCertificates.map { signer -> MessageDigest.getInstance("SHA-256").digest(signer.encoded).joinToString("") { "%02x".format(it) } }
    if (!result.isVerified || certificates != listOf(certificate)) throw ApkFailure(ApkError.SIGNATURE)
}

internal fun verifyApkIdentity(identity: ApkIdentity, release: ApkRelease, installed: Long) {
    validateApkRelease(release, installed)
    if (identity.packageId != ProductionPackage || identity.debuggable) throw ApkFailure(ApkError.PACKAGE)
    if (identity.versionCode != release.versionCode.toLong() || identity.versionName != release.versionName) throw ApkFailure(ApkError.VERSION)
}

internal class ApkDownload(private val connect: (URI) -> HttpsURLConnection = { it.toURL().openConnection() as HttpsURLConnection }) {
    @Volatile private var cancelled = false
    @Volatile private var active: HttpsURLConnection? = null
    fun cancel() { cancelled = true; active?.disconnect() }
    private fun checkCancelled() { if (cancelled) throw CancellationException("APK download cancelled") }

    fun download(release: ApkRelease, installed: Long, file: File, progress: (Int?) -> Unit) {
        validateApkRelease(release, installed)
        var uri = URI(release.url)
        var complete = false
        try {
            for (redirect in 0..3) {
                checkCancelled()
                if (!trustedApkUri(uri)) throw ApkFailure(ApkError.METADATA)
                val connection = connect(uri)
                active = connection
                try {
                    connection.instanceFollowRedirects = false
                    connection.connectTimeout = 15_000; connection.readTimeout = 15_000
                    connection.setRequestProperty("Accept", ApkMime)
                    connection.setRequestProperty("Accept-Encoding", "identity")
                    val status = connection.responseCode
                    if (status in listOf(301, 302, 303, 307, 308)) {
                        if (redirect == 3) throw ApkFailure(ApkError.NETWORK)
                        uri = uri.resolve(connection.getHeaderField("Location") ?: throw ApkFailure(ApkError.NETWORK))
                        continue
                    }
                    if (status != 200) throw ApkFailure(ApkError.NETWORK)
                    val total = connection.contentLengthLong
                    if (total == 0L || total > MaximumApkBytes) throw ApkFailure(ApkError.INTEGRITY)
                    if (connection.contentType?.substringBefore(';') != ApkMime) throw ApkFailure(ApkError.INTEGRITY)
                    if (!file.parentFile!!.isDirectory && !file.parentFile!!.mkdirs()) throw ApkFailure(ApkError.STORAGE)
                    val requiredSpace = (if (total > 0) total else 4L * 1024 * 1024) + 1024 * 1024
                    if (file.parentFile!!.usableSpace < requiredSpace) throw ApkFailure(ApkError.STORAGE)
                    var received = 0L
                    var lastProgress: Int? = null
                    progress(if (total > 0) 0 else null)
                    connection.inputStream.use { input ->
                        val output = try { FileOutputStream(file) } catch (_: IOException) { throw ApkFailure(ApkError.STORAGE) }
                        output.use {
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                checkCancelled()
                                val count = input.read(buffer)
                                if (count < 0) break
                                received += count
                                if (received > MaximumApkBytes || (total > 0 && received > total)) throw ApkFailure(ApkError.INTEGRITY)
                                try { output.write(buffer, 0, count) } catch (_: IOException) { throw ApkFailure(ApkError.STORAGE) }
                                val percent = if (total > 0) (received * 100 / total).toInt() else null
                                if (percent != lastProgress) { progress(percent); lastProgress = percent }
                            }
                            try { output.fd.sync() } catch (_: IOException) { throw ApkFailure(ApkError.STORAGE) }
                        }
                    }
                    checkCancelled()
                    if (received == 0L || (total > 0 && received != total) || apkSha256(file) != release.sha256) throw ApkFailure(ApkError.INTEGRITY)
                    complete = true
                    return
                } finally { connection.disconnect(); active = null }
            }
        } catch (failure: ApkFailure) { throw failure }
        catch (failure: CancellationException) { throw failure }
        catch (_: IOException) { checkCancelled(); throw ApkFailure(ApkError.NETWORK) }
        catch (_: IllegalArgumentException) { throw ApkFailure(ApkError.METADATA) }
        finally { if (!complete) file.delete() }
    }
}
