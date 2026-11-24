@file:OptIn(ExperimentalMaterial3Api::class)
package pl.virtualexchange.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.foundation.text.KeyboardOptions
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

private val BG = Color(0xFF080B10)
private val PANEL = Color(0xFF101720)
private val LINE = Color(0xFF202B37)
private val MUTED = Color(0xFF8291A3)
private val TXT = Color(0xFFE7EDF4)
private val BLUE = Color(0xFF3B8DFF)
private val GREEN = Color(0xFF00B987)
private val RED = Color(0xFFF05265)

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
    Text(s.uppercase(), color = MUTED, fontSize = 10.sp,
        fontWeight = FontWeight.Bold, letterSpacing = .7.sp)
}

@Composable private fun Panel(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = PANEL,
        shape = RoundedCornerShape(10.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, LINE)
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

@Composable
fun TerminalMarketTab(store: Store, onOpen: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val assets = store.assets.filter {
        query.isBlank() ||
        it.optString("symbol").contains(query, true) ||
        it.optString("name").contains(query, true)
    }

    Page {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 15.dp)
        ) {
            item {
                Heading("Rynek", "Wirtualne aktywa · wspólny rynek")
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Label("Instrumenty")
                        Text("${assets.size} aktywów", color = MUTED, fontSize = 11.sp)
                    }
                    Text(
                        if (store.status.startsWith("ONLINE")) "● ONLINE"
                        else "● SYMULACJA",
                        color = GREEN, fontSize = 10.sp, fontWeight = FontWeight.Bold
                    )
                }
                OutlinedTextField(
                    query, { query = it },
                    Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("Szukaj aktywa lub tickera") },
                    shape = RoundedCornerShape(8.dp)
                )
                Spacer(Modifier.height(14.dp))
                Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                    Label("Aktywo")
                    Spacer(Modifier.weight(1f))
                    Label("Cena")
                    Spacer(Modifier.width(65.dp))
                    Label("24h")
                }
                HorizontalDivider(color = LINE)
            }

            if (assets.isEmpty()) {
                item {
                    Text(
                        if (store.assets.isEmpty()) "Ładowanie rynku…"
                        else "Nie znaleziono aktywa.",
                        color = MUTED,
                        modifier = Modifier.padding(20.dp)
                    )
                }
            }

            items(assets, key = { it.optString("symbol") }) { a ->
                val symbol = a.optString("symbol")
                val stat = store.stats.optJSONObject(symbol)

                Row(
                    Modifier.fillMaxWidth()
                        .clickable {
                            store.selected = symbol
                            onOpen()
                        }
                        .padding(vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Badge(symbol)
                    Column(
                        Modifier.weight(1f).padding(start = 10.dp)
                    ) {
                        Text(symbol, color = TXT, fontWeight = FontWeight.Bold)
                        Text(
                            a.optString("name", symbol),
                            color = MUTED, fontSize = 10.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            "$" + fmt(a.optString("last_price", "0")),
                            color = TXT, fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            "Vol. $" + fmt(stat?.optString("volume_usd", "0") ?: "0"),
                            color = MUTED, fontSize = 9.sp
                        )
                    }
                    Box(Modifier.width(62.dp), contentAlignment = Alignment.CenterEnd) {
                        Change(change(store, symbol))
                    }
                }
                HorizontalDivider(color = LINE)
            }
        }
    }
}

