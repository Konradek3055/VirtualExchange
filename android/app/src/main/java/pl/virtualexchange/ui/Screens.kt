@file:OptIn(ExperimentalMaterial3Api::class)

package pl.virtualexchange.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.json.JSONObject
import pl.virtualexchange.data.Api
import pl.virtualexchange.data.fmt
import pl.virtualexchange.data.money

private val pad = Modifier.padding(horizontal = 14.dp)

// ------------------------------------------------------------------ RYNEK
@Composable
fun MarketTab(store: Store, onOpen: () -> Unit) {
    LazyColumn(pad, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text("RYNEK", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.headlineSmall) }
        items(store.assets) { a ->
            val sym = a.optString("symbol")
            val last = a.optString("last_price", "0")
            val supply = a.optString("total_supply", "0").toDoubleOrNull() ?: 0.0
            val lastD = last.toDoubleOrNull() ?: 0.0
            ElevatedCard(onClick = { store.selected = sym; onOpen() }, modifier = Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth().padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text(sym, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                        Text(a.optString("name"), style = MaterialTheme.typography.bodySmall)
                        if (!a.isNull("creator_id")) {
                            Text("aktywo użytkownika", color = Gold, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("\$" + fmt(last), fontWeight = FontWeight.Bold)
                        if (supply > 0.0 && lastD > 0.0) {
                            Text("MCap \$" + money(supply * lastD), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ HANDEL
@Composable
fun TradeTab(store: Store, scope: CoroutineScope) {
    var price by remember(store.selected) {
        mutableStateOf(if (store.lastPrice == "0") "" else fmt(store.lastPrice))
    }
    LazyColumn(pad, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            LazyRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(store.assets) { a ->
                    val s = a.optString("symbol")
                    FilterChip(store.selected == s, { store.selected = s }, label = { Text(s) })
                }
            }
        }
        item {
            val last = store.lastPrice
            Text(
                store.selected + "  " + (if (last == "0") "-" else "\$" + fmt(last)),
                fontWeight = FontWeight.Bold, fontSize = 22.sp,
            )
            Text(
                "Świece 1 min, tylko z realnych transakcji",
                style = MaterialTheme.typography.labelSmall,
            )
        }
        item {
            Box(Modifier.fillMaxWidth().height(220.dp)) {
                if (store.candles.isEmpty()) {
                    Text(
                        "Brak transakcji - wykres pojawi się po pierwszej realnej transakcji.",
                        Modifier.align(Alignment.Center),
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    CandleChart(store.candles, Modifier.fillMaxWidth().height(220.dp))
                }
            }
        }
        item { HorizontalDivider() }
        item {
            OrderBookView(store.asks, store.bids, store.lastPrice) { picked -> price = fmt(picked) }
        }
        item { HorizontalDivider() }
        item { OrderForm(store, scope, price) { price = it } }
        item { HorizontalDivider() }
        item { Text("OSTATNIE TRANSAKCJE", style = MaterialTheme.typography.titleSmall) }
        if (store.trades.isEmpty()) {
            item { Text("Brak transakcji.", style = MaterialTheme.typography.bodySmall) }
        }
        items(store.trades) { t ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(fmt(t.optString("price")), fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                Text(fmt(t.optString("quantity")), fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                Text(t.optString("created_at").takeLast(8), fontFamily = FontFamily.Monospace, fontSize = 13.sp)
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun OrderForm(store: Store, scope: CoroutineScope, price: String, onPrice: (String) -> Unit) {
    var side by remember { mutableStateOf("buy") }
    var kind by remember { mutableStateOf("limit") }
    var qty by remember { mutableStateOf("") }
    val estimate = (qty.toDoubleOrNull() ?: 0.0) * (price.toDoubleOrNull() ?: 0.0)
    val accent = if (side == "buy") Green else Red

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("ZŁÓŻ ZLECENIE", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(side == "buy", { side = "buy" }, label = { Text("KUP") })
            FilterChip(side == "sell", { side = "sell" }, label = { Text("SPRZEDAJ") })
            Spacer(Modifier.width(8.dp))
            FilterChip(kind == "limit", { kind = "limit" }, label = { Text("LIMIT") })
            FilterChip(kind == "market", { kind = "market" }, label = { Text("MARKET") })
        }
        OutlinedTextField(
            qty, { qty = it }, label = { Text("Ilość (${store.selected})") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        if (kind == "limit") {
            OutlinedTextField(
                price, onPrice, label = { Text("Cena limit (USD)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            if (estimate > 0.0) {
                Text("Wartość ok. \$" + money(estimate), style = MaterialTheme.typography.labelSmall)
            }
        } else {
            Text("Market: realizacja po najlepszych cenach z księgi.", style = MaterialTheme.typography.labelSmall)
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
                        val r = Api.post("orders", body)
                        store.message = "Zlecenie #" + r.optInt("order_id") + ": " + r.optString("status")
                        store.refreshMarket()
                        store.refreshAccount()
                    } catch (e: Exception) {
                        store.message = e.message ?: "Błąd"
                    }
                }
            },
            colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Color.White),
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (side == "buy") "KUP ${store.selected}" else "SPRZEDAJ ${store.selected}") }
        if (store.message.isNotEmpty()) Text(store.message, style = MaterialTheme.typography.bodySmall)
    }
}

// ------------------------------------------------------------------ PORTFEL
@Composable
fun PortfolioTab(store: Store) {
    var total = 0.0
    store.portfolio.forEach { r ->
        val amount = (r.optString("available").toDoubleOrNull() ?: 0.0) + (r.optString("locked").toDoubleOrNull() ?: 0.0)
        total += if (r.optString("symbol") == "USD") amount else amount * r.optDouble("last_price", 0.0)
    }
    LazyColumn(pad, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text("PORTFEL", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.headlineSmall)
            Text("Wartość ok. \$" + money(total), fontWeight = FontWeight.Bold, fontSize = 22.sp)
            Text(
                "Aktywa wyceniane po ostatniej cenie transakcji.",
                style = MaterialTheme.typography.labelSmall,
            )
            if (store.accountError.isNotEmpty()) {
                Text(store.accountError, color = Red, style = MaterialTheme.typography.bodySmall)
            }
        }
        items(store.portfolio) { r ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(r.optString("symbol"), fontWeight = FontWeight.Bold)
                Column(horizontalAlignment = Alignment.End) {
                    Text(fmt(r.optString("available")), fontFamily = FontFamily.Monospace)
                    if (r.optString("locked") != "0") {
                        Text("zablokowane: " + fmt(r.optString("locked")), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            HorizontalDivider()
        }
    }
}

// ------------------------------------------------------------------ ZLECENIA
@Composable
fun OrdersTab(store: Store, scope: CoroutineScope) {
    LazyColumn(pad, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            Text("MOJE ZLECENIA", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.headlineSmall)
            if (store.orders.isEmpty()) Text("Brak zleceń.", style = MaterialTheme.typography.bodySmall)
        }
        items(store.orders) { o ->
            val status = o.optString("status")
            val open = status == "OPEN" || status == "PARTIAL"
            val sideColor = if (o.optString("side") == "buy") Green else Red
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        o.optString("side").uppercase() + " " + o.optString("symbol") + " " + o.optString("order_type"),
                        color = sideColor, fontWeight = FontWeight.Bold,
                    )
                    val p = if (o.isNull("price")) "market" else "@ " + fmt(o.optString("price"))
                    Text(
                        fmt(o.optString("remaining")) + " / " + fmt(o.optString("quantity")) + " " + p,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(status + " · " + o.optString("created_at"), style = MaterialTheme.typography.labelSmall)
                }
                if (open) {
                    TextButton(onClick = {
                        scope.launch {
                            try {
                                Api.delete("orders/" + o.optInt("id"))
                                store.message = "Anulowano zlecenie #" + o.optInt("id")
                                store.refreshMarket()
                                store.refreshAccount()
                            } catch (e: Exception) {
                                store.message = e.message ?: "Błąd"
                            }
                        }
                    }) { Text("ANULUJ", color = Red) }
                }
            }
            HorizontalDivider()
        }
    }
}

// ------------------------------------------------------------------ WIĘCEJ (konto, serwer, nowe aktywo)
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
        pad.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("TRYB PRACY", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.headlineSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(offline, {
                Api.setOffline(true); offline = true
                store.message = "Tryb offline: symulacja z botami"
                scope.launch { store.refreshAll() }
            }, label = { Text("OFFLINE (boty)") })
            FilterChip(!offline, {
                Api.setOffline(false); offline = false
                Api.connect(store.selected)
                store.message = "Tryb online: serwer " + Api.baseUrl
                scope.launch { store.refreshAll() }
            }, label = { Text("ONLINE (serwer)") })
        }
        if (offline) {
            Text(
                "Rynek działa na telefonie, bez serwera. Boty (market makerzy i traderzy) składają zlecenia przez ten sam silnik co gracz; " +
                    "ceny i świece powstają wyłącznie z dopasowanych zleceń. Historia przed startem jest wcześniej zasymulowana przez boty. " +
                    "Stan nie jest zapisywany po zamknięciu aplikacji.",
                style = MaterialTheme.typography.labelSmall,
            )
            OutlinedButton(onClick = {
                Api.resetOffline()
                store.message = "Nowa symulacja: 10 000 USD, nowe boty"
                scope.launch { store.refreshAll() }
            }) { Text("RESETUJ SYMULACJĘ") }
        } else {
            Text("KONTO I SERWER", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(url, { url = it }, label = { Text("Adres serwera") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(key, { key = it }, label = { Text("Klucz API") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    Api.save(url, key)
                    Api.connect(store.selected)
                    scope.launch { store.refreshAll() }
                    store.message = "Zapisano ustawienia"
                }) { Text("ZAPISZ") }
                OutlinedButton(onClick = { key = "demo-token" }) { Text("KONTO DEMO") }
            }
            OutlinedTextField(username, { username = it }, label = { Text("Nazwa nowego gracza (3-24, A-Z a-z 0-9 _)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedButton(onClick = {
                Api.save(url, key)
                scope.launch {
                    try {
                        val r = Api.register(username)
                        key = r.optString("api_key")
                        Api.save(url, key)
                        store.message = "Konto utworzone: 10 000 wirtualnych USD"
                        store.refreshAll()
                    } catch (e: Exception) {
                        store.message = e.message ?: "Błąd"
                    }
                }
            }) { Text("UTWÓRZ KONTO") }
        }

        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Text("NOWE AKTYWO", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Podaż jest stała, trafia do Ciebie. Cenę ustala wyłącznie rynek.",
            style = MaterialTheme.typography.labelSmall,
        )
        OutlinedTextField(symbol, { symbol = it.uppercase() }, label = { Text("Ticker (np. KOT)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(name, { name = it }, label = { Text("Nazwa") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(description, { description = it }, label = { Text("Opis") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(
            supply, { supply = it }, label = { Text("Podaż") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = {
            scope.launch {
                try {
                    val body = JSONObject()
                        .put("symbol", symbol.trim())
                        .put("name", name.trim())
                        .put("description", description.trim())
                        .put("total_supply", supply.trim())
                    Api.post("assets", body)
                    store.message = "Utworzono aktywo " + symbol.trim()
                    store.refreshAll()
                } catch (e: Exception) {
                    store.message = e.message ?: "Błąd"
                }
            }
        }) { Text("UTWÓRZ AKTYWO") }
        if (store.message.isNotEmpty()) Text(store.message, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(24.dp))
    }
}
