package io.github.bovinemagnet.electrome.view;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ChartOptionsTest {

    @Test
    void halfHourLabelsCoverTheWholeDay() {
        var labels = ChartOptions.halfHourLabels();
        assertThat(labels).hasSize(48);
        assertThat(labels.get(0)).isEqualTo("00:00");
        assertThat(labels.get(32)).isEqualTo("16:00");
        assertThat(labels.get(47)).isEqualTo("23:30");
    }

    @Test
    void percentileIgnoresTheExtremeTail() {
        // 99 ordinary readings and one enormous one: the 99th percentile must not follow
        // the outlier, or every ordinary cell washes out to the palest shade.
        var values = new ArrayList<BigDecimal>();
        for (int i = 0; i < 99; i++) {
            values.add(BigDecimal.ONE);
        }
        values.add(new BigDecimal("500"));
        assertThat(ChartOptions.percentile(values, 0.99)).isEqualByComparingTo("1");
    }

    @Test
    void percentileHandlesEmptyAndAllZeroInput() {
        assertThat(ChartOptions.percentile(List.of(), 0.99)).isEqualByComparingTo("1");
        assertThat(ChartOptions.percentile(List.of(BigDecimal.ZERO, BigDecimal.ZERO), 0.99))
                .isEqualByComparingTo("1");
    }
}