@Composable
fun TerminalChartTab(store: Store, onTrade: () -> Unit) {
    val scope = rememberCoroutineScope()
    LaunchedEffect(store.interval) {
        try { store.refreshMarket() } catch (_: Exception) {}
    }
    val stat = store.stats.optJSONObject(store.selected)
    val asset = store.assets.firstOrNull {
        it.optString("symbol") == store.selected
    }

    Page {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 14.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            item {
                Heading("Wykres", "Analiza ceny · ${store.selected}")
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(store.assets, key = { it.optString("symbol") }) { a ->
                        val s = a.optString("symbol")
                        FilterChip(
                            selected = s == store.selected,
                            onClick = { store.selected = s },
                            label = { Text(s, fontSize = 11.sp) }
                        )
                    }
                }
            }

            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Badge(store.selected)
                    Column(Modifier.weight(1f).padding(start = 9.dp)) {
                        Text("${store.selected}/USD", color = TXT, fontWeight = FontWeight.Bold)
                        Text(
                            asset?.optString("name", "Aktywo") ?: "Aktywo",
                            color = MUTED, fontSize = 10.sp
                        )
                    }
                    listOf("1m" to "1M", "5m" to "5M", "1h" to "1H").forEach { (key, label) ->
                        Text(
                            label,
                            color = if (store.interval == key) BLUE else MUTED,
                            fontSize = 11.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .clickable { store.interval = key }
                                .padding(horizontal = 7.dp, vertical = 5.dp)
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        "$" + fmt(store.lastPrice),
                        color = TXT, fontSize = 27.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                    Spacer(Modifier.width(8.dp))
                    Change(change(store, store.selected))
                }
            }

            item {
                Panel {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Label("Wykres świecowy")
                        Text("${store.candles.size} świec", color = MUTED, fontSize = 10.sp)
                    }
                    Spacer(Modifier.height(10.dp))
                    if (store.candles.isEmpty()) {
                        Box(
                            Modifier.fillMaxWidth().height(230.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("Wykres pojawi się po transakcjach.", color = MUTED)
                        }
                    } else {
                        Candles(store.candles, Modifier.fillMaxWidth().height(230.dp))
                    }
                }
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    Metric("MAKSIMUM", stat?.optString("high") ?: "—", Modifier.weight(1f))
                    Metric("MINIMUM", stat?.optString("low") ?: "—", Modifier.weight(1f))
                    Metric("TRANSAKCJE", stat?.optInt("trades")?.toString() ?: "0", Modifier.weight(1f))
                }
            }

            item {
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
            }

            item {
                Panel {
                    Label("Wolumen 24h")
                    Text(
                        "$" + fmt(stat?.optString("volume_usd", "0") ?: "0"),
                        color = TXT, fontSize = 17.sp, fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = onTrade,
                        Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = BLUE)
                    ) {
                        Text("Przejdź do handlu")
                    }
                }
            }

            item { Label("Ostatnie transakcje") }
            items(store.trades.take(10)) { t ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("$" + fmt(t.optString("price")), color = TXT, fontSize = 11.sp)
                    Text(fmt(t.optString("quantity")), color = MUTED, fontSize = 11.sp)
                    Text(t.optString("created_at").takeLast(8), color = MUTED, fontSize = 10.sp)
                }
                HorizontalDivider(color = LINE)
            }
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

