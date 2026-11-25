package pl.virtualexchange.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import pl.virtualexchange.data.Candle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

// Paleta jak w TradingView (ciemny motyw).
private val UP = Color(0xFF089981)
private val DOWN = Color(0xFFF23645)
private val GRID = Color(0xFF2A2E39)
private val AXIS_TXT = Color(0xFFB2B5BE)
private val PL = Locale.forLanguageTag("pl-PL")

data class TvInterval(val key: String, val label: String, val seconds: Int, val group: String)

/** Interwały obsługiwane przez serwer i silnik offline (tick/sekundy nie istnieją - silnik liczy świece od minuty). */
object TvIntervals {
    val all = listOf(
        TvInterval("1m", "1m", 60, "MINUTY"),
        TvInterval("3m", "3m", 180, "MINUTY"),
        TvInterval("5m", "5m", 300, "MINUTY"),
        TvInterval("15m", "15m", 900, "MINUTY"),
        TvInterval("30m", "30m", 1800, "MINUTY"),
        TvInterval("1h", "1h", 3600, "GODZINY"),
        TvInterval("4h", "4h", 14400, "GODZINY"),
        TvInterval("1d", "1D", 86400, "DNI"),
    )

    fun seconds(key: String) = all.firstOrNull { it.key == key }?.seconds ?: 60
    fun label(key: String) = all.firstOrNull { it.key == key }?.label ?: key
}

/** Prośba o ustawienie zakresu (liczby widocznych świec); id odróżnia kolejne kliknięcia tego samego zakresu. */
data class RangeReq(val candles: Int, val id: Int)

/** Cena w stylu TradingView (PL): 4 056,67 / 0,0245. */
fun fmtPx(v: Double): String {
    if (v == 0.0 || v.isNaN()) return "—"
    val dec = when {
        abs(v) >= 1 -> 2
        abs(v) >= 0.01 -> 4
        else -> 8
    }
    return String.format(Locale.US, "%,.${dec}f", v).replace(',', '\u00A0').replace('.', ',')
}

fun fmtPx(s: String): String = s.toDoubleOrNull()?.let { fmtPx(it) } ?: s

private fun compact(v: Double): String = when {
    v >= 1e9 -> String.format(PL, "%.2f B", v / 1e9)
    v >= 1e6 -> String.format(PL, "%.2f M", v / 1e6)
    v >= 1e3 -> String.format(PL, "%.2f K", v / 1e3)
    else -> String.format(PL, "%.4f", v)
}

private fun niceStep(range: Double, target: Int): Double {
    val raw = range / target
    if (raw <= 0.0 || raw.isNaN()) return 1.0
    val mag = 10.0.pow(floor(log10(raw)))
    val f = raw / mag
    val nf = when {
        f < 1.5 -> 1.0
        f < 3.5 -> 2.0
        f < 7.5 -> 5.0
        else -> 10.0
    }
    return nf * mag
}

private fun axisText(v: Double, step: Double): String {
    val dec = (-floor(log10(step))).toInt().coerceIn(0, 8)
    return String.format(PL, "%.${dec}f", v)
}

@OptIn(ExperimentalTextApi::class)
private fun DrawScope.txt(
    m: TextMeasurer, text: String, x: Float, y: Float, color: Color,
    size: TextUnit = 11.sp, bold: Boolean = false, center: Boolean = false,
) {
    val style = TextStyle(color = color, fontSize = size, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal)
    val r = m.measure(text, style)
    drawText(r, topLeft = Offset(if (center) x - r.size.width / 2f else x, y))
}

