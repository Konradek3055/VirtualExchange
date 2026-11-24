package pl.virtualexchange.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import pl.virtualexchange.data.Candle

/** Wykres OHLC z dyskretną siatką i panelem wolumenu. */
@Composable
fun CandleChart(candles: List<Candle>, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        if (candles.isEmpty() || size.width <= 0f || size.height <= 0f) return@Canvas

        val volumeHeight = size.height * 0.20f
        val dividerY = size.height - volumeHeight - 4f
        val priceHeight = dividerY
        val high = candles.maxOf { it.high }
        val low = candles.minOf { it.low }
        val range = if (high - low > 0.0) high - low else if (high != 0.0) high * 0.02 else 1.0
        val maxVolume = candles.maxOf { it.volume }.let { if (it > 0.0) it else 1.0 }
        val slot = size.width / candles.size
        val bodyWidth = (slot * 0.64f).coerceAtLeast(2f)
        val grid = Color(0xFF263442)

        for (line in 0..4) {
            val y = priceHeight * line / 4f
            drawLine(grid.copy(alpha = 0.7f), Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
        }
        drawLine(grid, Offset(0f, dividerY), Offset(size.width, dividerY), strokeWidth = 1.2f)

        candles.forEachIndexed { index, candle ->
            val centerX = slot * index + slot / 2f
            val color = if (candle.close >= candle.open) Green else Red
            val highY = ((high - candle.high) / range * priceHeight).toFloat()
            val lowY = ((high - candle.low) / range * priceHeight).toFloat()
            val openY = ((high - candle.open) / range * priceHeight).toFloat()
            val closeY = ((high - candle.close) / range * priceHeight).toFloat()

            drawLine(color, Offset(centerX, highY), Offset(centerX, lowY), strokeWidth = 1.5f)
            val bodyTop = minOf(openY, closeY)
            val bodyHeight = kotlin.math.abs(closeY - openY).coerceAtLeast(2f)
            drawRect(color, Offset(centerX - bodyWidth / 2f, bodyTop), Size(bodyWidth, bodyHeight))

            val volumeBarHeight = (candle.volume / maxVolume * volumeHeight * 0.88).toFloat()
            drawRect(
                color.copy(alpha = 0.36f),
                Offset(centerX - bodyWidth / 2f, size.height - volumeBarHeight),
                Size(bodyWidth, volumeBarHeight),
            )
        }
    }
}