@Composable
fun TerminalTradeTab(store: Store, scope: CoroutineScope) {
    var side by remember { mutableStateOf("buy") }
    var kind by remember { mutableStateOf("limit") }
    var qty by remember { mutableStateOf("") }
    var price by remember(store.selected) {
        mutableStateOf(if (store.lastPrice == "0") "" else fmt(store.lastPrice))
    }

    val estimate =
        (qty.toDoubleOrNull() ?: 0.0) * (price.toDoubleOrNull() ?: 0.0)

    Page {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 14.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            item {
                Heading("Handel", "Księga zleceń · ${store.selected}")
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(store.assets, key = { it.optString("symbol") }) { a ->
                        val s = a.optString("symbol")
                        FilterChip(
                            s == store.selected,
                            { store.selected = s },
                            label = { Text(s, fontSize = 11.sp) }
                        )
                    }
                }
            }

            item {
                Panel {
                    Label("Ostatnia transakcja")
                    Text(
                        "$" + fmt(store.lastPrice),
                        color = TXT, fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            item {
                Panel {
                    Label("Księga zleceń")
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text("KUPNO", color = GREEN, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            store.bids.take(7).forEach {
                                BookLine(it, GREEN) { price = fmt(it.price) }
                            }
                        }
                        Column(Modifier.weight(1f)) {
                            Text("SPRZEDAŻ", color = RED, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            store.asks.take(7).reversed().forEach {
                                BookLine(it, RED) { price = fmt(it.price) }
                            }
                        }
                    }
                    HorizontalDivider(color = LINE, modifier = Modifier.padding(vertical = 7.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Spread", color = MUTED, fontSize = 10.sp)
                        val spread =
                            if (store.asks.isNotEmpty() && store.bids.isNotEmpty())
                                (store.asks.first().price.toDoubleOrNull() ?: 0.0) -
                                (store.bids.first().price.toDoubleOrNull() ?: 0.0)
                            else null
                        Text(spread?.let { "$" + fmt(it.toString()) } ?: "—", color = TXT)
                    }
                }
            }

            item {
                Panel {
                    Label("Nowe zlecenie")
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        Button(
                            onClick = { side = "buy" },
                            Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (side == "buy") GREEN else Color(0xFF1A2930)
                            )
                        ) { Text("KUP") }

                        Button(
                            onClick = { side = "sell" },
                            Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (side == "sell") RED else Color(0xFF302027)
                            )
                        ) { Text("SPRZEDAJ") }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(kind == "limit", { kind = "limit" }, label = { Text("Limit") })
                        FilterChip(kind == "market", { kind = "market" }, label = { Text("Market") })
                    }

                    OutlinedTextField(
                        qty, { qty = it },
                        Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("Ilość · ${store.selected}") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                    )
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf(25, 50, 75, 100).forEach { pct ->
                            OutlinedButton(
                                onClick = { qty = pctQty(store, side, kind, price, pct) },
                                Modifier.weight(1f),
                                contentPadding = PaddingValues(0.dp)
                            ) { Text("$pct%", fontSize = 11.sp) }
                        }
                    }

                    if (kind == "limit") {
                        OutlinedTextField(
                            price, { price = it },
                            Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text("Cena limitu · USD") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                        )
                    }

                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Szacowana wartość", color = MUTED, fontSize = 11.sp)
                        Text(
                            if (kind == "limit" && estimate > 0) "$" + money(estimate)
                            else "Zależna od księgi",
                            color = TXT, fontSize = 11.sp
                        )
                    }

                    Button(
                        onClick = {
                            scope.launch {
                                try {
                                    val body = JSONObject()
                                        .put("symbol", store.selected)
                                        .put("side", side)
                                        .put("order_type", kind)
                                        .put("quantity", qty.trim())
                                    if (kind == "limit") body.put("price", price.trim())

                                    val response = Api.post("orders", body)
                                    store.message = "Zlecenie: " + response.optString("status")
                                    qty = ""
                                    store.refreshAll()
                                } catch (e: Exception) {
                                    store.message = e.message ?: "Błąd zlecenia"
                                }
                            }
                        },
                        Modifier.fillMaxWidth().height(46.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (side == "buy") GREEN else RED
                        )
                    ) {
                        Text(if (side == "buy") "Kup ${store.selected}" else "Sprzedaj ${store.selected}")
                    }
                    if (store.message.isNotBlank()) {
                        Text(store.message, color = MUTED, fontSize = 10.sp)
                    }
                }
            }

            item { Label("Ostatnie transakcje") }
            items(store.trades.take(8)) { t ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("$" + fmt(t.optString("price")), color = TXT, fontSize = 11.sp)
                    Text(fmt(t.optString("quantity")), color = MUTED, fontSize = 11.sp)
                    Text(t.optString("created_at").takeLast(8), color = MUTED, fontSize = 10.sp)
                }
                HorizontalDivider(color = LINE)
            }
        }
    }
}

