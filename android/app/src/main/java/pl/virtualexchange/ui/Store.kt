package pl.virtualexchange.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject
import pl.virtualexchange.data.Api
import pl.virtualexchange.data.Candle
import pl.virtualexchange.data.Level
import pl.virtualexchange.data.objects
import pl.virtualexchange.data.parseCandles
import pl.virtualexchange.data.parseLevels

/** Dane prezentowane w UI są pobierane z lokalnego silnika lub wspólnego serwera. */
class Store {
    var assets by mutableStateOf(listOf<JSONObject>())
    var stats by mutableStateOf(JSONObject())
    var portfolio by mutableStateOf(listOf<JSONObject>())
    var orders by mutableStateOf(listOf<JSONObject>())
    var bids by mutableStateOf(listOf<Level>())
    var asks by mutableStateOf(listOf<Level>())
    var trades by mutableStateOf(listOf<JSONObject>())
    var bots by mutableStateOf(listOf<JSONObject>())
    var candles by mutableStateOf(listOf<Candle>())
    var selected by mutableStateOf("BTC")
    var interval by mutableStateOf("1m")
    var indicators by mutableStateOf(IndicatorSet())
    var tool by mutableStateOf(DrawTool.NONE)
    var chartType by mutableStateOf(ChartType.CANDLES)
    val spark = mutableStateMapOf<String, List<Double>>()
    private var sparkAt = 0L
    val drawings = mutableStateMapOf<String, List<Drawing>>()
    var status by mutableStateOf("Łączenie…")
    var userId by mutableStateOf(-1)
    var username by mutableStateOf("")
    var accountError by mutableStateOf("")
    var message by mutableStateOf("")

    val lastPrice: String
        get() = assets.firstOrNull { it.optString("symbol") == selected }?.optString("last_price", "0") ?: "0"

    suspend fun refreshMarket() {
        val symbol = selected
        val book = Api.getObject("orderbook/$symbol")
        bids = parseLevels(book.optJSONArray("bids"))
        asks = parseLevels(book.optJSONArray("asks"))
        trades = Api.getArray("trades/$symbol").objects().take(30)
        val iv = interval
        candles = fillCandles(
            parseCandles(Api.getArray("candles/$symbol?limit=2000&interval=$iv")),
            TvIntervals.seconds(iv),
        )
        stats = try {
            Api.getObject("stats")
        } catch (_: Exception) {
            JSONObject()
        }
    }

    /** Mini-wykresy na liście rynku. */
    private suspend fun refreshSparks() {
        val now = System.currentTimeMillis()
        if (now - sparkAt < 30_000L) return
        sparkAt = now
        for (a in assets.take(32)) {
            val symbol = a.optString("symbol")
            try {
                val list = fillCandles(
                    parseCandles(Api.getArray("candles/$symbol?limit=60&interval=5m")), 300
                )
                if (list.size >= 2) spark[symbol] = list.takeLast(48).map { it.close }
            } catch (_: Exception) {
                // Miniwykres jest opcjonalny.
            }
        }
    }

    suspend fun refreshAccount() {
        try {
            val identity = Api.getObject("me", auth = true)
            userId = identity.optInt("id", -1)
            username = identity.optString("username", "")
            portfolio = Api.getArray("portfolio", true).objects()
            orders = Api.getArray("orders", true).objects()
            accountError = ""
        } catch (error: Exception) {
            accountError = error.message ?: "Błąd konta"
        }
    }

    suspend fun refreshAll() {
        try {
            assets = Api.getArray("assets").objects()
            if (assets.none { it.optString("symbol") == selected } && assets.isNotEmpty()) {
                selected = assets.first().optString("symbol")
            }
            refreshMarket()
            bots = if (Api.offline) {
                try { Api.getArray("bots").objects() } catch (_: Exception) { emptyList() }
            } else emptyList()
            try { refreshSparks() } catch (_: Exception) {}
            status = if (Api.offline) "OFFLINE · symulacja z botami" else "ONLINE · wspólny rynek"
        } catch (error: Exception) {
            status = "BŁĄD RYNKU: ${error.message}"
        }
        refreshAccount()
    }
}
