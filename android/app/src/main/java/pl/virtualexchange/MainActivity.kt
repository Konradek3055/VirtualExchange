package pl.virtualexchange

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import pl.virtualexchange.data.Api
import pl.virtualexchange.ui.MarketTab
import pl.virtualexchange.ui.MoreTab
import pl.virtualexchange.ui.OrdersTab
import pl.virtualexchange.ui.PortfolioTab
import pl.virtualexchange.ui.Store
import pl.virtualexchange.ui.TradeTab
import pl.virtualexchange.ui.VeTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Api.init(applicationContext)
        setContent { VeTheme { ExchangeApp() } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExchangeApp() {
    val store = remember { Store() }
    val scope = rememberCoroutineScope()
    var tab by remember { mutableIntStateOf(0) }

    // Odświeżanie cykliczne (zapas, gdy WebSocket jest niedostępny) ...
    LaunchedEffect(Unit) {
        while (true) {
            store.refreshAll()
            delay(5000)
        }
    }
    // ... oraz natychmiastowe po zdarzeniu z WebSocketa wybranego aktywa.
    LaunchedEffect(store.selected) { Api.connect(store.selected) }
    LaunchedEffect(Unit) {
        Api.ticks.collect {
            try {
                store.refreshMarket()
                store.refreshAccount()
            } catch (e: Exception) {
                // błąd sieci obsłuży odświeżanie cykliczne
            }
        }
    }
    DisposableEffect(Unit) { onDispose { Api.disconnect() } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("VIRTUAL EXCHANGE")
                        Text(store.status, style = MaterialTheme.typography.labelSmall)
                    }
                },
                actions = { TextButton(onClick = { scope.launch { store.refreshAll() } }) { Text("ODŚWIEŻ") } },
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(tab == 0, { tab = 0 }, { Text("⌂") }, label = { Text("Rynek") })
                NavigationBarItem(tab == 1, { tab = 1 }, { Text("📈") }, label = { Text("Handel") })
                NavigationBarItem(tab == 2, { tab = 2 }, { Text("▤") }, label = { Text("Portfel") })
                NavigationBarItem(tab == 3, { tab = 3 }, { Text("☰") }, label = { Text("Zlecenia") })
                NavigationBarItem(tab == 4, { tab = 4 }, { Text("⚙") }, label = { Text("Więcej") })
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding)) {
            when (tab) {
                0 -> MarketTab(store) { tab = 1 }
                1 -> TradeTab(store, scope)
                2 -> PortfolioTab(store)
                3 -> OrdersTab(store, scope)
                else -> MoreTab(store, scope)
            }
        }
    }
}
