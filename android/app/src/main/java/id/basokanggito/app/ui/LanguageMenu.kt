package id.basokanggito.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import id.basokanggito.app.R

@Composable
fun LanguageMenu() {
    var open by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val description = stringResource(R.string.language)
    Box {
        TextButton(onClick = { open = true }, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).semantics { contentDescription = description }) {
            Text(stringResource(R.string.language_short), style = MaterialTheme.typography.labelLarge)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            listOf("en" to R.string.language_english, "id" to R.string.language_indonesian).forEach { (tag, label) ->
                DropdownMenuItem(text = { Text(stringResource(label)) }, onClick = { open = false; setAppLanguage(context, tag) })
            }
        }
    }
}
