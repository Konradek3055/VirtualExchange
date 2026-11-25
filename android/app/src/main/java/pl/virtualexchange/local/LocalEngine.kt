package pl.virtualexchange.local

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Lokalny silnik giełdy (tryb offline) - te same zasady co silnik serwera:
 * priorytet cena-czas, cena transakcji = cena zlecenia czekającego, rezerwacja środków,
 * ochrona przed self-trade, BigDecimal (bez floatów). Transakcje powstają wyłącznie z dopasowania zleceń.
 */
class EngineError(message: String) : Exception(message)

class Balance {
    var available: BigDecimal = BigDecimal.ZERO
    var locked: BigDecimal = BigDecimal.ZERO
}

class Account(val id: Int, val name: String, val isBot: Boolean) {
    val balances = LinkedHashMap<String, Balance>()
    fun bal(symbol: String): Balance = balances.getOrPut(symbol) { Balance() }
}

class Asset(
    val symbol: String,
    val name: String,
    val description: String,
    val creatorId: Int?,
    val supply: BigDecimal,
    var lastPrice: BigDecimal,
    val createdAt: Long,
)

class Order(
    val id: Int,
    val accountId: Int,
    val symbol: String,
    val side: String,
    val type: String,
    val quantity: BigDecimal,
    var remaining: BigDecimal,
    val price: BigDecimal?,
    var status: String,
    val createdAt: Long,
)

class Trade(
    val id: Int,
    val symbol: String,
    val price: BigDecimal,
    val quantity: BigDecimal,
    val buyOrderId: Int,
    val sellOrderId: Int,
    val buyerId: Int,
    val sellerId: Int,
    val ts: Long,
)

class Fill(val price: BigDecimal, val quantity: BigDecimal)
class PlaceResult(val order: Order, val fills: List<Fill>)
class BookLevel(val price: BigDecimal, val quantity: BigDecimal)
class Ohlc(val bucketMs: Long, val open: BigDecimal, val high: BigDecimal, val low: BigDecimal,
           val close: BigDecimal, val volume: BigDecimal)

class LocalEngine(var clock: () -> Long = { System.currentTimeMillis() }) {
    companion object {
        const val USD = "USD"
        const val DP = 8
        val MAX_PRICE = BigDecimal("1e10")
        val MAX_QTY = BigDecimal("1e24")
        private const val MAX_TRADES = 20000
        private const val MAX_ORDERS = 4000
        /** Opłata za utworzenie spółki; trafia do puli gospodarki. */
        val LISTING_FEE = BigDecimal("100")
    }

    private val lock = Any()
    val accounts = LinkedHashMap<Int, Account>()
    val assets = LinkedHashMap<String, Asset>()
    val orders = LinkedHashMap<Int, Order>()          // historia + otwarte
    private val open = LinkedHashMap<Int, Order>()    // tylko aktywne (księga)
    val trades = ArrayList<Trade>()
    private val issued = HashMap<String, BigDecimal>()
    private var accountSeq = 0
    private var orderSeq = 0
    private var tradeSeq = 0

    // ---------------------------------------------------------------- konta i aktywa
    fun createAccount(name: String, isBot: Boolean, usd: BigDecimal): Account = synchronized(lock) {
        val a = Account(++accountSeq, name, isBot)
        grant(a, USD, usd)
        accounts[a.id] = a
        a
    }

    /** Emisja środków/aktywa na konto (tylko przy starcie symulacji i tworzeniu aktywa). */
    private fun grant(a: Account, symbol: String, amount: BigDecimal) {
        a.bal(symbol).available += amount
        issued[symbol] = (issued[symbol] ?: BigDecimal.ZERO) + amount
    }

    fun grantAsset(accountId: Int, symbol: String, amount: BigDecimal) = synchronized(lock) {
        grant(accounts[accountId] ?: throw EngineError("Konto nie istnieje"), symbol, amount)
    }

    /** Nowe aktywo. creatorId != null: cała stała podaż trafia do twórcy. refPrice tylko dla aktywów startowych. */
    fun createAsset(creatorId: Int?, symbol: String, name: String, description: String,
                    supply: BigDecimal, refPrice: BigDecimal = BigDecimal.ZERO): Asset = synchronized(lock) {
        val s = symbol.trim().uppercase()
        if (!Regex("[A-Z][A-Z0-9]{1,9}").matches(s) || s == USD) throw EngineError("Niepoprawny ticker (2-10 znaków A-Z/0-9)")
        if (assets.containsKey(s)) throw EngineError("Ticker już istnieje")
        if (name.trim().length !in 2..40) throw EngineError("Nazwa: 2-40 znaków")
        if (description.length > 500) throw EngineError("Opis: max 500 znaków")
        checkDec(supply, "Podaż", MAX_QTY)
        val asset = Asset(s, name.trim(), description, creatorId, supply, refPrice, clock())
        assets[s] = asset
        if (creatorId != null) grant(accounts[creatorId] ?: throw EngineError("Konto nie istnieje"), s, supply)
        asset
    }

