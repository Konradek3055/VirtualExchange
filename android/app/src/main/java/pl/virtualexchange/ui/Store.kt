package pl.virtualexchange.ui

import androidx.compose.runtime.getValue
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
    var candles by mutableStateOf(listOf<Candle>())
    var selected by mutableStateOf("BTC")
    var interval by mutableStateOf("1m")
    var status by mutableStateOf("Łączenie…")
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
        candles = parseCandles(Api.getArray("candles/$symbol?limit=300&interval=$interval"))
        stats = try {
            Api.getObject("stats")
        } catch (_: Exception) {
            JSONObject()
        }
    }

    suspend fun refreshAccount() {
        try {
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
            status = if (Api.offline) "OFFLINE · symulacja z botami" else "ONLINE · wspólny rynek"
        } catch (error: Exception) {
            status = "BŁĄD RYNKU: ${error.message}"
        }
        refreshAccount()
    }
}
