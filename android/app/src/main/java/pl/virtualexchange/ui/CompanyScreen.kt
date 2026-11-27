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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import pl.virtualexchange.data.Api
import pl.virtualexchange.data.objects
import java.util.Locale

@Composable
fun TvCompanyTab(store: Store, scope: CoroutineScope) {
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var startupCapital by remember { mutableStateOf("5000") }
    var sector by remember { mutableStateOf("technology") }
    var legalForm by remember { mutableStateOf("sp_zoo") }
    var creating by remember { mutableStateOf(false) }
    var privateCompanies by remember { mutableStateOf(listOf<JSONObject>()) }
    var developments by remember { mutableStateOf<Map<String, JSONObject>>(emptyMap()) }
    var busyCompany by remember { mutableStateOf("") }
    val companies = store.assets.filter { !it.isNull("creator_id") }
    val mine = companies.filter { it.optInt("creator_id", -1) == store.userId }
    val privateLimitReached = mine.size + privateCompanies.size >= 5

    fun runCompanyAction(ticker: String, endpoint: String, payload: JSONObject, success: String) {
        busyCompany = ticker
        scope.launch {
            try {
                val result = Api.post("companies/" + ticker + "/" + endpoint, payload)
                developments = developments + (ticker to result)
                store.refreshAccount()
                if (Api.offline) {
                    try { privateCompanies = Api.getArray("companies/private", auth = true).objects() }
                    catch (_: Exception) { }
                }
                store.message = success
            } catch (error: Exception) {
                store.message = error.message ?: "Nie udało się wykonać operacji spółki."
            } finally {
                busyCompany = ""
            }
        }
    }

    LaunchedEffect(store.assets, store.userId) {
        val loaded = mutableMapOf<String, JSONObject>()
        for (asset in mine) {
            val ticker = asset.optString("symbol")
            try { loaded[ticker] = Api.getObject("companies/" + ticker + "/development", auth = true) }
            catch (_: Exception) { }
        }
        developments = loaded
        if (Api.offline) {
            try { privateCompanies = Api.getArray("companies/private", auth = true).objects() }
            catch (_: Exception) { /* Zachowaj ostatnią listę, jeśli chwilowo nie ma odpowiedzi. */ }
        }
    }
    LaunchedEffect(store.userId, Api.offline) {
        if (Api.offline) {
            while (true) {
                delay(10_000)
                try { privateCompanies = Api.getArray("companies/private", auth = true).objects() }
                catch (_: Exception) { }
            }
        }
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
        if (Api.offline) {
        item {
            TvCard {
                Text("REJESTRACJA PRYWATNEJ FIRMY", color = Tv.Blue, fontSize = 10.sp,
                    fontWeight = FontWeight.Bold, letterSpacing = 1.1.sp)
                Text("Najpierw zbuduj przedsiębiorstwo", color = Color.White, fontSize = 18.sp,
                    fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
                Text(
                    "Firma nie trafia od razu na giełdę. Najpierw przechodzi rejestrację, rozpoczyna działalność, zdobywa klientów i buduje wyniki. Dopiero później może złożyć wniosek o IPO.",
                    color = Tv.Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 5.dp),
                )
                Spacer(Modifier.height(9.dp))
                TvInput("Nazwa firmy", name, { name = it }, placeholder = "np. Nova Robotics")
                Spacer(Modifier.height(8.dp))
                TvInput("Opis działalności", description, { description = it }, placeholder = "Czym będzie zajmować się firma?")
                Spacer(Modifier.height(8.dp))
                TvInput("Kapitał startowy USD", startupCapital,
                    { startupCapital = it.filter { ch -> ch.isDigit() || ch == '.' || ch == ',' }.replace(',', '.') },
                    keyboard = KeyboardType.Decimal, placeholder = "5000")
                Text("FORMA PRAWNA", color = Tv.Muted, fontSize = 9.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 10.dp, bottom = 5.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    OutlinedButton(onClick = { legalForm = "jdg" }, modifier = Modifier.weight(1f)) {
                        Text(if (legalForm == "jdg") "✓ JDG" else "JDG", fontSize = 10.sp)
                    }
                    OutlinedButton(onClick = { legalForm = "sp_zoo" }, modifier = Modifier.weight(1f)) {
                        Text(if (legalForm == "sp_zoo") "✓ SP. Z O.O." else "SP. Z O.O.", fontSize = 10.sp)
                    }
                }
                Text("BRANŻA", color = Tv.Muted, fontSize = 9.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 8.dp, bottom = 5.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    listOf("technology" to "TECH", "industry" to "PRZEMYSŁ", "consumer" to "HANDEL",
                        "food" to "ŻYWNOŚĆ", "services" to "USŁUGI").forEach { (key, label) ->
                        OutlinedButton(
                            onClick = { sector = key },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 3.dp, vertical = 7.dp),
                        ) {
                            Text(if (sector == key) "✓ $label" else label, fontSize = 8.sp)
                        }
                    }
                }
                Text(
                    if (legalForm == "sp_zoo") "Minimalny kapitał: 5 000 USD • rejestracja: 400 USD"
                    else "Minimalny kapitał: 1 500 USD • rejestracja: 150 USD",
                    color = Tv.Muted, fontSize = 10.sp, modifier = Modifier.padding(top = 8.dp),
                )
                Button(
                    onClick = {
                        creating = true
                        scope.launch {
                            try {
                                Api.post("companies/private", JSONObject()
                                    .put("name", name.trim())
                                    .put("description", description.trim())
                                    .put("capital", startupCapital.trim())
                                    .put("sector", sector)
                                    .put("legal_form", legalForm))
                                store.message = "Zarejestrowano firmę. Trwa przygotowanie do rozpoczęcia działalności."
                                name = ""; description = ""; startupCapital = if (legalForm == "sp_zoo") "5000" else "1500"
                                privateCompanies = Api.getArray("companies/private", auth = true).objects()
                            } catch (error: Exception) {
                                store.message = error.message ?: "Nie udało się zarejestrować firmy."
                            } finally {
                                creating = false
                            }
                        }
                    },
                    enabled = !creating && !privateLimitReached && name.trim().length in 3..60 &&
                        (startupCapital.toBigDecimalOrNull()?.let {
                            it >= if (legalForm == "sp_zoo") java.math.BigDecimal("5000") else java.math.BigDecimal("1500")
                        } ?: false),
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp).height(48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Tv.Blue),
                    shape = RoundedCornerShape(11.dp),
                ) {
                    Text(if (creating) "REJESTRACJA..." else if (privateLimitReached) "LIMIT WYKORZYSTANY" else "ZAREJESTRUJ FIRMĘ",
                        color = Color(0xFF07131E), fontWeight = FontWeight.Bold)
                }
                if (store.message.isNotBlank()) Text(store.message, color = Tv.Muted, fontSize = 11.sp,
                    modifier = Modifier.padding(top = 8.dp))
                if (store.accountError.isNotBlank()) Text("Konto: " + store.accountError, color = Tv.Down,
                    fontSize = 10.sp, modifier = Modifier.padding(top = 5.dp))
            }
        }
        item { Text("TWOJE FIRMY PRYWATNE", color = Tv.Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold) }
        if (privateCompanies.isEmpty()) {
            item {
                TvCard {
                    Text("Nie masz jeszcze prywatnego przedsiębiorstwa.", color = Color.White,
                        fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text("Załóż firmę, zgromadź kapitał i prowadź ją przed debiutem giełdowym.",
                        color = Tv.Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 5.dp))
                }
            }
        } else {
            items(privateCompanies, key = { it.optString("business_id") }) { business ->
                val businessId = business.optString("business_id")
                PrivateBusinessCard(
                    business,
                    onConvert = {
                        scope.launch {
                            try {
                                Api.post("companies/$businessId/convert", JSONObject())
                                store.message = "Rozpoczęto przekształcenie firmy. Trwa procedura formalna."
                                privateCompanies = Api.getArray("companies/private", auth = true).objects()
                            } catch (error: Exception) {
                                store.message = error.message ?: "Nie udało się przekształcić firmy."
                            }
                        }
                    },
                    onIpo = { ticker, totalSupply ->
                        scope.launch {
                            try {
                                Api.post("companies/$businessId/ipo", JSONObject()
                                    .put("symbol", ticker.trim().uppercase(Locale.ROOT))
                                    .put("total_supply", totalSupply.trim()))
                                store.message = "Wniosek IPO przyjęty. Trwa przegląd i przygotowanie debiutu."
                                privateCompanies = Api.getArray("companies/private", auth = true).objects()
                            } catch (error: Exception) {
                                store.message = error.message ?: "Nie udało się złożyć wniosku IPO."
                            }
                        }
                    },
                )
                if (business.optString("stage") == "OPERATING") {
                    CompanyDevelopmentCard(
                        state = business,
                        busy = busyCompany == businessId,
                        onUpgrade = { branch -> runCompanyAction(businessId, "upgrade", JSONObject().put("branch", branch), "Rozwinięto dział firmy.") },
                        onFund = { amount -> runCompanyAction(businessId, "capital", JSONObject().put("amount", amount), "Zasilono kasę firmy.") },
                        onWithdraw = {},
                        onStaff = { action, count -> runCompanyAction(businessId, "staff", JSONObject().put("action", action).put("count", count), "Zaktualizowano zatrudnienie.") },
                        allowDividends = false,
                    )
                }
            }
        }
        } else {
            item {
                TvCard {
                    Text("CYKL PRYWATNA FIRMA → IPO", color = Tv.Blue, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Text("Ten proces jest na razie dostępny w trybie offline. W trybie online nadal możesz przeglądać notowane spółki; serwer wymaga osobnej migracji, zanim obsłuży prywatne firmy i IPO.",
                        color = Tv.Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 5.dp))
                }
            }
        }
        item { Text("TWOJE SPÓŁKI NOTOWANE", color = Tv.Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold) }
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
                    CompanyDevelopmentCard(
                        development,
                        busyCompany == ticker,
                        onUpgrade = { branch -> runCompanyAction(ticker, "upgrade", JSONObject().put("branch", branch), "Rozwinięto dział spółki " + ticker + ".") },
                        onFund = { amount -> runCompanyAction(ticker, "capital", JSONObject().put("amount", amount), "Zasilono kasę spółki " + ticker + ".") },
                        onWithdraw = { amount -> runCompanyAction(ticker, "dividend", JSONObject().put("amount", amount), "Wypłacono środki ze spółki " + ticker + ".") },
                        onStaff = { action, count -> runCompanyAction(ticker, "staff", JSONObject().put("action", action).put("count", count), "Zaktualizowano zatrudnienie w " + ticker + ".") },
                    )
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
private fun PrivateBusinessCard(
    state: JSONObject,
    onConvert: () -> Unit,
    onIpo: (String, String) -> Unit,
) {
    val id = state.optString("business_id")
    var ticker by remember(id) { mutableStateOf("") }
    var supply by remember(id) { mutableStateOf("1000000") }
    val stage = state.optString("stage", "REGISTERING")
    val legal = if (state.optString("legal_form") == "sp_zoo") "Spółka z o.o." else "JDG"
    val stageLabel = when (stage) {
        "REGISTERING" -> "REJESTRACJA"
        "OPERATING" -> "DZIAŁA PRYWATNIE"
        "IPO_REVIEW" -> "PRZEGLĄD IPO"
        "CONVERTING" -> "PRZEKSZTAŁCENIE"
        else -> stage
    }
    val cash = state.optString("cash", "0").toDoubleOrNull() ?: 0.0
    val revenue = state.optString("revenue_total", "0").toDoubleOrNull() ?: 0.0
    val expenses = state.optString("expense_total", "0").toDoubleOrNull() ?: 0.0
    TvCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(state.optString("name"), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text("$legal • ${state.optString("sector").uppercase(Locale.ROOT)}",
                    color = Tv.Muted, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            }
            Text(stageLabel, color = if (stage == "IPO_REVIEW") Tv.Blue else Tv.Up,
                fontSize = 9.sp, fontWeight = FontWeight.Bold)
        }
        Text(state.optString("description"), color = Tv.Muted, fontSize = 11.sp,
            modifier = Modifier.padding(top = 5.dp))
        HorizontalDivider(Modifier.padding(vertical = 9.dp), color = Tv.Line)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            CompanyStat("KASA FIRMY", fmtUsd(cash))
            CompanyStat("PRACOWNICY", state.optInt("employees").toString())
            CompanyStat("REPUTACJA", "${state.optDouble("reputation", 50.0).toInt()}/100")
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            CompanyStat("PRZYCHODY", fmtUsd(revenue))
            CompanyStat("KOSZTY", fmtUsd(expenses))
            CompanyStat("DZIAŁALNOŚĆ", "${state.optInt("operating_hours")} h")
        }
        when (stage) {
            "REGISTERING" -> Text(
                "Trwa rejestracja i przygotowanie operacyjne. Pozostało około ${state.optInt("registration_hours_left")} godzin gry.",
                color = Tv.Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 9.dp))
            "CONVERTING" -> Text(
                "Trwa przekształcenie w spółkę z o.o. Pozostało około ${state.optInt("registration_hours_left")} godzin gry.",
                color = Tv.Blue, fontSize = 11.sp, modifier = Modifier.padding(top = 9.dp))
            "IPO_REVIEW" -> Text(
                "Wniosek jest sprawdzany. Przewidywany koniec procesu za około ${state.optInt("ipo_hours_left")} godzin gry. Firma nadal działa.",
                color = Tv.Blue, fontSize = 11.sp, modifier = Modifier.padding(top = 9.dp))
            "OPERATING" -> {
                if (state.optBoolean("can_convert_to_sp_zoo")) {
                    Text("Prowadzisz działalność jako JDG. Aby ubiegać się o IPO, przekształć firmę w spółkę z o.o. Koszt procedury: 1 200 USD.",
                        color = Tv.Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 9.dp))
                    Button(onClick = onConvert, modifier = Modifier.fillMaxWidth().padding(top = 7.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Tv.Blue)) {
                        Text("PRZEKSZTAŁĆ W SP. Z O.O.", color = Color(0xFF07131E), fontWeight = FontWeight.Bold)
                    }
                } else if (state.optBoolean("ipo_eligible")) {
                    Text("Firma spełnia warunki rozpoczęcia procesu IPO.", color = Tv.Up,
                        fontSize = 11.sp, modifier = Modifier.padding(top = 9.dp))
                    TvInput("Ticker przy debiucie", ticker,
                        { ticker = it.uppercase(Locale.ROOT) }, placeholder = "np. NOVA")
                    Spacer(Modifier.height(7.dp))
                    TvInput("Całkowita liczba akcji", supply, { supply = it },
                        keyboard = KeyboardType.Decimal, placeholder = "1000000")
                    Button(
                        onClick = { onIpo(ticker, supply) },
                        enabled = ticker.matches(Regex("[A-Z][A-Z0-9]{1,9}")) &&
                            (supply.toBigDecimalOrNull()?.signum() ?: 0) > 0,
                        modifier = Modifier.fillMaxWidth().padding(top = 7.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Tv.Blue),
                    ) {
                        Text("ZŁÓŻ WNIOSEK O IPO", color = Color(0xFF07131E), fontWeight = FontWeight.Bold)
                    }
                } else {
                    val remaining = (72 - state.optInt("operating_hours")).coerceAtLeast(0)
                    Text("Firma musi jeszcze prowadzić działalność i zbudować wyniki. Minimalna historia do IPO: 72 godziny gry; pozostało co najmniej $remaining h.",
                        color = Tv.Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 9.dp))
                }
            }
        }
    }
}

