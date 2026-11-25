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
    private val companyDevelopment = LinkedHashMap<String, JSONObject>()
    private var simNow = System.currentTimeMillis()

    private class Seed(val symbol: String, val name: String, val supply: String, val price: String)

    private val starterSeeds = listOf(
        Seed("BTC", "Bitcoin (symulacja)", "1000", "65000"),
        Seed("ETH", "Ethereum (symulacja)", "10000", "3200"),
        Seed("NVDA", "NVIDIA (symulacja)", "100000", "140"),
        Seed("AAPL", "Apple (symulacja)", "100000", "230"),
        Seed("MSFT", "Microsoft (symulacja)", "100000", "425"),
        Seed("GOOGL", "Alphabet (symulacja)", "100000", "175"),
        Seed("AMZN", "Amazon (symulacja)", "100000", "185"),
        Seed("META", "Meta Platforms (symulacja)", "100000", "520"),
        Seed("TSLA", "Tesla (symulacja)", "100000", "250"),
        Seed("AMD", "AMD (symulacja)", "100000", "160"),
        Seed("INTC", "Intel (symulacja)", "100000", "30"),
        Seed("JPM", "JPMorgan Chase (symulacja)", "100000", "225"),
        Seed("KO", "Coca-Cola (symulacja)", "100000", "63"),
        Seed("DIS", "Disney (symulacja)", "100000", "105"),
        Seed("NFLX", "Netflix (symulacja)", "100000", "900"),
        Seed("ORCL", "Oracle (symulacja)", "100000", "170"),
    )

    /** Dodaje brakujące instrumenty bez wyzerowania istniejącego portfela i historii. */
    private fun ensureStarterAssets(): List<String> {
        val added = ArrayList<String>()
        for (seed in starterSeeds) {
            if (engine.assets.containsKey(seed.symbol)) continue
            val supply = BigDecimal(seed.supply)
            val reference = BigDecimal(seed.price)
            engine.createAsset(null, seed.symbol, seed.name,
                "Aktywo startowe (symulacja z botami)", supply, reference)
            refPrices[seed.symbol] = reference
            val makerShare = supply.multiply(BigDecimal("0.4"))
            val traderShare = supply.multiply(BigDecimal("0.2"))
                .divide(BigDecimal(traders.size), 8, RoundingMode.DOWN)
            makers.forEach { engine.grantAsset(it, seed.symbol, makerShare) }
            traders.forEach { engine.grantAsset(it, seed.symbol, traderShare) }
            added.add(seed.symbol)
        }
        return added
    }

    init {
        if (restoredState == null) {
            playerId = engine.createAccount("Gracz", false, BigDecimal("10000")).id
        listOf("MM-Alfa", "MM-Beta").forEach { makers.add(engine.createAccount(it, true, BigDecimal("5000000")).id) }
        listOf("Kuba", "Ola", "Wieloryb").forEach { traders.add(engine.createAccount(it, true, BigDecimal("1000000")).id) }
        ensureStarterAssets()
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
            val savedDevelopment = restoredState.optJSONObject("companyDevelopment")
            if (savedDevelopment != null) {
                val keys = savedDevelopment.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    companyDevelopment[key] = savedDevelopment.getJSONObject(key)
                }
            }
            // Migracja starych zapisów: dopisujemy nowe instrumenty do istniejącego rynku.
            val added = ensureStarterAssets()
            added.forEach { symbol ->
                makers.forEach { requote(it, symbol) }
                repeat(10) { traderAction(symbol) }
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
        val development = JSONObject()
        companyDevelopment.forEach { (symbol, state) -> development.put(symbol, state) }

        return JSONObject()
            .put("version", 1)
            .put("playerId", playerId)
            .put("makers", makerRows)
            .put("traders", traderRows)
            .put("refPrices", refs)
            .put("simNow", simNow)
            .put("companyDevelopment", development)
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

    private fun companyState(symbol: String): JSONObject = companyDevelopment.getOrPut(symbol) {
        JSONObject().put("office_level", 0).put("research_level", 0)
            .put("marketing_level", 0).put("invested", "0")
    }

    private fun companyPayload(symbol: String, state: JSONObject): JSONObject {
        val office = state.optInt("office_level")
        val research = state.optInt("research_level")
        val marketing = state.optInt("marketing_level")
        fun next(level: Int) = if (level >= 10) 0L else 500L * (level + 1) * (level + 1)
        return JSONObject().put("symbol", symbol)
            .put("office_level", office).put("research_level", research).put("marketing_level", marketing)
            .put("company_level", 1 + office + research + marketing)
            .put("development_index", 100 + office * 20 + research * 30 + marketing * 25)
            .put("invested", state.optString("invested", "0"))
            .put("next_costs", JSONObject().put("office", next(office))
                .put("research", next(research)).put("marketing", next(marketing)))
            .put("max_level", 10)
    }

    fun getCompanyDevelopment(symbolInput: String): JSONObject {
        val symbol = symbolInput.uppercase()
        val asset = engine.assets[symbol] ?: throw EngineError("Nie znaleziono spółki")
        if (asset.creatorId != playerId) throw EngineError("Rozwijać możesz tylko własną spółkę")
        return companyPayload(symbol, companyState(symbol))
    }

    fun upgradeCompany(symbolInput: String, branch: String): JSONObject {
        val symbol = symbolInput.uppercase()
        val asset = engine.assets[symbol] ?: throw EngineError("Nie znaleziono spółki")
        if (asset.creatorId != playerId) throw EngineError("Rozwijać możesz tylko własną spółkę")
        if (branch !in listOf("office", "research", "marketing")) throw EngineError("Nieznana gałąź rozwoju")
        val state = companyState(symbol)
        val key = branch + "_level"
        val level = state.optInt(key)
        if (level >= 10) throw EngineError("Ta gałąź osiągnęła maksymalny poziom")
        val cost = BigDecimal(500L * (level + 1) * (level + 1))
        engine.spendUsd(playerId, cost)
        val invested = BigDecimal(state.optString("invested", "0")).add(cost)
        state.put(key, level + 1).put("invested", invested.stripTrailingZeros().toPlainString())
        return companyPayload(symbol, state)
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

    private fun traderAction(forcedSymbol: String? = null) {
        val symbols = engine.symbolsWithLiquidity().filter { forcedSymbol == null || it == forcedSymbol }
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
