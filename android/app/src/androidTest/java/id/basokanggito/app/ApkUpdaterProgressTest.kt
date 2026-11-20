package id.basokanggito.app

import androidx.activity.ComponentActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import id.basokanggito.app.data.ApkError
import id.basokanggito.app.data.ApkPhase
import id.basokanggito.app.data.ApkUpdateState
import id.basokanggito.app.ui.AppUpdateDownloadProgress
import id.basokanggito.app.ui.BasoTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test

class ApkUpdaterProgressTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun progressSemanticsFollowKnownUnknownAndFinishedDownloadStates() {
        val state = MutableStateFlow(ApkUpdateState(ApkPhase.DOWNLOADING, percent = 0))
        compose.setContent {
            BasoTheme { AppUpdateDownloadProgress(state.collectAsState().value) }
        }
        val range = SemanticsProperties.ProgressBarRangeInfo
        val indicator = compose.onNode(SemanticsMatcher.keyIsDefined(range))
        fun show(next: ApkUpdateState) = compose.runOnIdle { state.value = next }
        fun expectProgress(expected: ProgressBarRangeInfo) {
            indicator.assert(SemanticsMatcher.expectValue(range, expected))
        }

        expectProgress(ProgressBarRangeInfo(0f, 0f..1f))
        show(ApkUpdateState(ApkPhase.DOWNLOADING))
        expectProgress(ProgressBarRangeInfo.Indeterminate)

        show(ApkUpdateState(ApkPhase.DOWNLOADING, percent = 37))
        expectProgress(ProgressBarRangeInfo(0.37f, 0f..1f))
        show(ApkUpdateState(ApkPhase.DOWNLOADING, percent = 100))
        expectProgress(ProgressBarRangeInfo(1f, 0f..1f))
        show(ApkUpdateState(ApkPhase.VERIFYING))
        expectProgress(ProgressBarRangeInfo.Indeterminate)
        show(ApkUpdateState(ApkPhase.READY))
        indicator.assertDoesNotExist()

        for (finished in listOf(
            ApkUpdateState(ApkPhase.CANCELLED),
            ApkUpdateState(ApkPhase.ERROR, error = ApkError.INTEGRITY),
            ApkUpdateState(),
        )) {
            show(ApkUpdateState(ApkPhase.DOWNLOADING, percent = 42))
            expectProgress(ProgressBarRangeInfo(0.42f, 0f..1f))
            show(finished)
            indicator.assertDoesNotExist()
        }
    }
}