    /** Emisja spółki społeczności: ta sama opłata i limit co w backendzie online. */
    fun createUserAsset(accountId: Int, symbol: String, name: String, description: String,
                        supply: BigDecimal): Asset = synchronized(lock) {
        val account = accounts[accountId] ?: throw EngineError("Konto nie istnieje")
        val owned = assets.values.count { it.creatorId == accountId }
        if (owned >= 3) throw EngineError("Limit 3 utworzonych spółek na gracza")
        val fee = LISTING_FEE
        val cash = account.bal(USD)
        if (cash.available < fee) throw EngineError("Opłata emisyjna wynosi 100 USD")
        val asset = createAsset(accountId, symbol, name, description, supply)
        spendUsd(accountId, fee)
        asset
    }

    /** Suma USD na kontach (uwzględnia salda dostępne i zablokowane). */
    fun issuedUsd(): BigDecimal = synchronized(lock) { issued[USD] ?: BigDecimal.ZERO }

    fun spendUsd(accountId: Int, amount: BigDecimal) = synchronized(lock) {
        if (amount.signum() <= 0) throw EngineError("Nieprawidłowa kwota inwestycji")
        val account = accounts[accountId] ?: throw EngineError("Konto nie istnieje")
        val cash = account.bal(USD)
        if (cash.available < amount) throw EngineError("Za mało USD na inwestycję")
        cash.available = cash.available.subtract(amount)
        issued[USD] = (issued[USD] ?: BigDecimal.ZERO).subtract(amount)
    }

    private fun checkDec(v: BigDecimal, field: String, max: BigDecimal) {
        if (v.signum() <= 0 || v > max) throw EngineError("$field: niepoprawna wartość")
        if (v.stripTrailingZeros().scale() > DP) throw EngineError("$field: maksymalnie $DP miejsc po przecinku")
    }

    // ---------------------------------------------------------------- zlecenia
    fun placeOrder(accountId: Int, symbolIn: String, side: String, type: String,
                   quantity: BigDecimal, price: BigDecimal?): PlaceResult = synchronized(lock) {
        val symbol = symbolIn.trim().uppercase()
        val acct = accounts[accountId] ?: throw EngineError("Konto nie istnieje")
        val asset = assets[symbol] ?: throw EngineError("Nieznane aktywo")
        if (side != "buy" && side != "sell") throw EngineError("Zła strona zlecenia")
        if (type != "limit" && type != "market") throw EngineError("Zły typ zlecenia")
        checkDec(quantity, "Ilość", MAX_QTY)
        if (type == "limit") {
            if (price == null) throw EngineError("Zlecenie limit wymaga ceny")
            checkDec(price, "Cena", MAX_PRICE)
        } else if (price != null) throw EngineError("Zlecenie market nie przyjmuje ceny")

        // rezerwacja środków - nie da się wydać więcej niż się ma
        if (side == "sell") {
            val b = acct.bal(symbol)
            if (b.available < quantity) throw EngineError("Za mało $symbol do sprzedaży")
            b.available -= quantity
            b.locked += quantity
        } else if (type == "limit") {
            val need = price!! * quantity
            val b = acct.bal(USD)
            if (b.available < need) throw EngineError("Za mało USD")
            b.available -= need
            b.locked += need
        } else if (acct.bal(USD).available.signum() <= 0) throw EngineError("Brak USD")

        val order = Order(++orderSeq, accountId, symbol, side, type, quantity, quantity, price, "OPEN", clock())
        orders[order.id] = order
        val fills = ArrayList<Fill>()
        var selfTrade = false

        while (order.remaining.signum() > 0) {
            val maker = bestOpposite(symbol, side, price) ?: break
            if (maker.accountId == accountId) { selfTrade = true; break }   // anuluj resztę nowego zlecenia
            val tradePrice = maker.price!!
            var fill = order.remaining.min(maker.remaining)
            if (side == "buy" && type == "market") {
                val affordable = acct.bal(USD).available.divide(tradePrice, DP, RoundingMode.DOWN)
                fill = fill.min(affordable)
                if (fill.signum() <= 0) break
            }
            settle(order, maker, fill, tradePrice, asset)
            fills.add(Fill(tradePrice, fill))
        }

        if (order.remaining.signum() == 0) {
            order.status = "FILLED"
        } else if (type == "market" || selfTrade) {
            releaseLock(order)
            order.status = "CANCELLED"
        } else {
            order.status = if (order.remaining < order.quantity) "PARTIAL" else "OPEN"
            open[order.id] = order
        }
        PlaceResult(order, fills)
    }

