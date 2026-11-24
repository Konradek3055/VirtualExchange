package pl.virtualexchange.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pl.virtualexchange.data.Level
import pl.virtualexchange.data.fmt

/** Order book: sprzedaż (czerwone) nad ceną ostatnią, kupno (zielone) pod nią; tapnięcie wstawia cenę do formularza. */
@Composable
fun OrderBookView(asks: List<Level>, bids: List<Level>, last: String, onPick: (String) -> Unit) {
    val shownAsks = asks.take(7)
    val shownBids = bids.take(7)
    val maxQty = (shownAsks + shownBids).maxOfOrNull { it.qty.toDoubleOrNull() ?: 0.0 }
        .let { if (it != null && it > 0.0) it else 1.0 }
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Cena (USD)", style = MaterialTheme.typography.labelSmall)
            Text("Ilość", style = MaterialTheme.typography.labelSmall)
        }
        if (shownAsks.isEmpty() && shownBids.isEmpty()) {
            Text("Brak zleceń - bądź pierwszy.", Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.bodySmall)
        }
        shownAsks.reversed().forEach { LevelRow(it, Red, maxQty, onPick) }
        Text(
            if (last == "0" || last.isBlank()) "-" else fmt(last),
            Modifier.padding(vertical = 6.dp, horizontal = 4.dp),
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp,
        )
        shownBids.forEach { LevelRow(it, Green, maxQty, onPick) }
    }
}

@Composable
private fun LevelRow(l: Level, color: Color, maxQty: Double, onPick: (String) -> Unit) {
    val frac = ((l.qty.toDoubleOrNull() ?: 0.0) / maxQty).toFloat().coerceIn(0.03f, 1f)
    Box(Modifier.fillMaxWidth().clickable { onPick(l.price) }.padding(vertical = 1.dp)) {
        Box(
            Modifier.fillMaxWidth(frac).height(22.dp)
                .background(color.copy(alpha = 0.18f))
                .align(Alignment.CenterEnd),
        )
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(fmt(l.price), color = color, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
            Text(fmt(l.qty), fontFamily = FontFamily.Monospace, fontSize = 13.sp)
        }
    }
}
