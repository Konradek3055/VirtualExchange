package pl.virtualexchange.data

import java.math.BigDecimal
import java.util.Locale

/** Usuwa zbędne zera z liczby dziesiętnej podanej jako tekst ("100.50000000" -> "100.5"). */
fun fmt(s: String): String = try {
    BigDecimal(s).stripTrailingZeros().toPlainString()
} catch (e: Exception) {
    s
}

/** Kwota z separatorem tysięcy i 2 miejscami, np. 1,234.50 */
fun money(x: Double): String = String.format(Locale.US, "%,.2f", x)
