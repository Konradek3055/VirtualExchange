package pl.virtualexchange.local

import org.json.JSONArray
import org.json.JSONObject
import pl.virtualexchange.data.ApiError
import java.math.BigDecimal
import java.math.RoundingMode
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
                method == "GET" && route == "me" -> JSONObject()
                    .put("id", m.playerId)
                    .put("username", e.accounts[m.playerId]?.name ?: "Gracz")
                    .toString()

                method == "GET" && route == "bots" -> m.botOverview().toString()

                method == "GET" && parts[0] == "companies" && parts.size == 3 && parts[2] == "development" ->
                    m.getCompanyDevelopment(parts[1]).toString()

                method == "POST" && parts[0] == "companies" && parts.size == 3 && parts[2] == "upgrade" -> {
                    val b = body ?: bad("Brak danych")
                    m.upgradeCompany(parts[1], b.optString("branch")).toString()
                }

                method == "POST" && parts[0] == "companies" && parts.size == 3 && parts[2] == "capital" -> {
                    val b = body ?: bad("Brak danych")
                    m.fundCompany(parts[1], b.optString("amount")).toString()
                }

                method == "POST" && parts[0] == "companies" && parts.size == 3 && parts[2] == "dividend" -> {
                    val b = body ?: bad("Brak danych")
                    m.withdrawCompany(parts[1], b.optString("amount")).toString()
                }

                method == "POST" && parts[0] == "companies" && parts.size == 3 && parts[2] == "staff" -> {
                    val b = body ?: bad("Brak danych")
                    m.manageCompanyStaff(parts[1], b.optString("action"), b.optInt("count", 1)).toString()
                }

                method == "GET" && route == "stats" -> {
                    val cutoff = m.clockMs - 86_400_000L
                    val out = JSONObject()

                    e.assets.values.forEach { asset ->
                        val recent = e.recentTrades(asset.symbol, Int.MAX_VALUE)
                            .filter { it.ts >= cutoff }
                            .sortedBy { it.id }

                        var volume = BigDecimal.ZERO
                        recent.forEach { trade ->
                            volume = volume.add(trade.price.multiply(trade.quantity))
                        }

                        val first = recent.firstOrNull()?.price
                        val last = recent.lastOrNull()?.price
                        val high = recent.maxOfOrNull { it.price }
                        val low = recent.minOfOrNull { it.price }

                        val change = if (first != null && first.signum() > 0 && last != null) {
                            last.subtract(first)
                                .multiply(BigDecimal("100"))
                                .divide(first, 8, RoundingMode.HALF_UP)
                                .toDouble()
                        } else 0.0

                        val holders = e.holderCount(asset.symbol)

                        out.put(asset.symbol, JSONObject()
                            .put("change_pct", Math.round(change * 10000.0) / 10000.0)
                            .put(
                                "volume_usd",
                                if (recent.isEmpty()) "0"
                                else volume.stripTrailingZeros().toPlainString()
                            )
                            .put("high", high?.toPlainString() ?: JSONObject.NULL)
                            .put("low", low?.toPlainString() ?: JSONObject.NULL)
                            .put("trades", recent.size)
                            .put("holders", holders))
                    }

                    out.toString()
                }

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
                    val iv = query.split("&").firstOrNull { it.startsWith("interval=") }
                        ?.removePrefix("interval=") ?: "1m"
                    val bucketMs = Regex("""(\d+)([mhd])""").matchEntire(iv)?.let { m ->
                        m.groupValues[1].toLong() * when (m.groupValues[2]) {
                            "m" -> 60_000L
                            "h" -> 3_600_000L
                            else -> 86_400_000L
                        }
                    } ?: 60_000L
                    JSONArray().also { arr ->
                        e.candles(parts[1].uppercase(), bucketMs, limit).forEach {
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
                    val asset = e.createUserAsset(m.playerId, b.optString("symbol"), b.optString("name"),
                        b.optString("description"), dec(b.optString("total_supply"), "Podaż"))
                    m.registerCreatedCompany(asset)
                    assetJson(asset).toString()
                }

                else -> bad("Nie znaleziono", 404)
            }
        } catch (ex: EngineError) {
            bad(ex.message ?: "Błąd")
        }
    }
}
