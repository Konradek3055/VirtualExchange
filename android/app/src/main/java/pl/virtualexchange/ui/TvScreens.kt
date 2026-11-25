@file:OptIn(ExperimentalMaterial3Api::class)

package pl.virtualexchange.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.math.BigDecimal
import java.math.RoundingMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.json.JSONObject
import pl.virtualexchange.data.Api
import pl.virtualexchange.data.Level

private fun changePct(store: Store, symbol: String): Double? {
    val s = store.stats.optJSONObject(symbol) ?: return null
    return if (s.optInt("trades") > 0) s.optDouble("change_pct") else null
}

private fun plain(s: String): String = s.toBigDecimalOrNull()?.stripTrailingZeros()?.toPlainString() ?: ""

@Composable
private fun Change(abs: Double?, pct: Double?, modifier: Modifier = Modifier) {
    val color = when {
        pct == null -> Tv.Muted
        pct >= 0 -> Tv.Up
        else -> Tv.Down
    }
    val text = if (pct == null) "—" else (if (abs != null) (if (pct < 0) "−" else "+") + fmtDelta(abs) + "  " else "") + fmtPct(pct)
    Text(text, color = color, fontSize = 13.sp, modifier = modifier, maxLines = 1)
}

/* ------------------------------------------------------------------ RYNEK (lista jak watchlista TradingView) */

/* ------------------------------------------------------------------ RYNEK */

@Composable
private fun MarketSparkline(values: List<Double>, modifier: Modifier = Modifier) {
    androidx.compose.foundation.Canvas(modifier) {
        if (values.size < 2 || size.width <= 0f || size.height <= 0f) return@Canvas
        val low = values.minOrNull() ?: return@Canvas
        val high = values.maxOrNull() ?: return@Canvas
        val span = (high - low).takeIf { it > 0.0 } ?: 1.0
        val path = androidx.compose.ui.graphics.Path()
        values.forEachIndexed { index, value ->
            val x = size.width * index / (values.size - 1).toFloat()
            val y = size.height - (((value - low) / span).toFloat() * (size.height - 4f)) - 2f
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        val color = if (values.last() >= values.first()) Tv.Up else Tv.Down
        drawPath(path, color, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.8.dp.toPx()))
    }
}

/* ------------------------------------------------------------------ RYNEK */

