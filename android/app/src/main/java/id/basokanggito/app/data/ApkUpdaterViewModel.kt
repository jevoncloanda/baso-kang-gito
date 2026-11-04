package id.basokanggito.app.data

import android.app.Activity
import android.app.Application
import android.content.ClipData
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import id.basokanggito.app.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

internal enum class ApkPhase { IDLE, DOWNLOADING, VERIFYING, READY, PERMISSION, INSTALLING, CANCELLED, ERROR, DISABLED }
internal data class ApkUpdateState(val phase: ApkPhase = ApkPhase.IDLE, val percent: Int? = null, val error: ApkError? = null)

@Suppress("DEPRECATION") // ACTION_INSTALL_PACKAGE returns the documented user-approved installer result.
internal fun apkInstallIntent(uri: Uri): Intent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
    require(uri.scheme == "content" && uri.authority == "${BuildConfig.APPLICATION_ID}.apk-updates")
    setDataAndType(uri, ApkMime)
    clipData = ClipData.newRawUri("APK", uri)
    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    putExtra(Intent.EXTRA_RETURN_RESULT, true)
}

internal class ApkUpdaterViewModel(application: Application) : AndroidViewModel(application) {
    private val context get() = getApplication<Application>()
    val enabled = productionUpdaterEnabled(BuildConfig.APPLICATION_ID, BuildConfig.BUILD_TYPE)
    private val mutable = MutableStateFlow(ApkUpdateState())
    val state = mutable.asStateFlow()
    private var job: Job? = null
    private var download: ApkDownload? = null
    private var release: ApkRelease? = null
    private var file: File? = null
    private var shared: Uri? = null

    fun begin(policy: AppVersionPolicy) = start(policy.artifact)
    private fun start(target: ApkRelease?) {
        if (job?.isCompleted == false || mutable.value.phase == ApkPhase.INSTALLING) return
        if (!enabled) { mutable.value = ApkUpdateState(ApkPhase.DISABLED); return }
        clearFile()
        release = target
        if (target == null) { mutable.value = ApkUpdateState(ApkPhase.ERROR, error = ApkError.METADATA); return }
        val candidate = File(context.cacheDir, "apk-updates/${UUID.randomUUID()}.apk")
        val transfer = ApkDownload()
        download = transfer
        mutable.value = ApkUpdateState(ApkPhase.DOWNLOADING, percent = 0)
        job = viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    transfer.download(target, BuildConfig.VERSION_CODE.toLong(), candidate) { progress ->
                        val previous = mutable.value
                        if (previous.phase == ApkPhase.DOWNLOADING) mutable.compareAndSet(previous, ApkUpdateState(ApkPhase.DOWNLOADING, percent = progress))
                    }
                    currentCoroutineContext().ensureActive()
                    withContext(Dispatchers.Main.immediate) { mutable.value = ApkUpdateState(ApkPhase.VERIFYING) }
                    verify(candidate, target)
                }
                file = candidate
                mutable.value = ApkUpdateState(ApkPhase.READY)
            } catch (cancelled: CancellationException) {
                candidate.delete()
                throw cancelled
            } catch (failure: Exception) {
                candidate.delete()
                mutable.value = ApkUpdateState(ApkPhase.ERROR, error = (failure as? ApkFailure)?.reason ?: ApkError.NETWORK)
            } finally { download = null }
        }
    }

    @Suppress("DEPRECATION")
    private fun verify(candidate: File, target: ApkRelease) {
        validateApkRelease(target, BuildConfig.VERSION_CODE.toLong())
        if (!candidate.isFile || apkSha256(candidate) != target.sha256) throw ApkFailure(ApkError.INTEGRITY)
        verifyApkSignature(candidate, BuildConfig.UPDATE_CERTIFICATE_SHA256)
        val archive = context.packageManager.getPackageArchiveInfo(candidate.path, 0) ?: throw ApkFailure(ApkError.PACKAGE)
        verifyApkIdentity(ApkIdentity(archive.packageName, if (Build.VERSION.SDK_INT >= 28) archive.longVersionCode else archive.versionCode.toLong(),
            archive.versionName, (archive.applicationInfo?.flags ?: 0) and ApplicationInfo.FLAG_DEBUGGABLE != 0), target, BuildConfig.VERSION_CODE.toLong())
    }

    fun requestInstall(launch: (Intent) -> Unit) {
        if (!enabled || mutable.value.phase !in listOf(ApkPhase.READY, ApkPhase.PERMISSION) || job?.isCompleted == false) return
        if (!context.packageManager.canRequestPackageInstalls()) { mutable.value = ApkUpdateState(ApkPhase.PERMISSION); return }
        val candidate = file ?: return failInstaller()
        val target = release ?: return failInstaller()
        mutable.value = ApkUpdateState(ApkPhase.VERIFYING)
        job = viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { verify(candidate, target) }
                val uri = FileProvider.getUriForFile(context, "${BuildConfig.APPLICATION_ID}.apk-updates", candidate)
                shared = uri
                mutable.value = ApkUpdateState(ApkPhase.INSTALLING)
                launch(apkInstallIntent(uri))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { mutable.value = ApkUpdateState(ApkPhase.ERROR, error = (failure as? ApkFailure)?.reason ?: ApkError.INSTALLER) }
        }
    }

    fun permissionIntent(): Intent? = if (enabled) Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${BuildConfig.APPLICATION_ID}")) else null
    fun permissionReturned() {
        if (mutable.value.phase == ApkPhase.PERMISSION && context.packageManager.canRequestPackageInstalls()) mutable.value = ApkUpdateState(ApkPhase.READY)
    }
    fun installed(result: Int) {
        clearFile()
        mutable.value = if (result == Activity.RESULT_OK) ApkUpdateState() else if (result == Activity.RESULT_CANCELED)
            ApkUpdateState(ApkPhase.CANCELLED) else ApkUpdateState(ApkPhase.ERROR, error = ApkError.INSTALLER)
    }
    fun failInstaller() { mutable.value = ApkUpdateState(ApkPhase.ERROR, error = ApkError.INSTALLER) }
    fun retry() = start(release)
    fun cancel() {
        download?.cancel(); job?.cancel(); clearFile()
        mutable.value = ApkUpdateState(ApkPhase.CANCELLED)
    }
    fun dismiss() { if (mutable.value.phase != ApkPhase.INSTALLING) { cancel(); mutable.value = ApkUpdateState() } }
    private fun clearFile() {
        shared?.let { context.revokeUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) }; shared = null
        file?.delete(); file = null
    }
    override fun onCleared() { download?.cancel(); clearFile(); super.onCleared() }
}
