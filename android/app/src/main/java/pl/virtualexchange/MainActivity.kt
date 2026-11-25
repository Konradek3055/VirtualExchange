package pl.virtualexchange

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import pl.virtualexchange.data.Api
import pl.virtualexchange.ui.TerminalChartTab
import pl.virtualexchange.ui.TvBottomBar
import pl.virtualexchange.ui.TvMarketTab
import pl.virtualexchange.ui.TvMenuTab
import pl.virtualexchange.ui.TvPortfolioTab
import pl.virtualexchange.ui.TvTopBar
import pl.virtualexchange.ui.TvTradeTab
import pl.virtualexchange.ui.Store
import pl.virtualexchange.ui.VeTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Api.init(applicationContext)
        setContent { VeTheme { ExchangeApp() } }
    }
}

@Composable
fun ExchangeApp() {
    val store = remember { Store() }
    val scope = rememberCoroutineScope()
    var tab by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        while (true) {
            store.refreshAll()
            delay(5000)
        }
    }
    LaunchedEffect(store.selected) { Api.connect(store.selected) }
    LaunchedEffect(Unit) {
        Api.ticks.collect {
            try {
                store.refreshMarket()
                store.refreshAccount()
            } catch (_: Exception) {
                // Cykliczne odświeżanie ponowi pobieranie.
            }
        }
    }
    DisposableEffect(Unit) { onDispose { Api.disconnect(); Api.pauseBots() } }

    Scaffold(
        containerColor = Color.Black,
        topBar = { if (tab != 1) TvTopBar(store.status) { scope.launch { store.refreshAll() } } },
        bottomBar = { TvBottomBar(tab) { tab = it } },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when (tab) {
                0 -> TvMarketTab(store) { tab = 1 }
                1 -> TerminalChartTab(store) { tab = 2 }
                2 -> TvTradeTab(store, scope)
                3 -> TvPortfolioTab(store, scope)
                else -> TvMenuTab(store, scope)
            }
        }
    }
}
