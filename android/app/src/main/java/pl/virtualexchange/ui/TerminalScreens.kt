@file:OptIn(ExperimentalMaterial3Api::class)
package pl.virtualexchange.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Timeline
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.json.JSONObject
import pl.virtualexchange.data.Api
import pl.virtualexchange.data.Candle
import pl.virtualexchange.data.Level
import pl.virtualexchange.data.fmt
import pl.virtualexchange.data.money
import java.math.BigDecimal
import java.math.RoundingMode

private val BG = Color(0xFF000000)
private val PANEL = Color(0xFF1E1E1E)
private val LINE = Color(0xFF2A2E39)
private val MUTED = Color(0xFF9A9A9A)
private val TXT = Color(0xFFFFFFFF)
private val BLUE = Color(0xFF2962FF)
private val GREEN = Color(0xFF089981)
private val RED = Color(0xFFF23645)

@Composable private fun Page(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(BG)) { content() }
}

@Composable private fun Heading(title: String, sub: String) {
    Column(Modifier.padding(top = 14.dp, bottom = 10.dp)) {
        Text(title, color = TXT, fontSize = 25.sp, fontWeight = FontWeight.Bold)
        Text(sub, color = MUTED, fontSize = 11.sp)
    }
}

@Composable private fun Label(s: String) {
    Text(s, color = MUTED, fontSize = 13.sp, fontWeight = FontWeight.Medium)
}

@Composable private fun Panel(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = PANEL,
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(Modifier.padding(13.dp), content = content)
    }
}

@Composable private fun Badge(symbol: String) {
    val colors = listOf(
        Color(0xFF2563EB), Color(0xFF0F9D85),
        Color(0xFF805AD5), Color(0xFFB7791F)
    )
    Surface(
        Modifier.size(35.dp),
        shape = CircleShape,
        color = colors[(symbol.hashCode() and 0x7fffffff) % colors.size].copy(alpha = .2f)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(symbol.take(1), color = TXT, fontWeight = FontWeight.Bold)
        }
    }
}

private fun change(store: Store, symbol: String): Double? {
    val s = store.stats.optJSONObject(symbol) ?: return null
    return if (s.optInt("trades") > 0) s.optDouble("change_pct") else null
}

