package de.mm20.launcher2.weather.jma

import android.content.Context
import android.util.Log
import de.mm20.launcher2.crashreporter.CrashReporter
import de.mm20.launcher2.preferences.weather.WeatherLocation
import de.mm20.launcher2.weather.Forecast
import de.mm20.launcher2.weather.GeocoderWeatherProvider
import de.mm20.launcher2.weather.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

/**
 * Forecasts from the 気象庁 (Japan Meteorological Agency), the national weather service of
 * Japan, through the JSON its own website is built on. No API key, no sign up, and the same
 * numbers every Japanese forecast quotes.
 *
 * JMA does not publish an hourly forecast the way the other providers do. What it publishes is,
 * per 一次細分区域:
 *
 *  - the weather for today, tomorrow and the day after, in half day steps
 *  - the chance of precipitation in six hour steps
 *  - the lowest and the highest temperature of a day, per observation point
 *  - a weekly forecast, one weather code and one temperature range per day
 *
 * That is turned into the series of forecasts the launcher works with by placing the low
 * temperature on the night slots of a day and the high temperature on the day slots. The
 * temperature of a slot in between is therefore not a forecast for that hour, it is the day's
 * low or high, which is all JMA says.
 *
 * The current conditions are the exception: for the day that is running, the AMeDAS observation
 * of the same point is used, which is a real measurement and also carries the humidity, air
 * pressure and wind that no part of the forecast does.
 */
