@file:OptIn(ExperimentalMaterial3Api::class)

package pl.virtualexchange.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.json.JSONObject
import pl.virtualexchange.data.Api
import pl.virtualexchange.data.fmt
import pl.virtualexchange.data.money

private val PageBg = Color(0xFF080D13)
private val PanelBg = Color(0xFF111923)
private val PanelBorder = Color(0xFF202C38)
private val Muted = Color(0xFF8796A8)
private val Blue = Color(0xFF388BFF)

@Composable
private fun Panel(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = PanelBg,
        border = androidx.compose.foundation.BorderStroke(1.dp, PanelBorder),
    ) {
        Column(Modifier.padding(14.dp), content = content)
    }
}

@Composable
private fun PageHeading(title: String, subtitle: String? = null) {
    Column(Modifier.padding(top = 16.dp, bottom = 8.dp)) {
        Text(title, color = Color.White, fontSize = 27.sp, fontWeight = FontWeight.Bold)
        if (subtitle != null) Text(subtitle, color = Muted, fontSize = 12.sp)
    }
}

@Composable
private fun AssetMark(symbol: String, modifier: Modifier = Modifier) {
    val tint = when (symbol.hashCode().absoluteValue % 4) {
        0 -> Color(0xFF1D78D7)
        1 -> Color(0xFF087F79)
        2 -> Color(0xFF6A55C8)
        else -> Color(0xFF9A6B28)
    }
    Surface(modifier = modifier.size(42.dp), shape = CircleShape, color = tint.copy(alpha = 0.2f)) {
        Box(contentAlignment = Alignment.Center) {
            Text(symbol.take(1), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
        }
    }
}

private val Int.absoluteValue: Int get() = if (this == Int.MIN_VALUE) 0 else kotlin.math.abs(this)

@Composable
private fun ChangeText(value: Double?, modifier: Modifier = Modifier) {
    if (value == null) {
        Text("—", modifier, color = Muted, fontSize = 12.sp)
    } else {
        val color = if (value >= 0.0) Green else Red
        val prefix = if (value > 0.0) "+" else ""
        Text("$prefix${"%.2f".format(java.util.Locale.US, value)}%", modifier, color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

// -----------------------------------------------------------------------------
// MARKET / WATCHLIST
@Composable
fun MarketTab(store: Store, onOpen: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val filtered = store.assets.filter {
        val q = query.trim()
        q.isEmpty() || it.optString("symbol").contains(q, true) || it.optString("name").contains(q, true)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(PageBg),
        contentPadding = PaddingValues(horizontal = 16.dp, bottom = 26.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            PageHeading("Rynek", "Obserwuj aktywa tworzone i wyceniane przez graczy")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("LISTA OBSERWOWANYCH", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Spacer(Modifier.weight(1f))
                Text("${store.assets.size} aktywów", color = Muted, fontSize = 12.sp)
            }
        }
        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("Szukaj nazwy lub symbolu", color = Muted) },
                shape = RoundedCornerShape(14.dp),
            )
        }
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("AKTYWO", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Text("CENA / 24H", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }
        if (filtered.isEmpty()) {
            item { Panel { Text(if (store.assets.isEmpty()) "Ładowanie rynku…" else "Nie znaleziono aktywa.", color = Muted) } }
        }
        items(filtered, key = { it.optString("symbol") }) { asset ->
            val symbol = asset.optString("symbol")
            val name = asset.optString("name", symbol)
            val price = asset.optString("last_price", "0")
            val stat = store.stats.optJSONObject(symbol)
            val change = if (stat != null && stat.optInt("trades", 0) > 0) stat.optDouble("change_pct") else null
            Row(
                Modifier.fillMaxWidth()
                    .background(PanelBg, RoundedCornerShape(16.dp))
                    .border(1.dp, PanelBorder, RoundedCornerShape(16.dp))
                    .clickable { store.selected = symbol; onOpen() }
                    .padding(horizontal = 12.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AssetMark(symbol)
                Spacer(Modifier.width(11.dp))
                Column(Modifier.weight(1f)) {
                    Text(symbol, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Text(name, color = Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (!asset.isNull("creator_id")) Text("AKTYWO SPOŁECZNOŚCI", color = Blue, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("\$${fmt(price)}", color = Color.White, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace, fontSize = 14.sp)
                    ChangeText(change)
                }
            }
        }
        item { Text("Ceny zmieniają się wyłącznie po dopasowaniu zleceń graczy.", color = Muted, fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp)) }
    }
}

// -----------------------------------------------------------------------------
// CHART
@Composable
fun ChartTab(store: Store, onTrade: () -> Unit) {
    val asset = store.assets.firstOrNull { it.optString("symbol") == store.selected }
    val stat = store.stats.optJSONObject(store.selected)
    val last = store.lastPrice
    val change = if (stat != null && stat.optInt("trades", 0) > 0) stat.optDouble("change_pct") else null

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(PageBg),
        contentPadding = PaddingValues(horizontal = 16.dp, bottom = 26.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            PageHeading("Wykres", "Historia cen i aktywność rynku")
            LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                items(store.assets) { a ->
                    val symbol = a.optString("symbol")
                    FilterChip(
                        selected = store.selected == symbol,
                        onClick = { store.selected = symbol },
                        label = { Text(symbol) },
                    )
                }
            }
        }
        item {
            Panel {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AssetMark(store.selected, Modifier.size(38.dp))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(store.selected, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Text(asset?.optString("name", "Aktywo wirtualne") ?: "Aktywo wirtualne", color = Muted, fontSize = 11.sp)
                    }
                    Text("1m", color = Blue, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(if (last == "0") "—" else "\$${fmt(last)}", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                    Spacer(Modifier.width(10.dp))
                    ChangeText(change, Modifier.padding(bottom = 4.dp))
                }
                Text("ostatnia cena transakcji · USD", color = Muted, fontSize = 10.sp)
            }
        }
        item {
            Panel {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("WYKRES CENY", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Text("Interwał 1 minuta", color = Muted, fontSize = 10.sp)
                }
                Spacer(Modifier.height(12.dp))
                Box(Modifier.fillMaxWidth().height(248.dp)) {
                    if (store.candles.isEmpty()) {
                        Text("Wykres pojawi się po pierwszych dopasowanych transakcjach.", Modifier.align(Alignment.Center), color = Muted, fontSize = 12.sp)
                    } else {
                        CandleChart(store.candles, Modifier.fillMaxSize())
                    }
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MetricCard("24H MAX", stat?.optString("high")?.takeUnless { it == "null" }?.let { "\$${fmt(it)}" } ?: "—", Modifier.weight(1f))
                MetricCard("24H MIN", stat?.optString("low")?.takeUnless { it == "null" }?.let { "\$${fmt(it)}" } ?: "—", Modifier.weight(1f))
                MetricCard("TRANSAKCJE", stat?.optInt("trades")?.toString() ?: "—", Modifier.weight(1f))
            }
        }
        item {
            Panel {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("WOLUMEN · 24H", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Text("\$${stat?.optString("volume_usd", "0")?.let { fmt(it) } ?: "0"}", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                }
                Spacer(Modifier.height(8.dp))
                Button(onClick = onTrade, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Blue)) {
                    Text("Przejdź do handlu")
                }
            }
        }
        item {
            Text("OSTATNIE TRANSAKCJE", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        items(store.trades.take(8)) { trade ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("\$${fmt(trade.optString("price"))}", color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                Text(fmt(trade.optString("quantity")), color = Muted, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                Text(trade.optString("created_at").takeLast(8), color = Muted, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun MetricCard(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(modifier = modifier, shape = RoundedCornerShape(14.dp), color = PanelBg, border = androidx.compose.foundation.BorderStroke(1.dp, PanelBorder)) {
        Column(Modifier.padding(10.dp)) {
            Text(label, color = Muted, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(5.dp))
            Text(value, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

// -----------------------------------------------------------------------------
// TRADE
@Composable
fun TradeTab(store: Store, scope: CoroutineScope) {
    var price by remember(store.selected) { mutableStateOf(if (store.lastPrice == "0") "" else fmt(store.lastPrice)) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(PageBg),
        contentPadding = PaddingValues(horizontal = 16.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            PageHeading("Handel", "Zlecenia trafiają do wspólnej księgi rynku")
            LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                items(store.assets) { a ->
                    val symbol = a.optString("symbol")
                    FilterChip(store.selected == symbol, { store.selected = symbol }, label = { Text(symbol) })
                }
            }
        }
        item {
            Panel {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column {
                        Text(store.selected, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                        Text("OSTATNIA TRANSAKCJA", color = Muted, fontSize = 9.sp)
                    }
                    Text(if (store.lastPrice == "0") "—" else "\$${fmt(store.lastPrice)}", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }
        item {
            Panel {
                Text("KSIĘGA ZLECEŃ", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                OrderBookView(store.asks, store.bids, store.lastPrice) { picked -> price = fmt(picked) }
            }
        }
        item { OrderForm(store, scope, price) { price = it } }
    }
}

@Composable
private fun OrderForm(store: Store, scope: CoroutineScope, price: String, onPrice: (String) -> Unit) {
    var side by remember { mutableStateOf("buy") }
    var kind by remember { mutableStateOf("limit") }
    var qty by remember { mutableStateOf("") }
    val estimate = (qty.toDoubleOrNull() ?: 0.0) * (price.toDoubleOrNull() ?: 0.0)
    val accent = if (side == "buy") Green else Red

    Panel {
        Text("ZŁÓŻ ZLECENIE", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(side == "buy", { side = "buy" }, label = { Text("KUP") })
            FilterChip(side == "sell", { side = "sell" }, label = { Text("SPRZEDAJ") })
            Spacer(Modifier.weight(1f))
            FilterChip(kind == "limit", { kind = "limit" }, label = { Text("LIMIT") })
            FilterChip(kind == "market", { kind = "market" }, label = { Text("MARKET") })
        }
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = qty,
            onValueChange = { qty = it },
            label = { Text("Ilość · ${store.selected}") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(13.dp),
        )
        if (kind == "limit") {
            OutlinedTextField(
                value = price,
                onValueChange = onPrice,
                label = { Text("Cena limitu · USD") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(13.dp),
            )
            if (estimate > 0.0) Text("Szacowana wartość  \$${money(estimate)}", color = Muted, fontSize = 11.sp)
        } else {
            Text("Zlecenie market zostanie wykonane po dostępnych cenach w księdze.", color = Muted, fontSize = 11.sp)
        }
        Spacer(Modifier.height(8.dp))
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
                        store.message = "Zlecenie #${response.optInt("order_id")}: ${response.optString("status")}"
                        store.refreshAll()
                    } catch (error: Exception) {
                        store.message = error.message ?: "Nie udało się złożyć zlecenia"
                    }
                }
            },
            modifier = Modifier.fillMaxWidth().height(48.dp),
            colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Color.White),
            shape = RoundedCornerShape(13.dp),
        ) { Text(if (side == "buy") "KUP ${store.selected}" else "SPRZEDAJ ${store.selected}", fontWeight = FontWeight.Bold) }
        if (store.message.isNotEmpty()) Text(store.message, color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
    }
}

// -----------------------------------------------------------------------------
// PORTFOLIO + OPEN ORDERS
@Composable
fun PortfolioTab(store: Store, scope: CoroutineScope) {
    var total = 0.0
    var cash = 0.0
    store.portfolio.forEach { row ->
        val amount = (row.optString("available").toDoubleOrNull() ?: 0.0) + (row.optString("locked").toDoubleOrNull() ?: 0.0)
        if (row.optString("symbol") == "USD") cash = amount
        total += if (row.optString("symbol") == "USD") amount else amount * row.optDouble("last_price", 0.0)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(PageBg),
        contentPadding = PaddingValues(horizontal = 16.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            PageHeading("Portfel", "Twoje wirtualne saldo i aktywa")
            Panel {
                Text("SZACOWANA WARTOŚĆ PORTFELA", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Text("\$${money(total)}", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(10.dp))
                HorizontalDivider(color = PanelBorder)
                Spacer(Modifier.height(9.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Dostępne środki", color = Muted, fontSize = 11.sp)
                    Text("\$${money(cash)}", color = Green, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                }
            }
            if (store.accountError.isNotEmpty()) Text(store.accountError, color = Red, fontSize = 11.sp)
        }
        item { Text("AKTYWA", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
        if (store.portfolio.isEmpty()) item { Panel { Text("Portfel jest pusty.", color = Muted) } }
        items(store.portfolio, key = { it.optString("symbol") }) { row ->
            val symbol = row.optString("symbol")
            val available = row.optString("available", "0")
            val locked = row.optString("locked", "0")
            Row(Modifier.fillMaxWidth().background(PanelBg, RoundedCornerShape(14.dp)).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                AssetMark(symbol, Modifier.size(36.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(symbol, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text(if (locked == "0") "Dostępne" else "Zablokowane: ${fmt(locked)}", color = Muted, fontSize = 10.sp)
                }
                Text(fmt(available), color = Color.White, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            }
        }
        item {
            Spacer(Modifier.height(4.dp))
            Text("OTWARTE ZLECENIA", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        val openOrders = store.orders.filter { it.optString("status") == "OPEN" || it.optString("status") == "PARTIAL" }
        if (openOrders.isEmpty()) item { Panel { Text("Brak otwartych zleceń.", color = Muted, fontSize = 12.sp) } }
        items(openOrders, key = { it.optInt("id") }) { order ->
            Row(Modifier.fillMaxWidth().background(PanelBg, RoundedCornerShape(14.dp)).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    val isBuy = order.optString("side") == "buy"
                    Text("${order.optString("side").uppercase()} ${order.optString("symbol")}", color = if (isBuy) Green else Red, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    val orderPrice = if (order.isNull("price")) "MARKET" else "@ \$${fmt(order.optString("price"))}"
                    Text("Pozostało ${fmt(order.optString("remaining"))} · $orderPrice", color = Muted, fontSize = 10.sp)
                }
                TextButton(onClick = {
                    scope.launch {
                        try {
                            Api.delete("orders/${order.optInt("id")}")
                            store.message = "Anulowano zlecenie #${order.optInt("id")}"
                            store.refreshAll()
                        } catch (error: Exception) {
                            store.message = error.message ?: "Nie udało się anulować zlecenia"
                        }
                    }
                }) { Text("ANULUJ", color = Red, fontWeight = FontWeight.Bold) }
            }
        }
    }
}

// -----------------------------------------------------------------------------
// MORE / SETTINGS / CREATE ASSET
@Composable
fun MoreTab(store: Store, scope: CoroutineScope) {
    var offline by remember { mutableStateOf(Api.offline) }
    var url by remember { mutableStateOf(Api.baseUrl) }
    var key by remember { mutableStateOf(Api.apiKey) }
    var username by remember { mutableStateOf("") }
    var symbol by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var supply by remember { mutableStateOf("1000000") }

    Column(
        Modifier.fillMaxSize().background(PageBg).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PageHeading("Więcej", "Tryb rynku, konto i tworzenie aktywów")
        Panel {
            Text("TRYB RYNKU", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(offline, {
                    Api.setOffline(true); offline = true
                    store.message = "Offline market enabled"
                    scope.launch { store.refreshAll() }
                }, label = { Text("OFFLINE · BOTY") })
                FilterChip(!offline, {
                    Api.setOffline(false); offline = false
                    Api.connect(store.selected)
                    store.message = "Online server selected"
                    scope.launch { store.refreshAll() }
                }, label = { Text("ONLINE · SERWER") })
            }
            Text(
                if (offline) "Lokalny rynek działa na telefonie. Boty i gracz korzystają z tego samego silnika dopasowywania zleceń."
                else "Wspólny rynek działa przez skonfigurowany serwer.",
                color = Muted, fontSize = 11.sp,
            )
            if (offline) {
                OutlinedButton(onClick = {
                    Api.resetOffline()
                    store.message = "New offline simulation created"
                    scope.launch { store.refreshAll() }
                }) { Text("ZRESETUJ RYNEK OFFLINE") }
            } else {
                OutlinedTextField(url, { url = it }, label = { Text("Adres serwera") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(key, { key = it }, label = { Text("Klucz API") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { Api.save(url, key); Api.connect(store.selected); scope.launch { store.refreshAll() } }) { Text("ZAPISZ") }
                    OutlinedButton(onClick = { key = "demo-token" }) { Text("KLUCZ DEMO") }
                }
                OutlinedTextField(username, { username = it }, label = { Text("Nazwa nowego gracza") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedButton(onClick = {
                    Api.save(url, key)
                    scope.launch {
                        try {
                            val response = Api.register(username)
                            key = response.optString("api_key")
                            Api.save(url, key)
                            store.message = "Utworzono konto z wirtualnymi USD"
                            store.refreshAll()
                        } catch (error: Exception) { store.message = error.message ?: "Rejestracja nie powiodła się" }
                    }
                }) { Text("UTWÓRZ KONTO") }
            }
        }
        Panel {
            Text("UTWÓRZ AKTYWO SPOŁECZNOŚCI", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Text("Podaż jest ustalana przy tworzeniu. Cenę kształtują późniejsze zlecenia użytkowników.", color = Muted, fontSize = 11.sp)
            OutlinedTextField(symbol, { symbol = it.uppercase() }, label = { Text("Symbol · np. NOVA") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(name, { name = it }, label = { Text("Nazwa aktywa") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(description, { description = it }, label = { Text("Opis") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(supply, { supply = it }, label = { Text("Stała podaż") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true, modifier = Modifier.fillMaxWidth())
            Button(
                onClick = {
                    scope.launch {
                        try {
                            val body = JSONObject().put("symbol", symbol.trim()).put("name", name.trim()).put("description", description.trim()).put("total_supply", supply.trim())
                            Api.post("assets", body)
                            store.message = "Created ${symbol.trim()}"
                            store.refreshAll()
                        } catch (error: Exception) { store.message = error.message ?: "Nie udało się utworzyć aktywa" }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Blue),
            ) { Text("UTWÓRZ AKTYWO") }
            if (store.message.isNotEmpty()) Text(store.message, color = Muted, fontSize = 11.sp)
        }
        Spacer(Modifier.height(18.dp))
    }
}
