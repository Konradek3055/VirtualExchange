package pl.virtualexchange.ui

import pl.virtualexchange.data.Candle
import kotlin.math.max
import kotlin.math.min
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

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


/**
 * Uzupełnia luki w szeregu świec, zachowując kolejność i wolumen.
 * Nie dopisuje świec poza zakresem danych otrzymanych z API.
 */
fun fillCandles(candles: List<Candle>, intervalSeconds: Int): List<Candle> {
    if (candles.size < 2 || intervalSeconds <= 0) return candles

    // Backend i silnik offline używają UTC: yyyy-MM-dd HH:mm.
    val parser = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).apply {
        isLenient = false
        timeZone = TimeZone.getTimeZone("UTC")
    }
    val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
    fun epoch(label: String): Long? = try { parser.parse(label)?.time } catch (_: Exception) { null }

    val stepMs = intervalSeconds.toLong() * 1000L
    val result = ArrayList<Candle>(candles.size + 16)
    for (candle in candles) {
        val previous = result.lastOrNull()
        val previousMs = previous?.let { epoch(it.time) }
        val currentMs = epoch(candle.time)
        if (previous != null && previousMs != null && currentMs != null) {
            val gap = currentMs - previousMs
            val missing = if (gap > stepMs && gap % stepMs == 0L) gap / stepMs - 1L else 0L
            // Ograniczenie chroni UI przed gigantycznym zakresem po błędnym znaczniku czasu.
            if (missing in 1L..500L) {
                var stamp = previousMs + stepMs
                while (stamp < currentMs) {
                    val flat = previous.close
                    result.add(Candle(formatter.format(Date(stamp)), flat, flat, flat, flat, 0.0))
                    stamp += stepMs
                }
            }
        }
        result.add(candle)
    }
    return if (result.size > 500) result.takeLast(500) else result
}

