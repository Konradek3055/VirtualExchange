package pl.virtualexchange.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.json.JSONObject
import pl.virtualexchange.data.Api
import java.util.Locale

@Composable
fun TvCompanyTab(store: Store, scope: CoroutineScope) {
    var symbol by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var supply by remember { mutableStateOf("1000000") }
    var creating by remember { mutableStateOf(false) }
    var developments by remember { mutableStateOf<Map<String, JSONObject>>(emptyMap()) }
    var busyCompany by remember { mutableStateOf("") }
    val companies = store.assets.filter { !it.isNull("creator_id") }
    val mine = companies.filter { it.optInt("creator_id", -1) == store.userId }
    val limitReached = mine.size >= 3

    LaunchedEffect(store.assets, store.userId) {
        val loaded = mutableMapOf<String, JSONObject>()
        for (asset in mine) {
            val ticker = asset.optString("symbol")
            try { loaded[ticker] = Api.getObject("companies/" + ticker + "/development", auth = true) }
            catch (_: Exception) { }
        }
        developments = loaded
    }

    LazyColumn(Modifier.fillMaxSize().background(Tv.Bg),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Column {
                Text("EKOSYSTEM BIZNESOWY", color = Tv.Blue, fontSize = 10.sp,
                    fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
                Text("Spółki", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                Text("Zakładaj własne firmy, emituj akcje i obserwuj ich pozycję na giełdzie.",
                    color = Tv.Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
            }
        }
        item {
            TvCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    CompanyMetric("TWOJE SPÓŁKI", mine.size.toString())
                    CompanyMetric("LIMIT", "3")
                    CompanyMetric("SPOŁECZNOŚĆ", companies.size.toString())
                }
                HorizontalDivider(Modifier.padding(vertical = 12.dp), color = Tv.Line)
                Text("Model spółki", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Text("Zakładasz markę z unikalnym tickerem i stałą podażą akcji. Cała emisja trafia początkowo do Ciebie. Nie ustawiasz ceny ręcznie — wyznacza ją pierwsza dopasowana transakcja. Później akcje mogą kupować i sprzedawać inni uczestnicy.",
                    color = Tv.Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 5.dp))
            }
        }
        item {
            TvCard {
                Text("ZAŁÓŻ NOWĄ SPÓŁKĘ", color = Tv.Blue, fontSize = 10.sp,
                    fontWeight = FontWeight.Bold, letterSpacing = 1.1.sp)
                Text("Wprowadź firmę na giełdę", color = Color.White, fontSize = 18.sp,
                    fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
                TvInput("Ticker (2–10 znaków)", symbol, { symbol = it.uppercase(Locale.ROOT) }, placeholder = "np. NOVA")
                Spacer(Modifier.height(9.dp))
                TvInput("Nazwa spółki", name, { name = it }, placeholder = "Nazwa firmy")
                Spacer(Modifier.height(9.dp))
                TvInput("Opis działalności", description, { description = it }, placeholder = "Czym zajmuje się Twoja spółka?")
                Spacer(Modifier.height(9.dp))
                TvInput("Stała podaż akcji", supply, { supply = it }, keyboard = KeyboardType.Decimal, placeholder = "1000000")
                Text("Opłata emisyjna: 100 wirtualnych USD • limit 3 spółek na gracza",
                    color = Tv.Muted, fontSize = 10.sp, modifier = Modifier.padding(top = 9.dp))
                Button(
                    onClick = {
                        creating = true
                        scope.launch {
                            try {
                                val payload = JSONObject().put("symbol", symbol.trim().uppercase(Locale.ROOT))
                                    .put("name", name.trim()).put("description", description.trim())
                                    .put("total_supply", supply.trim())
                                val result = Api.post("assets", payload)
                                store.message = "Utworzono spółkę " + result.optString("symbol", symbol) + "."
                                symbol = ""; name = ""; description = ""; supply = "1000000"
                                store.refreshAll()
                            } catch (error: Exception) {
                                store.message = error.message ?: "Nie udało się utworzyć spółki."
                            } finally {
                                creating = false
                            }
                        }
                    },
                    enabled = !creating && !limitReached && symbol.trim().matches(Regex("[A-Z][A-Z0-9]{1,9}")) &&
                        name.trim().length in 2..40 && (supply.toBigDecimalOrNull()?.signum() ?: 0) > 0,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp).height(48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Tv.Blue),
                    shape = RoundedCornerShape(11.dp),
                ) {
                    Text(if (creating) "TWORZENIE..." else if (limitReached) "LIMIT WYKORZYSTANY" else "ZAŁÓŻ SPÓŁKĘ",
                        color = Color(0xFF07131E), fontWeight = FontWeight.Bold)
                }
                if (store.message.isNotBlank()) Text(store.message, color = Tv.Muted, fontSize = 11.sp,
                    modifier = Modifier.padding(top = 8.dp))
                if (store.accountError.isNotBlank()) Text("Konto: " + store.accountError, color = Tv.Down,
                    fontSize = 10.sp, modifier = Modifier.padding(top = 5.dp))
            }
        }
        item { Text("TWOJE SPÓŁKI", color = Tv.Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold) }
        if (mine.isEmpty()) {
            item {
                TvCard {
                    Text("Nie masz jeszcze własnej spółki.", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text("Utwórz pierwszą firmę powyżej. Jej akcje pojawią się na rynku.",
                        color = Tv.Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 5.dp))
                }
            }
        } else {
            items(mine, key = { it.optString("symbol") }) { asset ->
                val ticker = asset.optString("symbol")
                CompanyListingCard(asset, store, true)
                developments[ticker]?.let { development ->
                    CompanyDevelopmentCard(development, busyCompany == ticker) { branch ->
                        busyCompany = ticker
                        scope.launch {
                            try {
                                val result = Api.post("companies/" + ticker + "/upgrade", JSONObject().put("branch", branch))
                                developments = developments + (ticker to result)
                                store.refreshAccount()
                                store.message = "Rozwinięto spółkę " + ticker + "."
                            } catch (error: Exception) {
                                store.message = error.message ?: "Nie udało się rozwinąć spółki."
                            } finally {
                                busyCompany = ""
                            }
                        }
                    }
                }
            }
        }
        item {
            Text("SPÓŁKI SPOŁECZNOŚCI", color = Tv.Muted, fontSize = 10.sp,
                fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
        }
        if (companies.isEmpty()) {
            item { Text("Na razie nie ma spółek utworzonych przez graczy.", color = Tv.Muted, fontSize = 12.sp) }
        } else {
            items(companies.sortedBy { it.optString("symbol") }, key = { it.optString("symbol") }) {
                CompanyListingCard(it, store, it.optInt("creator_id", -1) == store.userId)
            }
        }
    }
}

@Composable
private fun CompanyDevelopmentCard(state: JSONObject, busy: Boolean, onUpgrade: (String) -> Unit) {
    val next = state.optJSONObject("next_costs")
    val maxLevel = state.optInt("max_level", 10)
    TvCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text("ROZWÓJ FIRMY", color = Tv.Blue, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Text("Poziom " + state.optInt("company_level", 1), color = Color.White,
                    fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("INDEKS ROZWOJU", color = Tv.Muted, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                Text(state.optInt("development_index", 100).toString(), color = Tv.Up,
                    fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
        }
        Text("Zainwestowano: " + fmtUsd(state.optString("invested", "0").toDoubleOrNull() ?: 0.0) + " USD",
            color = Tv.Muted, fontSize = 10.sp, modifier = Modifier.padding(top = 5.dp, bottom = 10.dp))
        CompanyBranch("BIURO I ZARZĄDZANIE", "office", state.optInt("office_level"),
            next?.optLong("office", 500L) ?: 500L, maxLevel, busy, onUpgrade)
        Spacer(Modifier.height(8.dp))
        CompanyBranch("LABORATORIUM I R&D", "research", state.optInt("research_level"),
            next?.optLong("research", 500L) ?: 500L, maxLevel, busy, onUpgrade)
        Spacer(Modifier.height(8.dp))
        CompanyBranch("MARKETING I MARKA", "marketing", state.optInt("marketing_level"),
            next?.optLong("marketing", 500L) ?: 500L, maxLevel, busy, onUpgrade)
        Text("Inwestycje rozwijają poziom firmy. Nie ustawiają automatycznie kursu akcji — ten nadal wynika z dopasowanych zleceń.",
            color = Tv.Muted, fontSize = 9.sp, modifier = Modifier.padding(top = 10.dp))
    }
}

@Composable
private fun CompanyBranch(
    label: String, branch: String, level: Int, cost: Long, maxLevel: Int,
    busy: Boolean, onUpgrade: (String) -> Unit,
) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(11.dp)).background(Tv.Chip).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            Text("Poziom " + level + " / " + maxLevel, color = Tv.Muted, fontSize = 10.sp,
                modifier = Modifier.padding(top = 3.dp))
        }
        Button(onClick = { onUpgrade(branch) }, enabled = !busy && level < maxLevel,
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 5.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Tv.Blue),
            shape = RoundedCornerShape(9.dp)) {
            Text(if (level >= maxLevel) "MAX" else "ROZWIŃ · " + fmtUsd(cost.toDouble()) + " USD",
                color = Color(0xFF07131E), fontSize = 9.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun CompanyMetric(label: String, value: String) {
    Column {
        Text(value, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(label, color = Tv.Muted, fontSize = 8.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun CompanyListingCard(asset: JSONObject, store: Store, own: Boolean) {
    val symbol = asset.optString("symbol")
    val stat = store.stats.optJSONObject(symbol)
    val price = asset.optString("last_price", "0").toDoubleOrNull() ?: 0.0
    val supply = asset.optString("total_supply", "0").toDoubleOrNull() ?: 0.0
    val marketCap = price * supply
    val balance = store.portfolio.firstOrNull { it.optString("symbol") == symbol }
    val held = (balance?.optString("available")?.toDoubleOrNull() ?: 0.0) +
        (balance?.optString("locked")?.toDoubleOrNull() ?: 0.0)
    TvCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TvBadge(symbol, 42.dp)
            Column(Modifier.weight(1f).padding(start = 11.dp)) {
                Text(asset.optString("name", symbol), color = Color.White, fontSize = 15.sp,
                    fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(symbol + if (own) " • TWOJA SPÓŁKA" else " • SPOŁECZNOŚĆ",
                    color = if (own) Tv.Blue else Tv.Muted, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(if (price > 0.0) fmtPx(price) else "Nie notowana",
                    color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Text("USD / akcję", color = Tv.Muted, fontSize = 9.sp)
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 10.dp), color = Tv.Line)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            CompanyStat("KAPITALIZACJA", if (marketCap > 0.0) fmtUsd(marketCap) else "—")
            CompanyStat("POSIADACZE", stat?.optInt("holders")?.toString() ?: "0")
            CompanyStat("WOLUMEN 24H", stat?.optString("volume_usd")?.toDoubleOrNull()?.let { fmtUsd(it) } ?: "0")
        }
        if (own) Text("Twoje akcje: " + fmtQty(held.toString()) + " / " + fmtQty(supply.toString()),
            color = Tv.Muted, fontSize = 10.sp, modifier = Modifier.padding(top = 9.dp))
    }
}

@Composable
private fun CompanyStat(label: String, value: String) {
    Column {
        Text(value, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Text(label, color = Tv.Muted, fontSize = 8.sp, fontWeight = FontWeight.Bold)
    }
}