    fun cancelOrder(accountId: Int, orderId: Int): Order = synchronized(lock) {
        val o = orders[orderId]
        if (o == null || o.accountId != accountId) throw EngineError("Nie znaleziono zlecenia")
        if (o.status != "OPEN" && o.status != "PARTIAL") throw EngineError("Zlecenie nie jest aktywne")
        releaseLock(o)
        open.remove(o.id)
        o.status = "CANCELLED"
        o
    }

    private fun releaseLock(o: Order) {
        if (o.remaining.signum() <= 0) return
        val acct = accounts[o.accountId]!!
        if (o.side == "sell") {
            val b = acct.bal(o.symbol)
            b.locked -= o.remaining
            b.available += o.remaining
        } else if (o.type == "limit") {
            val amount = o.price!! * o.remaining
            val b = acct.bal(USD)
            b.locked -= amount
            b.available += amount
        }
    }

    private fun bestOpposite(symbol: String, side: String, limit: BigDecimal?): Order? {
        var best: Order? = null
        for (o in open.values) {
            if (o.symbol != symbol) continue
            val p = o.price ?: continue
            if (side == "buy") {
                if (o.side != "sell") continue
                if (limit != null && p > limit) continue
                val bp = best?.price
                if (best == null || p < bp!! || (p.compareTo(bp) == 0 && o.id < best.id)) best = o
            } else {
                if (o.side != "buy") continue
                if (limit != null && p < limit) continue
                val bp = best?.price
                if (best == null || p > bp!! || (p.compareTo(bp) == 0 && o.id < best.id)) best = o
            }
        }
        return best
    }

    private fun settle(taker: Order, maker: Order, qty: BigDecimal, price: BigDecimal, asset: Asset) {
        val buy = if (taker.side == "buy") taker else maker
        val sell = if (taker.side == "buy") maker else taker
        val buyer = accounts[buy.accountId]!!
        val seller = accounts[sell.accountId]!!
        val cost = price * qty
        val usd = buyer.bal(USD)
        if (buy.type == "limit") {
            val reserved = buy.price!! * qty
            usd.locked -= reserved
            usd.available += reserved - cost          // zwrot różnicy (price improvement)
        } else {
            usd.available -= cost
        }
        buyer.bal(asset.symbol).available += qty
        seller.bal(asset.symbol).locked -= qty
        seller.bal(USD).available += cost
        maker.remaining -= qty
        taker.remaining -= qty
        if (maker.remaining.signum() == 0) {
            maker.status = "FILLED"
            open.remove(maker.id)
        } else {
            maker.status = "PARTIAL"
        }
        asset.lastPrice = price
        trades.add(Trade(++tradeSeq, asset.symbol, price, qty, buy.id, sell.id, buy.accountId, sell.accountId, clock()))
    }

    // ---------------------------------------------------------------- odczyty
    fun orderBook(symbol: String, depth: Int = 30): Pair<List<BookLevel>, List<BookLevel>> = synchronized(lock) {
        val bids = TreeMap2()
        val asks = TreeMap2()
        for (o in open.values) {
            if (o.symbol != symbol) continue
            val p = o.price ?: continue
            (if (o.side == "buy") bids else asks).add(p, o.remaining)
        }
        Pair(bids.levels(true).take(depth), asks.levels(false).take(depth))
    }

    private class TreeMap2 {
        private val map = java.util.TreeMap<BigDecimal, BigDecimal>()
        fun add(p: BigDecimal, q: BigDecimal) { map[p] = (map[p] ?: BigDecimal.ZERO) + q }
        fun levels(descending: Boolean): List<BookLevel> {
            val src = if (descending) map.descendingMap() else map
            return src.entries.map { BookLevel(it.key, it.value) }
        }
    }

    fun openOrdersOf(accountId: Int, symbol: String): List<Order> = synchronized(lock) {
        open.values.filter { it.accountId == accountId && it.symbol == symbol }
    }

    fun ordersOf(accountId: Int, limit: Int = 100): List<Order> = synchronized(lock) {
        orders.values.filter { it.accountId == accountId }.sortedByDescending { it.id }.take(limit)
    }

    fun recentTrades(symbol: String, limit: Int = 100): List<Trade> = synchronized(lock) {
        trades.asReversed().filter { it.symbol == symbol }.take(limit)
    }

