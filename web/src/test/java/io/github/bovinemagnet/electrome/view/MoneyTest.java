package io.github.bovinemagnet.electrome.view;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class MoneyTest {

    @Test
    void formatsDollarsWithThousandsSeparators() {
        assertThat(Money.dollars(new BigDecimal("2852.7993626"))).isEqualTo("$2,852.80");
        assertThat(Money.dollars(BigDecimal.ZERO)).isEqualTo("$0.00");
        assertThat(Money.dollars(new BigDecimal("14"))).isEqualTo("$14.00");
    }

    @Test
    void formatsNegativeAmountsAsCredits() {
        assertThat(Money.dollars(new BigDecimal("-0.33"))).isEqualTo("-$0.33");
    }

    @Test
    void formatsRatesEnergyAndPower() {
        assertThat(Money.cents(new BigDecimal("49.54"))).isEqualTo("49.54c");
        assertThat(Money.cents(new BigDecimal("123.200"))).isEqualTo("123.20c");
        assertThat(Money.kWh(new BigDecimal("8469.770"))).isEqualTo("8,469.8 kWh");
        assertThat(Money.kW(new BigDecimal("1.4380"))).isEqualTo("1.44 kW");
        assertThat(Money.percent(new BigDecimal("41.5589"))).isEqualTo("41.6%");
    }

    @Test
    void nullsFormatAsEmptyRatherThanThrowing() {
        assertThat(Money.dollars(null)).isEmpty();
        assertThat(Money.cents(null)).isEmpty();
        assertThat(Money.kWh(null)).isEmpty();
        assertThat(Money.kW(null)).isEmpty();
    }

    @Test
    void slotTimeMapsHalfHourIndexToClock() {
        assertThat(Money.slotTime(0)).isEqualTo("00:00");
        assertThat(Money.slotTime(39)).isEqualTo("19:30");
        assertThat(Money.slotTime(47)).isEqualTo("23:30");
    }
}
