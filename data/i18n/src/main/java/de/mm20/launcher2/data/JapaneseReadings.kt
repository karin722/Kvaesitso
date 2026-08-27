package de.mm20.launcher2.data

import android.content.Context
import de.mm20.launcher2.preferences.ui.LocaleSettings
import de.mm20.launcher2.search.japanese.JapaneseSearchKeys
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Decides whether labels and queries get additional search keys for their Japanese reading, see
 * [JapaneseSearchKeys]. The readings are only useful to someone who types Japanese, and they do
 * cost a little recall precision for everyone else, so they are enabled automatically only if
 * Japanese is one of the device languages.
 */
internal class JapaneseReadings(
    context: Context,
    localeSettings: LocaleSettings,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val enabledByLocale: Boolean = run {
        val locales = context.resources.configuration.locales
        (0..<locales.size()).any { locales.get(it).language == "ja" }
    }

    private val setting = localeSettings.japaneseReadings
        .map { it ?: enabledByLocale }
        .stateIn(scope, SharingStarted.Eagerly, enabledByLocale)

    val enabled: Boolean
        get() = setting.value

    /**
     * A component for the normalizer id, so that cached normalization results are discarded
     * when this setting changes.
     */
    val id: String
        get() = if (enabled) "ja" else "-"

    fun keysOf(input: String): List<String> {
        if (!enabled) return emptyList()
        return JapaneseSearchKeys.keysOf(input)
    }
}
