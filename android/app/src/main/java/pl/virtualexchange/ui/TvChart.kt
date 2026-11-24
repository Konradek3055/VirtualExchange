package pl.virtualexchange.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
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

/**
 * Wykres świecowy w stylu TradingView: oś cen po prawej z ładnymi krokami, kropkowana siatka,
 * wolumen nałożony na dole, tag ostatniej ceny z odliczaniem do zamknięcia świecy, oś czasu
 * (północ = pogrubiona data), crosshair (przytrzymaj i przeciągnij), pinch-zoom i przesuwanie.
 * Skala ceny dopasowuje się do widocznych świec.
 */
@OptIn(ExperimentalTextApi::class)
@Composable
fun TvChart(
    candles: List<Candle>,
    symbol: String,
    name: String,
    intervalSec: Int,
    rangeReq: RangeReq?,
    modifier: Modifier = Modifier,
) {
    var visible by remember { mutableFloatStateOf(70f) }
    var shift by remember { mutableFloatStateOf(0f) }
    var cross by remember { mutableStateOf<Offset?>(null) }
    var widthPx by remember { mutableFloatStateOf(0f) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val density = LocalDensity.current
    val axisPx = with(density) { 62.dp.toPx() }
    val measurer = rememberTextMeasurer()
    val n = candles.size

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
    val cwNow by rememberUpdatedState(cw)
    val nNow by rememberUpdatedState(n)

    val hover: Candle? = cross?.let { c ->
        if (n == 0) null else candles[floor(c.x / cw + startIdx).toInt().coerceIn(0, n - 1)]
    }
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
            "${fmtPx(last.close)}  $sign${fmtPx(abs(chg))} ($sign${String.format(PL, "%.2f", abs(chg / prev.close * 100))}%)"
        } else fmtPx(last.close)
    } else {
        headerColor = AXIS_TXT
        headerText = "Brak danych"
    }

    Box(modifier.background(Color.Black).onSizeChanged { widthPx = it.width.toFloat() }) {
        if (n == 0) {
            Box(Modifier.fillMaxSize().padding(horizontal = 32.dp), contentAlignment = Alignment.Center) {
                Text(
                    "Brak transakcji — wykres powstanie z pierwszych dopasowanych zleceń",
                    color = AXIS_TXT, fontSize = 13.sp,
                )
            }
        } else {
            Canvas(
                Modifier.fillMaxSize()
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
                    },
            ) {
                val topPad = 66.dp.toPx()
                val timeH = 26.dp.toPx()
                val plotH = size.height - timeH
                val volH = plotH * 0.15f
                val priceBottom = plotH - volH * 0.6f
                val i0 = floor(startIdx).toInt().coerceAtLeast(0)
                val i1 = ceil(startIdx + vis).toInt().coerceAtMost(n)
                if (i1 <= i0) return@Canvas

                var lo = Double.MAX_VALUE
                var hi = -Double.MAX_VALUE
                var maxVol = 0.0
                for (i in i0 until i1) {
                    val c = candles[i]
                    lo = min(lo, c.low)
                    hi = max(hi, c.high)
                    maxVol = max(maxVol, c.volume)
                }
                val padV = if (hi > lo) (hi - lo) * 0.06 else abs(hi) * 0.01 + 1e-9
                lo -= padV
                hi += padV
                fun y(p: Double): Float = (topPad + (priceBottom - topPad) * (1.0 - (p - lo) / (hi - lo))).toFloat()

                val dash = PathEffect.dashPathEffect(floatArrayOf(3f, 7f), 0f)

                // siatka cen + etykiety osi
                val step = niceStep(hi - lo, 7)
                var p = ceil(lo / step) * step
                while (p <= hi) {
                    val yy = y(p)
                    drawLine(GRID, Offset(0f, yy), Offset(plotW, yy), 1f, pathEffect = dash)
                    txt(measurer, axisText(p, step), plotW + 8.dp.toPx(), yy - 7.dp.toPx(), AXIS_TXT)
                    p += step
                }

                // siatka czasu + etykiety (północ = pogrubiona data)
                val every = max(1, ceil(90.dp.toPx() / cw).toInt())
                for (i in i0 until i1) {
                    if (i % every != 0) continue
                    val t = candles[i].time
                    if (t.length < 16) continue
                    val x = (i - startIdx + 0.5f) * cw
                    if (x < 0f || x > plotW) continue
                    val hhmm = t.substring(11, 16)
                    val dayStart = hhmm == "00:00"
                    val label = when {
                        intervalSec >= 86400 -> t.substring(8, 10) + "." + t.substring(5, 7)
                        dayStart -> t.substring(8, 10)
                        else -> hhmm
                    }
                    drawLine(GRID, Offset(x, 0f), Offset(x, plotH), 1f, pathEffect = dash)
                    txt(measurer, label, x, plotH + 5.dp.toPx(), AXIS_TXT, bold = dayStart || intervalSec >= 86400, center = true)
                }

                // wolumen + świece
                clipRect(0f, 0f, plotW, plotH) {
                    for (i in i0 until i1) {
                        val c = candles[i]
                        val col = if (c.close >= c.open) UP else DOWN
                        val x = (i - startIdx + 0.5f) * cw
                        val vh = if (maxVol > 0) (c.volume / maxVol * volH * 0.95).toFloat() else 0f
                        drawRect(col.copy(alpha = 0.4f), Offset(x - cw * 0.45f, plotH - vh), Size(cw * 0.9f, vh))
                        val wick = (cw * 0.07f).coerceIn(1f, 2.5f)
                        drawLine(col, Offset(x, y(c.high)), Offset(x, y(c.low)), wick)
                        val top = y(max(c.open, c.close))
                        val bot = y(min(c.open, c.close))
                        drawRect(col, Offset(x - cw * 0.36f, top), Size(cw * 0.72f, max(bot - top, 1.5f)))
                    }
                }

                // ostatnia cena: linia + tag z odliczaniem do zamknięcia świecy
                val lastC = candles[n - 1]
                val lc = if (lastC.close >= lastC.open) UP else DOWN
                val ly = y(lastC.close).coerceIn(topPad, priceBottom)
                drawLine(lc, Offset(0f, ly), Offset(plotW, ly), 1f, pathEffect = dash)
                val remain = intervalSec - ((now / 1000) % intervalSec).toInt()
                val cd = if (remain >= 3600) {
                    String.format(Locale.US, "%d:%02d:%02d", remain / 3600, (remain % 3600) / 60, remain % 60)
                } else {
                    String.format(Locale.US, "%02d:%02d", remain / 60, remain % 60)
                }
                axisTag(measurer, listOf(axisText(lastC.close, step), cd), plotW, ly, lc, null, Color.White, topPad, plotH)

                // crosshair
                cross?.let { c ->
                    val idx = floor(c.x / cw + startIdx).toInt().coerceIn(0, n - 1)
                    val cx = (idx - startIdx + 0.5f) * cw
                    val cy = c.y.coerceIn(topPad, priceBottom)
                    drawLine(AXIS_TXT, Offset(cx, 0f), Offset(cx, plotH), 1f, pathEffect = dash)
                    drawLine(AXIS_TXT, Offset(0f, cy), Offset(plotW, cy), 1f, pathEffect = dash)
                    val price = lo + (priceBottom - cy) / (priceBottom - topPad) * (hi - lo)
                    axisTag(measurer, listOf(axisText(price, step)), plotW, cy, Color(0xFF0A0A0A), UP, UP, topPad, plotH)
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
