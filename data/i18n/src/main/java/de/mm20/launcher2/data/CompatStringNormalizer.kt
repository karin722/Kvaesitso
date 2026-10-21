package de.mm20.launcher2.data

import android.content.Context
import de.mm20.launcher2.preferences.ui.LocaleSettings
import de.mm20.launcher2.search.StringNormalizer
import org.apache.commons.lang3.StringUtils
import java.util.Locale

/**
 * Pre Android 10 StringNormalizer. Only strips accents from latin characters
 */
internal class CompatStringNormalizer(
    context: Context,
    localeSettings: LocaleSettings,
) : StringNormalizer {

    private val japaneseReadings = JapaneseReadings(context, localeSettings)

    override val id: String
        get() = "null|${japaneseReadings.id}"

    override fun normalize(input: String): String {
        return StringUtils.stripAccents(input.lowercase(Locale.getDefault()))
            .replace("æ", "ae")
            .replace("œ", "oe")
            .replace("ß", "ss")
    }

    override fun normalizeVariants(input: String): List<String> {
        val normalized = normalize(input)
        val readings = japaneseReadings.keysOf(input)
        if (readings.isEmpty()) return listOf(normalized)
        val variants = (listOf(normalized) + readings).filter { it.isNotBlank() }.distinct()
        return variants.ifEmpty { listOf(normalized) }
    }
}
