@file:OptIn(ExperimentalMaterial3Api::class)

package pl.virtualexchange.ui

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

@Composable
fun TvMarketTab(store: Store, onOpen: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableIntStateOf(0) }
    val collapsed = remember { mutableStateMapOf<String, Boolean>() }
    val filters = listOf("Wszystko", "Krypto", "Akcje", "Społeczność")

    val assets = store.assets.filter { a ->
        val s = a.optString("symbol")
        (filter == 0 || category(s) == filters[filter]) &&
            (query.isBlank() || s.contains(query, true) || a.optString("name").contains(query, true))
    }
    val groups = filters.drop(1).map { g -> g to assets.filter { category(it.optString("symbol")) == g } }
        .filter { it.second.isNotEmpty() }

    LazyColumn(Modifier.fillMaxSize().background(Tv.Bg)) {
        item {
            PillTabs(filters, filter, { filter = it }, Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            Row(
                Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp)).background(Tv.Card).padding(horizontal = 12.dp, vertical = 11.dp),
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
            }
        }
        if (assets.isEmpty()) {
            item {
                Text(
                    if (store.assets.isEmpty()) "Ładowanie rynku…" else "Nie znaleziono aktywa.",
                    color = Tv.Muted, modifier = Modifier.padding(24.dp),
                )
            }
        }
        groups.forEach { (name, list) ->
            item(key = "h$name") {
                Row(
                    Modifier.fillMaxWidth().clickable { collapsed[name] = collapsed[name] != true }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Outlined.KeyboardArrowDown, null, tint = Color.White,
                        modifier = Modifier.size(22.dp).rotate(if (collapsed[name] == true) -90f else 0f),
                    )
                    Text(
                        name, color = Color.White,
                        fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 10.dp),
                    )
                    Text("${list.size}", color = Tv.Muted, fontSize = 13.sp, modifier = Modifier.padding(start = 8.dp))
                }
            }
            if (collapsed[name] != true) {
                items(list, key = { it.optString("symbol") }) { a ->
                    val symbol = a.optString("symbol")
                    val price = a.optString("last_price", "0")
                    val pct = changePct(store, symbol)
                    val p = price.toDoubleOrNull() ?: 0.0
                    val abs = if (pct != null && p > 0) kotlin.math.abs(p - p / (1 + pct / 100)) else null
                    Row(
                        Modifier.fillMaxWidth().clickable { store.selected = symbol; onOpen() }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TvBadge(symbol)
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(symbol, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                            Text(
                                a.optString("name", symbol), color = Tv.Muted, fontSize = 14.sp,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(fmtPx(price), color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                            Change(abs, pct)
                        }
                    }
                    HorizontalDivider(Modifier.padding(start = 72.dp), color = Tv.Line)
                }
            }
        }
    }
}

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

    LazyColumn(Modifier.fillMaxSize().background(Tv.Bg), contentPadding = PaddingValues(bottom = 16.dp)) {
        // nagłówek pary jak w Binance/OKX
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.clickable { showPicker = true }, verticalAlignment = Alignment.CenterVertically) {
                    Text("${store.selected}/USD", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Icon(Icons.Outlined.KeyboardArrowDown, null, tint = Color.White)
                }
                Spacer(Modifier.weight(1f))
                Text(
                    if (pct == null) "—" else fmtPct(pct),
                    color = if (pct == null) Tv.Muted else if (pct >= 0) Tv.Up else Tv.Down,
                    fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                )
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(fmtPx(store.lastPrice), color = dirColor, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                    Text("Ostatnia transakcja", color = Tv.Muted, fontSize = 12.sp)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("Maks. 24h  ${stat?.optString("high")?.let { fmtPx(it) } ?: "—"}", color = Tv.Muted, fontSize = 12.sp)
                    Text("Min. 24h  ${stat?.optString("low")?.let { fmtPx(it) } ?: "—"}", color = Tv.Muted, fontSize = 12.sp)
                    Text("Wol. 24h  ${stat?.optString("volume_usd")?.toDoubleOrNull()?.let { fmtUsd(it) } ?: "—"}", color = Tv.Muted, fontSize = 12.sp)
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        // formularz (lewo) + księga zleceń (prawo)
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1.08f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Tv.Chip)) {
                        listOf("buy" to "Kup", "sell" to "Sprzedaj").forEach { (key, label) ->
                            val sel = side == key
                            Box(
                                Modifier.weight(1f).background(if (!sel) Color.Transparent else if (key == "buy") Tv.Up else Tv.Down)
                                    .clickable { side = key }.padding(vertical = 10.dp),
                                contentAlignment = Alignment.Center,
                            ) { Text(label, color = if (sel) Color.White else Tv.Muted, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1) }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        listOf("limit" to "Limit", "market" to "Rynkowe").forEach { (key, label) ->
                            Text(
                                label, color = if (kind == key) Color.White else Tv.Muted, fontSize = 14.sp,
                                fontWeight = if (kind == key) FontWeight.Bold else FontWeight.Normal,
                                modifier = Modifier.clickable { kind = key }.padding(vertical = 2.dp),
                            )
                        }
                    }
                    TvInput(
                        "Cena (USD)", if (kind == "limit") price else "", { price = it },
                        enabled = kind == "limit", keyboard = KeyboardType.Decimal, placeholder = if (kind == "limit") "0" else "Cena rynkowa",
                    )
                    TvInput("Ilość (${store.selected})", qty, { qty = it }, keyboard = KeyboardType.Decimal, placeholder = "0")
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        listOf(25, 50, 75, 100).forEach { p ->
                            Box(
                                Modifier.weight(1f).clip(RoundedCornerShape(6.dp)).background(Tv.Chip)
                                    .clickable { qty = pctQty(store, side, kind, price, p) }.padding(vertical = 7.dp),
                                contentAlignment = Alignment.Center,
                            ) { Text("$p%", color = Color.White, fontSize = 11.sp, maxLines = 1) }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Dostępne", color = Tv.Muted, fontSize = 12.sp)
                        Text(availableText, color = Color.White, fontSize = 12.sp, maxLines = 1)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Wartość", color = Tv.Muted, fontSize = 12.sp)
                        Text(if (kind == "limit" && estimate > 0) fmtUsd(estimate) + " USD" else "—", color = Color.White, fontSize = 12.sp, maxLines = 1)
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
                                    qty = ""
                                    store.refreshAll()
                                } catch (e: Exception) {
                                    store.message = e.message ?: "Błąd zlecenia"
                                }
                            }
                        },
                        Modifier.fillMaxWidth().height(46.dp),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = if (side == "buy") Tv.Up else Tv.Down),
                        contentPadding = PaddingValues(horizontal = 6.dp),
                    ) {
                        Text(
                            if (side == "buy") "Kup ${store.selected}" else "Sprzedaj ${store.selected}",
                            color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1, softWrap = false,
                        )
                    }
                    if (store.message.isNotBlank()) Text(store.message, color = Tv.Muted, fontSize = 11.sp)
                }

                Column(Modifier.weight(0.92f)) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Cena (USD)", color = Tv.Muted, fontSize = 11.sp)
                        Text("Ilość", color = Tv.Muted, fontSize = 11.sp)
                    }
                    store.asks.take(7).reversed().forEach { l -> BookRow(l, Tv.Down, maxQty) { price = plain(l.price); kind = "limit" } }
                    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp, horizontal = 4.dp)) {
                        Text(fmtPx(store.lastPrice), color = dirColor, fontSize = 19.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                        Text("Spread ${spread?.let { fmtPx(it) } ?: "—"}", color = Tv.Muted, fontSize = 11.sp, maxLines = 1)
                    }
                    store.bids.take(7).forEach { l -> BookRow(l, Tv.Up, maxQty) { price = plain(l.price); kind = "limit" } }
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        // dolne zakładki: otwarte zlecenia / transakcje / aktywa
        item {
            PillTabs(
                listOf("Otwarte zlecenia (${openOrders.size})", "Ostatnie transakcje", "Aktywa"), bottom, { bottom = it },
                Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        when (bottom) {
            0 -> {
                if (openOrders.isEmpty()) item { Text("Nie masz aktywnych zleceń.", color = Tv.Muted, modifier = Modifier.padding(16.dp)) }
                items(openOrders, key = { it.optInt("id") }) { o -> OrderRow(o, store, scope) }
            }
            1 -> {
                if (trades.isEmpty()) item { Text("Brak transakcji — pojawią się po pierwszym dopasowaniu zleceń.", color = Tv.Muted, modifier = Modifier.padding(16.dp)) }
                items(trades.take(20)) { t ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(fmtPx(t.optString("price")), color = Color.White, fontSize = 13.sp)
                        Text(fmtQty(t.optString("quantity")), color = Tv.Muted, fontSize = 13.sp)
                        Text(t.optString("created_at").takeLast(8), color = Tv.Muted, fontSize = 12.sp)
                    }
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = Tv.Line)
                }
            }
            else -> {
                item {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        listOf("USD" to usdRow, store.selected to symbolRow).forEach { (s, r) ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(s, color = Color.White, fontWeight = FontWeight.SemiBold)
                                val a = r?.optString("available")?.toDoubleOrNull() ?: 0.0
                                val l = r?.optString("locked")?.toDoubleOrNull() ?: 0.0
                                Text(if (s == "USD") fmtUsd(a) + " (zabl. " + fmtUsd(l) + ")" else fmtQty(a.toString()) + " (zabl. " + fmtQty(l.toString()) + ")", color = Color.White, fontSize = 13.sp)
                            }
                            HorizontalDivider(color = Tv.Line)
                        }
                    }
                }
            }
        }
    }

    if (showPicker) {
        ModalBottomSheet(onDismissRequest = { showPicker = false }, containerColor = Color(0xFF1E1E1E)) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
                store.assets.forEach { a ->
                    val s = a.optString("symbol")
                    Row(
                        Modifier.fillMaxWidth().clickable { store.selected = s; showPicker = false }.padding(horizontal = 16.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TvBadge(s, 36.dp)
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(s, color = Color.White, fontWeight = FontWeight.SemiBold)
                            Text(a.optString("name", s), color = Tv.Muted, fontSize = 12.sp, maxLines = 1)
                        }
                        Text(fmtPx(a.optString("last_price", "0")), color = Color.White)
                    }
                    HorizontalDivider(Modifier.padding(start = 64.dp), color = Tv.Line)
                }
            }
        }
    }
}

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

