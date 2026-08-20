package io.github.bovinemagnet.electrome.web;

import io.github.bovinemagnet.electrome.app.PlanPage;
import io.github.bovinemagnet.electrome.app.PlanQuery;
import io.github.bovinemagnet.electrome.core.domain.DateRange;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Response;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * The rate table as a file.
 *
 * <p>The one place this application emits anything but HTML, and deliberately so. A screen can
 * be read but not pivoted, and a household checking three hundred tariffs against its own
 * arithmetic needs the numbers rather than a picture of them.
 *
 * <p>Its own root path because JAX-RS joins a class path and a method path with a separator:
 * {@code /rates} and {@code .csv} would answer at {@code /rates/.csv}.
 */
@Path("/rates.csv")
public class RatesCsvResource {

    @Inject PlanBrowsing browsing;

    @GET
    @Produces("text/csv; charset=utf-8")
    public Response csv(
            @QueryParam("from") String from,
            @QueryParam("to") String to,
            @QueryParam("search") String search,
            @QueryParam("requirements") String requirements,
            @QueryParam("retailers") List<String> retailers,
            @QueryParam("shape") String shape,
            @QueryParam("sort") String sort,
            @QueryParam("have") List<String> have,
            @QueryParam("unconditional") String unconditional,
            @QueryParam("minSaving") String minimumSaving) {

        var range = browsing.range(from, to);
        // No limit: a file has no reason to be paginated, and a reader who asked for the data
        // did not ask for the first twenty-five rows of it.
        var page = browsing.page(range, browsing.query(search, requirements, retailers, shape,
                sort, "all", have, unconditional, minimumSaving));

        return Response.ok(toCsv(page))
                .header("Content-Disposition",
                        "attachment; filename=\"electrome-rates-" + range.from() + "-to-"
                                + range.to() + ".csv\"")
                .build();
    }

    static String toCsv(PlanPage page) {
        var out = new StringBuilder();
        var columns = page.rateColumns();

        out.append("plan_id,plan_name,retailer,shape,total_dollars,average_cents_per_kwh");
        for (var column : columns) {
            out.append(',').append(field(column.label()));
            out.append(',').append(field(column.label() + " beyond cap"));
        }
        out.append('\n');

        for (var row : page.results()) {
            var plan = row.bill().plan();
            var rates = page.ratesFor(row);
            out.append(field(plan.id())).append(',')
                    .append(field(plan.name())).append(',')
                    .append(field(plan.retailer())).append(',')
                    .append(field(PlanQuery.PlanShape.of(plan).name())).append(',')
                    .append(row.total().toPlainString()).append(',')
                    .append(row.bill().averageCentsPerKWh()
                            .setScale(3, RoundingMode.HALF_UP).toPlainString());
            for (var column : columns) {
                out.append(',').append(number(rates == null ? null : rates.rate(column)));
                out.append(',').append(number(rates == null ? null : rates.beyond(column)));
            }
            out.append('\n');
        }
        return out.toString();
    }

    /** An absent rate is an empty cell, not a zero: a plan without a peak does not charge 0c. */
    private static String number(BigDecimal value) {
        return value == null ? "" : value.stripTrailingZeros().toPlainString();
    }

    /** Quoted only where a value could otherwise break the row, which most never do. */
    private static String field(String value) {
        if (value == null) {
            return "";
        }
        if (value.indexOf(',') < 0 && value.indexOf('"') < 0 && value.indexOf('\n') < 0) {
            return value;
        }
        return '"' + value.replace("\"", "\"\"") + '"';
    }
}
