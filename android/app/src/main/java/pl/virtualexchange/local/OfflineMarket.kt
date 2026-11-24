package pl.virtualexchange.local

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Random

/**
 * Tryb offline: symulowany rynek z botami. Boty to zwykli uczestnicy - składają zlecenia przez TEN SAM silnik,
 * a transakcje powstają wyłącznie z dopasowania zleceń (nikt nie rysuje świec ani nie ustawia ceny).
 *  - maker-boty stawiają drabinki zleceń kupna/sprzedaży wokół ostatniej ceny,
 *  - trader-boty wysyłają zlecenia market o różnej wielkości (zdarzają się duże).
 */
class OfflineMarket(seed: Long = System.nanoTime(), restoredState: JSONObject? = null) {
    val engine = LocalEngine()
    val rnd = Random(seed)
    val playerId: Int
    private val makers = ArrayList<Int>()
    private val traders = ArrayList<Int>()
    private val refPrices = LinkedHashMap<String, BigDecimal>()
    private var simNow = System.currentTimeMillis()

    private class Seed(val symbol: String, val name: String, val supply: String, val price: String)

    init {
        if (restoredState == null) {
            playerId = engine.createAccount("Gracz", false, BigDecimal("10000")).id
        val seeds = listOf(
            Seed("BTC", "Bitcoin (symulacja)", "1000", "65000"),
            Seed("ETH", "Ethereum (symulacja)", "10000", "3200"),
            Seed("NVDA", "NVIDIA (symulacja)", "100000", "140"),
            Seed("AAPL", "Apple (symulacja)", "100000", "230"),
        )
        listOf("MM-Alfa", "MM-Beta").forEach { makers.add(engine.createAccount(it, true, BigDecimal("5000000")).id) }
        listOf("Kuba", "Ola", "Wieloryb").forEach { traders.add(engine.createAccount(it, true, BigDecimal("1000000")).id) }
        for (s in seeds) {
            val supply = BigDecimal(s.supply)
            engine.createAsset(null, s.symbol, s.name, "Aktywo startowe (symulacja z botami)", supply, BigDecimal(s.price))
            refPrices[s.symbol] = BigDecimal(s.price)
            val makerShare = supply.multiply(BigDecimal("0.4"))
            val traderShare = supply.multiply(BigDecimal("0.2")).divide(BigDecimal(traders.size), 8, RoundingMode.DOWN)
            makers.forEach { engine.grantAsset(it, s.symbol, makerShare) }
            traders.forEach { engine.grantAsset(it, s.symbol, traderShare) }
        }
        warmUp(WARMUP_STEPS)
        } else {
            require(restoredState.optInt("version") == 1) {
                "Nieobsługiwana wersja zapisu offline"
            }

            engine.restore(restoredState.getJSONObject("engine"))
            playerId = restoredState.getInt("playerId")

            val makerRows = restoredState.getJSONArray("makers")
            for (i in 0 until makerRows.length()) makers.add(makerRows.getInt(i))

            val traderRows = restoredState.getJSONArray("traders")
            for (i in 0 until traderRows.length()) traders.add(traderRows.getInt(i))

            val refs = restoredState.getJSONObject("refPrices")
            val keys = refs.keys()

            while (keys.hasNext()) {
                val symbol = keys.next()
                refPrices[symbol] = BigDecimal(refs.getString(symbol))
            }

            simNow = restoredState.optLong("simNow", System.currentTimeMillis())

            require(makers.isNotEmpty() && traders.isNotEmpty() && refPrices.isNotEmpty()) {
                "Zapis offline nie zawiera konfiguracji botów"
            }
        }
    }

