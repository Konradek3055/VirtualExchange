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
import androidx.compose.foundation.layout.weight
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
import pl.virtualexchange.ui.ChartTab
import pl.virtualexchange.ui.MarketTab
import pl.virtualexchange.ui.MoreTab
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
        containerColor = Color(0xFF080D13),
        topBar = {
            Column(Modifier.fillMaxWidth().background(Color(0xFF0C131B))) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(shape = RoundedCornerShape(12.dp), color = Color(0xFF153D66), modifier = Modifier.size(38.dp)) {
                        Box(contentAlignment = Alignment.Center) {
                            Text("V", color = Color(0xFF25D7B1), fontWeight = FontWeight.Black, fontSize = 24.sp)
                        }
                    }
                    Column(Modifier.weight(1f).padding(start = 10.dp)) {
                        Text("VIRTUALEXCHANGE", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp, letterSpacing = 1.sp)
                        Text(store.status, color = Color(0xFF8796A8), fontSize = 10.sp)
                    }
                    TextButton(onClick = { scope.launch { store.refreshAll() } }) {
                        Text("↻", color = Color(0xFF8FA8C0), fontSize = 22.sp)
                    }
                }
                Box(Modifier.fillMaxWidth().size(1.dp).background(Color(0xFF202C38)))
            }
        },
        bottomBar = {
            NavigationBar(containerColor = Color(0xFF0C131B), contentColor = Color.White, tonalElevation = 0.dp) {
                val labels = listOf("Rynek", "Wykres", "Handel", "Portfel", "Więcej")
                val glyphs = listOf("◈", "⌁", "⇄", "▣", "☰")
                labels.forEachIndexed { index, label ->
                    NavigationBarItem(
                        selected = tab == index,
                        onClick = { tab = index },
                        icon = { Text(glyphs[index], fontSize = 19.sp, fontWeight = FontWeight.Bold) },
                        label = { Text(label, fontSize = 10.sp) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Color(0xFF42A5FF),
                            selectedTextColor = Color(0xFF42A5FF),
                            indicatorColor = Color(0xFF142B40),
                            unselectedIconColor = Color(0xFF8190A2),
                            unselectedTextColor = Color(0xFF8190A2),
                        ),
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when (tab) {
                0 -> MarketTab(store) { tab = 1 }
                1 -> ChartTab(store) { tab = 2 }
                2 -> TradeTab(store, scope)
                3 -> PortfolioTab(store, scope)
                else -> MoreTab(store, scope)
            }
        }
    }
}
