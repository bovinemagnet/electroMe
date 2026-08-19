package io.github.bovinemagnet.electrome.web;

import io.github.bovinemagnet.electrome.app.ComparisonService;
import io.github.bovinemagnet.electrome.app.PlanResult;
import io.github.bovinemagnet.electrome.app.ScenarioOutcome;
import io.github.bovinemagnet.electrome.app.ScenarioService;
import io.github.bovinemagnet.electrome.app.UsageStore;
import io.github.bovinemagnet.electrome.core.domain.DateRange;
import io.github.bovinemagnet.electrome.view.ChartOptions;
import io.github.bovinemagnet.electrome.view.Charts;
import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;

/** The what-if panel. */
@Path("/fragments/scenarios")
public class ScenarioResource {

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    @CheckedTemplate
    static class Templates {
        static native TemplateInstance scenarios(
                List<ScenarioOutcome> outcomes, String chart, DateRange range,
                String baselinePlan, BigDecimal baselineBest,
                BigDecimal solarKW, BigDecimal batteryKWh, BigDecimal shiftPercent);
    }

    @Inject UsageStore usage;
    @Inject ScenarioService scenarios;
    @Inject ComparisonService comparisons;

    @GET
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance scenarios(
            @QueryParam("from") String from,
            @QueryParam("to") String to,
            @QueryParam("solarKW") @DefaultValue("6.6") BigDecimal solarKW,
            @QueryParam("batteryKWh") @DefaultValue("13.5") BigDecimal batteryKWh,
            @QueryParam("shiftPercent") @DefaultValue("30") BigDecimal shiftPercent) {

        var range = range(from, to);
        var shift = shiftPercent.divide(HUNDRED, MathContext.DECIMAL64);
        var outcomes = scenarios.evaluate(range, solarKW, batteryKWh, shift);
        var baseline = comparisons.compare(range).best();

        return Templates.scenarios(
                outcomes,
                Charts.payload(ChartOptions.scenarioSavings(outcomes)),
                range,
                baseline.map(PlanResult::planName).orElse(""),
                baseline.map(PlanResult::total).orElse(BigDecimal.ZERO),
                solarKW, batteryKWh, shiftPercent);
    }

    private DateRange range(String from, String to) {
        if (from == null || from.isBlank() || to == null || to.isBlank()) {
            return usage.defaultWindow();
        }
        try {
            return new DateRange(LocalDate.parse(from), LocalDate.parse(to));
        } catch (DateTimeParseException e) {
            throw new BadRequestException("Dates must be yyyy-MM-dd");
        } catch (IllegalArgumentException e) {
            throw new BadRequestException(e.getMessage());
        }
    }
}