    fun candles(symbol: String, bucketMs: Long = 60_000L, limit: Int = 100): List<Ohlc> = synchronized(lock) {
        val out = ArrayList<Ohlc>()
        for (t in trades) {
            if (t.symbol != symbol) continue
            val b = t.ts / bucketMs * bucketMs
            val last = out.lastOrNull()
            if (last == null || last.bucketMs != b) {
                out.add(Ohlc(b, t.price, t.price, t.price, t.price, t.quantity))
            } else {
                out[out.size - 1] = Ohlc(b, last.open, last.high.max(t.price), last.low.min(t.price),
                    t.price, last.volume + t.quantity)
            }
        }
        out.takeLast(limit)
    }

    /** Symbole, na których w księdze jest jakakolwiek oferta (tylko tam mają sens zlecenia market botów). */
    fun symbolsWithLiquidity(): List<String> = synchronized(lock) {
        open.values.filter { it.price != null }.map { it.symbol }.distinct()
    }

    fun prune() = synchronized(lock) {
        if (orders.size > MAX_ORDERS) {
            val it = orders.values.iterator()
            while (it.hasNext() && orders.size > MAX_ORDERS * 3 / 4) {
                val o = it.next()
                if (o.status != "OPEN" && o.status != "PARTIAL" && accounts[o.accountId]?.isBot == true) it.remove()
            }
        }
        if (trades.size > MAX_TRADES) trades.subList(0, trades.size - MAX_TRADES * 3 / 4).clear()
    }

    /** Pełny zapis kont, aktywów, sald, zleceń i transakcji. */
    fun snapshot(): JSONObject = synchronized(lock) {
        val accountRows = JSONArray()

        accounts.values.forEach { account ->
            val balanceRows = JSONArray()

            account.balances.forEach { (symbol, balance) ->
                balanceRows.put(JSONObject()
                    .put("symbol", symbol)
                    .put("available", balance.available.toPlainString())
                    .put("locked", balance.locked.toPlainString()))
            }

            accountRows.put(JSONObject()
                .put("id", account.id)
                .put("name", account.name)
                .put("isBot", account.isBot)
                .put("balances", balanceRows))
        }

        val assetRows = JSONArray()

        assets.values.forEach { asset ->
            assetRows.put(JSONObject()
                .put("symbol", asset.symbol)
                .put("name", asset.name)
                .put("description", asset.description)
                .put("creatorId", asset.creatorId ?: JSONObject.NULL)
                .put("supply", asset.supply.toPlainString())
                .put("lastPrice", asset.lastPrice.toPlainString())
                .put("createdAt", asset.createdAt))
        }

        val orderRows = JSONArray()

        orders.values.forEach { order ->
            orderRows.put(JSONObject()
                .put("id", order.id)
                .put("accountId", order.accountId)
                .put("symbol", order.symbol)
                .put("side", order.side)
                .put("type", order.type)
                .put("quantity", order.quantity.toPlainString())
                .put("remaining", order.remaining.toPlainString())
                .put("price", order.price?.toPlainString() ?: JSONObject.NULL)
                .put("status", order.status)
                .put("createdAt", order.createdAt))
        }

        val tradeRows = JSONArray()

        trades.forEach { trade ->
            tradeRows.put(JSONObject()
                .put("id", trade.id)
                .put("symbol", trade.symbol)
                .put("price", trade.price.toPlainString())
                .put("quantity", trade.quantity.toPlainString())
                .put("buyOrderId", trade.buyOrderId)
                .put("sellOrderId", trade.sellOrderId)
                .put("buyerId", trade.buyerId)
                .put("sellerId", trade.sellerId)
                .put("ts", trade.ts))
        }

        val issuedRows = JSONObject()
        issued.forEach { (symbol, amount) ->
            issuedRows.put(symbol, amount.toPlainString())
        }

        JSONObject()
            .put("version", 1)
            .put("accountSeq", accountSeq)
            .put("orderSeq", orderSeq)
            .put("tradeSeq", tradeSeq)
            .put("accounts", accountRows)
            .put("assets", assetRows)
            .put("orders", orderRows)
            .put("trades", tradeRows)
            .put("issued", issuedRows)
    }