    /** Stan do trwałego zapisu na telefonie. */
    fun snapshot(): JSONObject {
        val makerRows = JSONArray()
        makers.forEach { makerRows.put(it) }

        val traderRows = JSONArray()
        traders.forEach { traderRows.put(it) }

        val refs = JSONObject()
        refPrices.forEach { (symbol, price) ->
            refs.put(symbol, price.toPlainString())
        }

        return JSONObject()
            .put("version", 1)
            .put("playerId", playerId)
            .put("makers", makerRows)
            .put("traders", traderRows)
            .put("refPrices", refs)
            .put("simNow", simNow)
            .put("engine", engine.snapshot())
    }

    /** Symulowana historia: boty handlują "w przyspieszonym czasie", żeby wykres nie był pusty po starcie. */
    private fun warmUp(steps: Int) {
        val stepMs = 20_000L
        simNow = System.currentTimeMillis() - steps * stepMs
        engine.clock = { simNow }
        refPrices.keys.forEach { sym -> makers.forEach { requote(it, sym) } }
        repeat(steps) {
            simNow += stepMs
            step()
        }
        engine.clock = { System.currentTimeMillis() }
    }

    fun step() {
        if (rnd.nextDouble() < 0.5) {
            requote(makers[rnd.nextInt(makers.size)], refPrices.keys.elementAt(rnd.nextInt(refPrices.size)))
        }
        repeat(1 + rnd.nextInt(2)) { traderAction() }
        engine.prune()
    }

    private fun px(v: BigDecimal): BigDecimal = v.setScale(if (v >= BigDecimal.ONE) 2 else 6, RoundingMode.HALF_UP)

    private fun requote(maker: Int, symbol: String) {
        engine.openOrdersOf(maker, symbol).forEach { engine.cancelOrder(maker, it.id) }
        val asset = engine.assets[symbol] ?: return
        val ref = refPrices[symbol] ?: return
        val last = if (asset.lastPrice.signum() > 0) asset.lastPrice else ref
        // losowy dryf + lekki powrót do ceny referencyjnej (żeby cena nie uciekała w nieskończoność)
        val drift = 1.0 + rnd.nextGaussian() * 0.0006
        val fair = last.multiply(BigDecimal(0.99)).add(ref.multiply(BigDecimal(0.01))).multiply(BigDecimal(drift))
        val levelQty = asset.supply.multiply(BigDecimal("0.004")).setScale(4, RoundingMode.DOWN)
        for (i in 1..4) {
            val spread = BigDecimal(0.0015 * i)
            val bid = px(fair.multiply(BigDecimal.ONE.subtract(spread)))
            val ask = px(fair.multiply(BigDecimal.ONE.add(spread)))
            try { engine.placeOrder(maker, symbol, "buy", "limit", levelQty, bid) } catch (e: EngineError) { }
            try { engine.placeOrder(maker, symbol, "sell", "limit", levelQty, ask) } catch (e: EngineError) { }
        }
    }

    private fun traderAction() {
        val symbols = engine.symbolsWithLiquidity()
        if (symbols.isEmpty()) return
        val symbol = symbols[rnd.nextInt(symbols.size)]
        val asset = engine.assets[symbol] ?: return
        val trader = traders[rnd.nextInt(traders.size)]
        var price = asset.lastPrice
        if (price.signum() <= 0) {      // aktywo bez transakcji: odniesienie to najlepsza oferta w księdze
            val (b, a) = engine.orderBook(symbol, 1)
            price = a.firstOrNull()?.price ?: b.firstOrNull()?.price ?: return
        }
        val notional = 400.0 * Math.exp(rnd.nextDouble() * 4.0)          // 400 .. ~22 000 USD, ogon z dużymi zleceniami
        val qty = BigDecimal(notional).divide(price, 4, RoundingMode.DOWN)
        if (qty.signum() <= 0) return
        val side = if (rnd.nextBoolean()) "buy" else "sell"
        try {
            engine.placeOrder(trader, symbol, side, "market", qty, null)
        } catch (e: EngineError) {
            try { engine.placeOrder(trader, symbol, if (side == "buy") "sell" else "buy", "market", qty, null) }
            catch (e2: EngineError) { }
        }
    }

    companion object {
        const val WARMUP_STEPS = 400
    }
}
