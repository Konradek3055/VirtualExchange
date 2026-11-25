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
class OfflineMarket(
    seed: Long = System.nanoTime(),
    restoredState: JSONObject? = null,
    private val wallClock: () -> Long = { System.currentTimeMillis() },
    private val consumerSpendRatePerHour: BigDecimal = BigDecimal("0.00001"),
) {
    val engine = LocalEngine()
    val rnd = Random(seed)
    val playerId: Int
    private val makers = ArrayList<Int>()
    private val traders = ArrayList<Int>()
    private val refPrices = LinkedHashMap<String, BigDecimal>()
    private val companyDevelopment = LinkedHashMap<String, JSONObject>()
    private var simNow = wallClock()
    /** Pula popytu finansowana z opłat, wydatków botów i kosztów firm. */
    private var economyPool = BigDecimal.ZERO
    /** Dochody gospodarstw domowych, wydawane stopniowo; nie są natychmiastowym popytem. */
    private var householdPool = BigDecimal.ZERO
    /** Skarb państwa: opłaty i podatki nie są popytem klientów. */
    private var governmentTreasury = BigDecimal.ZERO
    private var lastEconomyTick = simNow

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
        listOf("Kuba", "Ola", "Wieloryb", "Marek").forEach { traders.add(engine.createAccount(it, true, BigDecimal("1000000")).id) }
        ensureStarterAssets()
        warmUp(WARMUP_STEPS)
        ensureTradeHistory()
        lastEconomyTick = simNow
        syncClock()
        } else {
            require(restoredState.optInt("version") == 1) {
                "Nieobsługiwana wersja zapisu offline"
            }

            // Stare wersje przyspieszały zegar gry; przesuwamy ich oś czasu bez zmiany odstępów.
            val savedClock = restoredState.optLong("simNow", 0L)
            val drift = savedClock - wallClock()
            if (restoredState.has("simNow") && drift > MAX_CLOCK_DRIFT_MS) {
                rebaseSnapshotTimes(restoredState, drift)
            }

            engine.restore(restoredState.getJSONObject("engine"))
            playerId = restoredState.getInt("playerId")

            val makerRows = restoredState.getJSONArray("makers")
            for (i in 0 until makerRows.length()) makers.add(makerRows.getInt(i))

            val traderRows = restoredState.getJSONArray("traders")
            for (i in 0 until traderRows.length()) traders.add(traderRows.getInt(i))
            // Migracja starszych zapisów: zachowaj konto, jeśli istnieje, lub utwórz czwarty profil.
            if (traders.size < 4) {
                val analyst = engine.accounts.values.firstOrNull { it.isBot && it.name == "Marek" }
                val analystId = analyst?.id ?: engine.createAccount("Marek", true, BigDecimal("1000000")).id
                traders.add(analystId)
            }

            val refs = restoredState.getJSONObject("refPrices")
            val keys = refs.keys()

            while (keys.hasNext()) {
                val symbol = keys.next()
                refPrices[symbol] = BigDecimal(refs.getString(symbol))
            }

            simNow = restoredState.optLong("simNow", wallClock())
            engine.clock = { simNow }

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
            economyPool = restoredState.optString("economyPool", "0").toBigDecimalOrNull() ?: BigDecimal.ZERO
            householdPool = restoredState.optString("householdPool", "0").toBigDecimalOrNull() ?: BigDecimal.ZERO
            governmentTreasury = restoredState.optString("governmentTreasury", "0").toBigDecimalOrNull() ?: BigDecimal.ZERO
            lastEconomyTick = if (restoredState.has("lastEconomyTick")) {
                restoredState.getLong("lastEconomyTick")
            } else {
                companyDevelopment.values.maxOfOrNull { it.optLong("last_settlement", simNow) } ?: simNow
            }
            // Wycofaj stare oferty oparte na sztucznej cenie 10 USD.
            dropLegacyUserReferencePrices()
            val added = ensureStarterAssets()
            added.forEach { symbol ->
                makers.forEach { requote(it, symbol) }
                repeat(10) { traderAction(symbol, alwaysAct = true) }
            }
            syncClock()
            settleEconomy()
        }
    }

    /** Stan do trwałego zapisu na telefonie. */
    @Synchronized
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
            .put("lastEconomyTick", lastEconomyTick)
            .put("economyPool", economyPool.toPlainString())
            .put("householdPool", householdPool.toPlainString())
            .put("governmentTreasury", governmentTreasury.toPlainString())
            .put("savedAt", wallClock())
            .put("companyDevelopment", development)
            .put("engine", engine.snapshot())
    }

    /** Symulowana historia: boty handlują "w przyspieszonym czasie", żeby wykres nie był pusty po starcie. */
    private fun warmUp(steps: Int) {
        val stepMs = 20_000L
        simNow = wallClock() - steps * stepMs
        engine.clock = { simNow }
        refPrices.keys.forEach { sym -> makers.forEach { requote(it, sym) } }
        repeat(steps) { step(stepMs) }
        engine.clock = { simNow }
    }

    private fun companyState(symbol: String): JSONObject {
        val state = companyDevelopment.getOrPut(symbol) {
            JSONObject().put("office_level", 0).put("research_level", 0)
                .put("marketing_level", 0).put("production_level", 0).put("logistics_level", 0)
                .put("invested", "0").put("cash", "0").put("revenue_total", "0")
                .put("expense_total", "0").put("dividends_total", "0")
                .put("last_revenue", "0").put("last_expense", "0")
                .put("employees", 3).put("reputation", 50.0).put("customers", 0)
                .put("last_settlement", simNow)
        }
        // Migracja zapisów offline z wcześniejszej wersji, bez kasowania portfela ani historii.
        if (!state.has("production_level")) state.put("production_level", 0)
        if (!state.has("logistics_level")) state.put("logistics_level", 0)
        if (!state.has("employees")) state.put("employees", 3)
        if (!state.has("cash")) state.put("cash", "0")
        if (!state.has("revenue_total")) state.put("revenue_total", "0")
        if (!state.has("expense_total")) state.put("expense_total", "0")
        if (!state.has("dividends_total")) state.put("dividends_total", "0")
        if (!state.has("last_revenue")) state.put("last_revenue", "0")
        if (!state.has("last_expense")) state.put("last_expense", "0")
        if (!state.has("reputation")) state.put("reputation", 50.0)
        if (!state.has("customers")) state.put("customers", 0)
        if (!state.has("last_settlement")) state.put("last_settlement", simNow)
        return state
    }

    private fun stateDecimal(state: JSONObject, key: String): BigDecimal =
        state.optString(key, "0").toBigDecimalOrNull() ?: BigDecimal.ZERO

    private fun companyHourlyRates(state: JSONObject): Pair<BigDecimal, BigDecimal> {
        val office = state.optInt("office_level")
        val research = state.optInt("research_level")
        val marketing = state.optInt("marketing_level")
        val production = state.optInt("production_level")
        val logistics = state.optInt("logistics_level")
        val employees = state.optInt("employees", 3).coerceAtLeast(1)
        val reputation = state.optDouble("reputation", 50.0).coerceIn(0.0, 100.0)
        val productivity = BigDecimal.ONE
            .add(BigDecimal(office).multiply(BigDecimal("0.035")))
            .add(BigDecimal(research).multiply(BigDecimal("0.09")))
            .add(BigDecimal(production).multiply(BigDecimal("0.16")))
            .add(BigDecimal(logistics).multiply(BigDecimal("0.06")))
        val demand = BigDecimal.ONE.add(BigDecimal(marketing).multiply(BigDecimal("0.11")))
            .add(BigDecimal.valueOf((reputation - 50.0) / 1000.0))
        val revenue = (BigDecimal("10").add(BigDecimal(employees).multiply(BigDecimal("1.4")))
            .add(BigDecimal(production).multiply(BigDecimal("5")))
            .add(BigDecimal(research).multiply(BigDecimal("1.8"))))
            .multiply(productivity).multiply(demand).setScale(2, RoundingMode.HALF_UP)
        val costs = (BigDecimal(employees).multiply(BigDecimal("1.1"))
            .add(BigDecimal(office).multiply(BigDecimal("1.3")))
            .add(BigDecimal(research).multiply(BigDecimal("2.2")))
            .add(BigDecimal(marketing).multiply(BigDecimal("1.5")))
            .add(BigDecimal(production).multiply(BigDecimal("2.5")))
            .add(BigDecimal(logistics).multiply(BigDecimal("1.4")))
            .add(BigDecimal("2"))).setScale(2, RoundingMode.HALF_UP)
        return Pair(revenue, costs)
    }

    /**
     * Rozliczenie całej gospodarki co godzinę czasu gry. Przychód firm jest ograniczony
     * realną pulą wydatków uczestników, a nie samym upływem czasu.
     */
    private fun settleEconomy() {
        val elapsedHours = (simNow - lastEconomyTick) / HOUR_MS
        if (elapsedHours <= 0L) return
        val hours = elapsedHours.coerceAtMost(MAX_CATCHUP_HOURS)
        repeat(hours.toInt()) { economyHour() }
        // Nie nadrabiamy bez limitu długiej nieobecności; przesuwamy zegar do teraz.
        lastEconomyTick = if (elapsedHours > MAX_CATCHUP_HOURS) simNow else lastEconomyTick + hours * HOUR_MS
        companyDevelopment.values.forEach { it.put("last_settlement", lastEconomyTick) }
    }

    private fun economyHour() {
        val symbols = companyDevelopment.keys.toList()
        if (symbols.isEmpty()) return

        // Boty oraz gospodarstwa domowe wydają tylko część posiadanej gotówki.
        // Dochód pracowników nie staje się automatycznie popytem w tej samej chwili.
        if (consumerSpendRatePerHour.signum() > 0) {
            engine.accounts.values.toList().filter { it.isBot }.forEach { bot ->
                val spend = bot.bal(LocalEngine.USD).available
                    .multiply(consumerSpendRatePerHour).setScale(2, RoundingMode.DOWN)
                if (spend.signum() > 0) {
                    engine.spendUsd(bot.id, spend)
                    economyPool = economyPool.add(spend)
                }
            }
            val householdSpend = householdPool.multiply(consumerSpendRatePerHour).setScale(2, RoundingMode.DOWN)
            if (householdSpend.signum() > 0) {
                householdPool = householdPool.subtract(householdSpend)
                economyPool = economyPool.add(householdSpend)
            }
        }

        // Zdolność produkcyjna ogranicza sprzedaż. Firmy dzielą tylko środki, które faktycznie są w puli.
        val states = symbols.associateWith { companyState(it) }
        val capacities = states.mapValues { companyHourlyRates(it.value).first }
        val totalCapacity = capacities.values.fold(BigDecimal.ZERO) { a, b -> a.add(b) }
        val demand = economyPool.max(BigDecimal.ZERO).min(totalCapacity)
        val revenues = symbols.associateWith { symbol ->
            if (demand.signum() <= 0 || totalCapacity.signum() <= 0) BigDecimal.ZERO
            else demand.multiply(capacities.getValue(symbol)).divide(totalCapacity, 2, RoundingMode.DOWN)
        }
        val paidRevenue = revenues.values.fold(BigDecimal.ZERO) { a, b -> a.add(b) }
        economyPool = economyPool.subtract(paidRevenue)

        // Koszty wracają do puli jako płatności dla pracowników i dostawców.
        symbols.forEach { symbol ->
            val state = states.getValue(symbol)
            val expectedCost = companyHourlyRates(state).second
            val earned = revenues.getValue(symbol)
            val available = stateDecimal(state, "cash").add(earned)
            val paidCost = expectedCost.min(available.max(BigDecimal.ZERO))
            householdPool = householdPool.add(paidCost)
            val reputationDelta = when {
                earned >= expectedCost -> 0.04
                paidCost < expectedCost -> -0.12
                else -> -0.04
            }
            val reputation = (state.optDouble("reputation", 50.0) + reputationDelta).coerceIn(0.0, 100.0)
            val employees = state.optInt("employees", 3)
            val customers = ((25 + employees * 10 + state.optInt("marketing_level") * 12
                + state.optInt("production_level") * 8) * (0.5 + reputation / 100.0)).toInt().coerceAtLeast(0)
            state.put("cash", available.subtract(paidCost).setScale(2, RoundingMode.HALF_UP)
                    .stripTrailingZeros().toPlainString())
                .put("revenue_total", stateDecimal(state, "revenue_total").add(earned).stripTrailingZeros().toPlainString())
                .put("expense_total", stateDecimal(state, "expense_total").add(paidCost).stripTrailingZeros().toPlainString())
                .put("last_revenue", earned.stripTrailingZeros().toPlainString())
                .put("last_expense", paidCost.stripTrailingZeros().toPlainString())
                .put("reputation", reputation)
                .put("customers", customers)
        }
    }

    /** Publiczny zegar rynku, wspólny dla wykresów, statystyk i rozliczeń. */
    val clockMs: Long get() = simNow

    /** Synchronizacja do zegara telefonu. Nigdy nie cofa historii transakcji. */
    @Synchronized
    fun syncClock() {
        val realNow = wallClock()
        if (realNow > simNow) simNow = realNow
        engine.clock = { simNow }
    }

    /** Suma USD w kontach, puli gospodarki i kasach wszystkich spółek. */
    @Synchronized
    fun economyTotal(): BigDecimal {
        var total = engine.issuedUsd().add(economyPool).add(householdPool).add(governmentTreasury)
        companyDevelopment.values.forEach { total = total.add(stateDecimal(it, "cash")) }
        return total
    }

    @Synchronized
    fun demandPool(): BigDecimal = economyPool

    @Synchronized
    fun householdIncomePool(): BigDecimal = householdPool

    /** Cena odniesienia: tylko aktywa startowe albo wartość gotówki spółki / podaż akcji. */
    private fun referencePrice(symbol: String): BigDecimal? {
        refPrices[symbol]?.let { return it }
        val asset = engine.assets[symbol] ?: return null
        if (asset.creatorId == null || asset.supply.signum() <= 0) return null
        val state = companyDevelopment[symbol] ?: return null
        val cash = stateDecimal(state, "cash")
        if (cash.signum() <= 0) return null
        return cash.divide(asset.supply, 8, RoundingMode.DOWN).takeIf { it.signum() > 0 }
    }

    /** Boty działają tylko na rynku z ceną odniesienia albo realną transakcją. */
    private fun botUniverse(): List<String> = engine.assets.keys.filter { symbol ->
        referencePrice(symbol) != null || (engine.assets[symbol]?.lastPrice?.signum() ?: 0) > 0
    }

    private fun hasLiquidity(symbol: String): Boolean =
        engine.orderBook(symbol, 1).let { it.first.isNotEmpty() || it.second.isNotEmpty() }

    /** Uzupełnia startową historię realnymi transakcjami, nie ręcznie generowanymi świecami. */
    private fun ensureTradeHistory(minTrades: Int = 8, attempts: Int = 80) {
        botUniverse().forEach { symbol ->
            var left = attempts
            while (left-- > 0 && engine.recentTrades(symbol, minTrades).size < minTrades) {
                traderAction(symbol, alwaysAct = true)
            }
        }
    }

    /** Usuwa stare oferty makerów dla spółek o sztucznej cenie 10 USD. */
    private fun dropLegacyUserReferencePrices() {
        val legacy = engine.assets.values.filter { it.creatorId != null && refPrices.containsKey(it.symbol) }
        legacy.forEach { asset ->
            refPrices.remove(asset.symbol)
            makers.forEach { maker ->
                engine.openOrdersOf(maker, asset.symbol).forEach { engine.cancelOrder(maker, it.id) }
            }
        }
    }

    /** Przesuwa stare znaczniki czasu, jeśli poprzednia wersja przyspieszała simNow względem zegara telefonu. */
    private fun rebaseSnapshotTimes(state: JSONObject, delta: Long) {
        val eng = state.getJSONObject("engine")
        fun shift(rows: JSONArray?, key: String) {
            if (rows == null) return
            for (i in 0 until rows.length()) {
                val row = rows.getJSONObject(i)
                if (row.has(key)) row.put(key, row.getLong(key) - delta)
            }
        }
        shift(eng.optJSONArray("trades"), "ts")
        shift(eng.optJSONArray("orders"), "createdAt")
        shift(eng.optJSONArray("assets"), "createdAt")
        state.put("simNow", state.getLong("simNow") - delta)
        if (state.has("lastEconomyTick")) state.put("lastEconomyTick", state.getLong("lastEconomyTick") - delta)
        val development = state.optJSONObject("companyDevelopment")
        if (development != null) {
            val keys = development.keys()
            while (keys.hasNext()) {
                val company = development.getJSONObject(keys.next())
                if (company.has("last_settlement")) {
                    company.put("last_settlement", company.getLong("last_settlement") - delta)
                }
            }
        }
    }

    private fun companyPayload(symbol: String, state: JSONObject): JSONObject {
        val office = state.optInt("office_level")
        val research = state.optInt("research_level")
        val marketing = state.optInt("marketing_level")
        val production = state.optInt("production_level")
        val logistics = state.optInt("logistics_level")
        val levels = office + research + marketing + production + logistics
        val (revenue, costs) = companyHourlyRates(state)
        fun next(level: Int) = if (level >= 10) 0L else 500L * (level + 1) * (level + 1)
        val cash = stateDecimal(state, "cash")
        return JSONObject().put("symbol", symbol)
            .put("office_level", office).put("research_level", research)
            .put("marketing_level", marketing).put("production_level", production)
            .put("logistics_level", logistics)
            .put("company_level", 1 + levels)
            .put("development_index", 100 + office * 18 + research * 28 + marketing * 22 + production * 32 + logistics * 16)
            .put("invested", state.optString("invested", "0"))
            .put("cash", cash.stripTrailingZeros().toPlainString())
            .put("revenue_total", state.optString("revenue_total", "0"))
            .put("expense_total", state.optString("expense_total", "0"))
            .put("dividends_total", state.optString("dividends_total", "0"))
            .put("last_revenue", state.optString("last_revenue", "0"))
            .put("last_expense", state.optString("last_expense", "0"))
            .put("hourly_revenue", revenue.toPlainString())
            .put("hourly_expenses", costs.toPlainString())
            .put("hourly_profit", revenue.subtract(costs).toPlainString())
            .put("employees", state.optInt("employees", 3))
            .put("reputation", state.optDouble("reputation", 50.0))
            .put("customers", state.optInt("customers"))
            .put("product_quality", (40 + research * 4 + production * 6).coerceAtMost(100))
            .put("status", if (cash.signum() > 0) "OPERATING" else "LOW_CAPITAL")
            .put("next_costs", JSONObject().put("office", next(office)).put("research", next(research))
                .put("marketing", next(marketing)).put("production", next(production)).put("logistics", next(logistics)))
            .put("max_level", 10).put("max_employees", 200)
            .put("demand_pool", economyPool.toPlainString())
    }

    @Synchronized
    fun getCompanyDevelopment(symbolInput: String): JSONObject {
        val symbol = symbolInput.uppercase()
        val asset = engine.assets[symbol] ?: throw EngineError("Nie znaleziono spółki")
        if (asset.creatorId != playerId) throw EngineError("Rozwijać możesz tylko własną spółkę")
        settleEconomy()
        return companyPayload(symbol, companyState(symbol))
    }

    @Synchronized
    fun upgradeCompany(symbolInput: String, branch: String): JSONObject {
        val symbol = symbolInput.uppercase()
        val asset = engine.assets[symbol] ?: throw EngineError("Nie znaleziono spółki")
        if (asset.creatorId != playerId) throw EngineError("Rozwijać możesz tylko własną spółkę")
        if (branch !in listOf("office", "research", "marketing", "production", "logistics"))
            throw EngineError("Nieznana gałąź rozwoju")
        settleEconomy()
        val state = companyState(symbol)
        val key = branch + "_level"
        val level = state.optInt(key)
        if (level >= 10) throw EngineError("Ta gałąź osiągnęła maksymalny poziom")
        val cost = BigDecimal(500L * (level + 1) * (level + 1))
        engine.spendUsd(playerId, cost)
        economyPool = economyPool.add(cost)
        val invested = stateDecimal(state, "invested").add(cost)
        state.put(key, level + 1).put("invested", invested.stripTrailingZeros().toPlainString())
        return companyPayload(symbol, state)
    }

    @Synchronized
    fun fundCompany(symbolInput: String, rawAmount: String): JSONObject {
        val symbol = symbolInput.uppercase()
        val asset = engine.assets[symbol] ?: throw EngineError("Nie znaleziono spółki")
        if (asset.creatorId != playerId) throw EngineError("Nie jesteś właścicielem spółki")
        val state = companyState(symbol)
        val amount = rawAmount.toBigDecimalOrNull() ?: throw EngineError("Nieprawidłowa kwota")
        if (amount.signum() <= 0 || amount > BigDecimal("1000000000") || amount.stripTrailingZeros().scale().coerceAtLeast(0) > 2)
            throw EngineError("Kwota musi wynosić od 0,01 do 1 000 000 000 USD i mieć maksymalnie 2 miejsca po przecinku")
        settleEconomy()
        engine.spendUsd(playerId, amount)
        state.put("cash", stateDecimal(state, "cash").add(amount).stripTrailingZeros().toPlainString())
        return companyPayload(symbol, state)
    }

    @Synchronized
    fun withdrawCompany(symbolInput: String, rawAmount: String): JSONObject {
        val symbol = symbolInput.uppercase()
        val asset = engine.assets[symbol] ?: throw EngineError("Nie znaleziono spółki")
        if (asset.creatorId != playerId) throw EngineError("Nie jesteś właścicielem spółki")
        val state = companyState(symbol)
        val amount = rawAmount.toBigDecimalOrNull() ?: throw EngineError("Nieprawidłowa kwota")
        if (amount.signum() <= 0 || amount > BigDecimal("1000000000") || amount.stripTrailingZeros().scale().coerceAtLeast(0) > 2)
            throw EngineError("Kwota musi wynosić od 0,01 do 1 000 000 000 USD i mieć maksymalnie 2 miejsca po przecinku")
        settleEconomy()
        val cash = stateDecimal(state, "cash")
        if (cash < amount) throw EngineError("Za mało środków w kasie spółki")
        val holders = engine.accounts.values.mapNotNull { holder ->
            val balance = holder.bal(symbol)
            val shares = balance.available.add(balance.locked)
            if (shares.signum() > 0) holder.id to shares else null
        }
        val totalShares = holders.fold(BigDecimal.ZERO) { sum, item -> sum.add(item.second) }
        if (totalShares.signum() <= 0) throw EngineError("Spółka nie ma akcjonariuszy")
        val totalCents = amount.movePointRight(2).toBigIntegerExact()
        val allocations = holders.map { (holderId, shares) ->
            val exact = BigDecimal(totalCents).multiply(shares).divide(totalShares, 16, RoundingMode.DOWN)
            val base = exact.setScale(0, RoundingMode.DOWN).toBigInteger()
            Triple(holderId, base, exact.subtract(BigDecimal(base)))
        }
        val baseSum = allocations.fold(java.math.BigInteger.ZERO) { sum, item -> sum.add(item.second) }
        val centsLeft = totalCents.subtract(baseSum).toInt()
        val extraIds = allocations.sortedWith(
            compareByDescending<Triple<Int, java.math.BigInteger, BigDecimal>> { it.third }.thenBy { it.first }
        ).take(centsLeft).map { it.first }.toSet()
        allocations.forEach { (holderId, base, _) ->
            val cents = base.add(if (holderId in extraIds) java.math.BigInteger.ONE else java.math.BigInteger.ZERO)
            val payout = BigDecimal(cents).movePointLeft(2)
            if (payout.signum() > 0) engine.grantAsset(holderId, LocalEngine.USD, payout)
        }
        val dividends = stateDecimal(state, "dividends_total").add(amount)
        state.put("cash", cash.subtract(amount).stripTrailingZeros().toPlainString())
            .put("dividends_total", dividends.stripTrailingZeros().toPlainString())
        return companyPayload(symbol, state)
    }

    @Synchronized
    fun manageCompanyStaff(symbolInput: String, action: String, count: Int): JSONObject {
        val symbol = symbolInput.uppercase()
        val asset = engine.assets[symbol] ?: throw EngineError("Nie znaleziono spółki")
        if (asset.creatorId != playerId) throw EngineError("Nie jesteś właścicielem spółki")
        if (action !in listOf("hire", "fire") || count !in 1..20) throw EngineError("Nieprawidłowa operacja kadrowa")
        settleEconomy()
        val state = companyState(symbol)
        val employees = state.optInt("employees", 3)
        var cash = stateDecimal(state, "cash")
        if (action == "hire") {
            if (employees + count > 200) throw EngineError("Limit zatrudnienia to 200 osób")
            val fee = BigDecimal(count * 75)
            if (cash < fee) throw EngineError("Kasa spółki potrzebuje ${fee} USD na rekrutację")
            cash = cash.subtract(fee)
            householdPool = householdPool.add(fee)
            state.put("employees", employees + count)
        } else {
            if (employees - count < 1) throw EngineError("Firma musi zatrudniać co najmniej jedną osobę")
            val severance = BigDecimal(count * 35)
            if (cash < severance) throw EngineError("Kasa spółki potrzebuje ${severance} USD na odprawy")
            cash = cash.subtract(severance)
            householdPool = householdPool.add(severance)
            state.put("employees", employees - count)
        }
        state.put("cash", cash.stripTrailingZeros().toPlainString())
        return companyPayload(symbol, state)
    }

    @Synchronized
    fun registerCreatedCompany(asset: Asset) {
        // Opłata została już pobrana przez LocalEngine.createUserAsset; trafia do skarbu, nie do popytu klientów.
        if (!companyDevelopment.containsKey(asset.symbol)) governmentTreasury = governmentTreasury.add(LocalEngine.LISTING_FEE)
        companyState(asset.symbol)
    }

    /** Krok botów. Domyślnie korzysta z rzeczywistego zegara telefonu; jawny argument służy tylko testom/rozgrzewce. */
    @Synchronized
    fun step(simulatedMs: Long = 0L) {
        if (simulatedMs > 0L) {
            simNow += simulatedMs
            engine.clock = { simNow }
        } else {
            syncClock()
        }
        val universe = botUniverse()
        if (rnd.nextDouble() < 0.62 && universe.isNotEmpty()) {
            val symbol = universe[rnd.nextInt(universe.size)]
            requote(makers[rnd.nextInt(makers.size)], symbol)
        }
        repeat(1 + rnd.nextInt(2)) { traderAction() }
        settleEconomy()
        engine.prune()
    }


    private fun px(v: BigDecimal): BigDecimal = v.setScale(if (v >= BigDecimal.ONE) 2 else 6, RoundingMode.HALF_UP)

    private fun requote(maker: Int, symbol: String) {
        engine.openOrdersOf(maker, symbol).forEach { engine.cancelOrder(maker, it.id) }
        val asset = engine.assets[symbol] ?: return
        val ref = referencePrice(symbol) ?: return
        val history = engine.recentTrades(symbol, 32).asReversed()
        val last = if (asset.lastPrice.signum() > 0) asset.lastPrice else ref
        val prices = history.map { it.price.toDouble() }.filter { it > 0.0 }
        val momentum = if (prices.size >= 2) (prices.last() / prices.first() - 1.0).coerceIn(-0.08, 0.08) else 0.0
        val volatility = if (prices.size >= 3) prices.zipWithNext().mapNotNull { (a, b) ->
            if (a > 0.0) kotlin.math.abs(b / a - 1.0) else null
        }.average().takeIf { it.isFinite() } ?: 0.0 else 0.0
        val held = engine.accounts[maker]?.bal(symbol)?.available ?: BigDecimal.ZERO
        val inventoryShare = if (asset.supply.signum() > 0) held.divide(asset.supply, 8, RoundingMode.DOWN).toDouble() else 0.0
        val inventorySkew = ((0.08 - inventoryShare) * 0.004).coerceIn(-0.003, 0.003)
        val adjustment = (1.0 + momentum * 0.18 + inventorySkew + rnd.nextGaussian() * 0.00015).coerceIn(0.90, 1.10)
        val fair = last.multiply(BigDecimal.valueOf(adjustment)).max(BigDecimal("0.000001"))
        val spreadBase = (0.0012 + volatility * 1.35).coerceIn(0.0012, 0.010)
        val levelQty = asset.supply.multiply(BigDecimal("0.003")).setScale(4, RoundingMode.DOWN)
        if (levelQty.signum() <= 0) return
        for (i in 1..4) {
            val spread = BigDecimal.valueOf(spreadBase * i)
            val bid = px(fair.multiply(BigDecimal.ONE.subtract(spread)))
            val ask = px(fair.multiply(BigDecimal.ONE.add(spread)))
            if (bid.signum() > 0) try { engine.placeOrder(maker, symbol, "buy", "limit", levelQty, bid) } catch (_: EngineError) { }
            try { engine.placeOrder(maker, symbol, "sell", "limit", levelQty, ask) } catch (_: EngineError) { }
        }
    }

    private data class BotSignal(val value: Double, val shortTrend: Double, val longTrend: Double,
                                 val valueGap: Double, val bookImbalance: Double)

    private fun signalFor(traderId: Int, symbol: String): BotSignal {
        val asset = engine.assets[symbol] ?: return BotSignal(0.0, 0.0, 0.0, 0.0, 0.0)
        val history = engine.recentTrades(symbol, 64).asReversed()
        val prices = history.map { it.price.toDouble() }.filter { it.isFinite() && it > 0.0 }
        val reference = (referencePrice(symbol) ?: asset.lastPrice.takeIf { it.signum() > 0 }
            ?: return BotSignal(0.0, 0.0, 0.0, 0.0, 0.0)).toDouble()
        val last = asset.lastPrice.takeIf { it.signum() > 0 }?.toDouble() ?: prices.lastOrNull() ?: reference
        fun trend(window: Int): Double {
            if (prices.size < 2) return 0.0
            val start = prices[(prices.size - window).coerceAtLeast(0)]
            return if (start > 0.0) (last / start - 1.0).coerceIn(-0.5, 0.5) else 0.0
        }
        val short = trend(8)
        val long = trend(32)
        val gap = if (reference > 0.0) ((reference - last) / reference).coerceIn(-0.5, 0.5) else 0.0
        val (bids, asks) = engine.orderBook(symbol, 8)
        val bidQty = bids.sumOf { it.quantity.toDouble() }
        val askQty = asks.sumOf { it.quantity.toDouble() }
        val total = bidQty + askQty
        val imbalance = if (total > 0.0) ((bidQty - askQty) / total).coerceIn(-1.0, 1.0) else 0.0
        val profile = traders.indexOf(traderId).coerceAtLeast(0)
        val signal = when (profile) {
            0 -> gap * 0.85 - long * 0.25 + imbalance * 0.15
            1 -> short * 0.95 + long * 0.45 + imbalance * 0.10
            2 -> -short * 0.90 - gap * 0.20 + imbalance * 0.08
            else -> gap * 0.35 + long * 0.55 + imbalance * 0.55
        }
        return BotSignal(signal.coerceIn(-0.25, 0.25), short, long, gap, imbalance)
    }

    @Synchronized
    fun botOverview(): JSONArray {
        val out = JSONArray()
        traders.forEachIndexed { index, id ->
            val best = botUniverse().map { it to signalFor(id, it) }
                .maxByOrNull { kotlin.math.abs(it.second.value) }
            val symbol = best?.first ?: "BTC"
            val signal = best?.second ?: BotSignal(0.0, 0.0, 0.0, 0.0, 0.0)
            val decision = when { signal.value > 0.001 -> "KUPUJ"; signal.value < -0.001 -> "SPRZEDAJ"; else -> "OBSERWUJ" }
            val strategy = when (index) {
                0 -> "Inwestor wartościowy"
                1 -> "Momentum / trend"
                2 -> "Kontrarianin"
                else -> "Fundusz / przepływ zleceń"
            }
            val reason = when (index) {
                0 -> "Porównuje kurs z wartością odniesienia i długim trendem"
                1 -> "Szuka potwierdzenia krótkiego i średniego momentum"
                2 -> "Reaguje przeciwnie do mocnych ruchów ceny"
                else -> "Ocenia trend, przewagę kupujących i płynność księgi"
            }
            out.put(JSONObject().put("name", engine.accounts[id]?.name ?: "Bot ${index + 1}")
                .put("strategy", strategy).put("symbol", symbol).put("decision", decision)
                .put("signal", signal.value).put("confidence", (kotlin.math.abs(signal.value) / 0.02 * 100.0).coerceIn(0.0, 100.0))
                .put("short_trend", signal.shortTrend).put("long_trend", signal.longTrend)
                .put("value_gap", signal.valueGap).put("book_imbalance", signal.bookImbalance).put("reason", reason))
        }
        return out
    }

    private fun traderAction(forcedSymbol: String? = null, alwaysAct: Boolean = false) {
        if (traders.isEmpty()) return
        val trader = traders[rnd.nextInt(traders.size)]
        val universe = botUniverse()
        if (universe.isEmpty()) return

        // Kontrolowana eksploracja zapobiega pomijaniu rynków o słabszym sygnale.
        val explore = forcedSymbol == null && rnd.nextDouble() < EXPLORE_PROBABILITY
        val chosen: Pair<String, BotSignal>
        if (explore) {
            val liquid = universe.filter { hasLiquidity(it) }
            if (liquid.isEmpty()) return
            val symbol = liquid[rnd.nextInt(liquid.size)]
            chosen = symbol to signalFor(trader, symbol)
        } else {
            val symbols = if (forcedSymbol != null) universe.filter { it == forcedSymbol } else {
                val sample = linkedSetOf<String>()
                repeat(minOf(6, universe.size)) { sample.add(universe[rnd.nextInt(universe.size)]) }
                sample.toList()
            }
            if (symbols.isEmpty()) return
            val ranked = symbols.map { it to signalFor(trader, it) }
                .sortedByDescending { kotlin.math.abs(it.second.value) }
            chosen = ranked.firstOrNull { hasLiquidity(it.first) } ?: return
        }

        val symbol = chosen.first
        val profile = traders.indexOf(trader)
        val signal = chosen.second.value
        val mustAct = explore || alwaysAct
        val effectiveSignal = if (kotlin.math.abs(signal) < 0.0008) {
            if (!mustAct && rnd.nextDouble() > 0.16) return
            if (rnd.nextBoolean()) 0.001 else -0.001
        } else signal
        val side = if (effectiveSignal >= 0.0) "buy" else "sell"
        val account = engine.accounts[trader] ?: return
        val asset = engine.assets[symbol] ?: return
        val price = asset.lastPrice.takeIf { it.signum() > 0 }
            ?: engine.orderBook(symbol, 1).let { it.second.firstOrNull()?.price ?: it.first.firstOrNull()?.price }
            ?: referencePrice(symbol) ?: return
        if (price.signum() <= 0) return

        val risk = if (profile == 3) 0.04 else 0.015
        val notional = BigDecimal.valueOf((300.0 + kotlin.math.abs(effectiveSignal) * 100_000.0)
            .coerceIn(250.0, if (profile == 3) 40_000.0 else 12_000.0))
        val quantity = if (side == "buy") {
            val budget = notional.min(account.bal(LocalEngine.USD).available.multiply(BigDecimal.valueOf(risk)))
            budget.divide(price, 4, RoundingMode.DOWN)
        } else {
            val holding = account.bal(symbol).available
            notional.divide(price, 4, RoundingMode.DOWN)
                .min(holding.multiply(BigDecimal.valueOf(risk)).setScale(4, RoundingMode.DOWN))
        }
        if (quantity.signum() <= 0) return
        try {
            engine.placeOrder(trader, symbol, side, "market", quantity, null)
        } catch (_: EngineError) {
            // Brak płynności lub środków nie tworzy sztucznego obrotu.
        }
    }

    companion object {
        const val WARMUP_STEPS = 400
        const val HOUR_MS = 3_600_000L
        const val MAX_CATCHUP_HOURS = 168L
        const val MAX_CLOCK_DRIFT_MS = 300_000L
        const val EXPLORE_PROBABILITY = 0.30
        val DEFAULT_CONSUMER_SPEND_RATE = BigDecimal("0.00001")
    }
}
