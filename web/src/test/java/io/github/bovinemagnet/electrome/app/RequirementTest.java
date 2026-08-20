package io.github.bovinemagnet.electrome.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Reading a plan's eligibility text as something a household can answer yes or no to.
 *
 * <p>The text is the retailer's own and the standard's type field says nothing, so this is
 * necessarily approximate. What it must never do is decide that an unreadable requirement is
 * met: that is how a plan the household cannot buy reaches the top of the ranking.
 */
class RequirementTest {

    @Test
    void readsTheEquipmentAPlanAsksFor() {
        assertThat(Requirement.of(List.of("Customer must have a solar PV system installed")))
                .containsExactly(Requirement.SOLAR);
        assertThat(Requirement.of(List.of("Available to customers with a home battery")))
                .containsExactly(Requirement.BATTERY);
        assertThat(Requirement.of(List.of("You must own an electric vehicle")))
                .containsExactly(Requirement.ELECTRIC_VEHICLE);
        assertThat(Requirement.of(List.of("Requires an active membership")))
                .containsExactly(Requirement.MEMBERSHIP);
    }

    /**
     * A concession card is a requirement, not a discount.
     *
     * <p>A seniors offer priced as though anyone could take it puts a saving at the top of the
     * ranking that most readers have no way to claim.
     */
    @Test
    void readsAConcessionCardAsSomethingTheHouseholdMustHold() {
        assertThat(Requirement.of(List.of("Must be a VIC Seniors cardholder.")))
                .containsExactly(Requirement.CONCESSION);
        assertThat(Requirement.of(List.of("Available to pensioners only")))
                .containsExactly(Requirement.CONCESSION);
    }

    @Test
    void oneEntryCanStateSeveralRequirements() {
        assertThat(Requirement.of(List.of("Requires solar PV and a home battery")))
                .containsExactlyInAnyOrder(Requirement.SOLAR, Requirement.BATTERY);
    }

    /**
     * Text this code cannot classify becomes a requirement that can never be met.
     *
     * <p>The alternative is to ignore it, which silently promotes the plan into a ranking of
     * things the household can actually sign up to.
     */
    @Test
    void anUnreadableRequirementIsNeverConsideredMet() {
        var unreadable = Requirement.of(List.of("Only available to residents of the moon"));
        assertThat(unreadable).containsExactly(Requirement.OTHER);
        assertThat(Requirement.OTHER.ownable()).isFalse();
        assertThat(Requirement.SOLAR.ownable()).isTrue();
    }

    @Test
    void aPlanWithNoEligibilityTextAsksForNothing() {
        assertThat(Requirement.of(null)).isEmpty();
        assertThat(Requirement.of(List.of())).isEmpty();
        assertThat(Requirement.of(List.of("  "))).isEmpty();
    }

    /**
     * Every requirement a household could hold must be answerable.
     *
     * <p>Classifying a requirement the reader has no way to tick off would hide those plans
     * from the answer permanently, which is worse than not classifying it at all.
     */
    @Test
    void everyOwnableRequirementCanBeDeclared() {
        for (var requirement : Requirement.values()) {
            if (requirement.ownable()) {
                assertThat(Requirement.parse(requirement.name()))
                        .as("%s must be declarable", requirement)
                        .isEqualTo(requirement);
            }
        }
    }

    @Test
    void readsWhatTheHouseholdSaysItHas() {
        assertThat(Requirement.parse("SOLAR")).isEqualTo(Requirement.SOLAR);
        assertThat(Requirement.parse("ev")).isEqualTo(Requirement.ELECTRIC_VEHICLE);
        assertThat(Requirement.parse("electric-vehicle")).isEqualTo(Requirement.ELECTRIC_VEHICLE);
        assertThat(Requirement.parse("hovercraft")).isNull();
        assertThat(Requirement.parse(null)).isNull();
    }
}
