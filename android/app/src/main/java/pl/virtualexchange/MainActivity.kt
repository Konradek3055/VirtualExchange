package pl.virtualexchange

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.json.JSONArray
import pl.virtualexchange.data.Api

data class AssetRow(val symbol: String, val name: String, val price: String)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { ExchangeScreen() } }
    }
}

@Composable
fun ExchangeScreen() {
    val scope = rememberCoroutineScope()
    var assets by remember { mutableStateOf(listOf<AssetRow>()) }
    var status by remember { mutableStateOf("Łączenie z serwerem…") }
    var selected by remember { mutableStateOf("BTC") }
    var side by remember { mutableStateOf("buy") }
    var quantity by remember { mutableStateOf("0.01") }
    var price by remember { mutableStateOf("65000") }
    var message by remember { mutableStateOf("") }

    fun refresh() {
        scope.launch {
            try {
                val arr: JSONArray = Api.assets()
                assets = (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    AssetRow(o.getString("symbol"), o.getString("name"), o.getString("last_price"))
                }
                status = "Połączono z VirtualExchange"
            } catch (e: Exception) { status = "Brak połączenia: ${e.message}" }
        }
    }
    LaunchedEffect(Unit) { refresh() }

    Scaffold(topBar = {
        TopAppBar(title = { Text("VirtualExchange") },
            actions = { TextButton(onClick = { refresh() }) { Text("Odśwież") } })
    }) { padding ->
        LazyColumn(Modifier.padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text(status, style = MaterialTheme.typography.bodySmall) }
            item { Text("RYNEK", style = MaterialTheme.typography.titleLarge) }
            items(assets) { asset ->
                ElevatedCard(onClick = { selected = asset.symbol; price = asset.price }, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column { Text(asset.symbol, style = MaterialTheme.typography.titleMedium); Text(asset.name) }
                        Text("$${asset.price}", style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
            item {
                HorizontalDivider()
                Text("NOWE ZLECENIE — $selected", style = MaterialTheme.typography.titleLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = side == "buy", onClick = { side = "buy" }, label = { Text("Kup") })
                    FilterChip(selected = side == "sell", onClick = { side = "sell" }, label = { Text("Sprzedaj") })
                }
                OutlinedTextField(value = quantity, onValueChange = { quantity = it },
                    label = { Text("Ilość") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = price, onValueChange = { price = it },
                    label = { Text("Cena USD") }, modifier = Modifier.fillMaxWidth())
                Button(onClick = {
                    scope.launch {
                        try {
                            val result = Api.order(selected, side, "limit", quantity, price)
                            message = "Zlecenie #${result.optInt("order_id")} — ${result.optString("status")}"
                            refresh()
                        } catch (e: Exception) { message = "Błąd: ${e.message}" }
                    }
                }, modifier = Modifier.fillMaxWidth()) { Text("Złóż zlecenie LIMIT") }
                Text(message)
                Text("Konto demonstracyjne: demo-token. Środki są wirtualne.")
            }
        }
    }
}
