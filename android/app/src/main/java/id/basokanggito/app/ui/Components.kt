package id.basokanggito.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Alignment
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.unit.dp
import id.basokanggito.app.R

@Composable
fun AppIcon(resource: Int, modifier: Modifier = Modifier, description: String? = null) {
    Icon(painterResource(resource), contentDescription = description, modifier = modifier.size(24.dp))
}

@Composable
fun AmountText(amount: Number, modifier: Modifier = Modifier, style: TextStyle = MaterialTheme.typography.titleLarge) {
    val description = stringResource(R.string.currency_accessibility, java.text.NumberFormat.getIntegerInstance(Indonesian).format(amount))
    Text(rupiah(amount), style = style, modifier = modifier.semantics {
        contentDescription = description
    })
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleLarge, modifier = modifier.semantics { heading() })
}

@Composable
fun EntryInput(
    label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier,
    money: Boolean = false, error: String? = null, prominent: Boolean = false,
    description: String? = null, imeAction: ImeAction = ImeAction.Next, onNext: (() -> Unit)? = null,
) {
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    TextField(
        value = value, onValueChange = onChange,
        label = { Text(label) }, singleLine = true,
        textStyle = if (prominent) MaterialTheme.typography.headlineLarge else MaterialTheme.typography.bodyLarge,
        prefix = if (money) ({ Text(stringResource(R.string.currency_symbol), style = if (prominent) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(end = 6.dp)) }) else null,
        visualTransformation = if (money) RupiahGrouping else VisualTransformation.None,
        isError = error != null,
        supportingText = error?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(keyboardType = if (money) KeyboardType.Number else KeyboardType.Text, imeAction = imeAction),
        keyboardActions = KeyboardActions(onNext = { if (onNext != null) onNext() else focus.moveFocus(FocusDirection.Next) }, onDone = { keyboard?.hide(); focus.clearFocus() }),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surface,
            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
            focusedIndicatorColor = MaterialTheme.colorScheme.primary,
            unfocusedIndicatorColor = if (prominent) MaterialTheme.colorScheme.outline else Color.Transparent,
        ),
        shape = MaterialTheme.shapes.medium,
        modifier = modifier.fillMaxWidth().heightIn(min = if (prominent) 112.dp else 56.dp)
            .then(if (prominent) Modifier else Modifier.border(1.dp, if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline, MaterialTheme.shapes.medium))
            .semantics { if (description != null) contentDescription = description },
    )
}

internal fun channelIcon(channel: String): Int = when (channel) {
    "Langsung" -> R.drawable.ic_store
    "ShopeeFood" -> R.drawable.ic_shopping
    else -> R.drawable.ic_delivery
}

@Composable
internal fun IconBadge(resource: Int, accent: Boolean = false) {
    Surface(shape = MaterialTheme.shapes.large,
        color = if (accent) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.primaryContainer,
        contentColor = if (accent) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onPrimaryContainer) {
        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) { AppIcon(resource) }
    }
}

@Composable
internal fun CustomerAvatar(name: String) {
    val trimmed = name.trim()
    val initial = if (trimmed.isEmpty()) "" else trimmed.substring(0, trimmed.offsetByCodePoints(0, 1))
        .uppercase(LocalConfiguration.current.locales[0])
    Surface(shape = androidx.compose.foundation.shape.CircleShape, color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer) {
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            Text(initial, style = MaterialTheme.typography.titleMedium, modifier = Modifier.clearAndSetSemantics {})
        }
    }
}

@Composable
internal fun BonBalanceSummary(total: Number, received: Number, outstanding: Number, totalLabel: Int, outstandingLabel: Int = R.string.bon_outstanding) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.small)) {
        Text(stringResource(outstandingLabel), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        AmountText(outstanding, style = MaterialTheme.typography.headlineLarge)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        val metric: @Composable (Int, Number) -> Unit = { label, value ->
            Column(verticalArrangement = Arrangement.spacedBy(Space.tiny)) {
                Text(stringResource(label), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                AmountText(value, style = MaterialTheme.typography.titleMedium)
            }
        }
        if (LocalDensity.current.fontScale > 1.3f) Column(verticalArrangement = Arrangement.spacedBy(Space.small)) {
            metric(totalLabel, total); metric(R.string.bon_previously_paid, received)
        } else Row(horizontalArrangement = Arrangement.spacedBy(Space.medium)) {
            Column(Modifier.weight(1f)) { metric(totalLabel, total) }
            Column(Modifier.weight(1f)) { metric(R.string.bon_previously_paid, received) }
        }
    }
}

object RupiahGrouping : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val raw = text.text
        if (raw.isEmpty() || raw.any { !it.isDigit() }) return TransformedText(text, OffsetMapping.Identity)
        val grouped = raw.reversed().chunked(3).joinToString(".").reversed()
        return TransformedText(AnnotatedString(grouped), object : OffsetMapping {
            override fun originalToTransformed(offset: Int): Int = if (offset == 0) 0 else offset + (raw.length - 1) / 3 - (raw.length - offset) / 3
            override fun transformedToOriginal(offset: Int): Int = grouped.take(offset).count(Char::isDigit)
        })
    }
}

enum class ContentStatus { Empty, Loading, Error }

@Composable
internal fun UtilityPage(title: Int, onBack: (() -> Unit)? = null, backEnabled: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Column(Modifier.widthIn(max = 640.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.large)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.small)) {
                    if (onBack != null) IconButton(onClick = onBack, enabled = backEnabled) { AppIcon(R.drawable.ic_back, description = stringResource(R.string.back)) }
                    Text(stringResource(title), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                    LanguageMenu()
                }
                content()
            }
        }
    }
}

@Composable
internal fun UtilitySection(title: Int, icon: Int, content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(Space.large), verticalArrangement = Arrangement.spacedBy(Space.medium)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.medium)) {
                IconBadge(icon)
                SectionTitle(stringResource(title), Modifier.weight(1f))
            }
            content()
        }
    }
}

@Composable
internal fun UtilityAction(title: Int, description: Int, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface) {
        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(vertical = Space.small), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.small)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.tiny)) {
                Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(description), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            AppIcon(R.drawable.ic_next)
        }
    }
}

@Composable
fun ContentState(status: ContentStatus, modifier: Modifier = Modifier, retry: () -> Unit = {}) {
    Column(modifier.fillMaxWidth().padding(Space.large), verticalArrangement = Arrangement.spacedBy(Space.medium)) {
        when (status) {
            ContentStatus.Empty -> {
                Text(stringResource(R.string.empty_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.empty_body), style = MaterialTheme.typography.bodyMedium)
            }
            ContentStatus.Loading -> {
                CircularProgressIndicator(modifier = Modifier.size(32.dp))
                Text(stringResource(R.string.loading))
            }
            ContentStatus.Error -> {
                Text(stringResource(R.string.error_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.error_body))
                OutlinedButton(onClick = retry, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.retry)) }
            }
        }
    }
}