@Composable
private fun CompanyDevelopmentCard(
    state: JSONObject,
    busy: Boolean,
    onUpgrade: (String) -> Unit,
    onFund: (String) -> Unit,
    onWithdraw: (String) -> Unit,
    onStaff: (String, Int) -> Unit,
    allowDividends: Boolean = true,
) {
    val next = state.optJSONObject("next_costs")
    val maxLevel = state.optInt("max_level", 10)
    val employees = state.optInt("employees", 3)
    var amount by remember(state.optString("symbol")) { mutableStateOf("500") }
    val cash = state.optString("cash", "0").toDoubleOrNull() ?: 0.0
    val revenue = state.optString("hourly_revenue", "0").toDoubleOrNull() ?: 0.0
    val expenses = state.optString("hourly_expenses", "0").toDoubleOrNull() ?: 0.0
    val profit = state.optString("hourly_profit", "0").toDoubleOrNull() ?: 0.0
    TvCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text("CENTRUM ZARZĄDZANIA", color = Tv.Blue, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Text("Poziom ${state.optInt("company_level", 1)}", color = Color.White,
                    fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("INDEKS ROZWOJU", color = Tv.Muted, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                Text(state.optInt("development_index", 100).toString(), color = Tv.Up,
                    fontSize = 19.sp, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(if (allowDividends) "KASA SPÓŁKI" else "KASA FIRMY", color = Tv.Muted, fontSize = 9.sp, fontWeight = FontWeight.Bold)
        Text(fmtUsd(cash) + " USD", color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.Bold)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            CompanyStat("PRZYCHÓD / H", fmtUsd(revenue))
            CompanyStat("KOSZTY / H", fmtUsd(expenses))
            CompanyStat("WYNIK / H", fmtUsd(profit))
        }
        Text("WYNIKI OD STARTU", color = Tv.Muted, fontSize = 8.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 9.dp, bottom = 4.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            CompanyStat("PRZYCHÓD", fmtUsd(state.optString("revenue_total", "0").toDoubleOrNull() ?: 0.0))
            CompanyStat("KOSZTY", fmtUsd(state.optString("expense_total", "0").toDoubleOrNull() ?: 0.0))
            if (allowDividends) CompanyStat("DYWIDENDY", fmtUsd(state.optString("dividends_total", "0").toDoubleOrNull() ?: 0.0))
            else CompanyStat("KAPITAŁ", fmtUsd(state.optString("invested", "0").toDoubleOrNull() ?: 0.0))
        }
        HorizontalDivider(Modifier.padding(vertical = 10.dp), color = Tv.Line)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            CompanyStat("PRACOWNICY", employees.toString())
            CompanyStat("KLIENCI", state.optInt("customers").toString())
            CompanyStat("REPUTACJA", "${state.optDouble("reputation", 50.0).toInt()} / 100")
            CompanyStat("JAKOŚĆ", "${state.optInt("product_quality", 40)} / 100")
        }
        Text("FINANSE I KADRY", color = Tv.Blue, fontSize = 10.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 13.dp, bottom = 5.dp))
        OutlinedTextField(
            value = amount, onValueChange = { amount = it.filter { ch -> ch.isDigit() || ch == '.' || ch == ',' }.replace(',', '.') },
            modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Kwota w USD") },
            enabled = !busy,
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                focusedBorderColor = Tv.Blue, unfocusedBorderColor = Tv.Line,
                focusedLabelColor = Tv.Blue, unfocusedLabelColor = Tv.Muted,
            ),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onFund(amount) }, enabled = !busy && (amount.toDoubleOrNull() ?: 0.0) > 0.0,
                modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = Tv.Blue)) {
                Text("WPŁAĆ KAPITAŁ", color = Color(0xFF07131E), fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
            if (allowDividends) {
                OutlinedButton(onClick = { onWithdraw(amount) }, enabled = !busy && (amount.toDoubleOrNull() ?: 0.0) > 0.0,
                    modifier = Modifier.weight(1f)) {
                    Text("WYPŁAĆ DYWIDENDĘ", fontSize = 10.sp)
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onStaff("fire", 1) }, enabled = !busy && employees > 1,
                modifier = Modifier.weight(1f)) { Text("ZWOLNIJ −1", fontSize = 10.sp) }
            Button(onClick = { onStaff("hire", 1) }, enabled = !busy && employees < state.optInt("max_employees", 200),
                modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = Tv.Blue)) {
                Text("ZATRUDNIJ +1", color = Color(0xFF07131E), fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }
        Text("ROZWÓJ DZIAŁÓW", color = Tv.Blue, fontSize = 10.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 14.dp, bottom = 7.dp))
        CompanyBranch("BIURO I ZARZĄDZANIE", "office", state.optInt("office_level"), next?.optLong("office", 500L) ?: 500L, maxLevel, busy, onUpgrade)
        Spacer(Modifier.height(7.dp))
        CompanyBranch("LABORATORIUM I R&D", "research", state.optInt("research_level"), next?.optLong("research", 500L) ?: 500L, maxLevel, busy, onUpgrade)
        Spacer(Modifier.height(7.dp))
        CompanyBranch("MARKETING I SPRZEDAŻ", "marketing", state.optInt("marketing_level"), next?.optLong("marketing", 500L) ?: 500L, maxLevel, busy, onUpgrade)
        Spacer(Modifier.height(7.dp))
        CompanyBranch("PRODUKCJA I PRODUKT", "production", state.optInt("production_level"), next?.optLong("production", 500L) ?: 500L, maxLevel, busy, onUpgrade)
        Spacer(Modifier.height(7.dp))
        CompanyBranch("LOGISTYKA I OPERACJE", "logistics", state.optInt("logistics_level"), next?.optLong("logistics", 500L) ?: 500L, maxLevel, busy, onUpgrade)
        Text(if (allowDividends)
            "Firma działa w symulacji godzinowej. Rozwój wpływa na produktywność, popyt, jakość produktu i koszty. Dywidenda trafia proporcjonalnie do aktualnych akcjonariuszy, a kurs wynika z dopasowanych zleceń."
            else "Prywatne przedsiębiorstwo nie wypłaca dywidendy giełdowej. Kapitał zostaje w firmie i finansuje pensje, inwestycje, zapasy oraz rozwój przed IPO.",
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
            Text("Poziom $level / $maxLevel", color = Tv.Muted, fontSize = 10.sp,
                modifier = Modifier.padding(top = 3.dp))
        }
        Button(onClick = { onUpgrade(branch) }, enabled = !busy && level < maxLevel,
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 5.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Tv.Blue),
            shape = RoundedCornerShape(9.dp)) {
            Text(if (level >= maxLevel) "MAX" else "ROZWIŃ · ${fmtUsd(cost.toDouble())} USD",
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
