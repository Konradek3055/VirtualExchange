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

/** Stan całej aplikacji. Dane zawsze pochodzą z serwera - klient niczego nie liczy ani nie ustala cen. */
class Store {
    var assets by mutableStateOf(listOf<JSONObject>())
    var portfolio by mutableStateOf(listOf<JSONObject>())
    var orders by mutableStateOf(listOf<JSONObject>())
    var bids by mutableStateOf(listOf<Level>())
    var asks by mutableStateOf(listOf<Level>())
    var trades by mutableStateOf(listOf<JSONObject>())
    var candles by mutableStateOf(listOf<Candle>())
    var selected by mutableStateOf("BTC")
    var status by mutableStateOf("Łączenie…")
    var accountError by mutableStateOf("")
    var message by mutableStateOf("")

    val lastPrice: String
        get() = assets.firstOrNull { it.optString("symbol") == selected }?.optString("last_price", "0") ?: "0"

    suspend fun refreshMarket() {
        val s = selected
        val book = Api.getObject("orderbook/$s")
        bids = parseLevels(book.optJSONArray("bids"))
        asks = parseLevels(book.optJSONArray("asks"))
        trades = Api.getArray("trades/$s").objects().take(30)
        candles = parseCandles(Api.getArray("candles/$s?limit=60"))
    }

    suspend fun refreshAccount() {
        try {
            portfolio = Api.getArray("portfolio", true).objects()
            orders = Api.getArray("orders", true).objects()
            accountError = ""
        } catch (e: Exception) {
            accountError = e.message ?: "Błąd konta"
        }
    }

    suspend fun refreshAll() {
        try {
            assets = Api.getArray("assets").objects()
            refreshMarket()
            status = if (Api.offline) "OFFLINE · symulacja z botami" else "ONLINE"
        } catch (e: Exception) {
            status = "BŁĄD SERWERA: ${e.message}"
        }
        refreshAccount()
    }
}
