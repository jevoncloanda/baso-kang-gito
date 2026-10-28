package id.basokanggito.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import id.basokanggito.app.R

/** Secret input is deliberately neither saveable nor exposed through text semantics. */
@Composable
internal fun PinUnlockScreen(
    busy: Boolean, seconds: Long, covered: Boolean = false,
    onUnlock: (String) -> Unit, onRecovery: () -> Unit,
    feedback: @Composable () -> Unit = {},
) {
    var pin by remember { mutableStateOf("") }
    LaunchedEffect(covered) { pin = "" }
    val enabled = !busy && seconds == 0L && !covered
    val progress = stringResource(R.string.pin_progress, pin.length)
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 420.dp).fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(Space.large), verticalArrangement = Arrangement.spacedBy(Space.large)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(painterResource(R.drawable.brand_bowl), contentDescription = null, modifier = Modifier.size(52.dp))
                    Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f).padding(horizontal = Space.small))
                    LanguageMenu()
                }
                Column(verticalArrangement = Arrangement.spacedBy(Space.small)) {
                    Text(stringResource(R.string.pin_enter), style = MaterialTheme.typography.headlineLarge, modifier = Modifier.semantics { heading() })
                    Text(stringResource(R.string.pin_instruction), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(Modifier.fillMaxWidth().testTag("pin_unlock").clearAndSetSemantics {
                    contentDescription = progress
                    liveRegion = LiveRegionMode.Polite
                }, horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally)) {
                    repeat(6) { index ->
                        Box(Modifier.size(18.dp).border(2.dp, MaterialTheme.colorScheme.primary, CircleShape)
                            .then(if (index < pin.length) Modifier.background(MaterialTheme.colorScheme.primary, CircleShape) else Modifier))
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(Space.small), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
                    if (busy) Text(stringResource(R.string.pin_verifying))
                    feedback()
                }
                Column(verticalArrangement = Arrangement.spacedBy(Space.small)) {
                    listOf(listOf(1, 2, 3), listOf(4, 5, 6), listOf(7, 8, 9), listOf(-1, 0, -2)).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(Space.small)) {
                            row.forEach { digit ->
                                if (digit == -1) Spacer(Modifier.weight(1f))
                                else {
                                    val label = stringResource(if (digit == -2) R.string.pin_backspace else R.string.pin_digit, digit)
                                    FilledTonalButton(onClick = {
                                        if (digit == -2) pin = pin.dropLast(1)
                                        else {
                                            val next = pin + digit
                                            if (next.length == 6) { pin = ""; onUnlock(next) } else pin = next
                                        }
                                    }, enabled = enabled && (digit != -2 || pin.isNotEmpty()),
                                        colors = ButtonDefaults.filledTonalButtonColors(
                                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                            contentColor = MaterialTheme.colorScheme.onSurface),
                                        shape = MaterialTheme.shapes.large,
                                        modifier = Modifier.weight(1f).heightIn(min = 80.dp)
                                            .testTag(if (digit == -2) "pin-backspace" else "pin-digit-$digit")
                                            .semantics { contentDescription = label }) {
                                        if (digit == -2) AppIcon(R.drawable.ic_backspace)
                                        else Text(digit.toString(), style = MaterialTheme.typography.headlineLarge)
                                    }
                                }
                            }
                        }
                    }
                }
                TextButton(onClick = onRecovery, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.pin_forgot))
                }
            }
        }
    }
}
