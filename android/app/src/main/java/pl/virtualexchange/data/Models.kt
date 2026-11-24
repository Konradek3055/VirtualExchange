package pl.virtualexchange.data

import org.json.JSONArray
import org.json.JSONObject

data class Level(val price: String, val qty: String)

data class Candle(
    val time: String,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double,
)

fun JSONArray?.objects(): List<JSONObject> =
    if (this == null) emptyList() else (0 until length()).map { getJSONObject(it) }

fun parseLevels(a: JSONArray?): List<Level> =
    a.objects().map { Level(it.optString("price"), it.optString("quantity")) }

fun parseCandles(a: JSONArray?): List<Candle> = a.objects().map {
    Candle(
        it.optString("time"), it.optDouble("open"), it.optDouble("high"),
        it.optDouble("low"), it.optDouble("close"), it.optDouble("volume"),
    )
}