@Composable
fun TvMarketTab(store: Store, onOpen: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableIntStateOf(0) }
    val collapsed = remember { mutableStateMapOf<String, Boolean>() }
    val filters = listOf("Wszystko", "Krypto", "Akcje", "Społeczność")

    val assets = store.assets.filter { a ->
        val symbol = a.optString("symbol")
        (filter == 0 || category(symbol) == filters[filter]) &&
            (query.isBlank() || symbol.contains(query.trim(), true) || a.optString("name").contains(query.trim(), true))
    }
    val groups = filters.drop(1).map { group -> group to assets.filter { category(it.optString("symbol")) == group } }
        .filter { it.second.isNotEmpty() }

    LazyColumn(
        Modifier.fillMaxSize().background(Tv.Bg),
        contentPadding = PaddingValues(bottom = 18.dp),
    ) {
        item {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("RYNEK", color = Tv.Blue, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
                        Text("Lista obserwowanych", color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.Bold)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("${store.assets.size}", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Text("instrumentów", color = Tv.Muted, fontSize = 11.sp)
                    }
                }
                Spacer(Modifier.height(13.dp))
                PillTabs(filters, filter, { filter = it })
                Spacer(Modifier.height(10.dp))
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Tv.Card)
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.Search, null, tint = Tv.Muted, modifier = Modifier.size(20.dp))
                    Box(Modifier.padding(start = 10.dp).weight(1f)) {
                        if (query.isEmpty()) Text("Szukaj aktywa lub tickera", color = Tv.Muted, fontSize = 15.sp)
                        BasicTextField(
                            query, { query = it }, singleLine = true,
                            textStyle = TextStyle(color = Color.White, fontSize = 15.sp),
                            cursorBrush = SolidColor(Color.White), modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (query.isNotEmpty()) {
                        Text(
                            "WYCZYŚĆ", color = Tv.Blue, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.clickable { query = "" }.padding(start = 8.dp),
                        )
                    }
                }
                Spacer(Modifier.height(13.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("INSTRUMENT", color = Tv.Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
                    Spacer(Modifier.weight(1f))
                    Text("TREND", color = Tv.Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp, modifier = Modifier.padding(end = 14.dp))
                    Text("CENA / 24H", color = Tv.Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
                }
            }
        }

        if (assets.isEmpty()) {
            item {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 44.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(if (store.assets.isEmpty()) "Ładowanie rynku…" else "Nie znaleziono aktywa", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (store.assets.isEmpty()) "Dane instrumentów pojawią się po połączeniu z rynkiem."
                        else "Spróbuj innej nazwy lub symbolu.",
                        color = Tv.Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 5.dp),
                    )
                }
            }
        }

        groups.forEach { (name, list) ->
            item(key = "market-header-$name") {
                Row(
                    Modifier.fillMaxWidth().background(Color(0xFF090909))
                        .clickable { collapsed[name] = collapsed[name] != true }
                        .padding(horizontal = 16.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Outlined.KeyboardArrowDown, null, tint = Color.White,
                        modifier = Modifier.size(21.dp).rotate(if (collapsed[name] == true) -90f else 0f),
                    )
                    Text(name, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 9.dp))
                    Text("${list.size}", color = Tv.Muted, fontSize = 12.sp, modifier = Modifier.padding(start = 7.dp))
                    Spacer(Modifier.weight(1f))
                    Text(if (collapsed[name] == true) "ROZWIŃ" else "ZWIŃ", color = Tv.Muted, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }
            }
            if (collapsed[name] != true) {
                items(list, key = { it.optString("symbol") }) { asset ->
                    val symbol = asset.optString("symbol")
                    val price = asset.optString("last_price", "0")
                    val pct = changePct(store, symbol)
                    val p = price.toDoubleOrNull() ?: 0.0
                    val abs = if (pct != null && p > 0) kotlin.math.abs(p - p / (1 + pct / 100)) else null
                    Row(
                        Modifier.fillMaxWidth().clickable { store.selected = symbol; onOpen() }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TvBadge(symbol, 42.dp)
                        Column(Modifier.weight(1f).padding(start = 11.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(symbol, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                if (!asset.isNull("creator_id")) {
                                    Text("SPOŁ.", color = Tv.Blue, fontSize = 8.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 6.dp))
                                }
                            }
                            Text(
                                asset.optString("name", symbol), color = Tv.Muted, fontSize = 12.sp,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                        MarketSparkline(store.spark[symbol].orEmpty(), Modifier.size(54.dp, 28.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(horizontalAlignment = Alignment.End) {
                            Text(fmtPx(price), color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                            Change(abs, pct)
                        }
                    }
                    HorizontalDivider(Modifier.padding(start = 69.dp), color = Tv.Line)
                }
            }
        }
        item {
            Text(
                "Ceny aktualizują się po dopasowaniu zleceń na rynku symulacyjnym.",
                color = Tv.Muted, fontSize = 10.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            )
        }
    }
}

/* ------------------------------------------------------------------ HANDEL */


/* ------------------------------------------------------------------ HANDEL (formularz po lewej, księga po prawej - jak Binance/OKX) */

@Composable
private fun BookRow(level: Level, color: Color, maxQty: Double, onClick: () -> Unit) {
    val frac = ((level.qty.toDoubleOrNull() ?: 0.0) / maxQty).coerceIn(0.03, 1.0).toFloat()
    Box(Modifier.fillMaxWidth().height(25.dp).clickable(onClick = onClick)) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(frac).align(Alignment.CenterEnd).background(color.copy(alpha = 0.16f)))
        Row(
            Modifier.fillMaxSize().padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(fmtPx(level.price), color = color, fontSize = 12.sp, maxLines = 1)
            Text(fmtQty(level.qty), color = Color.White, fontSize = 12.sp, maxLines = 1)
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
        val p = (if (kind == "limit") price.toBigDecimalOrNull() else store.asks.firstOrNull()?.price?.toBigDecimalOrNull())
            ?: return ""
        if (p.signum() <= 0) return ""
        share.divide(p, 8, RoundingMode.DOWN)
    } else {
        share.setScale(8, RoundingMode.DOWN)
    }
    return result.stripTrailingZeros().toPlainString()
}

@Composable
fun TvTradeTab(store: Store, scope: CoroutineScope) {
    var side by remember { mutableStateOf("buy") }
    var kind by remember { mutableStateOf("limit") }
    var qty by remember { mutableStateOf("") }
    var price by remember(store.selected) { mutableStateOf(if (store.lastPrice == "0") "" else plain(store.lastPrice)) }
    var bottom by remember { mutableIntStateOf(0) }
    var showPicker by remember { mutableStateOf(false) }

    val stat = store.stats.optJSONObject(store.selected)
    val pct = changePct(store, store.selected)
    val trades = store.trades
    val up = trades.size < 2 || (trades[0].optString("price").toDoubleOrNull() ?: 0.0) >= (trades[1].optString("price").toDoubleOrNull() ?: 0.0)
    val dirColor = if (up) Tv.Up else Tv.Down
    val maxQty = (store.asks.take(7) + store.bids.take(7)).maxOfOrNull { it.qty.toDoubleOrNull() ?: 0.0 }?.takeIf { it > 0 } ?: 1.0
    val symbolRow = store.portfolio.firstOrNull { it.optString("symbol") == store.selected }
    val usdRow = store.portfolio.firstOrNull { it.optString("symbol") == "USD" }
    val availableText = if (side == "buy") fmtUsd(usdRow?.optString("available")?.toDoubleOrNull() ?: 0.0) + " USD"
        else fmtQty(symbolRow?.optString("available") ?: "0") + " " + store.selected
    val estimate = (qty.toDoubleOrNull() ?: 0.0) * (price.toDoubleOrNull() ?: 0.0)
    val spread = if (store.asks.isNotEmpty() && store.bids.isNotEmpty())
        (store.asks.first().price.toDoubleOrNull() ?: 0.0) - (store.bids.first().price.toDoubleOrNull() ?: 0.0) else null
    val openOrders = store.orders.filter { it.optString("status") == "OPEN" || it.optString("status") == "PARTIAL" }

    LazyColumn(Modifier.fillMaxSize().background(Tv.Bg), contentPadding = PaddingValues(bottom = 18.dp)) {
        item {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("HANDEL", color = Tv.Blue, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
                        Row(Modifier.clickable { showPicker = true }, verticalAlignment = Alignment.CenterVertically) {
                            Text("${store.selected}/USD", color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.Bold)
                            Icon(Icons.Outlined.KeyboardArrowDown, null, tint = Color.White)
                        }
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(if (pct == null) "—" else fmtPct(pct), color = if (pct == null) Tv.Muted else if (pct >= 0) Tv.Up else Tv.Down, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text("ZMIANA 24H", color = Tv.Muted, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(fmtPx(store.lastPrice), color = dirColor, fontSize = 29.sp, fontWeight = FontWeight.Bold)
                    Text(" USD", color = Tv.Muted, fontSize = 12.sp, modifier = Modifier.padding(start = 5.dp, bottom = 5.dp))
                }
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TradeMetric("MAKS. 24H", stat?.optString("high")?.takeUnless { it == "null" }?.let { fmtPx(it) } ?: "—", Modifier.weight(1f))
                    TradeMetric("MIN. 24H", stat?.optString("low")?.takeUnless { it == "null" }?.let { fmtPx(it) } ?: "—", Modifier.weight(1f))
                    TradeMetric("WOLUMEN", stat?.optString("volume_usd")?.toDoubleOrNull()?.let { fmtUsd(it) } ?: "—", Modifier.weight(1f))
                }
            }
        }
        item {
            BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                if (maxWidth < 520.dp) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        TradeEntryPanel(
                            Modifier.fillMaxWidth(), store, scope, side, { side = it }, kind, { kind = it },
                            qty, { qty = it }, price, { price = it }, availableText, estimate,
                        )
                        OrderBookPanel(Modifier.fillMaxWidth(), store, dirColor, maxQty, spread) {
                            price = plain(it); kind = "limit"
                        }
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        TradeEntryPanel(
                            Modifier.weight(1.08f), store, scope, side, { side = it }, kind, { kind = it },
                            qty, { qty = it }, price, { price = it }, availableText, estimate,
                        )
                        OrderBookPanel(Modifier.weight(0.92f), store, dirColor, maxQty, spread) {
                            price = plain(it); kind = "limit"
                        }
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
        }
        item {
            PillTabs(listOf("Otwarte zlecenia (${openOrders.size})", "Transakcje", "Aktywa"), bottom, { bottom = it }, Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        }
        when (bottom) {
            0 -> {
                if (openOrders.isEmpty()) item { Text("Nie masz aktywnych zleceń.", color = Tv.Muted, modifier = Modifier.padding(18.dp)) }
                items(openOrders, key = { it.optInt("id") }) { order -> OrderRow(order, store, scope) }
            }
            1 -> {
                if (trades.isEmpty()) item { Text("Brak transakcji — pojawią się po pierwszym dopasowaniu zleceń.", color = Tv.Muted, modifier = Modifier.padding(18.dp)) }
                items(trades.take(30)) { t ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 9.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(fmtPx(t.optString("price")), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text(fmtQty(t.optString("quantity")), color = Tv.Muted, fontSize = 13.sp)
                        Text(t.optString("created_at").takeLast(8), color = Tv.Muted, fontSize = 11.sp)
                    }
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = Tv.Line)
                }
            }
            else -> item {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    listOf("USD" to usdRow, store.selected to symbolRow).forEach { (symbol, row) ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(symbol, color = Color.White, fontWeight = FontWeight.SemiBold)
                            val available = row?.optString("available")?.toDoubleOrNull() ?: 0.0
                            val locked = row?.optString("locked")?.toDoubleOrNull() ?: 0.0
                            Text(
                                if (symbol == "USD") fmtUsd(available) + " (zabl. " + fmtUsd(locked) + ")"
                                else fmtQty(available.toString()) + " (zabl. " + fmtQty(locked.toString()) + ")",
                                color = Color.White, fontSize = 13.sp,
                            )
                        }
                        HorizontalDivider(color = Tv.Line)
                    }
                }
            }
        }
    }

    if (showPicker) {
        ModalBottomSheet(onDismissRequest = { showPicker = false }, containerColor = Tv.Card) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
                Text("Wybierz instrument", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
                store.assets.forEach { asset ->
                    val symbol = asset.optString("symbol")
                    Row(Modifier.fillMaxWidth().clickable { store.selected = symbol; showPicker = false }.padding(horizontal = 16.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                        TvBadge(symbol, 36.dp)
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(symbol, color = Color.White, fontWeight = FontWeight.SemiBold)
                            Text(asset.optString("name", symbol), color = Tv.Muted, fontSize = 12.sp, maxLines = 1)
                        }
                        Text(fmtPx(asset.optString("last_price", "0")), color = Color.White)
                    }
                    HorizontalDivider(Modifier.padding(start = 64.dp), color = Tv.Line)
                }
            }
        }
    }
}

