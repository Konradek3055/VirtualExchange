package pl.virtualexchange.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val SHEET = Color(0xFF1E1E1E)
private val SUB = Color(0xFF9A9A9A)
private val DIV = Color(0xFF3A3A3A)

@Composable
private fun IndicatorRow(title: String, why: String, color: Color, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(RoundedCornerShape(5.dp)).background(color))
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(why, color = SUB, fontSize = 12.sp)
        }
        Switch(checked = on, onCheckedChange = onChange)
    }
    HorizontalDivider(color = DIV)
}

/** Arkusz wskaźników: każdy z krótkim wyjaśnieniem "po co to jest". */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IndicatorSheet(ind: IndicatorSet, onChange: (IndicatorSet) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = SHEET) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 28.dp)) {
            Text("Wskaźniki", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "Wskaźniki to wzory liczone z cen i wolumenu - pomagają ocenić trend, siłę ruchu i zmienność. " +
                    "Nie wpływają na rynek i niczego nie gwarantują.",
                color = SUB, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp, bottom = 8.dp)
            )
            IndicatorRow("Wolumen", "Ile aktywa zmieniło właściciela w świecy. Duży wolumen potwierdza siłę ruchu ceny.", Color(0xFF089981), ind.volume) { onChange(ind.copy(volume = it)) }
            IndicatorRow("SMA 20", "Średnia z 20 ostatnich cen zamknięcia. Wygładza szum; cena nad średnią sugeruje trend wzrostowy.", Color(0xFF2962FF), ind.sma) { onChange(ind.copy(sma = it)) }
            IndicatorRow("EMA 20", "Jak SMA, ale nowsze świece ważą więcej, więc szybciej reaguje na zmianę kierunku.", Color(0xFFFF9800), ind.ema) { onChange(ind.copy(ema = it)) }
            IndicatorRow("Bollinger 20, 2", "Wstęga ±2 odchylenia wokół SMA. Szeroka = duża zmienność, wąska = spokój; dotknięcie brzegu to ruch skrajny.", Color(0xFF9C27B0), ind.bb) { onChange(ind.copy(bb = it)) }
            IndicatorRow("VWAP", "Średnia cena ważona wolumenem od początku załadowanych świec - po jakiej cenie faktycznie handlowano.", Color(0xFF00BCD4), ind.vwap) { onChange(ind.copy(vwap = it)) }
            IndicatorRow("RSI 14", "Siła ruchu w skali 0-100. Powyżej 70 rynek bywa wykupiony, poniżej 30 wyprzedany.", Color(0xFF7E57C2), ind.rsi) { onChange(ind.copy(rsi = it)) }
            IndicatorRow("MACD 12, 26, 9", "Różnica dwóch EMA i linia sygnału. Przecięcie linii sygnalizuje zmianę impetu, histogram pokazuje jego siłę.", Color(0xFFFF6D00), ind.macd) { onChange(ind.copy(macd = it)) }
        }
    }
}

@Composable
private fun ToolRow(title: String, why: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp)) {
        Text(title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Text(why, color = SUB, fontSize = 12.sp)
    }
    HorizontalDivider(color = DIV)
}

/** Arkusz narzędzi rysowania z opisem zastosowania. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DrawSheet(
    count: Int,
    onPick: (DrawTool) -> Unit,
    onUndo: () -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = SHEET) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 28.dp)) {
            Text("Narzędzia rysowania", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "Rysunki to Twoje notatki na wykresie: zaznaczają poziomy i strefy, które chcesz obserwować. " +
                    "Są prywatne, trzymają się czasu i ceny (zoom i zmiana interwału ich nie ruszają) i znikają po zamknięciu aplikacji.",
                color = SUB, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp, bottom = 8.dp)
            )
            ToolRow(DrawTool.TREND.label, "Łączy dwa punkty - pokazuje kierunek trendu oraz dynamiczne wsparcie lub opór.") { onPick(DrawTool.TREND) }
            ToolRow(DrawTool.HLINE.label, "Stały poziom ceny (wsparcie/opór), np. miejsce, gdzie cena często się odbija.") { onPick(DrawTool.HLINE) }
            ToolRow(DrawTool.RECT.label, "Zaznacza strefę ceny i czasu, np. konsolidację albo obszar zleceń.") { onPick(DrawTool.RECT) }
            ToolRow(DrawTool.FIB.label, "Poziomy 23,6-78,6% ruchu między dwoma punktami - miejsca, w których często kończy się korekta.") { onPick(DrawTool.FIB) }
            Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onUndo, enabled = count > 0, modifier = Modifier.weight(1f)) { Text("Cofnij ostatni") }
                OutlinedButton(onClick = onClear, enabled = count > 0, modifier = Modifier.weight(1f)) { Text("Usuń wszystkie ($count)") }
            }
        }
    }
}