/** Prostokątny tag na osi cen (ostatnia cena + odliczanie / cena crosshaira). */
@OptIn(ExperimentalTextApi::class)
private fun DrawScope.axisTag(
    m: TextMeasurer, lines: List<String>, axisLeft: Float, centerY: Float,
    fill: Color, border: Color?, textColor: Color, minY: Float, maxY: Float,
) {
    val style = TextStyle(color = textColor, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    val layouts = lines.map { m.measure(it, style) }
    val h = layouts.sumOf { it.size.height }.toFloat() + 8.dp.toPx()
    val w = size.width - axisLeft - 2.dp.toPx()
    val top = (centerY - h / 2f).coerceIn(minY, max(minY, maxY - h))
    val radius = CornerRadius(4.dp.toPx())
    drawRoundRect(fill, Offset(axisLeft, top), Size(w, h), radius)
    if (border != null) drawRoundRect(border, Offset(axisLeft, top), Size(w, h), radius, style = Stroke(1.5f))
    var y = top + 4.dp.toPx()
    layouts.forEach { l ->
        drawText(l, topLeft = Offset(axisLeft + 6.dp.toPx(), y))
        y += l.size.height
    }
}

private val SMA_C = Color(0xFF2962FF)
private val EMA_C = Color(0xFFFF9800)
private val BB_C = Color(0xFF9C27B0)
private val VWAP_C = Color(0xFF00BCD4)
private val RSI_C = Color(0xFF7E57C2)
private val MACD_C = Color(0xFF2962FF)
private val SIG_C = Color(0xFFFF6D00)
private val DRAW_C = Color(0xFFFFC107)
private val LINE_C = Color(0xFF2962FF)

/** Gotowe serie wskaźników dla załadowanych świec (liczone raz na zmianę danych). */
private class Series(c: List<Candle>) {
    val closes = c.map { it.close }
    val sma = Ind.sma(closes, 20)
    val ema = Ind.ema(closes, 20)
    val bb = Ind.bollinger(closes, 20, 2.0)
    val vwap = Ind.vwap(c.map { it.high }, c.map { it.low }, closes, c.map { it.volume })
    val rsi = Ind.rsi(closes, 14)
    val macd = Ind.macd(closes, 12, 26, 9)
    val ep = LongArray(c.size) { epochOf(c[it].time) }
}

/** Geometria wykresu: zakres cen widocznych świec (z nakładkami) i przeliczanie cena <-> piksel. */
private class Geom(
    val i0: Int, val i1: Int, val lo: Double, val hi: Double,
    val topPad: Float, val mainBottom: Float, val priceBottom: Float, val volH: Float, val step: Double,
) {
    fun y(p: Double): Float = (topPad + (priceBottom - topPad) * (1.0 - (p - lo) / (hi - lo))).toFloat()
    fun price(yPx: Float): Double = lo + (priceBottom - yPx) / (priceBottom - topPad) * (hi - lo)
}

private fun DrawScope.seriesLine(
    values: List<Double?>, i0: Int, i1: Int, color: Color, width: Float,
    x: (Int) -> Float, y: (Double) -> Float,
) {
    val path = Path()
    var started = false
    for (i in i0 until i1) {
        val v = values[i]
        if (v == null) {
            started = false
            continue
        }
        if (!started) {
            path.moveTo(x(i), y(v))
            started = true
        } else {
            path.lineTo(x(i), y(v))
        }
    }
    drawPath(path, color, style = Stroke(width))
}

/** Rysunek użytkownika we współrzędnych pikseli (też podgląd podczas przeciągania). */
@OptIn(ExperimentalTextApi::class)
private fun DrawScope.shape(
    m: TextMeasurer, tool: DrawTool, xa: Float, ya: Float, xb: Float, yb: Float,
    plotW: Float, g: Geom, color: Color, handles: Boolean,
) {
    val r = 5.dp.toPx()
    when (tool) {
        DrawTool.TREND -> {
            drawLine(color, Offset(xa, ya), Offset(xb, yb), 2.5f)
            if (handles) {
                drawCircle(color, r, Offset(xa, ya))
                drawCircle(color, r, Offset(xb, yb))
            }
        }
        DrawTool.HLINE -> {
            drawLine(color, Offset(0f, ya), Offset(plotW, ya), 2f)
        }
        DrawTool.RECT -> {
            val l = min(xa, xb)
            val t = min(ya, yb)
            drawRect(color.copy(alpha = 0.14f), Offset(l, t), Size(abs(xb - xa), abs(yb - ya)))
            drawRect(color, Offset(l, t), Size(abs(xb - xa), abs(yb - ya)), style = Stroke(2f))
        }
        DrawTool.FIB -> {
            val p1 = g.price(ya)
            val p2 = g.price(yb)
            val left = min(xa, xb)
            drawLine(color.copy(alpha = 0.5f), Offset(xa, ya), Offset(xb, yb), 1.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
            for (lv in FIB_LEVELS) {
                val pr = p2 + (p1 - p2) * lv
                val yy = g.y(pr)
                drawLine(color.copy(alpha = 0.85f), Offset(left, yy), Offset(plotW, yy), 1.5f)
                val name = String.format(Locale.US, "%.3f", lv).trimEnd('0').trimEnd('.')
                txt(m, "$name  ${axisText(pr, g.step)}", left + 4.dp.toPx(), yy - 14.dp.toPx(), color)
            }
        }
        DrawTool.NONE -> {}
    }
}

/**
 * Wykres świecowy w stylu TradingView: oś cen po prawej z ładnymi krokami, kropkowana siatka,
 * wolumen nałożony na dole, tag ostatniej ceny z odliczaniem do zamknięcia świecy, oś czasu
 * (północ = pogrubiona data), crosshair (przytrzymaj i przeciągnij), pinch-zoom i przesuwanie.
 * Wskaźniki: SMA/EMA/Bollinger/VWAP na cenie oraz podokna RSI i MACD.
 * Narzędzia rysowania (linia trendu/pozioma, prostokąt, Fibonacci) są zakotwiczone w czasie i cenie.
 */
@OptIn(ExperimentalTextApi::class)
@Composable
fun TvChart(
    candles: List<Candle>,
    symbol: String,
    name: String,
    intervalSec: Int,
    rangeReq: RangeReq?,
    indicators: IndicatorSet,
    tool: DrawTool,
    drawings: List<Drawing>,
    onDrawingsChange: (List<Drawing>) -> Unit,
    onToolDone: () -> Unit,
    chartType: ChartType = ChartType.CANDLES,
    modifier: Modifier = Modifier,
) {
    var visible by remember { mutableFloatStateOf(70f) }
    var shift by remember { mutableFloatStateOf(0f) }
    var cross by remember { mutableStateOf<Offset?>(null) }
    var draftA by remember { mutableStateOf<Offset?>(null) }
    var draftB by remember { mutableStateOf<Offset?>(null) }
    var widthPx by remember { mutableFloatStateOf(0f) }
    var heightPx by remember { mutableFloatStateOf(0f) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val density = LocalDensity.current
    val axisPx = with(density) { 62.dp.toPx() }
    val topPad = with(density) { 84.dp.toPx() }
    val timeH = with(density) { 26.dp.toPx() }
    val measurer = rememberTextMeasurer()
    val n = candles.size
    val series = remember(candles) { Series(candles) }
    val shown = remember(candles, chartType) { if (chartType == ChartType.HEIKIN) heikinAshi(candles) else candles }

    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            now = System.currentTimeMillis()
        }
    }
    LaunchedEffect(rangeReq) {
        if (rangeReq != null) {
            visible = rangeReq.candles.toFloat().coerceIn(10f, 500f)
            shift = 0f
        }
    }
    LaunchedEffect(intervalSec) { shift = 0f }

    val vis = visible.roundToInt().coerceAtLeast(5)
    val shiftC = shift.coerceIn(-vis * 0.35f, max(0f, n - 5f))
    val plotW = (widthPx - axisPx).coerceAtLeast(1f)
    val cw = plotW / vis
    val startIdx = n - shiftC - vis

    // układ pionowy: cena + (opcjonalnie) RSI i MACD pod spodem
    val paneCount = (if (indicators.rsi) 1 else 0) + (if (indicators.macd) 1 else 0)
    val paneH = if (paneCount > 0) max(0f, heightPx - topPad - timeH) * 0.2f else 0f
    val mainBottom = heightPx - timeH - paneH * paneCount
    val volH = if (indicators.volume) (mainBottom - topPad) * 0.15f else 0f
    val priceBottom = mainBottom - volH * 0.6f

    val geom: Geom? = if (n == 0 || widthPx <= 0f || heightPx <= 0f || priceBottom <= topPad + 20f) null else {
        val i0 = floor(startIdx).toInt().coerceAtLeast(0)
        val i1 = ceil(startIdx + vis).toInt().coerceAtMost(n)
        if (i1 <= i0) null else {
            var lo = Double.MAX_VALUE
            var hi = -Double.MAX_VALUE
            fun take(v: Double?) {
                if (v != null) { lo = min(lo, v); hi = max(hi, v) }
            }
            for (i in i0 until i1) {
                take(shown[i].low); take(shown[i].high)
                if (indicators.sma) take(series.sma[i])
                if (indicators.ema) take(series.ema[i])
                if (indicators.bb) { take(series.bb.second[i]); take(series.bb.third[i]) }
                if (indicators.vwap) take(series.vwap[i])
            }
            val padV = if (hi > lo) (hi - lo) * 0.06 else abs(hi) * 0.01 + 1e-9
            lo -= padV
            hi += padV
            Geom(i0, i1, lo, hi, topPad, mainBottom, priceBottom, volH, niceStep(hi - lo, 7))
        }
    }

    fun toAnchor(o: Offset): Anchor? {
        val g = geom ?: return null
        val idx = (o.x / cw + startIdx - 0.5f).toDouble()
        return Anchor(epochAt(series.ep, idx, intervalSec), g.price(o.y))
    }
    fun anchorPx(a: Anchor): Offset? {
        val g = geom ?: return null
        val idx = xIndex(series.ep, a.epoch, intervalSec)
        return Offset(((idx - startIdx + 0.5) * cw).toFloat(), g.y(a.price))
    }

    val cwNow by rememberUpdatedState(cw)
    val nNow by rememberUpdatedState(n)
    val toAnchorNow by rememberUpdatedState<(Offset) -> Anchor?>({ o -> toAnchor(o) })
    val drawingsNow by rememberUpdatedState(drawings)

    val hoverIdx: Int? = cross?.let { c -> if (n == 0) null else floor(c.x / cw + startIdx).toInt().coerceIn(0, n - 1) }
    val hover: Candle? = hoverIdx?.let { candles[it] }
    val last = candles.lastOrNull()
    val prev = if (n >= 2) candles[n - 2] else null
    val headerColor: Color
    val headerText: String
    if (hover != null) {
        headerColor = if (hover.close >= hover.open) UP else DOWN
        headerText = "O ${fmtPx(hover.open)}  H ${fmtPx(hover.high)}  L ${fmtPx(hover.low)}  " +
            "C ${fmtPx(hover.close)}  Wol. ${compact(hover.volume)}"
    } else if (last != null) {
        val chg = if (prev != null) last.close - prev.close else null
        headerColor = if (chg == null || chg >= 0) UP else DOWN
        headerText = if (chg != null && prev != null && prev.close != 0.0) {
            val sign = if (chg >= 0) "+" else "−"
            "${fmtPx(last.close)}  $sign${fmtDelta(chg)} ($sign${String.format(PL, "%.2f", abs(chg / prev.close * 100))}%)"
        } else fmtPx(last.close)
    } else {
        headerColor = AXIS_TXT
        headerText = "Brak danych"
    }
    val li = hoverIdx ?: (n - 1)
    val legend = buildList<Pair<String, Color>> {
        fun f(v: Double?) = if (v == null) "—" else fmtPx(v)
        if (n > 0) {
            if (indicators.sma) add("SMA 20  ${f(series.sma[li])}" to SMA_C)
            if (indicators.ema) add("EMA 20  ${f(series.ema[li])}" to EMA_C)
            if (indicators.bb) add("BB 20,2  ${f(series.bb.third[li])} – ${f(series.bb.second[li])}" to BB_C)
            if (indicators.vwap) add("VWAP  ${f(series.vwap[li])}" to VWAP_C)
        }
    }

    // gesty: bez narzędzia = przesuwanie/zoom/crosshair; z narzędziem = rysowanie
    val gestures: Modifier = when (tool) {
        DrawTool.NONE -> Modifier
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    visible = (visible / zoom).coerceIn(10f, max(20f, nNow + 30f))
                    shift = (shift + pan.x / cwNow).coerceIn(-visible * 0.35f, max(0f, nNow - 5f))
                }
            }
            .pointerInput(Unit) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { cross = it },
                    onDrag = { change, _ -> cross = change.position },
                    onDragEnd = { cross = null },
                    onDragCancel = { cross = null },
                )
            }
        DrawTool.HLINE -> Modifier.pointerInput(tool) {
            detectTapGestures { pos ->
                val a = toAnchorNow(pos)
                if (a != null) {
                    onDrawingsChange(drawingsNow + Drawing(DrawTool.HLINE, a))
                    onToolDone()
                }
            }
        }
        else -> Modifier.pointerInput(tool) {
            detectDragGestures(
                onDragStart = { draftA = it; draftB = it },
                onDrag = { change, _ -> draftB = change.position },
                onDragEnd = {
                    val a = draftA?.let { toAnchorNow(it) }
                    val b = draftB?.let { toAnchorNow(it) }
                    if (a != null && b != null && a != b) {
                        onDrawingsChange(drawingsNow + Drawing(tool, a, b))
                        onToolDone()
                    }
                    draftA = null
                    draftB = null
                },
                onDragCancel = { draftA = null; draftB = null },
            )
        }
    }

    Box(
        modifier.background(Color.Black).onSizeChanged {
            widthPx = it.width.toFloat()
            heightPx = it.height.toFloat()
        }
    ) {
        if (n == 0) {
            Box(Modifier.fillMaxSize().padding(horizontal = 32.dp), contentAlignment = Alignment.Center) {
                Text(
                    "Brak transakcji — wykres powstanie z pierwszych dopasowanych zleceń",
                    color = AXIS_TXT, fontSize = 13.sp,
                )
            }
        } else {
            Canvas(Modifier.fillMaxSize().then(gestures)) {
                val g = geom ?: return@Canvas
                val bottomAll = size.height - timeH
                fun cx(i: Int): Float = (i - startIdx + 0.5f) * cw
                val dash = PathEffect.dashPathEffect(floatArrayOf(3f, 7f), 0f)

                // siatka cen + etykiety osi
                var p = ceil(g.lo / g.step) * g.step
                while (p <= g.hi) {
                    val yy = g.y(p)
                    drawLine(GRID, Offset(0f, yy), Offset(plotW, yy), 1f, pathEffect = dash)
                    txt(measurer, axisText(p, g.step), plotW + 8.dp.toPx(), yy - 7.dp.toPx(), AXIS_TXT)
                    p += g.step
                }

                // siatka czasu + etykiety (północ = pogrubiona data)
                val every = max(1, ceil(90.dp.toPx() / cw).toInt())
                for (i in g.i0 until g.i1) {
                    if (i % every != 0) continue
                    val t = candles[i].time
                    if (t.length < 16) continue
                    val x = cx(i)
                    if (x < 0f || x > plotW) continue
                    val hhmm = t.substring(11, 16)
                    val dayStart = hhmm == "00:00"
                    val label = when {
                        intervalSec >= 86400 -> t.substring(8, 10) + "." + t.substring(5, 7)
                        dayStart -> t.substring(8, 10)
                        else -> hhmm
                    }
                    drawLine(GRID, Offset(x, 0f), Offset(x, bottomAll), 1f, pathEffect = dash)
                    txt(measurer, label, x, bottomAll + 5.dp.toPx(), AXIS_TXT, bold = dayStart || intervalSec >= 86400, center = true)
                }

                // wolumen, wstęgi, świece, nakładki, rysunki (przycięte do obszaru ceny)
                clipRect(0f, 0f, plotW, mainBottom) {
                    var maxVol = 0.0
                    for (i in g.i0 until g.i1) maxVol = max(maxVol, candles[i].volume)

                    if (indicators.bb) {
                        val path = Path()
                        var first = true
                        val idxs = (g.i0 until g.i1).filter { series.bb.second[it] != null }
                        for (i in idxs) {
                            if (first) { path.moveTo(cx(i), g.y(series.bb.second[i]!!)); first = false }
                            else path.lineTo(cx(i), g.y(series.bb.second[i]!!))
                        }
                        for (i in idxs.reversed()) path.lineTo(cx(i), g.y(series.bb.third[i]!!))
                        if (idxs.size > 1) {
                            path.close()
                            drawPath(path, BB_C.copy(alpha = 0.08f))
                        }
                    }

                    for (i in g.i0 until g.i1) {
                        val c = shown[i]
                        val col = if (c.close >= c.open) UP else DOWN
                        val x = cx(i)
                        if (indicators.volume) {
                            val vh = if (maxVol > 0) (candles[i].volume / maxVol * volH * 0.95).toFloat() else 0f
                            drawRect(col.copy(alpha = 0.4f), Offset(x - cw * 0.45f, mainBottom - vh), Size(cw * 0.9f, vh))
                        }
                        when (chartType) {
                            ChartType.CANDLES, ChartType.HEIKIN -> {
                                val wick = (cw * 0.07f).coerceIn(1f, 2.5f)
                                drawLine(col, Offset(x, g.y(c.high)), Offset(x, g.y(c.low)), wick)
                                val top = g.y(max(c.open, c.close))
                                val bot = g.y(min(c.open, c.close))
                                drawRect(col, Offset(x - cw * 0.36f, top), Size(cw * 0.72f, max(bot - top, 1.5f)))
                            }
                            ChartType.BARS -> {
                                val w = (cw * 0.08f).coerceIn(1.5f, 3f)
                                val tick = cw * 0.38f
                                drawLine(col, Offset(x, g.y(c.high)), Offset(x, g.y(c.low)), w)
                                drawLine(col, Offset(x - tick, g.y(c.open)), Offset(x, g.y(c.open)), w)
                                drawLine(col, Offset(x, g.y(c.close)), Offset(x + tick, g.y(c.close)), w)
                            }
                            ChartType.LINE, ChartType.AREA -> {}
                        }
                    }

                    if (chartType == ChartType.LINE || chartType == ChartType.AREA) {
                        val line = Path()
                        val fill = Path()
                        var started = false
                        var lastX = 0f
                        for (i in g.i0 until g.i1) {
                            val x = cx(i)
                            val y = g.y(candles[i].close)
                            if (!started) {
                                line.moveTo(x, y)
                                fill.moveTo(x, g.priceBottom)
                                fill.lineTo(x, y)
                                started = true
                            } else {
                                line.lineTo(x, y)
                                fill.lineTo(x, y)
                            }
                            lastX = x
                        }
                        if (started) {
                            if (chartType == ChartType.AREA) {
                                fill.lineTo(lastX, g.priceBottom)
                                fill.close()
                                drawPath(fill, LINE_C.copy(alpha = 0.18f))
                            }
                            drawPath(line, LINE_C, style = Stroke(2.5f))
                        }
                    }

                    if (indicators.sma) seriesLine(series.sma, g.i0, g.i1, SMA_C, 2f, ::cx, g::y)
                    if (indicators.ema) seriesLine(series.ema, g.i0, g.i1, EMA_C, 2f, ::cx, g::y)
                    if (indicators.bb) {
                        seriesLine(series.bb.first, g.i0, g.i1, BB_C, 1.5f, ::cx, g::y)
                        seriesLine(series.bb.second, g.i0, g.i1, BB_C, 1.5f, ::cx, g::y)
                        seriesLine(series.bb.third, g.i0, g.i1, BB_C, 1.5f, ::cx, g::y)
                    }
                    if (indicators.vwap) seriesLine(series.vwap, g.i0, g.i1, VWAP_C, 2f, ::cx, g::y)

                    drawings.forEach { d ->
                        val a = anchorPx(d.a)
                        val b = d.b?.let { anchorPx(it) } ?: a
                        if (a != null && b != null) shape(measurer, d.tool, a.x, a.y, b.x, b.y, plotW, g, DRAW_C, true)
                    }
                    val da = draftA
                    val db = draftB
                    if (da != null && db != null) shape(measurer, tool, da.x, da.y, db.x, db.y, plotW, g, DRAW_C, true)
                }

                // etykiety cen linii poziomych na osi (poza przycięciem do wykresu)
                drawings.filter { it.tool == DrawTool.HLINE }.forEach { d ->
                    val a = anchorPx(d.a)
                    if (a != null && a.y >= g.topPad && a.y <= g.priceBottom) {
                        axisTag(measurer, listOf(axisText(d.a.price, g.step)), plotW, a.y, DRAW_C, null, Color.Black, g.topPad, g.mainBottom)
                    }
                }

                // podokna RSI / MACD
                var slot = 0
                fun paneTop() = mainBottom + paneH * slot
                if (indicators.rsi && paneH > 0f) {
                    val y0 = paneTop()
                    fun ry(v: Double): Float = (y0 + 18.dp.toPx() + (paneH - 24.dp.toPx()) * (1.0 - v / 100.0)).toFloat()
                    drawLine(GRID, Offset(0f, y0), Offset(plotW + axisPx, y0), 1f)
                    for (lv in listOf(30.0, 70.0)) {
                        drawLine(GRID, Offset(0f, ry(lv)), Offset(plotW, ry(lv)), 1f, pathEffect = dash)
                        txt(measurer, lv.toInt().toString(), plotW + 8.dp.toPx(), ry(lv) - 7.dp.toPx(), AXIS_TXT)
                    }
                    clipRect(0f, y0, plotW, y0 + paneH) {
                        seriesLine(series.rsi, g.i0, g.i1, RSI_C, 2f, ::cx, ::ry)
                    }
                    txt(measurer, "RSI 14  " + (series.rsi.getOrNull(li)?.let { String.format(PL, "%.2f", it) } ?: "—"), 8.dp.toPx(), y0 + 3.dp.toPx(), RSI_C)
                    slot++
                }
                if (indicators.macd && paneH > 0f) {
                    val y0 = paneTop()
                    var m = 1e-12
                    for (i in g.i0 until g.i1) {
                        for (v in listOf(series.macd.first[i], series.macd.second[i], series.macd.third[i])) if (v != null) m = max(m, abs(v))
                    }
                    val mid = y0 + 18.dp.toPx() + (paneH - 24.dp.toPx()) / 2f
                    val half = (paneH - 24.dp.toPx()) / 2f
                    fun my(v: Double): Float = (mid - v / m * half).toFloat()
                    drawLine(GRID, Offset(0f, y0), Offset(plotW + axisPx, y0), 1f)
                    drawLine(GRID, Offset(0f, mid), Offset(plotW, mid), 1f, pathEffect = dash)
                    clipRect(0f, y0, plotW, y0 + paneH) {
                        for (i in g.i0 until g.i1) {
                            val hv = series.macd.third[i] ?: continue
                            val col = if (hv >= 0) UP else DOWN
                            val top = min(mid, my(hv))
                            drawRect(col.copy(alpha = 0.55f), Offset(cx(i) - cw * 0.35f, top), Size(cw * 0.7f, abs(my(hv) - mid)))
                        }
                        seriesLine(series.macd.first, g.i0, g.i1, MACD_C, 2f, ::cx, ::my)
                        seriesLine(series.macd.second, g.i0, g.i1, SIG_C, 2f, ::cx, ::my)
                    }
                    txt(measurer, "MACD 12,26,9  " + (series.macd.first.getOrNull(li)?.let { String.format(PL, "%.4f", it) } ?: "—"), 8.dp.toPx(), y0 + 3.dp.toPx(), MACD_C)
                }

                // ostatnia cena: linia + tag z odliczaniem do zamknięcia świecy
                val lastC = candles[n - 1]
                val lc = if (lastC.close >= lastC.open) UP else DOWN
                val ly = g.y(lastC.close).coerceIn(g.topPad, g.priceBottom)
                drawLine(lc, Offset(0f, ly), Offset(plotW, ly), 1f, pathEffect = dash)
                val remain = intervalSec - ((now / 1000) % intervalSec).toInt()
                val cd = if (remain >= 3600) {
                    String.format(Locale.US, "%d:%02d:%02d", remain / 3600, (remain % 3600) / 60, remain % 60)
                } else {
                    String.format(Locale.US, "%02d:%02d", remain / 60, remain % 60)
                }
                axisTag(measurer, listOf(fmtPx(lastC.close), cd), plotW, ly, lc, null, Color.White, g.topPad, g.mainBottom)

                // crosshair
                cross?.let { c ->
                    val idx = floor(c.x / cw + startIdx).toInt().coerceIn(0, n - 1)
                    val x = cx(idx)
                    drawLine(AXIS_TXT, Offset(x, 0f), Offset(x, bottomAll), 1f, pathEffect = dash)
                    if (c.y <= g.priceBottom) {
                        val cy = c.y.coerceIn(g.topPad, g.priceBottom)
                        drawLine(AXIS_TXT, Offset(0f, cy), Offset(plotW, cy), 1f, pathEffect = dash)
                        axisTag(measurer, listOf(axisText(g.price(cy), g.step)), plotW, cy, Color(0xFF0A0A0A), UP, UP, g.topPad, g.mainBottom)
                    }
                }
            }
        }

        Column(Modifier.align(Alignment.TopStart).padding(start = 12.dp, top = 8.dp, end = 70.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(symbol, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(8.dp))
                Text(name, color = AXIS_TXT, fontSize = 13.sp, maxLines = 1)
            }
            Text(headerText, color = headerColor, fontSize = 13.sp)
            if (hover == null && last != null) {
                Text(
                    "O ${fmtPx(last.open)}  H ${fmtPx(last.high)}  L ${fmtPx(last.low)}  C ${fmtPx(last.close)}",
                    color = headerColor, fontSize = 11.sp, maxLines = 1,
                )
            }
            if (legend.isNotEmpty()) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    legend.forEach { (text, color) -> Text(text, color = color, fontSize = 10.sp, maxLines = 1) }
                }
            }
        }

        if (shiftC > 0.5f && n > 0) {
            Box(
                Modifier.align(Alignment.BottomEnd).padding(end = 72.dp, bottom = 40.dp).size(36.dp)
                    .clip(RoundedCornerShape(8.dp)).background(Color(0xFF2A2A2A))
                    .clickable { shift = 0f },
                contentAlignment = Alignment.Center,
            ) { Text("»", color = Color.White, fontSize = 20.sp) }
        }

        if (tool != DrawTool.NONE) {
            Text(
                if (tool == DrawTool.HLINE) "${tool.label}: dotknij wykresu  ✕ anuluj"
                else "${tool.label}: przeciągnij po wykresie  ✕ anuluj",
                color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 36.dp)
                    .clip(RoundedCornerShape(16.dp)).background(DRAW_C)
                    .clickable { onToolDone() }.padding(horizontal = 14.dp, vertical = 7.dp),
            )
        }
    }
}

