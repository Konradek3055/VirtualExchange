package pl.virtualexchange.ui

import pl.virtualexchange.data.Candle
import kotlin.math.max
import kotlin.math.min
import java.util.Locale

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
 * Normalizuje świece z backendu i silnika offline. Sortuje czas, scala duplikaty,
 * uzupełnia prawdziwe luki świecą płaską i odrzuca wadliwe wartości OHLC.
 * Etykiety API są UTC: yyyy-MM-dd HH:mm (sekundy są tolerowane).
 */
private fun candleEpochMillis(label: String): Long? {
    val value = label.trim()
    val formats = listOf(
        java.time.format.DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm", Locale.US),
        java.time.format.DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss", Locale.US),
    )
    for (format in formats) {
        val parsed = runCatching { java.time.LocalDateTime.parse(value, format) }.getOrNull()
        if (parsed != null) return parsed.toInstant(java.time.ZoneOffset.UTC).toEpochMilli()
    }
    return null
}

fun fillCandles(candles: List<Candle>, intervalSeconds: Int, continuous: Boolean = false): List<Candle> {
    if (candles.isEmpty() || intervalSeconds <= 0) return emptyList()
    val stepMs = intervalSeconds.toLong() * 1000L
    val grouped = java.util.TreeMap<Long, Candle>()
    candles.sortedBy { candleEpochMillis(it.time) ?: Long.MAX_VALUE }.forEach { c ->
        val stamp = candleEpochMillis(c.time) ?: return@forEach
        if (!c.open.isFinite() || !c.high.isFinite() || !c.low.isFinite() || !c.close.isFinite()) return@forEach
        if (c.open <= 0.0 || c.high <= 0.0 || c.low <= 0.0 || c.close <= 0.0) return@forEach
        val bucket = Math.floorDiv(stamp, stepMs) * stepMs
        val high = max(c.high, max(c.open, c.close))
        val low = min(c.low, min(c.open, c.close))
        val valid = c.copy(high = high, low = low, volume = if (c.volume.isFinite() && c.volume > 0.0) c.volume else 0.0)
        val previous = grouped[bucket]
        grouped[bucket] = if (previous == null) valid else Candle(
            time = previous.time,
            open = previous.open,
            high = max(previous.high, valid.high),
            low = min(previous.low, valid.low),
            close = valid.close,
            volume = previous.volume + valid.volume,
        )
    }
    if (grouped.isEmpty()) return emptyList()

    val formatter = java.time.format.DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm", Locale.US)
    fun label(ms: Long) = java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneOffset.UTC).toLocalDateTime().format(formatter)
    val result = ArrayList<Candle>(min(2000, grouped.size + 32))
    var previousTime: Long? = null
    var previousClose: Double? = null
    for ((stamp, candle) in grouped) {
        val priorTime = previousTime
        val priorClose = previousClose
        if (priorTime != null && priorClose != null && stamp > priorTime + stepMs) {
            val missing = (stamp - priorTime) / stepMs - 1L
            if (missing in 1L..2000L) {
                var gapStamp = priorTime + stepMs
                while (gapStamp < stamp && result.size < 2000) {
                    result.add(Candle(label(gapStamp), priorClose, priorClose, priorClose, priorClose, 0.0))
                    gapStamp += stepMs
                }
            }
        }
        // Ciągłość wykresu: otwarcie świecy = zamknięcie poprzedniej (jak na giełdach z ciągłym rynkiem).
        // Bez tego między świecami widać "dziury" cenowe, gdy pierwsza transakcja okna jest po innej cenie.
        // Zmienia tylko prezentację open/high/low; close i wolumen pozostają rzeczywiste.
        val linked = if (continuous && priorClose != null) candle.copy(
            open = priorClose,
            high = max(candle.high, priorClose),
            low = min(candle.low, priorClose),
        ) else candle
        result.add(linked.copy(time = label(stamp)))
        previousTime = stamp
        previousClose = candle.close
    }
    return if (result.size > 2000) result.takeLast(2000) else result
}
