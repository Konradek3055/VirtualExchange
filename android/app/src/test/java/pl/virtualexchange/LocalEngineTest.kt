package pl.virtualexchange

import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import pl.virtualexchange.local.EngineError
import pl.virtualexchange.local.LocalEngine
import pl.virtualexchange.local.OfflineMarket

class LocalEngineTest {
    private fun bd(s: String) = BigDecimal(s)

    private fun engine(): Triple<LocalEngine, Int, Int> {
        val e = LocalEngine()
        val seller = e.createAccount("seller", false, BigDecimal.ZERO).id
        val buyer = e.createAccount("buyer", false, bd("10000")).id
        e.createAsset(seller, "BTCX", "Bitcoin X", "", bd("100"))
        return Triple(e, seller, buyer)
    }

    @Test fun crossExecutesAtMakerPrice() {
        val (e, s, b) = engine()
        e.placeOrder(s, "BTCX", "sell", "limit", bd("1"), bd("100"))
        val r = e.placeOrder(b, "BTCX", "buy", "limit", bd("1"), bd("120"))
        assertEquals("FILLED", r.order.status)
        assertEquals(bd("100"), r.fills[0].price)
        assertEquals(0, e.accounts[b]!!.bal("USD").available.compareTo(bd("9900")))   // zwrot różnicy
        e.checkInvariants()
    }

    @Test fun timePriorityAtSamePrice() {
        val (e, s, b) = engine()
        val first = e.placeOrder(s, "BTCX", "sell", "limit", bd("1"), bd("100")).order.id
        e.placeOrder(s, "BTCX", "sell", "limit", bd("1"), bd("100"))
        e.placeOrder(b, "BTCX", "buy", "limit", bd("1"), bd("100"))
        assertEquals("FILLED", e.orders[first]!!.status)
    }

    @Test fun marketBuyWalksTheBookAndNeverOverspends() {
        val (e, s, b) = engine()
        e.placeOrder(s, "BTCX", "sell", "limit", bd("1"), bd("100"))
        e.placeOrder(s, "BTCX", "sell", "limit", bd("1"), bd("101"))
        val r = e.placeOrder(b, "BTCX", "buy", "market", bd("1.5"), null)
        assertEquals(2, r.fills.size)
        assertTrue(e.accounts[b]!!.bal("USD").available.signum() >= 0)
        e.checkInvariants()
    }

    @Test fun cannotSpendWhatYouDoNotHave() {
        val (e, s, b) = engine()
        for (call in listOf<() -> Unit>(
            { e.placeOrder(b, "BTCX", "buy", "limit", bd("101"), bd("100")) },
            { e.placeOrder(b, "BTCX", "sell", "limit", bd("1"), bd("100")) },
            { e.placeOrder(b, "BTCX", "buy", "limit", bd("-1"), bd("1")) },
            { e.placeOrder(b, "BTCX", "buy", "limit", bd("0.123456789"), bd("1")) },
            { e.placeOrder(b, "BTCX", "buy", "market", bd("1"), bd("1")) },
        )) {
            try { call(); fail("powinno być odrzucone") } catch (x: EngineError) { }
        }
        e.checkInvariants()
    }

    @Test fun selfTradeIsPrevented() {
        val e = LocalEngine()
        val a = e.createAccount("a", false, bd("10000")).id
        e.createAsset(a, "SELF", "Self", "", bd("10"))
        e.placeOrder(a, "SELF", "sell", "limit", bd("1"), bd("100"))
        val r = e.placeOrder(a, "SELF", "buy", "limit", bd("1"), bd("100"))
        assertEquals("CANCELLED", r.order.status)
        assertTrue(e.trades.isEmpty())
        e.checkInvariants()
    }

    @Test fun duplicateTickerRejected() {
        val (e, s, _) = engine()
        try { e.createAsset(s, "btcx", "Dup", "", bd("5")); fail() } catch (x: EngineError) { }
    }

    @Test fun offlineBotsExposeDifferentAnalysisProfiles() {
        val market = OfflineMarket(19)
        val overview = market.botOverview()
        assertEquals(4, overview.length())
        val strategies = (0 until overview.length()).map { overview.getJSONObject(it).getString("strategy") }.toSet()
        assertTrue(strategies.size >= 4)
        assertTrue((0 until overview.length()).all { overview.getJSONObject(it).has("reason") })
    }

    @Test fun legacyOfflineSaveAddsFourthBotWithoutResettingWallets() {
        val market = OfflineMarket(31)
        val snapshot = market.snapshot()
        val traders = snapshot.getJSONArray("traders")
        val legacyTraders = org.json.JSONArray()
        for (i in 0 until 3) legacyTraders.put(traders.getInt(i))
        snapshot.put("traders", legacyTraders)
        val restored = OfflineMarket(restoredState = snapshot)
        assertEquals(4, restored.botOverview().length())
        assertEquals(market.playerId, restored.playerId)
        assertEquals(market.engine.accounts.size, restored.engine.accounts.size)
        assertEquals(market.engine.accounts[market.playerId]!!.bal("USD").available,
            restored.engine.accounts[restored.playerId]!!.bal("USD").available)
        restored.engine.checkInvariants()
    }

