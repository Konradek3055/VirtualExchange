package pl.virtualexchange.ui

import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.math.floor
import kotlin.math.sqrt

/** Czysta matematyka wskaźników (bez Androida) - testowana jednostkowo. Wynik: lista tej samej długości, null = brak danych. */
object Ind {
    fun sma(v: List<Double>, n: Int): List<Double?> {
        val out = MutableList<Double?>(v.size) { null }
        var sum = 0.0
        for (i in v.indices) {
            sum += v[i]
            if (i >= n) sum -= v[i - n]
            if (i >= n - 1) out[i] = sum / n
        }
        return out
    }

    fun ema(v: List<Double>, n: Int): List<Double?> = emaNullable(v.map { it }, n)

    /** EMA z ziarnem = SMA pierwszych n wartości; pomija początkowe nulle. */
    fun emaNullable(v: List<Double?>, n: Int): List<Double?> {
        val out = MutableList<Double?>(v.size) { null }
        val first = v.indexOfFirst { it != null }
        if (first < 0 || v.size - first < n) return out
        val k = 2.0 / (n + 1)
        var prev = 0.0
        for (i in first until first + n) prev += v[i]!!
        prev /= n
        out[first + n - 1] = prev
        for (i in first + n until v.size) {
            prev = v[i]!! * k + prev * (1 - k)
            out[i] = prev
        }
        return out
    }

    /** Wstęgi Bollingera: (środek = SMA, górna, dolna); odchylenie standardowe populacyjne. */
    fun bollinger(v: List<Double>, n: Int = 20, k: Double = 2.0): Triple<List<Double?>, List<Double?>, List<Double?>> {
        val mid = sma(v, n)
        val up = MutableList<Double?>(v.size) { null }
        val lo = MutableList<Double?>(v.size) { null }
        for (i in n - 1 until v.size) {
            val m = mid[i]!!
            var s = 0.0
            for (j in i - n + 1..i) s += (v[j] - m) * (v[j] - m)
            val sd = sqrt(s / n)
            up[i] = m + k * sd
            lo[i] = m - k * sd
        }
        return Triple(mid, up, lo)
    }

    /** RSI Wildera (0-100). Pierwsza wartość na indeksie n. */
    fun rsi(v: List<Double>, n: Int = 14): List<Double?> {
        val out = MutableList<Double?>(v.size) { null }
        if (v.size <= n) return out
        var gain = 0.0
        var loss = 0.0
        for (i in 1..n) {
            val d = v[i] - v[i - 1]
            if (d > 0) gain += d else loss -= d
        }
        gain /= n
        loss /= n
        fun value() = when {
            gain == 0.0 && loss == 0.0 -> 50.0
            loss == 0.0 -> 100.0
            else -> 100.0 - 100.0 / (1.0 + gain / loss)
        }
        out[n] = value()
        for (i in n + 1 until v.size) {
            val d = v[i] - v[i - 1]
            gain = (gain * (n - 1) + if (d > 0) d else 0.0) / n
            loss = (loss * (n - 1) + if (d < 0) -d else 0.0) / n
            out[i] = value()
        }
        return out
    }

    /** MACD: (linia MACD, sygnał, histogram). */
    fun macd(v: List<Double>, fast: Int = 12, slow: Int = 26, sig: Int = 9):
        Triple<List<Double?>, List<Double?>, List<Double?>> {
        val f = ema(v, fast)
        val s = ema(v, slow)
        val line = List(v.size) { i -> if (f[i] != null && s[i] != null) f[i]!! - s[i]!! else null }
        val signal = emaNullable(line, sig)
        val hist = List(v.size) { i -> if (line[i] != null && signal[i] != null) line[i]!! - signal[i]!! else null }
        return Triple(line, signal, hist)
    }

    /** VWAP narastająco od pierwszej załadowanej świecy (cena typowa = (H+L+C)/3). */
    fun vwap(h: List<Double>, l: List<Double>, c: List<Double>, vol: List<Double>): List<Double?> {
        val out = MutableList<Double?>(c.size) { null }
        var pv = 0.0
        var vv = 0.0
        for (i in c.indices) {
            pv += (h[i] + l[i] + c[i]) / 3.0 * vol[i]
            vv += vol[i]
            if (vv > 0) out[i] = pv / vv
        }
        return out
    }
}

/** "yyyy-MM-dd HH:mm" (UTC) -> sekundy epoch; 0 przy błędnym formacie. */
fun epochOf(time: String): Long = try {
    LocalDateTime.parse(time.trim().replace(' ', 'T').let { if (it.length == 16) "$it:00" else it })
        .toEpochSecond(ZoneOffset.UTC)
} catch (_: Exception) {
    0L
}

/** Pozycja (indeks, ułamkowy) czasu t na osi świec; luki bez transakcji nie zajmują miejsca. */
fun xIndex(ep: LongArray, t: Long, intervalSec: Int): Double {
    if (ep.isEmpty()) return 0.0
    if (t >= ep.last()) return ep.lastIndex + (t - ep.last()).toDouble() / intervalSec
    if (t <= ep.first()) return (t - ep.first()).toDouble() / intervalSec
    var lo = 0
    var hi = ep.lastIndex
    while (lo < hi) {
        val mid = (lo + hi + 1) / 2
        if (ep[mid] <= t) lo = mid else hi = mid - 1
    }
    val next = if (lo < ep.lastIndex) ep[lo + 1] else ep[lo] + intervalSec
    val frac = if (next > ep[lo]) (t - ep[lo]).toDouble() / (next - ep[lo]) else 0.0
    return lo + frac
}

/** Odwrotność [xIndex]: indeks -> czas. */
fun epochAt(ep: LongArray, idx: Double, intervalSec: Int): Long {
    if (ep.isEmpty()) return 0L
    if (idx >= ep.lastIndex) return ep.last() + ((idx - ep.lastIndex) * intervalSec).toLong()
    if (idx <= 0.0) return ep.first() + (idx * intervalSec).toLong()
    val lo = floor(idx).toInt()
    val frac = idx - lo
    return ep[lo] + ((ep[lo + 1] - ep[lo]) * frac).toLong()
}

enum class DrawTool(val label: String) {
    NONE("—"), TREND("Linia trendu"), HLINE("Linia pozioma"), RECT("Prostokąt"), FIB("Fibonacci")
}

/** Punkt zakotwiczony w czasie i cenie, więc rysunek zostaje na miejscu przy zoomie, przesuwaniu i zmianie interwału. */
data class Anchor(val epoch: Long, val price: Double)

data class Drawing(val tool: DrawTool, val a: Anchor, val b: Anchor? = null)

val FIB_LEVELS = listOf(0.0, 0.236, 0.382, 0.5, 0.618, 0.786, 1.0)

data class IndicatorSet(
    val volume: Boolean = true,
    val sma: Boolean = false,
    val ema: Boolean = false,
    val bb: Boolean = false,
    val vwap: Boolean = false,
    val rsi: Boolean = false,
    val macd: Boolean = false,
)
