package pl.virtualexchange

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.virtualexchange.data.Candle
import pl.virtualexchange.ui.fillCandles

class ChartTypeTest {
    @Test fun sortsCandlesAndFillsMissingMinuteWithoutChangingRealClose() {
        val input = listOf(
            Candle("2026-11-25 10:02", 11.0, 12.0, 10.0, 11.5, 4.0),
            Candle("2026-11-25 10:00", 9.0, 10.0, 8.0, 9.5, 2.0),
        )
        val result = fillCandles(input, 60)
        assertEquals(3, result.size)
        assertEquals("2026-11-25 10:00", result[0].time)
        assertEquals(9.5, result[1].close, 1e-9)
        assertEquals(0.0, result[1].volume, 1e-9)
        assertEquals(11.5, result[2].close, 1e-9)
    }

    @Test fun mergesDuplicateBucketsAndAcceptsSeconds() {
        val input = listOf(
            Candle("2026-11-25 10:00:59", 10.0, 11.0, 8.0, 12.0, 2.0),
            Candle("2026-11-25 10:00:10", 9.0, 10.0, 7.0, 10.0, 3.0),
        )
        val result = fillCandles(input, 60)
        assertEquals(1, result.size)
        assertEquals(9.0, result[0].open, 1e-9)
        assertEquals(12.0, result[0].high, 1e-9)
        assertEquals(7.0, result[0].low, 1e-9)
        assertEquals(12.0, result[0].close, 1e-9)
        assertEquals(5.0, result[0].volume, 1e-9)
    }

    @Test fun invalidTimeAndNonFiniteCandlesAreIgnored() {
        val result = fillCandles(listOf(
            Candle("bad-time", 1.0, 1.0, 1.0, 1.0, 1.0),
            Candle("2026-11-25 10:00", Double.NaN, 1.0, 1.0, 1.0, 1.0),
        ), 60)
        assertTrue(result.isEmpty())
    }
}
