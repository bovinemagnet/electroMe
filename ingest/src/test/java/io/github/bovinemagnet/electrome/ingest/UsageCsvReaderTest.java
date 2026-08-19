package io.github.bovinemagnet.electrome.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.bovinemagnet.electrome.core.domain.Quality;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class UsageCsvReaderTest {

    private static final String HEADER =
            "AccountNumber,NMI,DeviceNumber,DeviceType,RegisterCode,RateTypeDescription,"
                    + "StartDate,EndDate,ProfileReadValue,RegisterReadValue,QualityFlag\n";

    private static Reader sample() {
        return new InputStreamReader(
                UsageCsvReaderTest.class.getResourceAsStream("/sample-usage.csv"),
                StandardCharsets.UTF_8);
    }

    @Test
    void parsesConsumptionRows() throws IOException {
        var imported = UsageCsvReader.read(sample());
        assertThat(imported.usage().consumption().readings()).hasSize(5);
        assertThat(imported.usage().consumption().totalKWh()).isEqualByComparingTo("5.250");
    }

    @Test
    void routesExportRowsToTheExportSeries() throws IOException {
        var imported = UsageCsvReader.read(sample());
        assertThat(imported.usage().export().readings()).hasSize(1);
        assertThat(imported.usage().export().totalKWh()).isEqualByComparingTo("1.750");
    }

    @Test
    void normalisesTheOneSecondShortIntervalToThirtyMinutes() throws IOException {
        var first = UsageCsvReader.read(sample()).usage().consumption().readings().get(0);
        assertThat(first.length()).isEqualTo(Duration.ofMinutes(30));
        assertThat(first.end()).isEqualTo(LocalDateTime.of(2025, 1, 1, 0, 30));
        assertThat(first.averageKW()).isEqualByComparingTo("1");
    }

    @Test
    void acceptsAnExactBoundaryEndTimestampToo() throws IOException {
        var csv = HEADER
                + "1,2,3,MRIM,R,Generalusage,01/01/2025 12:00:00 AM,"
                + "01/01/2025 12:30:00 AM,0.500,0,A\n";
        var reading = UsageCsvReader.read(new StringReader(csv))
                .usage().consumption().readings().get(0);
        assertThat(reading.length()).isEqualTo(Duration.ofMinutes(30));
    }

    @Test
    void parsesTwelveHourClockCorrectly() throws IOException {
        var readings = UsageCsvReader.read(sample()).usage().consumption().readings();
        assertThat(readings.get(0).start()).isEqualTo(LocalDateTime.of(2025, 1, 1, 0, 0));
        assertThat(readings.get(2).minuteOfDay()).isEqualTo(960);
    }

    @Test
    void mapsQualityFlags() throws IOException {
        var report = UsageCsvReader.read(sample()).report();
        assertThat(report.qualityCounts()).containsEntry(Quality.ACTUAL, 5);
        assertThat(report.qualityCounts()).containsEntry(Quality.ESTIMATED, 1);
    }

    @Test
    void reportsDuplicateTimestamps() throws IOException {
        var report = UsageCsvReader.read(sample()).report();
        assertThat(report.duplicateStarts())
                .containsExactly(LocalDateTime.of(2025, 1, 1, 16, 0));
        assertThat(report.clean()).isFalse();
    }

    @Test
    void recordsEveryRateTypeSeen() throws IOException {
        var report = UsageCsvReader.read(sample()).report();
        assertThat(report.rateTypes()).containsExactlyInAnyOrder("Generalusage", "Solarexport");
    }

    @Test
    void countsIntervalsPerDay() throws IOException {
        var report = UsageCsvReader.read(sample()).report();
        assertThat(report.intervalsPerDay()).containsEntry(LocalDate.of(2025, 1, 1), 4);
        assertThat(report.intervalsPerDay()).containsEntry(LocalDate.of(2025, 1, 2), 1);
    }

    @Test
    void summaryDescribesEachProblem() throws IOException {
        var report = UsageCsvReader.read(sample()).report();
        assertThat(report.summary()).isNotEmpty();
        assertThat(String.join("\n", report.summary())).contains("duplicate");
    }

    @Test
    void rejectsAMissingRequiredColumn() {
        var csv = "StartDate,EndDate,ProfileReadValue\n01/01/2025 12:00:00 AM,"
                + "01/01/2025 12:29:59 AM,0.5\n";
        assertThatThrownBy(() -> UsageCsvReader.read(new StringReader(csv)))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("QualityFlag");
    }

    @Test
    void rejectsAnUnparseableTimestampNamingTheLineNumber() {
        var csv = HEADER + "1,2,3,MRIM,R,Generalusage,not-a-date,01/01/2025 12:29:59 AM,0.5,0,A\n";
        assertThatThrownBy(() -> UsageCsvReader.read(new StringReader(csv)))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("line 2");
    }

    @Test
    void ignoresBlankLines() throws IOException {
        var csv = HEADER
                + "1,2,3,MRIM,R,Generalusage,01/01/2025 12:00:00 AM,"
                + "01/01/2025 12:29:59 AM,0.500,0,A\n\n   \n";
        assertThat(UsageCsvReader.read(new StringReader(csv)).usage().consumption().readings())
                .hasSize(1);
    }
}