internal class JmaProvider(
    private val context: Context,
) : GeocoderWeatherProvider(context) {

    private val jmaApi = JmaApi()

    override suspend fun getWeatherData(location: WeatherLocation): List<Forecast>? {
        return when (location) {
            is WeatherLocation.Id -> {
                val area = JmaAreas.parseId(location.locationId)
                if (area == null) {
                    Log.e("JmaProvider", "Not a JMA area: ${location.locationId}")
                    return null
                }
                getWeatherData(area.first, area.second, location.name)
            }

            is WeatherLocation.LatLon -> getWeatherData(location.lat, location.lon)

            else -> {
                Log.e("JmaProvider", "Unsupported location type: $location")
                null
            }
        }
    }

    override suspend fun getWeatherData(lat: Double, lon: Double): List<Forecast>? {
        val areas = JmaAreas.get(jmaApi) ?: return null
        val address = getAddress(lat, lon)
        val place = areas.resolve(
            prefecture = address?.adminArea,
            municipality = address?.locality ?: address?.subAdminArea,
        )
        if (place == null) {
            Log.e("JmaProvider", "No JMA area covers $lat, $lon")
            return null
        }
        return getWeatherData(place.office, place.class10, place.name)
    }

    override suspend fun findLocation(query: String): List<WeatherLocation> {
        val areas = JmaAreas.get(jmaApi) ?: return emptyList()
        return withContext(Dispatchers.Default) {
            areas.search(query).map {
                WeatherLocation.Id(name = it.name, locationId = it.id)
            }
        }
    }

    private suspend fun getWeatherData(
        office: String,
        class10: String,
        locationName: String,
    ): List<Forecast>? = withContext(Dispatchers.IO) {
        val blocks = try {
            jmaApi.forecast(office)
        } catch (e: Exception) {
            CrashReporter.logException(e)
            return@withContext null
        }

        val shortTerm = blocks.getOrNull(0) ?: return@withContext null
        val weekly = blocks.getOrNull(1)
        val updateTime = parseTime(shortTerm.reportDatetime) ?: System.currentTimeMillis()

        val days = mutableMapOf<LocalDate, Day>()

        val areaName = readWeather(shortTerm.timeSeries.getOrNull(0), class10, days)
        readPops(shortTerm.timeSeries.getOrNull(1), class10, days)
        val point = readTemperatures(shortTerm.timeSeries.getOrNull(2), areaName, days)

        readWeeklyWeather(weekly?.timeSeries?.getOrNull(0), class10, days)
        val weeklyPoint = readWeeklyTemperatures(weekly?.timeSeries?.getOrNull(1), areaName, days)

        val observed = readObservation(point ?: weeklyPoint)

        val forecasts = days.entries
            .sortedBy { it.key }
            .flatMap { (date, day) -> day.toForecasts(date, observed, locationName, office, updateTime) }

        if (forecasts.isEmpty()) null else forecasts
    }

    /**
     * The weather codes, in half day steps. Returns the name of the area they were read from,
     * which is what the temperature point is picked by.
     */
    private fun readWeather(
        series: JmaTimeSeries?,
        class10: String,
        days: MutableMap<LocalDate, Day>,
    ): String? {
        val area = series?.areas?.pick(class10) ?: return null
        val codes = area.weatherCodes ?: return area.area.name
        val texts = area.weathers
        for ((i, time) in series.timeDefines.withIndex()) {
            val timestamp = parseTime(time) ?: continue
            val code = codes.getOrNull(i)?.toIntOrNull() ?: continue
            days.of(timestamp).weather[timestamp] = Weather(code, texts?.getOrNull(i))
        }
        return area.area.name
    }

    private fun readPops(
        series: JmaTimeSeries?,
        class10: String,
        days: MutableMap<LocalDate, Day>,
    ) {
        val area = series?.areas?.pick(class10) ?: return
        val pops = area.pops ?: return
        for ((i, time) in series.timeDefines.withIndex()) {
            val timestamp = parseTime(time) ?: continue
            val pop = pops.getOrNull(i)?.toIntOrNull() ?: continue
            days.of(timestamp).pops[timestamp] = pop
        }
    }

    /**
     * The temperatures of today and tomorrow. Each one is a reading for a point in time: the
     * low of a day is filed at night, the high in the morning.
     */
    private fun readTemperatures(
        series: JmaTimeSeries?,
        areaName: String?,
        days: MutableMap<LocalDate, Day>,
    ): String? {
        val area = series?.areas?.nearest(areaName) ?: return null
        val temps = area.temps ?: return area.area.code
        for ((i, time) in series.timeDefines.withIndex()) {
            val timestamp = parseTime(time) ?: continue
            val temperature = temps.getOrNull(i)?.toDoubleOrNull() ?: continue
            days.of(timestamp).temperatures[timestamp] = temperature
        }
        return area.area.code
    }

    private fun readWeeklyWeather(
        series: JmaTimeSeries?,
        class10: String,
        days: MutableMap<LocalDate, Day>,
    ) {
        val area = series?.areas?.pick(class10) ?: return
        for ((i, time) in series.timeDefines.withIndex()) {
            val timestamp = parseTime(time) ?: continue
            val day = days.of(timestamp)
            area.weatherCodes?.getOrNull(i)?.toIntOrNull()?.let {
                if (day.weather.isEmpty()) day.weather[timestamp] = Weather(it, null)
            }
            area.pops?.getOrNull(i)?.toIntOrNull()?.let {
                if (day.pops.isEmpty()) day.pops[timestamp] = it
            }
        }
    }

    private fun readWeeklyTemperatures(
        series: JmaTimeSeries?,
        areaName: String?,
        days: MutableMap<LocalDate, Day>,
    ): String? {
        val area = series?.areas?.nearest(areaName) ?: return null
        for ((i, time) in series.timeDefines.withIndex()) {
            val timestamp = parseTime(time) ?: continue
            val day = days.of(timestamp)
            area.tempsMin?.getOrNull(i)?.toDoubleOrNull()?.let { day.min = day.min ?: it }
            area.tempsMax?.getOrNull(i)?.toDoubleOrNull()?.let { day.max = day.max ?: it }
        }
        return area.area.code
    }

    /**
     * The most recent measurement of an AMeDAS point, if it is recent enough to pass for the
     * current conditions.
     */
    private suspend fun readObservation(point: String?): Observed? {
        val station = point ?: return null
        return try {
            val time = OffsetDateTime.parse(jmaApi.latestObservationTime())
            val bucket = String.format(
                Locale.ROOT,
                "%04d%02d%02d_%02d",
                time.year, time.monthValue, time.dayOfMonth, time.hour / 3 * 3,
            )
            val observations = jmaApi.observations(station, bucket)
            val latest = observations.maxByOrNull { it.key } ?: return null
            val temperature = latest.value.temperature ?: return null
            Observed(time.toInstant().toEpochMilli(), temperature, latest.value)
        } catch (e: Exception) {
            // Observations are a bonus, a forecast without them is still worth showing
            CrashReporter.logException(e)
            null
        }
    }

    private class Weather(val code: Int, val text: String?)

    private class Observed(
        val timestamp: Long,
        val temperature: Double,
        val values: JmaObservation,
    )

    private class Day {
        val weather = sortedMapOf<Long, Weather>()
        val pops = sortedMapOf<Long, Int>()
        val temperatures = sortedMapOf<Long, Double>()
        var min: Double? = null
        var max: Double? = null

        /**
         * The slots to report. Those JMA gives values for, plus a morning and an afternoon slot
         * if it only gave a low and a high for the whole day, so that the daily low and high
         * both end up in the series.
         */
        fun slots(date: LocalDate): List<Long> {
            val slots = sortedSetOf<Long>()
            slots.addAll(weather.keys)
            slots.addAll(pops.keys)
            slots.addAll(temperatures.keys)
            val low = min ?: temperatures.values.minOrNull()
            val high = max ?: temperatures.values.maxOrNull()
            if (low != null && high != null && low != high) {
                if (slots.none { hourOf(it) < DayStart }) slots.add(timestampOf(date, NightSlot))
                if (slots.none { hourOf(it) >= DayStart }) slots.add(timestampOf(date, DaySlot))
            }
            return slots.toList()
        }

        fun temperatureAt(slot: Long): Double? {
            temperatures[slot]?.let { return it }
            val low = min ?: temperatures.values.minOrNull()
            val high = max ?: temperatures.values.maxOrNull()
            return if (hourOf(slot) in DayStart until DayEnd) high ?: low else low ?: high
        }

        fun weatherAt(slot: Long): Weather? =
            weather.headMap(slot + 1).values.lastOrNull() ?: weather.values.firstOrNull()

        fun popAt(slot: Long): Int? = pops.headMap(slot + 1).values.lastOrNull()
    }

    private fun Day.toForecasts(
        date: LocalDate,
        observed: Observed?,
        locationName: String,
        office: String,
        updateTime: Long,
    ): List<Forecast> {
        // The observation is a slot of its own, so that the widget, which shows the last
        // forecast before now, shows a measurement rather than a half day old prediction.
        val observedToday = observed?.takeIf { dateOf(it.timestamp) == date }
        if (observedToday != null) {
            observedToday.values.minTemp?.firstOrNull()?.let { if (min == null) min = it }
            observedToday.values.maxTemp?.firstOrNull()?.let { if (max == null) max = it }
            temperatures[observedToday.timestamp] = observedToday.temperature
        }

        val forecasts = mutableListOf<Forecast>()
        for (slot in slots(date)) {
            val temperature = temperatureAt(slot) ?: continue
            val measured = observedToday?.values?.takeIf { observedToday.timestamp == slot }
            forecasts.add(
                forecast(
                    timestamp = slot,
                    temperature = temperature,
                    weather = weatherAt(slot),
                    precipProbability = popAt(slot),
                    locationName = locationName,
                    office = office,
                    updateTime = updateTime,
                    humidity = measured?.humidity?.firstOrNull(),
                    pressure = measured?.pressure?.firstOrNull(),
                    windSpeed = measured?.wind?.firstOrNull(),
                    windDirection = measured?.windDirection?.firstOrNull()
                        ?.takeIf { it > 0 }?.let { it * 22.5 },
                    precipitation = measured?.precipitation1h?.firstOrNull(),
                )
            )
        }
        return forecasts
    }

    private fun forecast(
        timestamp: Long,
        temperature: Double,
        weather: Weather?,
        precipProbability: Int?,
        locationName: String,
        office: String,
        updateTime: Long,
        humidity: Double? = null,
        pressure: Double? = null,
        windSpeed: Double? = null,
        windDirection: Double? = null,
        precipitation: Double? = null,
    ): Forecast {
        val icon = weather?.let { iconOf(it.code) } ?: Forecast.UNKNOWN
        return Forecast(
            timestamp = timestamp,
            temperature = temperature + KelvinOffset,
            icon = icon,
            condition = conditionOf(weather, icon),
            night = hourOf(timestamp).let { it < DayStart || it >= DayEnd },
            location = locationName,
            provider = context.getString(R.string.provider_jma),
            providerUrl = "https://www.jma.go.jp/bosai/forecast/#area_type=offices&area_code=$office",
            precipProbability = precipProbability,
            humidity = humidity,
            pressure = pressure,
            windSpeed = windSpeed,
            windDirection = windDirection,
            precipitation = precipitation,
            updateTime = updateTime,
        )
    }

    /**
     * JMA spells the weather out in Japanese, in more detail than an icon can carry
     * ("くもり時々晴"). That is worth keeping for someone reading Japanese; everyone else gets
     * the condition the icon stands for, translated like every other provider's.
     */
    private fun conditionOf(weather: Weather?, icon: Int): String {
        val text = weather?.text?.replace('　', ' ')?.trim()?.replace(Regex(" +"), " ")
        if (!text.isNullOrEmpty() && Locale.getDefault().language == "ja") return text
        return context.getString(
            when (icon) {
                Forecast.CLEAR -> R.string.weather_condition_clearsky
                Forecast.PARTLY_CLOUDY -> R.string.weather_condition_partlycloudy
                Forecast.OVERCAST -> R.string.weather_condition_cloudy
                Forecast.FOG -> R.string.weather_condition_fog
                Forecast.WIND -> R.string.weather_condition_wind
                Forecast.LIGHT_RAIN -> R.string.weather_condition_lightrain
                Forecast.RAIN -> R.string.weather_condition_rain
                Forecast.HEAVY_RAIN -> R.string.weather_condition_heavyrain
                Forecast.SLEET -> R.string.weather_condition_sleet
                Forecast.SNOW -> R.string.weather_condition_snow
                Forecast.THUNDERSTORM -> R.string.weather_condition_thunderstorm
                else -> R.string.weather_condition_cloudy
            }
        )
    }

    /**
     * The area a forecast is for. JMA repeats an area only when a forecast really differs, so
     * the first area is the one to fall back to.
     */
    private fun List<JmaAreaForecast>.pick(class10: String): JmaAreaForecast? =
        firstOrNull { it.area.code == class10 } ?: firstOrNull()

    /**
     * Temperatures are given per observation point, not per forecast area, and the two do not
     * line up. The point whose name the forecast area is named after is the right one; failing
     * that, the first point is the prefecture's main one.
     */
    private fun List<JmaAreaForecast>.nearest(areaName: String?): JmaAreaForecast? {
        if (areaName == null) return firstOrNull()
        return firstOrNull { areaName.startsWith(it.area.name) || it.area.name.startsWith(areaName) }
            ?: firstOrNull()
    }

    private fun MutableMap<LocalDate, Day>.of(timestamp: Long): Day =
        getOrPut(dateOf(timestamp)) { Day() }

    companion object {
        internal const val Id = "jma"

        private const val KelvinOffset = 273.15

        /** Japan is one time zone, and JMA reports in it. */
        private val Zone: ZoneId = ZoneId.of("Asia/Tokyo")

        /** When the day's high is reported instead of its low. */
        private const val DayStart = 9
        private const val DayEnd = 18

        private const val NightSlot = 6
        private const val DaySlot = 15

        private fun parseTime(time: String?): Long? {
            time ?: return null
            return try {
                OffsetDateTime.parse(time).toInstant().toEpochMilli()
            } catch (e: Exception) {
                null
            }
        }

        private fun dateOf(timestamp: Long): LocalDate =
            Instant.ofEpochMilli(timestamp).atZone(Zone).toLocalDate()

        private fun hourOf(timestamp: Long): Int =
            Instant.ofEpochMilli(timestamp).atZone(Zone).hour

        private fun timestampOf(date: LocalDate, hour: Int): Long =
            ZonedDateTime.of(date, LocalTime.of(hour, 0), Zone).toInstant().toEpochMilli()

        /**
         * JMA weather codes are three digits, the first of which is the base condition: 1 晴,
         * 2 曇, 3 雨, 4 雪. The rest spells out how the day develops (晴のち曇, 曇一時雨), which
         * is more than an icon can carry. Listed below are the codes where the base condition
         * alone would be misleading; anything else falls back to it, so a code JMA adds later
         * still shows something sensible.
         */
        internal fun iconOf(code: Int): Int {
            when (code) {
                209, 231 -> return Forecast.FOG
                308, 406, 407 -> return Forecast.WIND
                306, 328 -> return Forecast.HEAVY_RAIN
                108, 119, 125, 140, 208, 219, 240, 250, 350, 450 -> return Forecast.THUNDERSTORM
            }
            return when (code) {
                // 雨か雪, みぞれ
                106, 107, 118, 160, 170, 181,
                206, 207, 218, 260, 270, 281,
                303, 304, 309, 314, 315, 316, 317, 322, 326, 327, 329, 340, 361, 371,
                403, 409, 414, 422, 423, 426, 427,
                    -> Forecast.SLEET
                // 晴/曇 with snow
                104, 105, 115, 116, 117, 124,
                204, 205, 215, 216, 217, 228, 229, 230,
                    -> Forecast.SNOW
                // 晴/曇 turning to rain
                114, 214 -> Forecast.RAIN
                // 晴/曇 with passing rain
                102, 103, 112, 113, 120, 121, 122, 126, 127, 128,
                202, 203, 212, 213, 220, 221, 222, 224, 225, 226,
                    -> Forecast.LIGHT_RAIN

                100, 123, 130, 131 -> Forecast.CLEAR
                101, 110, 111, 132, 201, 210, 211, 223 -> Forecast.PARTLY_CLOUDY

                else -> when (code / 100) {
                    1 -> Forecast.CLEAR
                    2 -> Forecast.OVERCAST
                    3 -> Forecast.RAIN
                    4 -> Forecast.SNOW
                    else -> Forecast.UNKNOWN
                }
            }
        }
    }
}
