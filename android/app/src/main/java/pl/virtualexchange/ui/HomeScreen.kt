package pl.virtualexchange.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject

private data class HomeMover(val asset: JSONObject, val change: Double)

@Composable
fun TvHomeTab(
    store: Store,
    onMarket: () -> Unit,
    onChart: () -> Unit,
    onTrade: () -> Unit,
    onPortfolio: () -> Unit,
    onCompanies: () -> Unit,
) {
    val indexed = store.assets.associateBy { it.optString("symbol") }
    val movers = store.assets.mapNotNull { asset ->
        val stats = store.stats.optJSONObject(asset.optString("symbol")) ?: return@mapNotNull null
        if (stats.optInt("trades") <= 0) return@mapNotNull null
        HomeMover(asset, stats.optDouble("change_pct"))
    }
    val gainers = movers.filter { it.change > 0.0 }.sortedByDescending { it.change }.take(4)
    val losers = movers.filter { it.change < 0.0 }.sortedBy { it.change }.take(4)
    val average = movers.takeIf { it.isNotEmpty() }?.map { it.change }?.average()
    val value = store.portfolio.sumOf { row ->
        val symbol = row.optString("symbol")
        val quantity = (row.optString("available").toDoubleOrNull() ?: 0.0) +
            (row.optString("locked").toDoubleOrNull() ?: 0.0)
        if (symbol == "USD") quantity else quantity * (
            row.optString("last_price").toDoubleOrNull()
                ?: indexed[symbol]?.optString("last_price")?.toDoubleOrNull() ?: 0.0
        )
    }
    val openOrders = store.orders.count {
        it.optString("status") == "OPEN" || it.optString("status") == "PARTIAL"
    }
    val companies = store.assets.count { !it.isNull("creator_id") }

    LazyColumn(
        Modifier.fillMaxSize().background(Tv.Bg),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Column {
                Text("VIRTUALEXCHANGE  /  CENTRUM RYNKU", color = Tv.Blue, fontSize = 10.sp,
                    fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
                Text("Przegląd giełdy", color = Color.White, fontSize = 27.sp, fontWeight = FontWeight.Bold)
                Text("Rynek symulacyjny • notowania, inwestycje i spółki", color = Tv.Muted,
                    fontSize = 12.sp, modifier = Modifier.padding(top = 3.dp))
            }
        }
        item {
            TvCard {
                Text("WARTOŚĆ PORTFELA", color = Tv.Muted, fontSize = 10.sp,
                    fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Text(fmtUsd(value) + " USD", color = Color.White, fontSize = 28.sp,
                    fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 5.dp))
                Text("Gotówka i wycena posiadanych aktywów", color = Tv.Muted, fontSize = 11.sp)
                HorizontalDivider(Modifier.padding(vertical = 13.dp), color = Tv.Line)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text("ŚREDNIA ZMIANA RYNKU 24H", color = Tv.Muted, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                        Text(average?.let { fmtPct(it) } ?: "Brak danych",
                            color = when { average == null -> Tv.Muted; average >= 0 -> Tv.Up; else -> Tv.Down },
                            fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("INSTRUMENTY", color = Tv.Muted, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                        Text(store.assets.size.toString(), color = Color.White, fontSize = 17.sp,
                            fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HomeMetric("OTWARTE ZLECENIA", openOrders.toString(), Tv.Blue, Modifier.weight(1f))
                HomeMetric("SPÓŁKI GRACZY", companies.toString(), Tv.Up, Modifier.weight(1f))
                HomeMetric("RYNKI", store.assets.size.toString(), Color(0xFFB69CFF), Modifier.weight(1f))
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("SZYBKIE DZIAŁANIA", color = Tv.Muted, fontSize = 10.sp,
                    fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HomeAction("RYNEK", "Przeglądaj notowania", onMarket, Modifier.weight(1f))
                    HomeAction("WYKRES", "Analizuj cenę", onChart, Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HomeAction("HANDEL", "Kupuj i sprzedawaj", onTrade, Modifier.weight(1f))
                    HomeAction("SPÓŁKI", "Zakładaj własne firmy", onCompanies, Modifier.weight(1f))
                }
            }
        }
        item {
            TvCard {
                Text("NAJWIĘKSZE WZROSTY", color = Tv.Muted, fontSize = 10.sp,
                    fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Text("Liderzy rynku", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                if (gainers.isEmpty()) Text("Wzrosty pojawią się po zawarciu transakcji.",
                    color = Tv.Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp))
                else gainers.forEach { HomeMoverRow(it) {
                    store.selected = it.asset.optString("symbol")
                    onChart()
                } }
            }
        }
        item {
            TvCard {
                Text("NAJWIĘKSZE SPADKI", color = Tv.Muted, fontSize = 10.sp,
                    fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Text("Aktywa pod presją", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                if (losers.isEmpty()) Text("Brak aktywów ze spadkiem w bieżącym okresie.",
                    color = Tv.Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp))
                else losers.forEach { HomeMoverRow(it) {
                    store.selected = it.asset.optString("symbol")
                    onChart()
                } }
            }
        }
        if (store.bots.isNotEmpty()) {
            item {
                TvCard {
                    Text("UCZESTNICY ALGORYTMICZNI", color = Tv.Blue, fontSize = 10.sp,
                        fontWeight = FontWeight.Bold, letterSpacing = 1.1.sp)
                    Text("Co analizują boty", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    store.bots.forEach { bot ->
                        val decision = bot.optString("decision", "OBSERWUJ")
                        val tone = when (decision) { "KUPUJ" -> Tv.Up; "SPRZEDAJ" -> Tv.Down; else -> Tv.Muted }
                        Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(bot.optString("name") + " · " + bot.optString("strategy"),
                                    color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                Text(bot.optString("symbol") + " · " + bot.optString("reason"),
                                    color = Tv.Muted, fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(decision, color = tone, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                Text("${bot.optDouble("confidence").toInt()}%", color = Tv.Muted, fontSize = 9.sp)
                            }
                        }
                        HorizontalDivider(Modifier.padding(top = 8.dp), color = Tv.Line)
                    }
                }
            }
        }
        item {
            TvCard {
                Text("OD INWESTORA DO WŁAŚCICIELA", color = Tv.Blue, fontSize = 10.sp,
                    fontWeight = FontWeight.Bold, letterSpacing = 1.1.sp)
                Text("Zbuduj własną spółkę", color = Color.White, fontSize = 19.sp,
                    fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
                Text("Emituj akcje o stałej podaży, rozwijaj markę i szukaj inwestorów na wspólnym rynku.",
                    color = Tv.Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 5.dp))
                Button(onClick = onCompanies, modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Tv.Blue),
                    shape = RoundedCornerShape(11.dp)) {
                    Text("PRZEJDŹ DO SPÓŁEK", color = Color(0xFF07131E), fontWeight = FontWeight.Bold)
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().clickable(onClick = onPortfolio).padding(vertical = 5.dp),
                horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("Twój rachunek", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    Text("Saldo, aktywa i zlecenia", color = Tv.Muted, fontSize = 12.sp)
                }
                Text("OTWÓRZ  →", color = Tv.Blue, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun HomeMetric(label: String, value: String, accent: Color, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(13.dp)).background(Tv.Card).padding(11.dp)) {
        Text(value, color = accent, fontSize = 19.sp, fontWeight = FontWeight.Bold)
        Text(label, color = Tv.Muted, fontSize = 8.sp, fontWeight = FontWeight.Bold, maxLines = 2)
    }
}

@Composable
private fun HomeAction(title: String, subtitle: String, onClick: () -> Unit, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(13.dp)).background(Tv.Card).clickable(onClick = onClick).padding(14.dp)) {
        Text(title, color = Tv.Blue, fontSize = 12.sp, fontWeight = FontWeight.Black, letterSpacing = 0.8.sp)
        Text(subtitle, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun HomeMoverRow(mover: HomeMover, onClick: () -> Unit) {
    val asset = mover.asset
    val symbol = asset.optString("symbol")
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically) {
        TvBadge(symbol, 36.dp)
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Text(symbol, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text(asset.optString("name", symbol), color = Tv.Muted, fontSize = 11.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(fmtPx(asset.optString("last_price", "0")), color = Color.White, fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold)
            Text(fmtPct(mover.change), color = if (mover.change >= 0) Tv.Up else Tv.Down,
                fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
    }
    HorizontalDivider(color = Tv.Line)
}