@Composable private fun BookLine(level: Level, color: Color, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(fmt(level.price), color = color, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        Text(fmt(level.qty), color = TXT, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
fun TerminalPortfolioTab(store: Store, scope: CoroutineScope) {
    var total = 0.0
    var cash = 0.0
    var locked = 0.0

    store.portfolio.forEach { row ->
        val available = row.optString("available").toDoubleOrNull() ?: 0.0
        val held = row.optString("locked").toDoubleOrNull() ?: 0.0

        if (row.optString("symbol") == "USD") {
            cash = available
            locked = held
        }

        total += if (row.optString("symbol") == "USD") available + held
        else (available + held) * row.optDouble("last_price", 0.0)
    }

    val openOrders = store.orders.filter {
        it.optString("status") == "OPEN" || it.optString("status") == "PARTIAL"
    }

    Page {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 15.dp)
        ) {
            item {
                Heading("Portfel", "Saldo, aktywa i aktywne zlecenia")
                Panel {
                    Label("Łączna wartość")
                    Text(
                        "$" + money(total),
                        color = TXT, fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider(color = LINE)
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Dostępne USD", color = MUTED, fontSize = 11.sp)
                        Text("$" + money(cash), color = GREEN)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Zablokowane USD", color = MUTED, fontSize = 11.sp)
                        Text("$" + money(locked), color = TXT)
                    }
                }
                if (store.accountError.isNotBlank()) {
                    Text(store.accountError, color = RED, fontSize = 10.sp)
                }
                Spacer(Modifier.height(15.dp))
                Label("Twoje aktywa")
            }

            if (store.portfolio.isEmpty()) {
                item { Text("Portfel jest pusty.", color = MUTED) }
            }

            items(store.portfolio, key = { it.optString("symbol") }) { row ->
                val symbol = row.optString("symbol")
                val available = row.optString("available", "0")
                val held = row.optString("locked", "0")

                Row(
                    Modifier.fillMaxWidth().padding(vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Badge(symbol)
                    Column(Modifier.weight(1f).padding(start = 9.dp)) {
                        Text(symbol, color = TXT, fontWeight = FontWeight.Bold)
                        Text(
                            "Dostępne ${fmt(available)} · zablokowane ${fmt(held)}",
                            color = MUTED, fontSize = 9.sp
                        )
                    }
                    Text(
                        fmt(((available.toDoubleOrNull() ?: 0.0) +
                            (held.toDoubleOrNull() ?: 0.0)).toString()),
                        color = TXT, fontSize = 12.sp
                    )
                }
                HorizontalDivider(color = LINE)
            }

            item {
                Spacer(Modifier.height(15.dp))
                Label("Otwarte zlecenia · ${openOrders.size}")
            }

            if (openOrders.isEmpty()) {
                item { Text("Nie masz aktywnych zleceń.", color = MUTED) }
            }

            items(openOrders, key = { it.optInt("id") }) { order ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        val buy = order.optString("side") == "buy"
                        Text(
                            "${order.optString("side").uppercase()} ${order.optString("symbol")}",
                            color = if (buy) GREEN else RED,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "Pozostało ${fmt(order.optString("remaining"))}",
                            color = MUTED, fontSize = 10.sp
                        )
                    }
                    TextButton(onClick = {
                        scope.launch {
                            try {
                                Api.delete("orders/${order.optInt("id")}")
                                store.message = "Anulowano zlecenie"
                                store.refreshAll()
                            } catch (e: Exception) {
                                store.message = e.message ?: "Nie udało się anulować"
                            }
                        }
                    }) {
                        Text("ANULUJ", color = RED, fontSize = 10.sp)
                    }
                }
                HorizontalDivider(color = LINE)
            }

            if (store.message.isNotBlank()) {
                item { Text(store.message, color = MUTED, fontSize = 10.sp) }
            }
        }
    }
}

/** Ilość odpowiadająca procentowi dostępnego salda (kupno: USD / cena, sprzedaż: dostępne aktywo). */
private fun pctQty(store: Store, side: String, kind: String, price: String, pct: Int): String {
    val symbol = if (side == "buy") "USD" else store.selected
    val available = store.portfolio.firstOrNull { it.optString("symbol") == symbol }
        ?.optString("available")?.toBigDecimalOrNull() ?: return ""
    val share = available.multiply(BigDecimal(pct)).divide(BigDecimal(100))
    val result = if (side == "buy") {
        val p = (if (kind == "limit") price.toBigDecimalOrNull()
                 else store.asks.firstOrNull()?.price?.toBigDecimalOrNull()) ?: return ""
        if (p.signum() <= 0) return ""
        share.divide(p, 8, RoundingMode.DOWN)
    } else {
        share.setScale(8, RoundingMode.DOWN)
    }
    return result.stripTrailingZeros().toPlainString()
}