    @Test fun offlineCompanyCapitalStaffAndProRataDividendKeepLedgerConsistent() {
        val market = OfflineMarket(23)
        val asset = market.engine.createUserAsset(market.playerId, "NOVA", "Nova Systems", "Test company", bd("1000000"))
        market.registerCreatedCompany(asset)
        val investor = market.engine.createAccount("Shareholder", false, bd("10000"))
        market.engine.accounts.values.filter { it.isBot && it.name.startsWith("MM-") }.forEach { maker ->
            market.engine.openOrdersOf(maker.id, "NOVA").forEach { market.engine.cancelOrder(maker.id, it.id) }
        }
        market.engine.placeOrder(market.playerId, "NOVA", "sell", "limit", bd("250000"), bd("0.01"))
        market.engine.placeOrder(investor.id, "NOVA", "buy", "limit", bd("250000"), bd("0.01"))
        market.fundCompany("NOVA", "1000")
        val ownerBefore = market.engine.accounts[market.playerId]!!.bal("USD").available
        val investorBefore = market.engine.accounts[investor.id]!!.bal("USD").available
        val hired = market.manageCompanyStaff("NOVA", "hire", 2)
        assertEquals(5, hired.getInt("employees"))
        val fired = market.manageCompanyStaff("NOVA", "fire", 1)
        assertEquals(4, fired.getInt("employees"))
        val paid = market.withdrawCompany("NOVA", "100")
        assertEquals(0, market.engine.accounts[market.playerId]!!.bal("USD").available.subtract(ownerBefore).compareTo(bd("75")))
        assertEquals(0, market.engine.accounts[investor.id]!!.bal("USD").available.subtract(investorBefore).compareTo(bd("25")))
        assertEquals("100", paid.getString("dividends_total"))
        market.engine.checkInvariants()
        assertTrue(market.getCompanyDevelopment("NOVA").getString("cash").toBigDecimal() > BigDecimal.ZERO)
    }

    @Test fun offlineClockFollowsInjectedWallClock() {
        var now = 1_700_000_000_000L
        val market = OfflineMarket(seed = 101, wallClock = { now }, consumerSpendRatePerHour = BigDecimal.ZERO)
        now += 90_000L
        market.syncClock()
        assertEquals(now, market.clockMs)
    }

    @Test fun offlineStepAdvancesSimulationClockByGameStep() {
        val market = OfflineMarket(
            seed = 102,
            wallClock = { 1_700_000_000_000L },
            consumerSpendRatePerHour = BigDecimal.ZERO,
        )
        val before = market.clockMs
        market.step()
        assertEquals(before + OfflineMarket.GAME_STEP_MS, market.clockMs)
    }

    @Test fun newCompanyDoesNotReceiveArtificialTenDollarQuote() {
        val market = OfflineMarket(seed = 103, consumerSpendRatePerHour = BigDecimal.ZERO)
        val asset = market.engine.createUserAsset(market.playerId, "NOVA", "Nova Systems", "Test company", bd("1000000"))
        market.registerCreatedCompany(asset)
        assertEquals(0, asset.lastPrice.compareTo(BigDecimal.ZERO))
        val book = market.engine.orderBook("NOVA", 5)
        assertTrue(book.first.isEmpty() && book.second.isEmpty())
    }

    @Test fun companyRevenueUsesDemandPoolAndKeepsUsdTotalConstant() {
        var now = 1_700_000_000_000L
        val market = OfflineMarket(seed = 107, wallClock = { now }, consumerSpendRatePerHour = BigDecimal.ZERO)
        val asset = market.engine.createUserAsset(market.playerId, "NOVA", "Nova Systems", "Test company", bd("1000000"))
        market.registerCreatedCompany(asset)
        market.fundCompany("NOVA", "1000")
        val totalBefore = market.economyTotal()
        now += OfflineMarket.HOUR_MS
        market.step()
        val company = market.getCompanyDevelopment("NOVA")
        val realized = company.getString("revenue_total").toBigDecimal()
        // Bez wydatków konsumentów firma nie może dostać przychodu wyłącznie za upływ czasu.
        assertEquals(0, realized.compareTo(BigDecimal.ZERO))
        assertEquals(0, totalBefore.compareTo(market.economyTotal()))
    }

    @Test fun botsKeepBooksConsistent() {
        val m = OfflineMarket(7)
        repeat(1500) { m.step() }
        m.engine.checkInvariants()
        assertTrue(m.engine.trades.size > 100)
        assertTrue(m.engine.candles("BTC", 60_000L, 60).isNotEmpty())
    }
}
