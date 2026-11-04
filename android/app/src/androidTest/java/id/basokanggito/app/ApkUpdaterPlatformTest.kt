package id.basokanggito.app

import android.content.Intent
import android.net.Uri
import id.basokanggito.app.data.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ApkUpdaterPlatformTest {
    @Test fun installerUsesContentUriAndTemporaryReadPermissionOnly() {
        val uri = Uri.parse("content://${BuildConfig.APPLICATION_ID}.apk-updates/apk_updates/verified.apk")
        val intent = apkInstallIntent(uri)
        @Suppress("DEPRECATION")
        assertEquals(Intent.ACTION_INSTALL_PACKAGE, intent.action)
        assertEquals(uri, intent.data); assertEquals(uri, intent.clipData!!.getItemAt(0).uri)
        assertEquals(ApkMime, intent.type)
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, intent.flags)
        assertTrue(intent.getBooleanExtra(Intent.EXTRA_RETURN_RESULT, false))
        assertFalse(productionUpdaterEnabled(BuildConfig.APPLICATION_ID, BuildConfig.BUILD_TYPE))
    }
    @Test fun discoveryKeepsVerifiedArtifactMetadataAndRejectsIncompleteDetails() {
        val value = JSONObject().put("latestVersionCode", 3).put("minimumSupportedVersionCode", 1).put("recommendedVersionCode", 1)
            .put("latestVersionName", "0.0.3").put("downloadPageUrl", AppDownloadPage).put("mandatoryUpdateMessage", "")
            .put("downloadUrl", "$AppDownloadPage/releases/baso-kang-gito-v0.0.3-3.apk").put("sha256", "a".repeat(64))
        assertEquals("a".repeat(64), AppVersionPolicy.parse(value).artifact!!.sha256)
        value.remove("sha256")
        assertTrue(runCatching { AppVersionPolicy.parse(value) }.isFailure)
    }
}
