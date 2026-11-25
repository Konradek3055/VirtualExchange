package pl.virtualexchange.ui

import pl.virtualexchange.data.Candle
import kotlin.math.max
import kotlin.math.min

/**
 * Typ prezentacji wykresu. Wartości są wspólne dla stanu Store,
 * arkusza wyboru oraz renderera TvChart.
 */
enum class ChartType(
    val label: String,
    val hint: String,
) {
    CANDLES("Świece", "Klasyczne świece OHLC"),
    HEIKIN("Heikin Ashi", "Wygładzone świece ograniczające szum"),
    BARS("Słupkowy", "Zakres ceny z poziomami otwarcia i zamknięcia"),
    LINE("Linia", "Linia oparta na cenach zamknięcia"),
    AREA("Obszar", "Linia ceny z wypełnionym obszarem"),
}

/**
 * Przekształca zwykłe świece OHLC w Heikin Ashi.
 * Czas i wolumen pozostają przypisane do oryginalnej świecy.
 */
fun heikinAshi(candles: List<Candle>): List<Candle> {
    if (candles.isEmpty()) return emptyList()

    var previousOpen = 0.0
    var previousClose = 0.0

    return candles.mapIndexed { index, candle ->
        val haClose = (candle.open + candle.high + candle.low + candle.close) / 4.0
        val haOpen = if (index == 0) {
            (candle.open + candle.close) / 2.0
        } else {
            (previousOpen + previousClose) / 2.0
        }
        val haHigh = max(candle.high, max(haOpen, haClose))
        val haLow = min(candle.low, min(haOpen, haClose))

        previousOpen = haOpen
        previousClose = haClose

        Candle(
            time = candle.time,
            open = haOpen,
            high = haHigh,
            low = haLow,
            close = haClose,
            volume = candle.volume,
        )
    }
}