@Composable
private fun TradeMetric(label: String, value: String, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(10.dp)).background(Tv.Card).padding(horizontal = 10.dp, vertical = 9.dp)) {
        Text(label, color = Tv.Muted, fontSize = 9.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        Spacer(Modifier.height(4.dp))
        Text(value, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun TradeEntryPanel(
    modifier: Modifier, store: Store, scope: CoroutineScope,
    side: String, onSide: (String) -> Unit,
    kind: String, onKind: (String) -> Unit,
    qty: String, onQty: (String) -> Unit,
    price: String, onPrice: (String) -> Unit,
    availableText: String, estimate: Double,
) {
    TvCard(modifier) {
        Text("ZŁÓŻ ZLECENIE", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp)).background(Tv.Chip)) {
            listOf("buy" to "Kup", "sell" to "Sprzedaj").forEach { (key, label) ->
                val selected = side == key
                Box(
                    Modifier.weight(1f).background(if (!selected) Color.Transparent else if (key == "buy") Tv.Up else Tv.Down)
                        .clickable { onSide(key) }.padding(vertical = 11.dp),
                    contentAlignment = Alignment.Center,
                ) { Text(label, color = Color.White.copy(alpha = if (selected) 1f else 0.68f), fontWeight = FontWeight.SemiBold, fontSize = 14.sp) }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            listOf("limit" to "Limit", "market" to "Rynkowe").forEach { (key, label) ->
                Text(label, color = if (kind == key) Color.White else Tv.Muted, fontSize = 13.sp,
                    fontWeight = if (kind == key) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier.clickable { onKind(key) }.padding(vertical = 3.dp))
            }
        }
        TvInput("Cena (USD)", if (kind == "limit") price else "", onPrice, enabled = kind == "limit",
            keyboard = KeyboardType.Decimal, placeholder = if (kind == "limit") "0,00" else "Cena rynkowa")
        Spacer(Modifier.height(8.dp))
        TvInput("Ilość (${store.selected})", qty, onQty, keyboard = KeyboardType.Decimal, placeholder = "0")
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(25, 50, 75, 100).forEach { percent ->
                Box(Modifier.weight(1f).clip(RoundedCornerShape(7.dp)).background(Tv.Chip)
                    .clickable { onQty(pctQty(store, side, kind, price, percent)) }.padding(vertical = 9.dp), contentAlignment = Alignment.Center) {
                    Text("$percent%", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
        Spacer(Modifier.height(11.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Dostępne", color = Tv.Muted, fontSize = 12.sp)
            Text(availableText, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
        Row(Modifier.fillMaxWidth().padding(top = 5.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Szacowana wartość", color = Tv.Muted, fontSize = 12.sp)
            Text(if (estimate > 0 && kind == "limit") fmtUsd(estimate) + " USD" else "—", color = Color.White, fontSize = 12.sp)
        }
        Button(
            onClick = {
                scope.launch {
                    try {
                        val body = JSONObject().put("symbol", store.selected).put("side", side)
                            .put("order_type", kind).put("quantity", qty.trim().replace(',', '.'))
                        if (kind == "limit") body.put("price", price.trim().replace(',', '.'))
                        val response = Api.post("orders", body)
                        store.message = "Zlecenie: " + response.optString("status")
                        onQty("")
                        store.refreshAll()
                    } catch (error: Exception) {
                        store.message = error.message ?: "Błąd zlecenia"
                    }
                }
            },
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp).height(48.dp),
            shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.buttonColors(containerColor = if (side == "buy") Tv.Up else Tv.Down),
            contentPadding = PaddingValues(horizontal = 8.dp),
        ) { Text(if (side == "buy") "Kup ${store.selected}" else "Sprzedaj ${store.selected}", fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1) }
        if (store.message.isNotBlank()) Text(store.message, color = Tv.Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun OrderBookPanel(modifier: Modifier, store: Store, dirColor: Color, maxQty: Double, spread: Double?, onPricePick: (String) -> Unit) {
    TvCard(modifier) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("KSIĘGA ZLECEŃ", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
            Spacer(Modifier.weight(1f))
            Text("USD", color = Tv.Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
        Row(Modifier.fillMaxWidth().padding(top = 9.dp, bottom = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Cena", color = Tv.Muted, fontSize = 10.sp)
            Text("Ilość", color = Tv.Muted, fontSize = 10.sp)
        }
        store.asks.take(7).reversed().forEach { level -> BookRow(level, Tv.Down, maxQty) { onPricePick(level.price) } }
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(fmtPx(store.lastPrice), color = dirColor, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("Ostatnia cena", color = Tv.Muted, fontSize = 10.sp)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(spread?.let { fmtPx(it) } ?: "—", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text("Spread", color = Tv.Muted, fontSize = 10.sp)
            }
        }
        store.bids.take(7).forEach { level -> BookRow(level, Tv.Up, maxQty) { onPricePick(level.price) } }
        if (store.asks.isEmpty() && store.bids.isEmpty()) Text("Brak aktywnych zleceń w księdze.", color = Tv.Muted, fontSize = 12.sp, modifier = Modifier.padding(vertical = 12.dp))
    }
}

/* ------------------------------------------------------------------ PORTFEL */


@Composable
private fun OrderRow(order: JSONObject, store: Store, scope: CoroutineScope) {
    val buy = order.optString("side") == "buy"
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                (if (buy) "Kup " else "Sprzedaj ") + order.optString("symbol"),
                color = if (buy) Tv.Up else Tv.Down, fontWeight = FontWeight.Bold,
            )
            Text(
                "Cena ${order.optString("price").takeIf { it.isNotBlank() && it != "null" }?.let { fmtPx(it) } ?: "rynkowa"} · " +
                    "pozostało ${fmtQty(order.optString("remaining"))}",
                color = Tv.Muted, fontSize = 12.sp,
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
        }) { Text("Anuluj", color = Tv.Down) }
    }
    HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = Tv.Line)
}

/* ------------------------------------------------------------------ PORTFEL (jak Paper Trading w TradingView) */

/* ------------------------------------------------------------------ PORTFEL */

/* ------------------------------------------------------------------ PORTFEL */

@Composable
fun TvPortfolioTab(store: Store, scope: CoroutineScope) {
    var tab by remember { mutableIntStateOf(0) }
    var showValues by remember { mutableStateOf(true) }

    var cash = 0.0
    var lockedCash = 0.0
    var assetsValue = 0.0
    store.portfolio.forEach { row ->
        val available = row.optString("available").toDoubleOrNull() ?: 0.0
        val locked = row.optString("locked").toDoubleOrNull() ?: 0.0
        if (row.optString("symbol") == "USD") {
            cash = available
            lockedCash = locked
        } else {
            assetsValue += (available + locked) * row.optDouble("last_price", 0.0)
        }
    }
    val total = cash + lockedCash + assetsValue
    val positions = store.portfolio.filter {
        it.optString("symbol") != "USD" &&
            ((it.optString("available").toDoubleOrNull() ?: 0.0) + (it.optString("locked").toDoubleOrNull() ?: 0.0)) > 0
    }
    val open = store.orders.filter { it.optString("status") == "OPEN" || it.optString("status") == "PARTIAL" }
    val history = store.orders.filter { it.optString("status") != "OPEN" && it.optString("status") != "PARTIAL" }

    LazyColumn(Modifier.fillMaxSize().background(Tv.Bg), contentPadding = PaddingValues(bottom = 20.dp)) {
        item {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("PORTFEL", color = Tv.Blue, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
                        Text("Paper Trading", color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.Bold)
                    }
                    Row(Modifier.clip(RoundedCornerShape(20.dp)).background(Tv.Chip).padding(horizontal = 11.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(7.dp).clip(RoundedCornerShape(4.dp)).background(if (Api.offline) Tv.Blue else Tv.Up))
                        Text(if (Api.offline) "SYMULACJA" else "ONLINE", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 7.dp))
                    }
                }
                Spacer(Modifier.height(12.dp))
                TvCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("KAPITAŁ CAŁKOWITY", color = Tv.Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
                            Spacer(Modifier.height(4.dp))
                            Text(if (showValues) fmtUsd(total) + " USD" else "••••••", color = Color.White, fontSize = 29.sp, fontWeight = FontWeight.Bold)
                        }
                        TextButton(onClick = { showValues = !showValues }) {
                            Text(if (showValues) "UKRYJ" else "POKAŻ", color = Tv.Blue, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PortfolioMetric("GOTÓWKA", cash, showValues, Modifier.weight(1f), Tv.Up)
                        PortfolioMetric("AKTYWA", assetsValue, showValues, Modifier.weight(1f), Color.White)
                        PortfolioMetric("ZABLOK.", lockedCash, showValues, Modifier.weight(1f), Tv.Muted)
                    }
                }
                if (store.accountError.isNotBlank()) Text(store.accountError, color = Tv.Down, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp))
                Spacer(Modifier.height(14.dp))
                PillTabs(listOf("Pozycje", "Zlecenia (${open.size})", "Historia zleceń"), tab, { tab = it })
            }
        }

        when (tab) {
            0 -> {
                if (positions.isEmpty()) item { PortfolioEmpty("Brak otwartych pozycji", "Twoje aktywa pojawią się tutaj po pierwszym zakupie.") }
                items(positions, key = { it.optString("symbol") }) { row ->
                    val symbol = row.optString("symbol")
                    val available = row.optString("available").toDoubleOrNull() ?: 0.0
                    val locked = row.optString("locked").toDoubleOrNull() ?: 0.0
                    val quantity = available + locked
                    val last = row.optDouble("last_price", 0.0)
                    val value = quantity * last
                    val assetName = store.assets.firstOrNull { it.optString("symbol") == symbol }?.optString("name", symbol) ?: symbol
                    Column {
                        Row(
                            Modifier.fillMaxWidth().clickable { store.selected = symbol }.padding(horizontal = 16.dp, vertical = 13.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TvBadge(symbol, 42.dp)
                            Column(Modifier.weight(1f).padding(start = 11.dp)) {
                                Text(symbol, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                                Text(assetName, color = Tv.Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("Dostępne ${fmtQty(available.toString())} · zablokowane ${fmtQty(locked.toString())}", color = Tv.Muted, fontSize = 10.sp)
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(if (showValues) fmtUsd(value) + " USD" else "••••", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                Text("${fmtPx(last.toString())} USD", color = Tv.Muted, fontSize = 11.sp)
                            }
                        }
                        HorizontalDivider(Modifier.padding(start = 69.dp), color = Tv.Line)
                    }
                }
            }
            1 -> {
                if (open.isEmpty()) item { PortfolioEmpty("Brak aktywnych zleceń", "Zlecenia limit pojawią się tutaj, dopóki nie zostaną wykonane lub anulowane.") }
                items(open, key = { it.optInt("id") }) { order -> OrderRow(order, store, scope) }
            }
            else -> {
                if (history.isEmpty()) item { PortfolioEmpty("Brak historii zleceń", "Wykonane i anulowane zlecenia będą widoczne w tym miejscu.") }
                items(history.take(100), key = { it.optInt("id") }) { order ->
                    val buy = order.optString("side") == "buy"
                    val status = order.optString("status")
                    Column {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background((if (buy) Tv.Up else Tv.Down).copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                                Text(if (buy) "B" else "S", color = if (buy) Tv.Up else Tv.Down, fontWeight = FontWeight.Bold)
                            }
                            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                                Text((if (buy) "Kup " else "Sprzedaj ") + order.optString("symbol"), color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                Text("Ilość ${fmtQty(order.optString("quantity"))} · ${order.optString("created_at").takeLast(8)}", color = Tv.Muted, fontSize = 11.sp)
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(when (status) { "FILLED" -> "Wykonane"; "CANCELLED" -> "Anulowane"; else -> status }, color = if (status == "FILLED") Tv.Up else Tv.Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                Text(order.optString("price").takeUnless { it.isBlank() || it == "null" }?.let { fmtPx(it) + " USD" } ?: "Rynkowe", color = Tv.Muted, fontSize = 10.sp)
                            }
                        }
                        HorizontalDivider(Modifier.padding(start = 60.dp), color = Tv.Line)
                    }
                }
            }
        }
    }
}

@Composable
private fun PortfolioMetric(label: String, amount: Double, visible: Boolean, modifier: Modifier, valueColor: Color) {
    Column(modifier.clip(RoundedCornerShape(10.dp)).background(Tv.Chip).padding(horizontal = 9.dp, vertical = 10.dp)) {
        Text(label, color = Tv.Muted, fontSize = 9.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        Spacer(Modifier.height(5.dp))
        Text(if (visible) fmtUsd(amount) else "••••", color = valueColor, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun PortfolioEmpty(title: String, description: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 58.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(54.dp).clip(RoundedCornerShape(18.dp)).background(Tv.Card), contentAlignment = Alignment.Center) {
            Text("—", color = Tv.Muted, fontSize = 25.sp, fontWeight = FontWeight.Light)
        }
        Text(title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 14.dp))
        Text(description, color = Tv.Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 5.dp))
    }
}

/* ------------------------------------------------------------------ MENU */


/* ------------------------------------------------------------------ MENU (karty jak w TradingView) */

@Composable
fun TvMenuTab(store: Store, scope: CoroutineScope) {
    var offline by remember { mutableStateOf(Api.offline) }
    var url by remember { mutableStateOf(Api.baseUrl) }
    var key by remember { mutableStateOf(Api.apiKey) }
    var username by remember { mutableStateOf("") }
    var symbol by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var supply by remember { mutableStateOf("1000000") }
    val openCount = store.orders.count { it.optString("status") == "OPEN" || it.optString("status") == "PARTIAL" }

    Column(
        Modifier.fillMaxSize().background(Tv.Bg).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TvCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(56.dp).clip(RoundedCornerShape(28.dp)).background(Color(0xFF9C5B7B)), contentAlignment = Alignment.Center) {
                    Text("V", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                }
                Column(Modifier.padding(start = 14.dp)) {
                    Text(if (offline) "Gracz offline" else "Gracz online", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text(if (offline) "Symulacja z botami na telefonie" else "Wspólny rynek na serwerze", color = Tv.Muted, fontSize = 13.sp)
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text("${store.portfolio.count { it.optString("symbol") != "USD" }}", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text("Aktywa", color = Tv.Muted, fontSize = 13.sp)
                }
                Column(Modifier.weight(1f)) {
                    Text("$openCount", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text("Otwarte zlecenia", color = Tv.Muted, fontSize = 13.sp)
                }
                Column(Modifier.weight(1f)) {
                    Text("${store.assets.size}", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text("Instrumenty", color = Tv.Muted, fontSize = 13.sp)
                }
            }
        }

        TvCard {
            Text("Tryb rynku", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("Offline · boty", offline) {
                    Api.setOffline(true); offline = true
                    store.message = "Włączono rynek offline"
                    scope.launch { store.refreshAll() }
                }
                Pill("Online · serwer", !offline) {
                    Api.setOffline(false); offline = false
                    Api.connect(store.selected)
                    store.message = "Wybrano serwer online"
                    scope.launch { store.refreshAll() }
                }
            }
            Text(
                if (offline) "Lokalny rynek działa na telefonie. Boty i gracz korzystają z tego samego silnika dopasowywania zleceń."
                else "Wspólny rynek działa przez skonfigurowany serwer.",
                color = Tv.Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp),
            )
            if (offline) {
                Spacer(Modifier.height(10.dp))
                OutlinedButton(onClick = {
                    Api.resetOffline()
                    store.message = "Utworzono nową symulację offline"
                    scope.launch { store.refreshAll() }
                }) { Text("Zresetuj rynek offline") }
            } else {
                Spacer(Modifier.height(10.dp))
                TvInput("Adres serwera", url, { url = it })
                Spacer(Modifier.height(8.dp))
                TvInput("Klucz API", key, { key = it })
                Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { Api.save(url, key); Api.connect(store.selected); scope.launch { store.refreshAll() } }) { Text("Zapisz") }
                    OutlinedButton(onClick = { key = "demo-token" }) { Text("Klucz demo") }
                }
                Spacer(Modifier.height(10.dp))
                TvInput("Nazwa nowego gracza", username, { username = it })
                OutlinedButton(
                    onClick = {
                        Api.save(url, key)
                        scope.launch {
                            try {
                                val response = Api.register(username)
                                key = response.optString("api_key")
                                Api.save(url, key)
                                store.message = "Utworzono konto z wirtualnymi USD"
                                store.refreshAll()
                            } catch (error: Exception) {
                                store.message = error.message ?: "Rejestracja nie powiodła się"
                            }
                        }
                    },
                    modifier = Modifier.padding(top = 8.dp),
                ) { Text("Utwórz konto") }
            }
        }

        TvCard {
            Text("Utwórz aktywo społeczności", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "Podaż jest stała od startu, a cenę kształtują wyłącznie zlecenia użytkowników. Tryb online: opłata emisji 100 USD, limit 3 aktywa na gracza.",
                color = Tv.Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp, bottom = 10.dp),
            )
            TvInput("Symbol, np. NOVA", symbol, { symbol = it.uppercase() })
            Spacer(Modifier.height(8.dp))
            TvInput("Nazwa aktywa", name, { name = it })
            Spacer(Modifier.height(8.dp))
            TvInput("Opis", description, { description = it })
            Spacer(Modifier.height(8.dp))
            TvInput("Stała podaż", supply, { supply = it }, keyboard = KeyboardType.Decimal)
            Button(
                onClick = {
                    scope.launch {
                        try {
                            val body = JSONObject().put("symbol", symbol.trim()).put("name", name.trim())
                                .put("description", description.trim()).put("total_supply", supply.trim())
                            Api.post("assets", body)
                            store.message = "Utworzono ${symbol.trim()}"
                            store.refreshAll()
                        } catch (error: Exception) {
                            store.message = error.message ?: "Nie udało się utworzyć aktywa"
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp).height(46.dp),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Tv.Blue),
            ) { Text("Utwórz aktywo", fontWeight = FontWeight.Bold) }
            if (store.message.isNotEmpty()) Text(store.message, color = Tv.Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
        }
        Spacer(Modifier.height(16.dp))
    }
}
