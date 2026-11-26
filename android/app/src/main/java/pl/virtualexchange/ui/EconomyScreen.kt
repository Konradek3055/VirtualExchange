package pl.virtualexchange.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import pl.virtualexchange.data.Api
import pl.virtualexchange.data.objects

/** Ekran gospodarki: wszystkie dane pochodzą z API /economy/* (backend: Economy Core), bez danych lokalnych. */
private val KIND_PL = mapOf("materials" to "Surowce", "energy" to "Energia", "capital" to "Maszyny")
private val PRODUCTS = listOf("widget" to "Widget (stal)", "bread" to "Chleb (zboże)")
private val ACTIONS = listOf("Wpłata", "Surowce", "Zatrudnienie", "Płaca", "Cena", "Maszyny", "Dywidenda")

private fun money(s: String?): String = s?.toDoubleOrNull()?.let { fmtUsd(it) } ?: "—"

@Composable
fun TvEconomyTab(store: Store, scope: CoroutineScope) {
    if (Api.offline) {
        EconomyNotice(
            "Gospodarka wymaga trybu online",
            "Silnik gospodarki (firmy, dostawcy, podatki, księgowość) działa na serwerze. " +
                "W trybie offline z botami ten ekran jest nieaktywny. Przełącz tryb i podaj adres serwera oraz klucz API w Menu.",
        )
        return
    }
    var section by remember { mutableIntStateOf(0) }
    var reload by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var status by remember { mutableStateOf(JSONObject()) }
    var regions by remember { mutableStateOf(listOf<JSONObject>()) }
    var households by remember { mutableStateOf(listOf<JSONObject>()) }
    var entities by remember { mutableStateOf(listOf<JSONObject>()) }
    var myFirms by remember { mutableStateOf(listOf<JSONObject>()) }
    var ledger by remember { mutableStateOf(listOf<JSONObject>()) }

    LaunchedEffect(reload, store.userId) {
        loading = true
        try {
            status = Api.getObject("economy/status", auth = true)
            regions = Api.getArray("economy/regions", auth = true).objects()
            households = Api.getArray("economy/households", auth = true).objects()
            entities = Api.getArray("economy/entities", auth = true).objects()
            myFirms = Api.getArray("economy/player/firms", auth = true).objects()
            ledger = Api.getArray("economy/transactions?limit=60&cash_only=true", auth = true).objects().asReversed()
            error = ""
        } catch (e: Exception) {
            error = e.message ?: "Nie udało się pobrać danych gospodarki."
        } finally {
            loading = false
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(10_000)
            reload++
        }
    }

    fun act(path: String, payload: JSONObject, success: String) {
        scope.launch {
            try {
                Api.post("economy/$path", payload)
                store.message = success
                store.refreshAccount()
                reload++
            } catch (e: Exception) {
                store.message = e.message ?: "Operacja nie powiodła się."
            }
        }
    }

    LazyColumn(
        Modifier.fillMaxSize().background(Tv.Bg),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column {
                Text("SYMULATOR GOSPODARKI", color = Tv.Blue, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
                Text("Gospodarka", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                Text(
                    "Firmy, dostawcy, płace, podatki i dywidendy z rzeczywistych zapisów księgowych serwera.",
                    color = Tv.Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        item { PillTabs(listOf("Przegląd", "Moje firmy", "Dostawcy", "Rejestr"), section, { section = it }) }
        if (error.isNotEmpty()) {
            item {
                TvCard {
                    Text("Brak danych", color = Tv.Down, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Text(error, color = Tv.Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                    Text("Sprawdź w Menu adres serwera i klucz API konta.", color = Tv.Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                    Spacer(Modifier.height(10.dp))
                    EcoButton("Spróbuj ponownie", true) { reload++ }
                }
            }
        } else if (loading && status.length() == 0) {
            item { Text("Ładowanie…", color = Tv.Muted, fontSize = 13.sp) }
        } else when (section) {
            0 -> {
                item { OverviewCard(status) }
                items(regions) { RegionCard(it) }
                items(households) { HouseholdCard(it) }
            }
            1 -> {
                item {
                    WithdrawCard { amount -> act("player/withdraw", JSONObject().put("amount", amount), "Wypłacono do portfela.") }
                }
                item {
                    FoundFirmCard(regions) { name, region, product ->
                        act("player/firms", JSONObject().put("name", name).put("region", region).put("product", product), "Firma założona.")
                    }
                }
                if (myFirms.isEmpty()) {
                    item { Text("Nie masz jeszcze firm. Załóż pierwszą powyżej, a potem wpłać kapitał.", color = Tv.Muted, fontSize = 13.sp) }
                }
                items(myFirms) { firm -> FirmPanel(firm, ::act) }
            }
            2 -> {
                if (entities.isEmpty()) item { Text("Brak dostawców.", color = Tv.Muted, fontSize = 13.sp) }
                items(entities) { SupplierCard(it) }
            }
            else -> {
                if (ledger.isEmpty()) item { Text("Brak przelewów gotówkowych. Uruchom ticki gospodarki na serwerze.", color = Tv.Muted, fontSize = 13.sp) }
                items(ledger) { LedgerRow(it) }
            }
        }
    }
}

@Composable
private fun EconomyNotice(title: String, body: String) {
    Column(Modifier.fillMaxSize().background(Tv.Bg).padding(16.dp)) {
        TvCard {
            Text(title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(body, color = Tv.Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

@Composable
private fun EcoButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(12.dp))
            .background(if (enabled) Tv.Blue else Tv.Chip)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 11.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (enabled) Color.Black else Tv.Muted, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun Metric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, color = Tv.Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
        Text(value, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 2.dp))
    }
}

@Composable
private fun StatRow(label: String, value: String, bold: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = if (bold) Color.White else Tv.Muted, fontSize = 13.sp, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal)
        Text(value, color = Color.White, fontSize = 13.sp, fontWeight = if (bold) FontWeight.Bold else FontWeight.Medium)
    }
}

@Composable
private fun OverviewCard(status: JSONObject) {
    TvCard {
        Text("STAN ŚWIATA", color = Tv.Blue, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.1.sp)
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Metric("TICK", status.optInt("last_tick", 0).toString())
            Metric("FIRMY", status.optInt("firms", 0).toString())
            Metric("GOSPODARSTWA", status.optInt("households", 0).toString())
        }
        HorizontalDivider(Modifier.padding(vertical = 12.dp), color = Tv.Line)
        StatRow("Podaż pieniądza", money(status.optString("money_supply")))
        StatRow("Zapisy księgowe", status.optInt("journal_entries", 0).toString())
        StatRow("Wersja schematu", status.optInt("schema_version", 0).toString())
        if (status.optInt("last_tick", 0) == 0) {
            Text(
                "Świat nie wykonał jeszcze żadnego ticku. Ticki uruchamia serwer (endpoint administracyjny) — aplikacja ich nie wywołuje.",
                color = Tv.Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun RegionCard(r: JSONObject) {
    TvCard {
        Text("Region ${r.optString("region")}", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        StatRow("Podatek dochodowy", pct(r.optString("income_tax_rate")))
        StatRow("Podatek CIT", pct(r.optString("corporate_tax_rate")))
        StatRow("Wydatki rządu (udział kasy)", pct(r.optString("gov_spend_ratio")))
    }
}

private fun pct(s: String): String = s.toDoubleOrNull()?.let { fmtPct(it * 100.0).removePrefix("+") } ?: "—"

@Composable
private fun HouseholdCard(h: JSONObject) {
    TvCard {
        Text("Gospodarstwa ${h.optString("group_id")}", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        StatRow("Region", h.optString("region"))
        StatRow("Populacja", h.optInt("population", 0).toString())
        StatRow("Zatrudnieni", h.optInt("employed", 0).toString())
        StatRow("Gotówka", money(h.optString("cash")))
    }
}

@Composable
private fun SupplierCard(e: JSONObject) {
    val sells = e.optString("sells", "{}").let { runCatching { JSONObject(it) }.getOrNull() ?: JSONObject() }
    val offer = sells.keys().asSequence().joinToString(", ") { "$it @ ${money(sells.optString(it))}" }
    TvCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(e.optString("entity_id"), color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text(KIND_PL[e.optString("kind")] ?: e.optString("kind"), color = Tv.Blue, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        StatRow("Region", e.optString("region"))
        StatRow("Pracownicy", e.optInt("employees", 0).toString())
        StatRow("Właściciel", e.optString("owner_group").ifEmpty { "—" })
        if (offer.isNotEmpty()) StatRow("Oferta", offer)
    }
}

@Composable
private fun LedgerRow(t: JSONObject) {
    TvCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("#${t.optInt("id")} · tick ${t.optInt("tick")}", color = Tv.Muted, fontSize = 11.sp)
            Text(money(t.optString("amount")), color = Tv.Up, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
        Text(t.optString("description"), color = Color.White, fontSize = 13.sp, modifier = Modifier.padding(top = 3.dp))
        Text("${t.optString("credit")} → ${t.optString("debit")}", color = Tv.Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
    }
}

@Composable
private fun WithdrawCard(onWithdraw: (String) -> Unit) {
    var amount by remember { mutableStateOf("") }
    TvCard {
        Text("WYPŁATA DO PORTFELA", color = Tv.Blue, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.1.sp)
        Text("Przenosi gotówkę z dywidend do Twojego portfela USD. Wymaga włączonego mostu na serwerze.", color = Tv.Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
        Spacer(Modifier.height(8.dp))
        TvInput("Kwota", amount, { amount = it }, suffix = "USD", keyboard = KeyboardType.Decimal)
        Spacer(Modifier.height(8.dp))
        EcoButton("Wypłać", amount.toDoubleOrNull()?.let { it > 0 } == true) { onWithdraw(amount.trim()); amount = "" }
    }
}

@Composable
private fun FoundFirmCard(regions: List<JSONObject>, onFound: (String, String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var product by remember { mutableStateOf(PRODUCTS.first().first) }
    var regionIdx by remember { mutableIntStateOf(0) }
    val names = regions.map { it.optString("region") }
    TvCard {
        Text("ZAŁÓŻ FIRMĘ", color = Tv.Blue, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.1.sp)
        Spacer(Modifier.height(8.dp))
        TvInput("Nazwa firmy (3–40 znaków)", name, { name = it }, placeholder = "np. Piekarnia Nova")
        Spacer(Modifier.height(10.dp))
        Text("Produkt", color = Tv.Muted, fontSize = 12.sp)
        Spacer(Modifier.height(4.dp))
        PillTabs(PRODUCTS.map { it.second }, PRODUCTS.indexOfFirst { it.first == product }.coerceAtLeast(0), { product = PRODUCTS[it].first })
        if (names.size > 1) {
            Spacer(Modifier.height(10.dp))
            Text("Region", color = Tv.Muted, fontSize = 12.sp)
            Spacer(Modifier.height(4.dp))
            PillTabs(names, regionIdx.coerceIn(0, names.size - 1), { regionIdx = it })
        }
        Spacer(Modifier.height(10.dp))
        EcoButton("Załóż", name.trim().length >= 3 && names.isNotEmpty()) {
            onFound(name.trim(), names[regionIdx.coerceIn(0, names.size - 1)], product)
            name = ""
        }
    }
}

@Composable
private fun FirmPanel(firm: JSONObject, act: (String, JSONObject, String) -> Unit) {
    val id = firm.optString("firm_id")
    var open by remember(id) { mutableStateOf(false) }
    var statements by remember(id) { mutableStateOf<JSONObject?>(null) }
    var statementError by remember(id) { mutableStateOf("") }
    var material by remember(id) { mutableStateOf("") }
    var action by remember(id) { mutableIntStateOf(0) }
    var value by remember(id) { mutableStateOf("") }

    LaunchedEffect(open, firm.optString("cash"), firm.optInt("employees")) {
        if (open) {
            try {
                statements = Api.getObject("economy/firms/$id/statements", auth = true)
                val detail = Api.getObject("economy/firms/$id", auth = true)
                material = detail.optJSONArray("products")?.optJSONObject(0)?.optString("material_sku").orEmpty()
                statementError = ""
            } catch (e: Exception) {
                statementError = e.message ?: "Nie udało się pobrać sprawozdań."
            }
        }
    }

    TvCard {
        Row(Modifier.fillMaxWidth().clickable { open = !open }, horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text(firm.optString("name").ifEmpty { id }, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text("$id · ${firm.optString("region")}", color = Tv.Muted, fontSize = 11.sp)
            }
            Text(if (open) "Zwiń" else "Szczegóły", color = Tv.Blue, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Metric("GOTÓWKA", money(firm.optString("cash")))
            Metric("PRACOWNICY", firm.optInt("employees", 0).toString())
            Metric("MOC/H", firm.optString("capacity_per_hour").toDoubleOrNull()?.let { fmtPx(it) } ?: "—")
        }
        if (open) {
            HorizontalDivider(Modifier.padding(vertical = 12.dp), color = Tv.Line)
            StatRow("Płaca za godzinę", money(firm.optString("wage_per_hour")))
            StatRow("Marża nad kosztem", "×" + (firm.optString("sales_markup").toDoubleOrNull()?.let { fmtPx(it) } ?: "—"))
            Spacer(Modifier.height(10.dp))
            Text("DECYZJE", color = Tv.Blue, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.1.sp)
            Spacer(Modifier.height(6.dp))
            PillTabs(ACTIONS, action, { action = it; value = "" })
            Spacer(Modifier.height(8.dp))
            val label = when (action) {
                0 -> "Kwota wpłaty z portfela (USD)"
                1 -> "Ilość surowca ($material) do kupienia"
                2 -> "Zmiana zatrudnienia (np. 5 lub -3)"
                3 -> "Nowa płaca za godzinę (5–500)"
                4 -> "Marża nad kosztem (1,0–5,0)"
                5 -> "Kwota inwestycji w maszyny (USD)"
                else -> "Kwota dywidendy do wypłaty (USD)"
            }
            TvInput(label, value, { value = it.replace(',', '.') }, keyboard = KeyboardType.Decimal)
            Spacer(Modifier.height(8.dp))
            val number = value.toDoubleOrNull()
            val valid = number != null && (action == 2 && number != 0.0 && number == Math.rint(number) || action != 2 && number > 0)
            EcoButton("Wykonaj", valid) {
                val base = "player/firms/$id"
                val v = value.trim()
                when (action) {
                    0 -> act("$base/deposit", JSONObject().put("amount", v), "Kapitał wpłacony.")
                    1 -> act("$base/materials", JSONObject().put("sku", material).put("quantity", v), "Surowce kupione.")
                    2 -> {
                        val n = number!!.toInt()
                        act("$base/staff", JSONObject().put("action", if (n > 0) "hire" else "fire").put("count", Math.abs(n)),
                            if (n > 0) "Zatrudniono pracowników." else "Zwolniono pracowników.")
                    }
                    3 -> act("$base/wage", JSONObject().put("wage_per_hour", v), "Płaca zmieniona.")
                    4 -> act("$base/price", JSONObject().put("markup", v), "Marża zmieniona.")
                    5 -> act("$base/capex", JSONObject().put("amount", v), "Maszyny kupione.")
                    else -> act("$base/dividend", JSONObject().put("amount", v), "Dywidenda wypłacona.")
                }
                value = ""
            }
            HorizontalDivider(Modifier.padding(vertical = 12.dp), color = Tv.Line)
            if (statementError.isNotEmpty()) Text(statementError, color = Tv.Down, fontSize = 12.sp)
            statements?.let { Statements(it) }
        }
    }
}

@Composable
private fun Statements(s: JSONObject) {
    val bs = s.optJSONObject("balance_sheet") ?: JSONObject()
    val a = bs.optJSONObject("assets") ?: JSONObject()
    val l = bs.optJSONObject("liabilities") ?: JSONObject()
    val e = bs.optJSONObject("equity") ?: JSONObject()
    val inc = s.optJSONObject("income_statement") ?: JSONObject()
    val cf = s.optJSONObject("cash_flow") ?: JSONObject()
    Text("BILANS", color = Tv.Blue, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.1.sp)
    StatRow("Gotówka", money(a.optString("cash")))
    StatRow("Zapasy (gotowe + surowce)", money(((a.optString("finished_goods").toDoubleOrNull() ?: 0.0) + (a.optString("raw_materials").toDoubleOrNull() ?: 0.0)).toString()))
    StatRow("Środki trwałe netto", money(a.optString("fixed_assets_net")))
    StatRow("Aktywa razem", money(a.optString("total")), bold = true)
    StatRow("Zobowiązania podatkowe", money(l.optString("taxes_payable")))
    StatRow("Kapitał wniesiony", money(e.optString("paid_in_capital")))
    StatRow("Zysk zatrzymany", money(e.optString("retained_earnings")))
    StatRow("Kapitał własny", money(e.optString("total")), bold = true)
    Text(
        if (bs.optBoolean("balanced")) "Bilans zgodny z zapisami księgowymi" else "UWAGA: bilans niezgodny z zapisami",
        color = if (bs.optBoolean("balanced")) Tv.Up else Tv.Down, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp),
    )
    Spacer(Modifier.height(12.dp))
    Text("RACHUNEK ZYSKÓW I STRAT", color = Tv.Blue, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.1.sp)
    StatRow("Przychody", money(inc.optString("revenue")))
    StatRow("Koszt sprzedanych towarów", money(inc.optString("cost_of_goods_sold")))
    StatRow("Zysk brutto", money(inc.optString("gross_profit")), bold = true)
    StatRow("Amortyzacja", money(inc.optString("depreciation")))
    StatRow("Podatek CIT", money(inc.optString("corporate_tax")))
    StatRow("Zysk netto", money(inc.optString("net_income")), bold = true)
    StatRow("Dywidendy", money(inc.optString("dividends_declared")))
    Spacer(Modifier.height(12.dp))
    Text("PRZEPŁYWY PIENIĘŻNE", color = Tv.Blue, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.1.sp)
    StatRow("Operacyjne", money(cf.optString("operating")))
    StatRow("Inwestycyjne", money(cf.optString("investing")))
    StatRow("Finansowe", money(cf.optString("financing")))
    StatRow("Zmiana gotówki", money(cf.optString("net_change")), bold = true)
}
