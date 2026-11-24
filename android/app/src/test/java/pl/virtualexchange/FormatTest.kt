package pl.virtualexchange

import org.junit.Assert.assertEquals
import org.junit.Test
import pl.virtualexchange.data.fmt
import pl.virtualexchange.data.money

class FormatTest {
    @Test fun trimsTrailingZeros() = assertEquals("100.5", fmt("100.50000000"))

    @Test fun keepsIntegersPlain() = assertEquals("100", fmt("100"))

    @Test fun badInputIsReturnedAsIs() = assertEquals("abc", fmt("abc"))

    @Test fun moneyHasThousandsSeparator() = assertEquals("1,234.50", money(1234.5))
}
