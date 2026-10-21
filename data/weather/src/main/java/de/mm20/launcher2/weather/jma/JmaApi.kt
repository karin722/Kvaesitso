package de.mm20.launcher2.weather.jma

import de.mm20.launcher2.serialization.Json
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.get
import io.ktor.http.path
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable

/**
 * An area in the JMA area hierarchy: 中枢官署 (center) > 府県予報区 (office) > 一次細分区域
 * (class 10) > 市町村等をまとめた地域 (class 15) > 市区町村 (class 20).
 */
@Serializable
internal data class JmaArea(
    val name: String = "",
    val enName: String? = null,
    /** Only municipalities carry a reading, in hiragana. */
    val kana: String? = null,
    /** Code of the area one level up. Null for the topmost level. */
    val parent: String? = null,
)

@Serializable
internal data class JmaAreaIndex(
    val offices: Map<String, JmaArea> = emptyMap(),
    val class10s: Map<String, JmaArea> = emptyMap(),
    val class15s: Map<String, JmaArea> = emptyMap(),
    val class20s: Map<String, JmaArea> = emptyMap(),
)

@Serializable
internal data class JmaAreaRef(
    val name: String = "",
    val code: String = "",
)

/**
 * The values of one area within a time series. Which of the lists are present depends on the
 * series; all of them are indexed by the same position as [JmaTimeSeries.timeDefines].
 */
@Serializable
internal data class JmaAreaForecast(
    val area: JmaAreaRef = JmaAreaRef(),
    val weatherCodes: List<String>? = null,
    val weathers: List<String>? = null,
    val pops: List<String>? = null,
    val temps: List<String>? = null,
    val tempsMin: List<String>? = null,
    val tempsMax: List<String>? = null,
)

@Serializable
internal data class JmaTimeSeries(
    val timeDefines: List<String> = emptyList(),
    val areas: List<JmaAreaForecast> = emptyList(),
)

/**
 * One block of a forecast document. The first block is the detailed forecast for the next three
 * days, the second one the weekly forecast. Weather is given per class 10 area, temperatures per
 * observation point, which is why the areas of the time series within one block do not line up.
 */
@Serializable
internal data class JmaForecast(
    val reportDatetime: String? = null,
    val publishingOffice: String? = null,
    val timeSeries: List<JmaTimeSeries> = emptyList(),
)

/**
 * One AMeDAS observation. Every value comes as a pair of the reading and a quality flag, which
 * is why they are lists. Wind direction is a 16 point compass index, 0 meaning 静穏.
 */
@Serializable
internal data class JmaObservation(
    val temp: List<Double>? = null,
    val humidity: List<Double>? = null,
    val pressure: List<Double>? = null,
    val wind: List<Double>? = null,
    val windDirection: List<Double>? = null,
    val precipitation1h: List<Double>? = null,
    val maxTemp: List<Double>? = null,
    val minTemp: List<Double>? = null,
) {
    val temperature: Double? get() = temp?.firstOrNull()
}

internal class JmaApi {

    private val httpClient by lazy {
        HttpClient {
            install(ContentNegotiation) {
                json(Json.Lenient)
            }
            defaultRequest {
                url("https://www.jma.go.jp/")
            }
        }
    }

    suspend fun areas(): JmaAreaIndex {
        return httpClient.get {
            url { path("bosai", "common", "const", "area.json") }
        }.body()
    }

    suspend fun forecast(officeCode: String): List<JmaForecast> {
        return httpClient.get {
            url { path("bosai", "forecast", "data", "forecast", "$officeCode.json") }
        }.body()
    }

    /** The time of the most recent AMeDAS observation, as an ISO-8601 timestamp. */
    suspend fun latestObservationTime(): String {
        return httpClient.get {
            url { path("bosai", "amedas", "data", "latest_time.txt") }
        }.body<String>().trim()
    }

    /**
     * Three hours worth of observations for one AMeDAS point, keyed by `yyyyMMddHHmmss`.
     * [threeHourBucket] is the `yyyyMMdd_HH` the observations are filed under, where the hour is
     * rounded down to a multiple of three.
     */
    suspend fun observations(point: String, threeHourBucket: String): Map<String, JmaObservation> {
        return httpClient.get {
            url { path("bosai", "amedas", "data", "point", point, "$threeHourBucket.json") }
        }.body()
    }
}
