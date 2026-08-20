package io.github.bovinemagnet.electrome.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.bovinemagnet.electrome.app.PlanQuery.PlanShape;
import io.github.bovinemagnet.electrome.app.PlanQuery.RequirementFilter;
import io.github.bovinemagnet.electrome.app.PlanQuery.SortBy;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Criteria parsing.
 *
 * <p>Every value arrives as text from a query string, so the parsing has to survive a hand-edited
 * URL. A bad criterion falls back to its default rather than rejecting the request: the reader
 * asked to see plans, and an unknown sort order is no reason to show them nothing.
 */
class PlanQueryTest {

    @Test
    void defaultsHideRequirementPlansAndSortByTotal() {
        var query = PlanQuery.defaults();

        // The four cheapest published plans all need equipment. Showing them by default puts an
        // unreachable number at the top of the page.
        assertThat(query.requirements()).isEqualTo(RequirementFilter.OPEN_ONLY);
        assertThat(query.sort()).isEqualTo(SortBy.TOTAL);
        assertThat(query.shape()).isEqualTo(PlanShape.ANY);
        assertThat(query.search()).isEmpty();
        assertThat(query.retailers()).isEmpty();
        assertThat(query.limit()).isEqualTo(25);
    }

    @Test
    void parsesEveryCriterion() {
        var query = PlanQuery.of("origin", "ANY", List.of("AGL", "Origin"), "TIME_OF_USE",
                "PEAK_RATE", "100");

        assertThat(query.search()).isEqualTo("origin");
        assertThat(query.requirements()).isEqualTo(RequirementFilter.ANY);
        assertThat(query.retailers()).containsExactlyInAnyOrder("AGL", "Origin");
        assertThat(query.shape()).isEqualTo(PlanShape.TIME_OF_USE);
        assertThat(query.sort()).isEqualTo(SortBy.PEAK_RATE);
        assertThat(query.limit()).isEqualTo(100);
    }

    @Test
    void acceptsCriteriaInAnyCase() {
        var query = PlanQuery.of(null, "any", null, "time_of_use", "name", "10");

        assertThat(query.requirements()).isEqualTo(RequirementFilter.ANY);
        assertThat(query.shape()).isEqualTo(PlanShape.TIME_OF_USE);
        assertThat(query.sort()).isEqualTo(SortBy.NAME);
    }

    @Test
    void unknownValuesFallBackToTheDefaultRatherThanRejectingTheRequest() {
        var query = PlanQuery.of(null, "NONSENSE", null, "SQUARE", "BY_VIBES", "eleventy");

        assertThat(query.requirements()).isEqualTo(RequirementFilter.OPEN_ONLY);
        assertThat(query.shape()).isEqualTo(PlanShape.ANY);
        assertThat(query.sort()).isEqualTo(SortBy.TOTAL);
        assertThat(query.limit()).isEqualTo(25);
    }

    @Test
    void limitAcceptsAllAsAWord() {
        assertThat(PlanQuery.of(null, null, null, null, null, "all").limit())
                .isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    void anUnofferedLimitIsClampedToTheNearestOffered() {
        // The control offers 10, 25, 100 and all. A hand-edited 5000 must not become a page
        // that ships every plan when the reader asked for a bounded list.
        assertThat(PlanQuery.of(null, null, null, null, null, "5000").limit()).isEqualTo(100);
        assertThat(PlanQuery.of(null, null, null, null, null, "1").limit()).isEqualTo(10);
        assertThat(PlanQuery.of(null, null, null, null, null, "-4").limit()).isEqualTo(25);
    }

    @Test
    void blankSearchAndBlankRetailersMeanNoRestriction() {
        var query = PlanQuery.of("   ", null, List.of("", "  "), null, null, null);

        assertThat(query.search()).isEmpty();
        assertThat(query.retailers()).isEmpty();
        assertThat(query.restricted()).isFalse();
    }

    @Test
    void knowsWhenItRestrictsWhatIsShown() {
        assertThat(PlanQuery.defaults().restricted()).isFalse();
        assertThat(PlanQuery.defaults().withSearch("agl").restricted()).isTrue();
        assertThat(PlanQuery.of(null, "ANY", null, "FLAT", null, null).restricted()).isTrue();
    }

    @Test
    void namesTheCriteriaInForceSoAnEmptyResultCanExplainItself() {
        var query = PlanQuery.of("agl", "REQUIREMENTS_ONLY", List.of("Origin"), "FLAT", null, null);

        // A blank table is never acceptable; the reader must be told what excluded everything.
        assertThat(query.activeCriteria())
                .contains("search “agl”")
                .contains("only plans with requirements")
                .contains("retailer Origin")
                .contains("flat rate plans");
    }

    @Test
    void carriesItselfInAQueryStringSoAScreenIsLinkable() {
        var query = PlanQuery.of("agl solar", "ANY", List.of("Origin"), "FLAT", "NAME", "100");

        // A URL is a complete description of what you are looking at.
        assertThat(query.queryString())
                .contains("search=agl+solar")
                .contains("requirements=ANY")
                .contains("retailers=Origin")
                .contains("shape=FLAT")
                .contains("sort=NAME")
                .contains("limit=100");
    }

    @Test
    void aDefaultQueryContributesNothingToTheUrl() {
        // Defaults left in the URL make a shared link look like a set of deliberate choices.
        assertThat(PlanQuery.defaults().queryString()).isEmpty();
    }
}
