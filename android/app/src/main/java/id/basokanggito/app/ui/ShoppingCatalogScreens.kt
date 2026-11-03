package id.basokanggito.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import id.basokanggito.app.R
import id.basokanggito.app.data.BookRecord
import id.basokanggito.app.data.CatalogItem
import java.time.LocalDate

internal data class CatalogUsage(val count: Int, val lastDate: String)

internal fun catalogUsage(records: List<BookRecord>): Map<String, CatalogUsage> {
    val usage = mutableMapOf<String, CatalogUsage>()
    records.filter { it.kind == "expense" && !it.voided }.forEach { record ->
        record.items.forEach { line -> line.masterId?.let { id ->
            val previous = usage[id]
            usage[id] = CatalogUsage((previous?.count ?: 0) + 1, maxOf(previous?.lastDate.orEmpty(), record.date))
        } }
    }
    return usage
}

@Composable
internal fun ShoppingCatalogPicker(
    items: List<CatalogItem>, records: List<BookRecord>, onSelect: (CatalogItem) -> Unit,
    onCreate: (String, String) -> Unit, onManage: (CatalogItem, String, String, Boolean, String) -> Unit,
    onBack: () -> Unit, error: String, saving: Boolean,
) {
    var search by rememberSaveable { mutableStateOf("") }
    var includeArchived by rememberSaveable { mutableStateOf(false) }
    var frequent by rememberSaveable { mutableStateOf(false) }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var editingVersion by rememberSaveable { mutableIntStateOf(0) }
    var name by rememberSaveable { mutableStateOf("") }
    var unit by rememberSaveable { mutableStateOf("") }
    var archived by rememberSaveable { mutableStateOf(false) }
    var reason by rememberSaveable { mutableStateOf("") }
    var attempted by rememberSaveable { mutableStateOf(false) }
    val usage = remember(records) { catalogUsage(records) }
    val matches = items.filter { item ->
        (includeArchived || !item.archived) && (item.name.contains(search.trim(), ignoreCase = true) ||
            item.aliases.any { it.contains(search.trim(), ignoreCase = true) })
    }.sortedWith(if (frequent) compareByDescending<CatalogItem> { usage[it.id]?.count ?: 0 }
        .thenByDescending { usage[it.id]?.lastDate.orEmpty() }.thenBy { it.name.lowercase() }.thenBy { it.id }
    else compareByDescending<CatalogItem> { usage[it.id]?.lastDate.orEmpty() }
        .thenByDescending { usage[it.id]?.count ?: 0 }.thenBy { it.name.lowercase() }.thenBy { it.id })
    fun closeEditor() { editingId = null; attempted = false }
    fun edit(item: CatalogItem?) {
        editingId = item?.id.orEmpty(); editingVersion = item?.version ?: 0
        name = item?.name ?: search.trim(); unit = item?.unit.orEmpty()
        archived = item?.archived ?: false; reason = ""; attempted = false
    }
    BackHandler(editingId != null && !saving) { closeEditor() }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(Modifier.widthIn(max = 640.dp).fillMaxSize(), contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(Space.medium)) {
            item {
                TextButton(onClick = { if (editingId != null) closeEditor() else onBack() }, enabled = !saving,
                    modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.back)) }
                SectionTitle(stringResource(if (editingId == null) R.string.catalog_choose else if (editingId!!.isEmpty()) R.string.catalog_add else R.string.catalog_edit))
            }
            if (error.isNotEmpty()) item {
                Text(error, color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
            if (editingId != null) {
                item { EntryInput(stringResource(R.string.item_name), name, { name = it },
                    error = if (attempted && name.trim().length !in 1..200) stringResource(R.string.catalog_name_error) else null) }
                item { EntryInput(stringResource(R.string.catalog_unit), unit, { unit = it },
                    error = if (attempted && unit.trim().length > 50) stringResource(R.string.catalog_unit_error) else null) }
                item { Text(stringResource(R.string.catalog_unit_hint), style = MaterialTheme.typography.bodyMedium) }
                if (editingId!!.isNotEmpty()) {
                    item {
                        FilterChip(selected = archived, onClick = { archived = !archived }, enabled = !saving,
                            label = { Text(stringResource(R.string.catalog_archive)) }, modifier = Modifier.heightIn(min = 48.dp))
                        Text(stringResource(R.string.catalog_history_hint), style = MaterialTheme.typography.bodyMedium)
                    }
                    item { EntryInput(stringResource(R.string.correction_reason), reason, { reason = it }, imeAction = ImeAction.Done,
                        error = if (attempted && reason.trim().length !in 1..500) stringResource(R.string.reason_required) else null) }
                }
                item {
                    Button(onClick = {
                        attempted = true
                        if (name.trim().length in 1..200 && unit.trim().length <= 50) {
                            if (editingId!!.isEmpty()) onCreate(name.trim(), unit.trim())
                            else if (reason.trim().length in 1..500) items.find { it.id == editingId }?.let {
                                onManage(it.copy(version = editingVersion), name.trim(), unit.trim(), archived, reason.trim())
                            }
                        }
                    }, enabled = !saving, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                        Text(stringResource(if (editingId!!.isEmpty()) R.string.catalog_create_choose else R.string.save_record))
                    }
                }
                item { TextButton(onClick = { closeEditor() }, enabled = !saving, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.cancel)) } }
            } else {
                item { EntryInput(stringResource(R.string.catalog_search), search, { search = it }, imeAction = ImeAction.Done) }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(Space.tiny)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(Space.small)) {
                            FilterChip(selected = !frequent, onClick = { frequent = false }, label = { Text(stringResource(R.string.catalog_recent)) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp))
                            FilterChip(selected = frequent, onClick = { frequent = true }, label = { Text(stringResource(R.string.catalog_frequent)) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp))
                        }
                        FilterChip(selected = includeArchived, onClick = { includeArchived = !includeArchived },
                            label = { Text(stringResource(R.string.catalog_include_archived)) }, modifier = Modifier.heightIn(min = 48.dp))
                    }
                }
                item {
                    OutlinedButton(onClick = { edit(null) }, enabled = !saving, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                        AppIcon(R.drawable.ic_add); Spacer(Modifier.width(Space.small)); Text(stringResource(R.string.catalog_add))
                    }
                }
                if (matches.isEmpty()) item { Text(stringResource(R.string.catalog_empty)) }
                items(matches, key = { it.id }) { item ->
                    Column(verticalArrangement = Arrangement.spacedBy(Space.tiny)) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        TextButton(onClick = { onSelect(item) }, enabled = !item.archived && !saving,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), contentPadding = PaddingValues(vertical = Space.small)) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.tiny)) {
                                Text(item.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.fillMaxWidth())
                                if (item.unit.isNotBlank()) Text(stringResource(R.string.catalog_default_unit, item.unit), modifier = Modifier.fillMaxWidth())
                                if (item.archived) Text(stringResource(R.string.catalog_archived), modifier = Modifier.fillMaxWidth())
                            }
                            if (!item.archived) AppIcon(R.drawable.ic_next)
                        }
                        usage[item.id]?.let { used ->
                            Text(stringResource(R.string.catalog_usage, used.count, dateLabel(LocalDate.parse(used.lastDate))),
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = { edit(item) }, enabled = !saving, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.catalog_edit_named, item.name))
                        }
                    }
                }
            }
        }
    }
}
