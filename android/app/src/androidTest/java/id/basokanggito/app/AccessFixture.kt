package id.basokanggito.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import id.basokanggito.app.data.*
import org.json.JSONObject
import java.security.SecureRandom
import java.util.UUID

// Only the isolated port-3001 helper accepts this control token. Financial routes never do.
fun enrolledTestClient(): NotebookHttpClient {
    val proof = "${UUID.randomUUID()}.${DeviceAccessStore.encode(ByteArray(32).also { SecureRandom().nextBytes(it) })}"
    val control = NotebookHttpClient("http://127.0.0.1:3001", BuildConfig.SYNC_TOKEN)
    check(control.post("/test/enroll", JSONObject().put("credential", proof)).status == 200)
    return NotebookHttpClient("http://127.0.0.1:3001", proof)
}
fun testControlClient() = NotebookHttpClient("http://127.0.0.1:3001", BuildConfig.SYNC_TOKEN)

fun <R : org.junit.rules.TestRule, A : androidx.activity.ComponentActivity> AndroidComposeTestRule<R, A>.unlockNotebookFixture() {
    waitUntil(10_000) { onAllNodesWithTag("pin_unlock").fetchSemanticsNodes().isNotEmpty() }
    onNodeWithTag("pin_unlock").performTextInput("135790")
    onNodeWithTag("unlock").performScrollTo().performClick()
    waitUntil(10_000) { onAllNodesWithText("Tools").fetchSemanticsNodes().isNotEmpty() }
}