@Composable private fun Change(value: Double?) {
    val color = when {
        value == null -> MUTED
        value >= 0 -> GREEN
        else -> RED
    }
    val text = if (value == null) "—" else
        (if (value > 0) "+" else "") +
        "%.2f".format(java.util.Locale.US, value) + "%"
    Text(text, color = color, fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalChartTab(store: Store, onTrade: () -> Unit) {
    val scope = rememberCoroutineScope()
    var showInterval by remember { mutableStateOf(false) }
    var showInfo by remember { mutableStateOf(false) }
    var showPicker by remember { mutableStateOf(false) }
    var showIndicators by remember { mutableStateOf(false) }
    var showDraw by remember { mutableStateOf(false) }
    var rangeReq by remember { mutableStateOf<RangeReq?>(null) }
    var rangeId by remember { mutableIntStateOf(0) }
    val drawings = store.drawings[store.selected] ?: emptyList()
    LaunchedEffect(store.interval, store.selected) {
        try { store.refreshMarket() } catch (_: Exception) {}
    }
    val asset = store.assets.firstOrNull { it.optString("symbol") == store.selected }
    val intervalSec = TvIntervals.seconds(store.interval)

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        TvChart(
            store.candles, store.selected, asset?.optString("name").orEmpty(),
            intervalSec, rangeReq, store.indicators, store.tool, drawings,
            onDrawingsChange = { list -> store.drawings[store.selected] = list },
            onToolDone = { store.tool = DrawTool.NONE },
            modifier = Modifier.weight(1f).fillMaxWidth()
        )
        Row(
            Modifier.fillMaxWidth().background(Color(0xFF0A0A0A)).padding(horizontal = 10.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = { showPicker = true }) {
                Text(store.selected, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
            TextButton(onClick = { showInterval = true }) {
                Text(TvIntervals.label(store.interval), color = Color.White, fontSize = 16.sp)
            }
            IconButton(onClick = { showIndicators = true }) {
                Icon(Icons.Outlined.Timeline, "Wskaźniki", tint = if (store.indicators != IndicatorSet()) BLUE else Color.White)
            }
            IconButton(onClick = { showDraw = true }) {
                Icon(Icons.Outlined.Edit, "Rysuj", tint = if (drawings.isNotEmpty() || store.tool != DrawTool.NONE) BLUE else Color.White)
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { showInfo = true }) { Icon(Icons.Outlined.Info, "Info", tint = MUTED) }
            Button(
                onClick = onTrade,
                colors = ButtonDefaults.buttonColors(containerColor = BLUE),
                contentPadding = PaddingValues(horizontal = 16.dp)
            ) { Text("Handel", maxLines = 1, softWrap = false) }
        }
    }

    if (showInterval) {
        IntervalSheet(
            selected = store.interval,
            intervalSec = intervalSec,
            onInterval = { store.interval = it; showInterval = false },
            onRange = { c -> rangeId++; rangeReq = RangeReq(c, rangeId); showInterval = false },
            onDismiss = { showInterval = false }
        )
    }
    if (showIndicators) {
        IndicatorSheet(store.indicators, onChange = { store.indicators = it }, onDismiss = { showIndicators = false })
    }
    if (showDraw) {
        DrawSheet(
            count = drawings.size,
            onPick = { store.tool = it; showDraw = false },
            onUndo = { store.drawings[store.selected] = drawings.dropLast(1) },
            onClear = { store.drawings[store.selected] = emptyList(); showDraw = false },
            onDismiss = { showDraw = false }
        )
    }
    if (showPicker) {
        ModalBottomSheet(onDismissRequest = { showPicker = false }, containerColor = Color(0xFF1E1E1E)) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
                store.assets.forEach { a ->
                    val s = a.optString("symbol")
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable { store.selected = s; showPicker = false }
                            .padding(vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Badge(s)
                        Column(Modifier.weight(1f).padding(start = 10.dp)) {
                            Text(s, color = Color.White, fontWeight = FontWeight.Bold)
                            Text(a.optString("name", s), color = MUTED, fontSize = 11.sp, maxLines = 1)
                        }
                        Text(fmtPx(a.optString("last_price", "0")), color = Color.White)
                    }
                    HorizontalDivider(color = LINE)
                }
            }
        }
    }
    if (showInfo) {
        ModalBottomSheet(onDismissRequest = { showInfo = false }, containerColor = Color(0xFF14181F)) {
            ChartInfo(store, scope) { showInfo = false; onTrade() }
        }
    }
}

@Composable
private fun ChartInfo(store: Store, scope: CoroutineScope, onTrade: () -> Unit) {
    val stat = store.stats.optJSONObject(store.selected)
    val asset = store.assets.firstOrNull { it.optString("symbol") == store.selected }
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp).padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Metric("MAKSIMUM", stat?.optString("high") ?: "—", Modifier.weight(1f))
            Metric("MINIMUM", stat?.optString("low") ?: "—", Modifier.weight(1f))
            Metric("TRANSAKCJE", stat?.optInt("trades")?.toString() ?: "0", Modifier.weight(1f))
        }
        Panel {
            Label("Informacje o aktywie")
            Spacer(Modifier.height(6.dp))
            val desc = asset?.optString("description").orEmpty()
            if (desc.isNotBlank()) Text(desc, color = TXT, fontSize = 11.sp)
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Podaż całkowita (stała)", color = MUTED, fontSize = 11.sp)
                Text(fmt(asset?.optString("total_supply", "0") ?: "0"), color = TXT, fontSize = 11.sp)
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Posiadacze", color = MUTED, fontSize = 11.sp)
                Text(stat?.optInt("holders")?.toString() ?: "0", color = TXT, fontSize = 11.sp)
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Wolumen 24h", color = MUTED, fontSize = 11.sp)
                Text("$" + fmt(stat?.optString("volume_usd", "0") ?: "0"), color = TXT, fontSize = 11.sp)
            }
            if (!Api.offline) {
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            try {
                                val r = Api.post("assets/${store.selected}/report", JSONObject())
                                store.message = "Zgłoszono (${r.optInt("reports")})" +
                                    if (r.optBoolean("flagged")) " · oznaczone do moderacji" else ""
                            } catch (e: Exception) {
                                store.message = e.message ?: "Błąd zgłoszenia"
                            }
                        }
                    },
                    Modifier.fillMaxWidth().padding(top = 8.dp)
                ) { Text("Zgłoś podejrzane aktywo", fontSize = 11.sp) }
                if (store.message.isNotBlank()) Text(store.message, color = MUTED, fontSize = 10.sp)
            }
        }
        Button(
            onClick = onTrade,
            Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = BLUE)
        ) { Text("Przejdź do handlu") }
        Label("Ostatnie transakcje")
        store.trades.take(10).forEach { tr ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(fmtPx(tr.optString("price")), color = TXT, fontSize = 11.sp)
                Text(fmt(tr.optString("quantity")), color = MUTED, fontSize = 11.sp)
                Text(tr.optString("created_at").takeLast(8), color = MUTED, fontSize = 10.sp)
            }
            HorizontalDivider(color = LINE)
        }
    }
}

@Composable private fun Metric(label: String, value: String, modifier: Modifier) {
    Surface(
        modifier,
        color = PANEL,
        shape = RoundedCornerShape(8.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, LINE)
    ) {
        Column(Modifier.padding(9.dp)) {
            Text(label, color = MUTED, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            Text(value, color = TXT, fontSize = 11.sp, maxLines = 1)
        }
    }
}

@Composable private fun Candles(candles: List<Candle>, modifier: Modifier) {
    Canvas(modifier) {
        if (candles.isEmpty()) return@Canvas
        val top = 8f
        val bottom = size.height * .76f
        val low = candles.minOf { it.low }
        val high = candles.maxOf { it.high }
        val range = (high - low).let { if (it <= 0.0) 1.0 else it }
        val maxVol = candles.maxOf { it.volume }.let { if (it <= 0.0) 1.0 else it }
        val step = size.width / candles.size
        val body = (step * .58f).coerceAtLeast(2f)

        for (i in 0..4) {
            val y = top + (bottom - top) * i / 4f
            drawLine(LINE, Offset(0f, y), Offset(size.width, y), 1f)
        }

        candles.forEachIndexed { i, c ->
            val x = step * (i + .5f)
            fun y(v: Double) =
                bottom - ((v - low) / range).toFloat() * (bottom - top)

            val color = if (c.close >= c.open) GREEN else RED
            drawLine(color, Offset(x, y(c.high)), Offset(x, y(c.low)), 1.4f)
            drawRect(
                color,
                Offset(x - body / 2, minOf(y(c.open), y(c.close))),
                androidx.compose.ui.geometry.Size(
                    body,
                    maxOf(2f, kotlin.math.abs(y(c.close) - y(c.open)))
                )
            )
            val vh = (c.volume / maxVol).toFloat() * (size.height - bottom - 5f)
            drawRect(
                color.copy(alpha = .4f),
                Offset(x - body / 2, size.height - vh),
                androidx.compose.ui.geometry.Size(body, vh)
            )
        }
    }
}
