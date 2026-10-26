package id.basokanggito.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
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
        prefix = if (money) ({ Text(stringResource(R.string.currency_symbol), modifier = Modifier.padding(end = 6.dp)) }) else null,
        visualTransformation = if (money) RupiahGrouping else VisualTransformation.None,
        isError = error != null,
        supportingText = error?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(keyboardType = if (money) KeyboardType.Number else KeyboardType.Text, imeAction = imeAction),
        keyboardActions = KeyboardActions(onNext = { if (onNext != null) onNext() else focus.moveFocus(FocusDirection.Next) }, onDone = { keyboard?.hide(); focus.clearFocus() }),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surface,
            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
            focusedIndicatorColor = MaterialTheme.colorScheme.primary,
            unfocusedIndicatorColor = Color.Transparent,
        ),
        shape = MaterialTheme.shapes.small,
        modifier = modifier.fillMaxWidth().heightIn(min = if (prominent) 96.dp else 56.dp).semantics { if (description != null) contentDescription = description },
    )
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
