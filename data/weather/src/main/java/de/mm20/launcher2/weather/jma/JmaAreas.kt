package de.mm20.launcher2.weather.jma

import de.mm20.launcher2.crashreporter.CrashReporter
import de.mm20.launcher2.search.ResultScore
import de.mm20.launcher2.search.japanese.JapaneseSearchKeys
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The JMA area index: which forecast has to be downloaded for a place, and how to find that
 * place by name.
 *
 * JMA publishes one forecast per 府県予報区 (office), broken down into 一次細分区域 (class 10)
 * of which there are about 140. Nobody knows the name of theirs, so the index the user searches
 * is built from the 1800 municipalities instead, each resolved to the area that covers it.
 */
internal class JmaAreas(private val index: JmaAreaIndex) {

    /** A place a forecast can be requested for. */
    class Place(
        /** What the user picked, e.g. "千代田区, 東京都". */
        val name: String,
        /** 府県予報区, the document to download. */
        val office: String,
        /** 一次細分区域, the entry within that document. */
        val class10: String,
    ) {
        val id: String
            get() = "$office/$class10"
    }

    /**
     * Places matching [query], best match first. Municipalities can be found by their name in
     * kanji, by their reading, and by their English name, so 千代田区 turns up for 千代田,
     * ちよだ and chiyoda alike.
     */
    fun search(query: String, limit: Int = 20): List<Place> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()
        val queries = (JapaneseSearchKeys.keysOf(trimmed) + trimmed.lowercase()).distinct()

        val results = mutableListOf<Pair<Float, Place>>()
        for ((code, area) in index.class20s) {
            val score = ResultScore.from(queries, primaryFields = searchFields(area))
            if (score.score < Threshold) continue
            val place = placeOf(code, area) ?: continue
            results.add(score.score to place)
        }
        for ((code, area) in index.class10s) {
            val score = ResultScore.from(queries, primaryFields = searchFields(area))
            if (score.score < Threshold) continue
            val office = area.parent ?: continue
            results.add(score.score to Place(qualify(area.name, office), office, code))
        }
        return results
            .sortedByDescending { it.first }
            .distinctBy { it.second.name }
            .take(limit)
            .map { it.second }
    }

    /**
     * The place the device is in, as far as the reverse geocoder could tell. Municipalities are
     * matched first: [prefecture] is of no use in Hokkaido, which JMA splits into fourteen
     * offices that are named after a region rather than after the prefecture.
     */
    fun resolve(prefecture: String?, municipality: String?): Place? {
        if (municipality != null) {
            // A municipality JMA forecasts in parts is listed as 横浜市北部, 横浜市南部 and so
            // on, which the geocoder's 横浜市 only prefixes. An exact name still wins.
            val candidates = index.class20s.entries
                .filter { it.value.name.startsWith(municipality) }
                .sortedBy { if (it.value.name == municipality) 0 else 1 }
            val match = candidates.firstOrNull { prefecture == null || inPrefecture(it.key, prefecture) }
                ?: candidates.firstOrNull()
            if (match != null) return placeOf(match.key, match.value)
        }
        if (prefecture != null) {
            val office = index.offices.entries.firstOrNull {
                it.value.name == prefecture || prefecture.startsWith(it.value.name)
            }
            if (office != null) {
                val class10 = index.class10s.entries.firstOrNull { it.value.parent == office.key }
                if (class10 != null) {
                    return Place(
                        qualify(class10.value.name, office.key),
                        office.key,
                        class10.key,
                    )
                }
            }
        }
        return null
    }

    private fun searchFields(area: JmaArea): List<String> {
        val fields = mutableListOf(area.name.lowercase())
        area.kana?.let {
            fields.add(it)
            fields.addAll(JapaneseSearchKeys.keysOf(it))
        }
        area.enName?.let { fields.add(it.lowercase()) }
        if (area.kana == null) fields.addAll(JapaneseSearchKeys.keysOf(area.name))
        return fields
    }

    private fun placeOf(class20: String, area: JmaArea): Place? {
        val class10 = class10Of(class20) ?: return null
        val office = index.class10s[class10]?.parent ?: return null
        return Place(qualify(area.name, office), office, class10)
    }

    /**
     * Walks up from a municipality to the 一次細分区域 that covers it. The levels in between
     * differ between prefectures, so this follows parents until it lands on a known area.
     */
    private fun class10Of(class20: String): String? {
        var code = index.class20s[class20]?.parent
        var hops = 0
        while (code != null && hops < MaxHops) {
            if (index.class10s.containsKey(code)) return code
            code = index.class15s[code]?.parent
            hops++
        }
        return null
    }

    private fun inPrefecture(class20: String, prefecture: String): Boolean {
        val office = class10Of(class20)?.let { index.class10s[it]?.parent } ?: return false
        val name = index.offices[office]?.name ?: return false
        return name == prefecture || prefecture.startsWith(name) || name.startsWith(prefecture)
    }

    private fun qualify(name: String, office: String): String {
        val officeName = index.offices[office]?.name
        return if (officeName == null || officeName == name) name else "$name, $officeName"
    }

    companion object {
        private const val Threshold = 0.8f
        private const val MaxHops = 4

        /** The index changes at most a few times a year. */
        private const val CacheDuration = 7L * 24 * 60 * 60 * 1000

        private val mutex = Mutex()
        private var cached: JmaAreas? = null
        private var cachedAt = 0L

        suspend fun get(api: JmaApi): JmaAreas? = mutex.withLock {
            val existing = cached
            if (existing != null && System.currentTimeMillis() - cachedAt < CacheDuration) {
                return@withLock existing
            }
            val index = try {
                api.areas()
            } catch (e: Exception) {
                // Keep whatever is cached, however old, over having no areas at all
                CrashReporter.logException(e)
                return@withLock existing
            }
            JmaAreas(index).also {
                cached = it
                cachedAt = System.currentTimeMillis()
            }
        }

        /** Reads back what [Place.id] wrote. */
        fun parseId(id: String): Pair<String, String>? {
            val parts = id.split('/')
            if (parts.size != 2 || parts.any { it.isEmpty() }) return null
            return parts[0] to parts[1]
        }
    }
}