@Composable
private fun TvChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .defaultMinSize(minWidth = 64.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) Color(0xFFF1F1F1) else Color(0xFF2A2A2A))
            .clickable(onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (selected) Color.Black else Color.White, fontSize = 18.sp)
    }
}

/** Arkusz "Zakres dat" + "Interwał" jak w TradingView. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IntervalSheet(
    selected: String,
    intervalSec: Int,
    onInterval: (String) -> Unit,
    onRange: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Color(0xFF1E1E1E)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp).padding(bottom = 28.dp),
        ) {
            Text("Zakres dat", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(16.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf("1D" to 1, "5D" to 5, "1M" to 30, "3M" to 90, "6M" to 180).forEach { (label, days) ->
                    TvChip(label, false) { onRange((days * 86400 / intervalSec).coerceIn(10, 500)) }
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 16.dp), color = Color(0xFF3A3A3A))
            Text("Interwał", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
            TvIntervals.all.groupBy { it.group }.forEach { (group, list) ->
                Spacer(Modifier.height(16.dp))
                Text(group, color = Color(0xFF9A9A9A), fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(10.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    list.forEach { iv -> TvChip(iv.label, iv.key == selected) { onInterval(iv.key) } }
                }
                HorizontalDivider(Modifier.padding(top = 16.dp), color = Color(0xFF3A3A3A))
            }
        }
    }
}
