package id.basokanggito.app.ui

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.core.content.edit
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import id.basokanggito.app.R
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

fun localizedAppContext(context: Context): Context {
    val selected = if (Build.VERSION.SDK_INT >= 33) context.getSystemService(LocaleManager::class.java).applicationLocales else LocaleList.getEmptyLocaleList()
    val language = if (!selected.isEmpty) selected[0].language else context.getSharedPreferences("appearance", Context.MODE_PRIVATE).getString("language", "en")!!
    return context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocales(LocaleList.forLanguageTags(language)) })
}

fun setAppLanguage(context: Context, language: String) {
    // Only an appearance preference is stored here, never prototype financial records.
    context.getSharedPreferences("appearance", Context.MODE_PRIVATE).edit { putString("language", language) }
    if (Build.VERSION.SDK_INT >= 33) context.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags(language)
    else {
        var current = context
        while (current is ContextWrapper && current !is Activity) current = current.baseContext
        (current as? Activity)?.recreate()
    }
}

@Composable
fun channelLabel(channel: String): String = if (channel == "Langsung") stringResource(R.string.channel_direct) else channel

@Composable
fun entryTitle(entry: JournalEntry): String = if (entry.isReceipt) stringResource(R.string.receipt_title) else if (entry.isExpense) stringResource(R.string.shopping) else stringResource(R.string.sale_title, channelLabel(entry.channel))

@Composable
fun lineLabel(line: JournalLine): String = if (line.sampleNameRes != 0) stringResource(line.sampleNameRes) else line.name

@Composable
fun dateLabel(date: LocalDate): String = date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(LocalConfiguration.current.locales[0]))
