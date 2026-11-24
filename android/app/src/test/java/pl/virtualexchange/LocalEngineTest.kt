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

    @Test fun botsKeepBooksConsistent() {
        val m = OfflineMarket(7)
        repeat(1500) { m.step() }
        m.engine.checkInvariants()
        assertTrue(m.engine.trades.size > 100)
        assertTrue(m.engine.candles("BTC", 60_000L, 60).isNotEmpty())
    }
}
