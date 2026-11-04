package id.basokanggito.app.data

import id.basokanggito.app.BuildConfig
import org.json.JSONObject
import java.io.IOException

const val AppDownloadPage = "https://basokanggito.jevoncl.com"
data class AppVersionPolicy(val latest: Int, val minimum: Int, val recommended: Int, val versionName: String, val message: String, val artifact: ApkRelease? = null) {
    fun required(installed: Int = BuildConfig.VERSION_CODE) = installed < minimum
    fun updateAvailable(installed: Int = BuildConfig.VERSION_CODE) = installed < latest
    fun updateRecommended(installed: Int = BuildConfig.VERSION_CODE) = installed < recommended
    fun json() = JSONObject().put("latestVersionCode", latest).put("minimumSupportedVersionCode", minimum)
        .put("recommendedVersionCode", recommended).put("latestVersionName", versionName)
        .put("mandatoryUpdateMessage", message).put("downloadPageUrl", AppDownloadPage)
        .put("downloadUrl", artifact?.url ?: JSONObject.NULL).put("sha256", artifact?.sha256 ?: JSONObject.NULL)
    companion object {
        fun parse(json: JSONObject): AppVersionPolicy {
            require(json.getString("downloadPageUrl") == AppDownloadPage)
            fun code(key: String): Int { val value = json.get(key); require(value is Number && value.toLong() in 1..Int.MAX_VALUE.toLong() && value.toDouble() == value.toLong().toDouble()); return value.toInt() }
            return AppVersionPolicy(code("latestVersionCode"), code("minimumSupportedVersionCode"), code("recommendedVersionCode"),
                json.getString("latestVersionName").also { require(it.length in 1..50) },
                json.optString("mandatoryUpdateMessage").also { require(it.length <= 1000) },
                if ((!json.has("downloadUrl") || json.isNull("downloadUrl")) && (!json.has("sha256") || json.isNull("sha256"))) null
                else ApkRelease(code("latestVersionCode"), json.getString("latestVersionName"), json.getString("downloadUrl"), json.getString("sha256"))
                    .also { validateApkRelease(it, 0) }
            ).also { require(it.minimum <= it.recommended && it.recommended <= it.latest) }
        }
        fun cached(meta: Map<String, String>): AppVersionPolicy? = meta["appVersionPolicy"]?.takeIf { it.isNotEmpty() }?.let { runCatching { parse(JSONObject(it)) }.getOrNull() }
    }
}
class AppUpdateRequired(val policy: JSONObject) : IOException("APP_UPDATE_REQUIRED")
