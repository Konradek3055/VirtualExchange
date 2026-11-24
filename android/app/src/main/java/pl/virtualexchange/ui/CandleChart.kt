package pl.virtualexchange.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import pl.virtualexchange.data.Candle

/** Świece OHLC + wolumen (natywny Canvas). Rysuje wyłącznie świece z realnych transakcji. */
@Composable
fun CandleChart(candles: List<Candle>, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        if (candles.isEmpty()) return@Canvas
        val volH = size.height * 0.22f
        val priceH = size.height - volH - 6f
        val hi = candles.maxOf { it.high }
        val lo = candles.minOf { it.low }
        val range = if (hi - lo > 0.0) hi - lo else (if (hi != 0.0) hi * 0.02 else 1.0)
        val maxVol = candles.maxOf { it.volume }.let { if (it > 0.0) it else 1.0 }
        val slot = size.width / candles.size
        val bodyW = slot * 0.7f
        candles.forEachIndexed { i, c ->
            val cx = slot * i + slot / 2f
            val color = if (c.close >= c.open) Green else Red
            val yHigh = ((hi - c.high) / range * priceH).toFloat()
            val yLow = ((hi - c.low) / range * priceH).toFloat()
            val yOpen = ((hi - c.open) / range * priceH).toFloat()
            val yClose = ((hi - c.close) / range * priceH).toFloat()
            drawLine(color, Offset(cx, yHigh), Offset(cx, yLow), strokeWidth = 2f)
            val top = minOf(yOpen, yClose)
            val h = maxOf(kotlin.math.abs(yClose - yOpen), 2f)
            drawRect(color, Offset(cx - bodyW / 2f, top), Size(bodyW, h))
            val vh = (c.volume / maxVol * volH).toFloat()
            drawRect(color.copy(alpha = 0.4f), Offset(cx - bodyW / 2f, size.height - vh), Size(bodyW, vh))
        }
    }
}