@Composable
fun TvPortfolioTab(store: Store, scope: CoroutineScope) {
    var tab by remember { mutableIntStateOf(0) }
    var cash = 0.0
    var locked = 0.0
    var assetsValue = 0.0
    store.portfolio.forEach { row ->
        val a = row.optString("available").toDoubleOrNull() ?: 0.0
        val l = row.optString("locked").toDoubleOrNull() ?: 0.0
        if (row.optString("symbol") == "USD") { cash = a; locked = l } else assetsValue += (a + l) * row.optDouble("last_price", 0.0)
    }
    val total = cash + locked + assetsValue
    val positions = store.portfolio.filter {
        it.optString("symbol") != "USD" && ((it.optString("available").toDoubleOrNull() ?: 0.0) + (it.optString("locked").toDoubleOrNull() ?: 0.0)) > 0
    }
    val open = store.orders.filter { it.optString("status") == "OPEN" || it.optString("status") == "PARTIAL" }
    val history = store.orders.filter { it.optString("status") != "OPEN" && it.optString("status") != "PARTIAL" }

    LazyColumn(Modifier.fillMaxSize().background(Tv.Bg), contentPadding = PaddingValues(bottom = 16.dp)) {
        item {
            Text("Konto wirtualne", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 16.dp, top = 8.dp, end = 16.dp))
            Row(
                Modifier.padding(16.dp).clip(RoundedCornerShape(24.dp)).background(Tv.Chip).padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(fmtUsd(total), color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                Text("USD", color = Tv.Muted, fontSize = 12.sp, modifier = Modifier.padding(start = 6.dp))
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("Wartość aktywów", color = Tv.Muted, fontSize = 15.sp)
                    Text(fmtUsd(assetsValue), color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(14.dp))
                    Text("Dostępne USD", color = Tv.Muted, fontSize = 15.sp)
                    Text(fmtUsd(cash), color = Tv.Up, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                }
                Column(Modifier.weight(1f)) {
                    Text("Kapitał", color = Tv.Muted, fontSize = 15.sp)
                    Text(fmtUsd(total), color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(14.dp))
                    Text("Zablokowane USD", color = Tv.Muted, fontSize = 15.sp)
                    Text(fmtUsd(locked), color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            if (store.accountError.isNotBlank()) Text(store.accountError, color = Tv.Down, fontSize = 12.sp, modifier = Modifier.padding(16.dp))
            Spacer(Modifier.height(16.dp))
            PillTabs(listOf("Pozycje", "Zlecenia (${open.size})", "Historia zleceń"), tab, { tab = it }, Modifier.padding(horizontal = 16.dp))
            Spacer(Modifier.height(8.dp))
        }
        val empty = when (tab) { 0 -> positions.isEmpty(); 1 -> open.isEmpty(); else -> history.isEmpty() }
        if (empty) {
            item {
                Column(Modifier.fillMaxWidth().padding(top = 56.dp, start = 24.dp, end = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Na razie nie ma nic do wyświetlenia", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Text("Tutaj pojawi się aktywność na Twoim koncie.", color = Tv.Muted, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))
                }
            }
        }
        when (tab) {
            0 -> items(positions, key = { it.optString("symbol") }) { row ->
                val s = row.optString("symbol")
                val qty = (row.optString("available").toDoubleOrNull() ?: 0.0) + (row.optString("locked").toDoubleOrNull() ?: 0.0)
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    TvBadge(s, 40.dp)
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(s, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Text("${fmtQty(qty.toString())} szt.", color = Tv.Muted, fontSize = 13.sp)
                    }
                    Text(fmtUsd(qty * row.optDouble("last_price", 0.0)) + " USD", color = Color.White, fontSize = 15.sp)
                }
                HorizontalDivider(Modifier.padding(start = 68.dp), color = Tv.Line)
            }
            1 -> items(open, key = { it.optInt("id") }) { o -> OrderRow(o, store, scope) }
            else -> items(history.take(50), key = { it.optInt("id") }) { o ->
                val buy = o.optString("side") == "buy"
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text((if (buy) "Kup " else "Sprzedaj ") + o.optString("symbol"), color = if (buy) Tv.Up else Tv.Down, fontWeight = FontWeight.Bold)
                        Text("Ilość ${fmtQty(o.optString("quantity"))}", color = Tv.Muted, fontSize = 12.sp)
                    }
                    Text(if (o.optString("status") == "FILLED") "Wykonane" else "Anulowane", color = Tv.Muted, fontSize = 13.sp)
                }
                HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = Tv.Line)
            }
        }
    }
}

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
