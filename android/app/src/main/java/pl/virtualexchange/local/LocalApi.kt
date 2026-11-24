package pl.virtualexchange.local

import org.json.JSONArray
import org.json.JSONObject
import pl.virtualexchange.data.ApiError
import java.math.BigDecimal
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Odpowiada na te same ścieżki co serwer (te same kształty JSON), dzięki czemu cały interfejs działa bez zmian. */
object LocalApi {
    private fun d(v: BigDecimal): String = v.stripTrailingZeros().toPlainString()

    private fun time(ms: Long): String {
        val f = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        f.timeZone = TimeZone.getTimeZone("UTC")
        return f.format(Date(ms))
    }

    private fun bad(msg: String, code: Int = 400): Nothing =
        throw ApiError(JSONObject().put("detail", msg).toString(), code)

    private fun dec(s: String?, field: String): BigDecimal =
        try { BigDecimal((s ?: "").trim()) } catch (e: NumberFormatException) { bad("$field: niepoprawna liczba") }

    private fun assetJson(a: Asset) = JSONObject()
        .put("symbol", a.symbol).put("name", a.name).put("last_price", d(a.lastPrice))
        .put("description", a.description).put("total_supply", d(a.supply))
        .put("created_at", time(a.createdAt))
        .put("creator_id", a.creatorId ?: JSONObject.NULL)

    private fun orderJson(o: Order) = JSONObject()
        .put("id", o.id).put("account_id", o.accountId).put("symbol", o.symbol).put("side", o.side)
        .put("order_type", o.type).put("quantity", d(o.quantity)).put("remaining", d(o.remaining))
        .put("price", o.price?.let { d(it) } ?: JSONObject.NULL)
        .put("status", o.status).put("created_at", time(o.createdAt))

    fun handle(m: OfflineMarket, method: String, path: String, body: JSONObject?): String {
        val e = m.engine
        val route = path.substringBefore("?").trim('/')
        val query = path.substringAfter("?", "")
        val parts = route.split("/")
        try {
            return when {
                method == "GET" && route == "assets" ->
                    JSONArray().also { arr -> e.assets.values.sortedBy { it.symbol }.forEach { arr.put(assetJson(it)) } }.toString()

                method == "GET" && route == "portfolio" -> {
                    val arr = JSONArray()
                    val acct = e.accounts[m.playerId]!!
                    acct.balances.entries.sortedBy { it.key }.forEach { (sym, b) ->
                        if (sym == LocalEngine.USD || b.available.signum() != 0 || b.locked.signum() != 0) {
                            val asset = e.assets[sym]
                            arr.put(JSONObject().put("symbol", sym)
                                .put("available", d(b.available)).put("locked", d(b.locked))
                                .put("last_price", asset?.let { d(it.lastPrice) } ?: JSONObject.NULL)
                                .put("name", asset?.name ?: JSONObject.NULL))
                        }
                    }
                    arr.toString()
                }

                method == "GET" && route == "orders" ->
                    JSONArray().also { arr -> e.ordersOf(m.playerId).forEach { arr.put(orderJson(it)) } }.toString()

                method == "GET" && parts[0] == "orderbook" && parts.size == 2 -> {
                    val sym = parts[1].uppercase()
                    if (!e.assets.containsKey(sym)) bad("Nieznane aktywo", 404)
                    val (bids, asks) = e.orderBook(sym)
                    fun lv(l: List<BookLevel>) = JSONArray().also { a ->
                        l.forEach { a.put(JSONObject().put("price", d(it.price)).put("quantity", d(it.quantity))) }
                    }
                    JSONObject().put("symbol", sym).put("bids", lv(bids)).put("asks", lv(asks)).toString()
                }

                method == "GET" && parts[0] == "trades" && parts.size == 2 ->
                    JSONArray().also { arr ->
                        e.recentTrades(parts[1].uppercase()).forEach {
                            arr.put(JSONObject().put("id", it.id).put("symbol", it.symbol)
                                .put("price", d(it.price)).put("quantity", d(it.quantity))
                                .put("created_at", time(it.ts)))
                        }
                    }.toString()

                method == "GET" && parts[0] == "candles" && parts.size == 2 -> {
                    val limit = query.split("&").firstOrNull { it.startsWith("limit=") }
                        ?.removePrefix("limit=")?.toIntOrNull()?.coerceIn(1, 500) ?: 100
                    JSONArray().also { arr ->
                        e.candles(parts[1].uppercase(), 60_000L, limit).forEach {
                            arr.put(JSONObject().put("time", time(it.bucketMs).substring(0, 16))
                                .put("open", it.open.toDouble()).put("high", it.high.toDouble())
                                .put("low", it.low.toDouble()).put("close", it.close.toDouble())
                                .put("volume", it.volume.toDouble()))
                        }
                    }.toString()
                }

                method == "POST" && route == "orders" -> {
                    val b = body ?: bad("Brak danych")
                    val price = if (b.isNull("price")) null else dec(b.optString("price"), "Cena")
                    val r = e.placeOrder(m.playerId, b.optString("symbol"), b.optString("side"),
                        b.optString("order_type"), dec(b.optString("quantity"), "Ilość"), price)
                    JSONObject().put("order_id", r.order.id).put("status", r.order.status)
                        .put("remaining", d(r.order.remaining)).put("symbol", r.order.symbol)
                        .put("trades", JSONArray().also { a ->
                            r.fills.forEach { a.put(JSONObject().put("price", d(it.price)).put("quantity", d(it.quantity))) }
                        }).toString()
                }

                method == "DELETE" && parts[0] == "orders" && parts.size == 2 -> {
                    val o = e.cancelOrder(m.playerId, parts[1].toIntOrNull() ?: bad("Zły numer zlecenia"))
                    JSONObject().put("ok", true).put("symbol", o.symbol).toString()
                }

                method == "POST" && route == "assets" -> {
                    val b = body ?: bad("Brak danych")
                    assetJson(e.createAsset(m.playerId, b.optString("symbol"), b.optString("name"),
                        b.optString("description"), dec(b.optString("total_supply"), "Podaż"))).toString()
                }

                else -> bad("Nie znaleziono", 404)
            }
        } catch (ex: EngineError) {
            bad(ex.message ?: "Błąd")
        }
    }
}