    /** Odtwarza stan, w tym otwarte zlecenia i ich blokady. */
    fun restore(snapshot: JSONObject) = synchronized(lock) {
        require(snapshot.optInt("version") == 1) {
            "Nieobsługiwana wersja zapisu offline"
        }

        accounts.clear()
        assets.clear()
        orders.clear()
        open.clear()
        trades.clear()
        issued.clear()

        val accountRows = snapshot.getJSONArray("accounts")

        for (i in 0 until accountRows.length()) {
            val row = accountRows.getJSONObject(i)
            val account = Account(
                row.getInt("id"),
                row.getString("name"),
                row.getBoolean("isBot"),
            )

            val balanceRows = row.getJSONArray("balances")

            for (j in 0 until balanceRows.length()) {
                val balance = balanceRows.getJSONObject(j)
                val b = account.bal(balance.getString("symbol"))

                b.available = BigDecimal(balance.getString("available"))
                b.locked = BigDecimal(balance.getString("locked"))
            }

            accounts[account.id] = account
        }

        val assetRows = snapshot.getJSONArray("assets")

        for (i in 0 until assetRows.length()) {
            val row = assetRows.getJSONObject(i)
            val creator = if (row.isNull("creatorId")) null else row.getInt("creatorId")

            val asset = Asset(
                row.getString("symbol"),
                row.getString("name"),
                row.getString("description"),
                creator,
                BigDecimal(row.getString("supply")),
                BigDecimal(row.getString("lastPrice")),
                row.getLong("createdAt"),
            )

            assets[asset.symbol] = asset
        }

        val orderRows = snapshot.getJSONArray("orders")

        for (i in 0 until orderRows.length()) {
            val row = orderRows.getJSONObject(i)
            val price = if (row.isNull("price")) null
                else BigDecimal(row.getString("price"))

            val order = Order(
                row.getInt("id"),
                row.getInt("accountId"),
                row.getString("symbol"),
                row.getString("side"),
                row.getString("type"),
                BigDecimal(row.getString("quantity")),
                BigDecimal(row.getString("remaining")),
                price,
                row.getString("status"),
                row.getLong("createdAt"),
            )

            orders[order.id] = order

            if (order.status == "OPEN" || order.status == "PARTIAL") {
                open[order.id] = order
            }
        }

        val tradeRows = snapshot.getJSONArray("trades")

        for (i in 0 until tradeRows.length()) {
            val row = tradeRows.getJSONObject(i)

            trades.add(Trade(
                row.getInt("id"),
                row.getString("symbol"),
                BigDecimal(row.getString("price")),
                BigDecimal(row.getString("quantity")),
                row.getInt("buyOrderId"),
                row.getInt("sellOrderId"),
                row.getInt("buyerId"),
                row.getInt("sellerId"),
                row.getLong("ts"),
            ))
        }

        val issuedRows = snapshot.getJSONObject("issued")
        val keys = issuedRows.keys()

        while (keys.hasNext()) {
            val symbol = keys.next()
            issued[symbol] = BigDecimal(issuedRows.getString(symbol))
        }

        accountSeq = snapshot.getInt("accountSeq")
        orderSeq = snapshot.getInt("orderSeq")
        tradeSeq = snapshot.getInt("tradeSeq")

        check(accounts.isNotEmpty() && assets.isNotEmpty()) {
            "Zapis offline jest niekompletny"
        }

        checkInvariants()
    }

    fun holderCount(symbol: String): Int = synchronized(lock) {
        accounts.values.count { account ->
            val balance = account.balances[symbol]
            balance != null &&
                balance.available.add(balance.locked).signum() > 0
        }
    }

    /** Kontrola spójności księgowości (testy): stała podaż, brak ujemnych sald, blokady == aktywne zlecenia. */
    fun checkInvariants() = synchronized(lock) {
        val totals = HashMap<String, BigDecimal>()
        val locks = HashMap<Pair<Int, String>, BigDecimal>()
        for (a in accounts.values) for ((sym, b) in a.balances) {
            check(b.available.signum() >= 0 && b.locked.signum() >= 0) { "ujemne saldo ${a.name}/$sym" }
            totals[sym] = (totals[sym] ?: BigDecimal.ZERO) + b.available + b.locked
            if (b.locked.signum() != 0) locks[Pair(a.id, sym)] = b.locked.stripTrailingZeros()
        }
        for ((sym, amount) in issued) {
            check(totals[sym]?.compareTo(amount) == 0) { "zmiana podaży $sym" }
        }
        val expected = HashMap<Pair<Int, String>, BigDecimal>()
        for (o in open.values) {
            val key = if (o.side == "sell") Pair(o.accountId, o.symbol) else Pair(o.accountId, USD)
            val amount = if (o.side == "sell") o.remaining else o.price!! * o.remaining
            expected[key] = (expected[key] ?: BigDecimal.ZERO) + amount
        }
        val exp = expected.filterValues { it.signum() != 0 }.mapValues { it.value.stripTrailingZeros() }
        check(exp == locks) { "blokady != aktywne zlecenia" }
    }
}
