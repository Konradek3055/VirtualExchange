package pl.virtualexchange.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.FormatListBulleted
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.ShowChart
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlin.math.abs

/** Wspólna paleta w stylu TradingView / Binance / OKX: czarne tło, szare karty, zieleń i czerwień z TradingView. */
object Tv {
    val Bg = Color(0xFF000000)
    val Card = Color(0xFF1E1E1E)
    val Chip = Color(0xFF2A2A2A)
    val Line = Color(0xFF2A2E39)
    val Muted = Color(0xFF9A9A9A)
    val Up = Color(0xFF089981)
    val Down = Color(0xFFF23645)
    val Blue = Color(0xFF2962FF)
    val Light = Color(0xFFF1F1F1)
}

private val PL = Locale.forLanguageTag("pl-PL")

/** 10 000,00 */
fun fmtUsd(v: Double): String = String.format(Locale.US, "%,.2f", v).replace(',', '\u00A0').replace('.', ',')

/** Ilość bez zbędnych zer: 4 / 3,9848 */
fun fmtQty(s: String): String {
    val v = s.toDoubleOrNull() ?: return s
    return String.format(Locale.US, "%.4f", v).trimEnd('0').trimEnd('.').replace('.', ',')
}

/** +0,35% / −0,35% (prawdziwy minus jak w TradingView). */
fun fmtPct(v: Double): String =
    (if (v > 0) "+" else if (v < 0) "−" else "") + String.format(PL, "%.2f", abs(v)) + "%"

/** Zmiana ceny bez znaku; zero to "0,00" (a nie kreska). */
fun fmtDelta(v: Double): String = if (v == 0.0) "0,00" else fmtPx(abs(v))

fun category(symbol: String): String = when (symbol) {
    "BTC", "ETH" -> "Krypto"
    "NVDA", "AAPL" -> "Akcje"
    else -> "Społeczność"
}

private val BadgePalette = listOf(
    Color(0xFF2962FF), Color(0xFF089981), Color(0xFF9C27B0), Color(0xFFFF9800), Color(0xFF00BCD4), Color(0xFFE91E63),
)

@Composable
fun TvBadge(symbol: String, size: Dp = 44.dp) {
    val color = when (symbol) {
        "BTC" -> Color(0xFFF7931A)
        "ETH" -> Color(0xFF627EEA)
        "NVDA" -> Color(0xFF76B900)
        "AAPL" -> Color(0xFF8E8E93)
        else -> BadgePalette[(symbol.hashCode() and 0x7fffffff) % BadgePalette.size]
    }
    Box(Modifier.size(size).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
        Text(symbol.take(1), color = Color.White, fontWeight = FontWeight.Bold, fontSize = (size.value * 0.42f).sp)
    }
}

/** Pigułka jak w TradingView: zaznaczona = jasna z czarnym tekstem. */
@Composable
fun Pill(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(20.dp))
            .background(if (selected) Tv.Light else Tv.Chip)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (selected) Color.Black else Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

@Composable
fun PillTabs(labels: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        labels.forEachIndexed { i, l -> Pill(l, i == selected) { onSelect(i) } }
    }
}

@Composable
fun TvCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Tv.Card).padding(16.dp), content = content)
}

/** Pole tekstowe: ciemne, wypełnione (bez obramowania), z etykietą nad polem. */
@Composable
fun TvInput(
    label: String, value: String, onChange: (String) -> Unit,
    modifier: Modifier = Modifier, enabled: Boolean = true, suffix: String = "",
    keyboard: KeyboardType = KeyboardType.Text, placeholder: String = "",
) {
    Column(modifier) {
        Text(label, color = Tv.Muted, fontSize = 12.sp)
        Row(
            Modifier.fillMaxWidth().padding(top = 4.dp).clip(RoundedCornerShape(10.dp)).background(Tv.Chip)
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f)) {
                if (value.isEmpty() && placeholder.isNotEmpty()) Text(placeholder, color = Tv.Muted, fontSize = 15.sp)
                BasicTextField(
                    value, onChange, enabled = enabled, singleLine = true,
                    textStyle = TextStyle(color = if (enabled) Color.White else Tv.Muted, fontSize = 15.sp),
                    cursorBrush = SolidColor(Color.White),
                    keyboardOptions = KeyboardOptions(keyboardType = keyboard),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (suffix.isNotEmpty()) Text(suffix, color = Tv.Muted, fontSize = 12.sp)
        }
    }
}

@Composable
fun TvTopBar(status: String, onRefresh: () -> Unit) {
    val label = when {
        status.startsWith("ONLINE") -> "ONLINE"
        status.startsWith("OFFLINE") -> "SYMULACJA"
        status.startsWith("BŁĄD") -> "BŁĄD"
        else -> "ŁĄCZENIE"
    }
    val dot = when (label) {
        "ONLINE" -> Tv.Up
        "BŁĄD" -> Tv.Down
        else -> Tv.Blue
    }
    Row(
        Modifier.fillMaxWidth().background(Tv.Bg).statusBarsPadding().padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(32.dp).clip(RoundedCornerShape(9.dp)).background(Tv.Blue), contentAlignment = Alignment.Center) {
            Text("V", color = Color.White, fontWeight = FontWeight.Black, fontSize = 18.sp)
        }
        Text(
            "VirtualExchange", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 10.dp).weight(1f), maxLines = 1,
        )
        Row(
            Modifier.clip(RoundedCornerShape(14.dp)).background(Tv.Chip).padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(dot))
            Text(label, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 6.dp))
        }
        IconButton(onClick = onRefresh) { Icon(Icons.Outlined.Refresh, contentDescription = "Odśwież", tint = Color.White) }
    }
}

/** Dolna nawigacja jak w TradingView: czarna, ikony liniowe, aktywna zakładka = jasny zaokrąglony kwadrat. */
@Composable
fun TvBottomBar(tab: Int, onTab: (Int) -> Unit) {
    val items = listOf(
        "Rynek" to Icons.Outlined.FormatListBulleted,
        "Wykres" to Icons.Outlined.ShowChart,
        "Handel" to Icons.Outlined.SwapHoriz,
        "Portfel" to Icons.Outlined.AccountBalanceWallet,
        "Menu" to Icons.Outlined.Menu,
    )
    Column(Modifier.fillMaxWidth().background(Tv.Bg).navigationBarsPadding()) {
        HorizontalDivider(color = Tv.Line)
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            items.forEachIndexed { i, (label, icon) ->
                val sel = tab == i
                Column(
                    Modifier.weight(1f).clickable { onTab(i) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        Modifier.width(46.dp).height(32.dp).clip(RoundedCornerShape(10.dp))
                            .background(if (sel) Tv.Light else Color.Transparent),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(icon, contentDescription = label, tint = if (sel) Color.Black else Color(0xFFB2B5BE), modifier = Modifier.size(24.dp))
                    }
                    Text(
                        label, color = if (sel) Color.White else Color(0xFFB2B5BE),
                        fontSize = 11.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
    }
}
