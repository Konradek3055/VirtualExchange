package pl.virtualexchange

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import pl.virtualexchange.ui.Ind
import pl.virtualexchange.ui.epochAt
import pl.virtualexchange.ui.xIndex

class IndicatorsTest {
    @Test fun smaAndEma() {
        val v = listOf(1.0, 2.0, 3.0, 4.0, 5.0)
        assertNull(Ind.sma(v, 3)[1])
        assertEquals(4.0, Ind.sma(v, 3)[4]!!, 1e-9)
        assertEquals(4.0, Ind.ema(v, 3)[4]!!, 1e-9)
    }

    @Test fun bollingerFlatSeriesHasZeroWidth() {
        val (mid, up, _) = Ind.bollinger(List(25) { 7.0 }, 20, 2.0)
        assertEquals(7.0, mid[24]!!, 1e-9)
        assertEquals(7.0, up[24]!!, 1e-9)
    }

    @Test fun rsiExtremesAndWilderExample() {
        assertEquals(100.0, Ind.rsi(List(20) { it.toDouble() })[19]!!, 1e-9)
        assertEquals(0.0, Ind.rsi(List(20) { 20.0 - it })[19]!!, 1e-9)
        val w = listOf(44.34, 44.09, 44.15, 43.61, 44.33, 44.83, 45.10, 45.42, 45.84, 46.08, 45.89, 46.03, 45.61, 46.28, 46.28)
        assertEquals(70.46, Ind.rsi(w, 14)[14]!!, 0.05)
    }

    @Test fun macdStartsAfterSlowPeriod() {
        val (line, _, _) = Ind.macd(List(60) { 10.0 })
        assertNull(line[24])
        assertNotNull(line[25])
        assertEquals(0.0, line[59]!!, 1e-9)
    }

    @Test fun timeMappingSkipsGapsAndRoundTrips() {
        val ep = longArrayOf(0, 60, 300)
        assertEquals(0.5, xIndex(ep, 30, 60), 1e-9)
        assertEquals(4.0, xIndex(ep, 420, 60), 1e-9)
        assertEquals(200L, epochAt(ep, xIndex(ep, 200, 60), 60))
    }
}
