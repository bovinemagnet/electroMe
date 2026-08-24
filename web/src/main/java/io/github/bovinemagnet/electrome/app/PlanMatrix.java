package io.github.bovinemagnet.electrome.app;

import io.github.bovinemagnet.electrome.core.cost.BillBreakdown;
import io.github.bovinemagnet.electrome.core.cost.ChargeLine;
import io.github.bovinemagnet.electrome.core.cost.Unit;
import io.github.bovinemagnet.electrome.core.domain.DateRange;
import io.github.bovinemagnet.electrome.core.tariff.Band;
import io.github.bovinemagnet.electrome.core.tariff.Plan;
import io.github.bovinemagnet.electrome.core.tariff.Tiered;
import io.github.bovinemagnet.electrome.core.tariff.TimeOfUse;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Several plans costed over one window, pivoted into charge components against plans.
 *
 * <p>Rows are components rather than plans because the useful reading is horizontal: this plan
 * wins on supply, that one on the evening peak. A ranking cannot show that, which is why two
 * plans $40 apart can look like a close call when one of them is simply a different shape.
 *
 * <p>Rows are the union across every selected plan. A plan without a given component shows an
 * absence rather than being left out, so the rows align and a missing charge reads as the
 * information it is.
 */
public record PlanMatrix(List<Plan> plans, List<Row> rows, List<BillBreakdown> bills,
        DateRange range) {

    /**
     * The vocabulary a tariff is described in.
     *
     * <p>Deliberately closed and deliberately semantic. Using the bill's own line labels would
     * put "Usage 16:00-21:00" and "Usage 17:00-21:00" on separate rows, which is exactly the
     * comparison a reader came to make. Declaration order is display order: supply first, then
     * the day from its cheapest window to its dearest.
     */
    public enum Component {
        DAILY_SUPPLY("Daily supply"),
        OFFPEAK("Overnight / off-peak"),
        MIDDAY("Middle of day"),
        SHOULDER("Shoulder"),
        PEAK("Evening peak"),
        FLAT("Flat usage rate"),
        BLOCK("Block rates"),
        DEMAND("Demand charge"),
        CONTROLLED("Controlled load"),
        FEED_IN("Solar feed-in"),
        MEMBERSHIP("Membership fee"),
        DISCOUNT("Discount");

        private final String label;

        Component(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** One component across every plan. Cells align with {@link PlanMatrix#plans()}. */
    public record Row(Component component, List<Cell> cells) {

        public Row {
            cells = List.copyOf(cells);
        }

        public String label() {
            return component.label();
        }
    }

    /**
     * One plan's figure for one component.
     *
     * @param present false when this plan has no such charge, which renders as a dash
     * @param cheapest true only where at least two plans have the component, because "cheapest"
     *     among one is not a comparison and a mark there would read as a win
     */
    public record Cell(BigDecimal cost, BigDecimal quantity, Unit unit, boolean present,
            boolean cheapest) {

        static Cell absent() {
            return new Cell(null, null, null, false, false);
        }
    }

    /**
     * One component's contribution to the gap between two plans.
     *
     * @param amount signed: negative where the first plan is cheaper on this component
     * @param favoursFirst true where this component makes the first plan the cheaper one
     */
    public record Difference(Component component, BigDecimal amount, boolean favoursFirst) {

        public BigDecimal size() {
            return amount.abs();
        }
    }

    public static PlanMatrix of(List<BillBreakdown> bills, DateRange range) {
        var plans = bills.stream().map(BillBreakdown::plan).toList();

        // Component totals per plan, keeping the enum's declaration order for free.
        var perPlan = new ArrayList<Map<Component, Cell>>();
        for (var bill : bills) {
            perPlan.add(componentsOf(bill));
        }

        var present = new EnumMap<Component, Boolean>(Component.class);
        for (var columns : perPlan) {
            columns.keySet().forEach(component -> present.put(component, true));
        }

        var rows = new ArrayList<Row>();
        for (var component : Component.values()) {
            if (!present.containsKey(component)) {
                continue;
            }
            var cells = new ArrayList<Cell>(perPlan.size());
            for (var columns : perPlan) {
                cells.add(columns.getOrDefault(component, Cell.absent()));
            }
            rows.add(new Row(component, mark(cells)));
        }
        return new PlanMatrix(plans, List.copyOf(rows), List.copyOf(bills), range);
    }

    public BigDecimal total(int column) {
        return bills.get(column).totalRounded();
    }

    public Plan plan(int column) {
        return plans.get(column);
    }

    /**
     * What separates two plans, largest contribution first.
     *
     * <p>The whole gap is attributed. A summary that explained most of the difference and
     * silently dropped the rest would produce a sentence that does not add up.
     */
    public List<Difference> differences(int first, int second) {
        var differences = new ArrayList<Difference>();
        for (var row : rows) {
            var a = row.cells().get(first);
            var b = row.cells().get(second);
            var costA = a.present() ? a.cost() : BigDecimal.ZERO;
            var costB = b.present() ? b.cost() : BigDecimal.ZERO;
            var amount = costA.subtract(costB);
            if (amount.signum() == 0) {
                continue;
            }
            differences.add(new Difference(row.component(), amount, amount.signum() < 0));
        }
        differences.sort(Comparator.comparing(Difference::size).reversed());
        return List.copyOf(differences);
    }

    // -----------------------------------------------------------------

    private static List<Cell> mark(List<Cell> cells) {
        long populated = cells.stream().filter(Cell::present).count();
        if (populated < 2) {
            return cells;
        }
        BigDecimal cheapest = cells.stream()
                .filter(Cell::present)
                .map(Cell::cost)
                .min(BigDecimal::compareTo)
                .orElseThrow();

        var marked = new ArrayList<Cell>(cells.size());
        for (var cell : cells) {
            marked.add(cell.present() && cell.cost().compareTo(cheapest) == 0
                    ? new Cell(cell.cost(), cell.quantity(), cell.unit(), true, true)
                    : cell);
        }
        return marked;
    }

    /** Bill lines folded into the component vocabulary, summing anything that shares a row. */
    private static Map<Component, Cell> componentsOf(BillBreakdown bill) {
        var totals = new EnumMap<Component, Cell>(Component.class);
        for (var line : bill.lines()) {
            var component = componentOf(bill.plan(), line);
            totals.merge(
                    component,
                    new Cell(line.cost(), line.quantity(), line.unit(), true, false),
                    (existing, added) -> new Cell(
                            existing.cost().add(added.cost()),
                            existing.quantity().add(added.quantity()),
                            existing.unit() == added.unit() ? existing.unit() : Unit.NONE,
                            true, false));
        }
        return totals;
    }

    private static Component componentOf(Plan plan, ChargeLine line) {
        return switch (line.kind()) {
            case SUPPLY -> Component.DAILY_SUPPLY;
            case DEMAND -> Component.DEMAND;
            case FEED_IN -> Component.FEED_IN;
            case DISCOUNT -> Component.DISCOUNT;
            case CONTROLLED -> Component.CONTROLLED;
            case MEMBERSHIP -> Component.MEMBERSHIP;
            case USAGE -> usageComponent(plan, line);
        };
    }

    /**
     * Which part of the day a usage line belongs to.
     *
     * <p>Block and flat tariffs get rows of their own. Calling a block rate a shoulder — which
     * is what a band-based classification would do, having no bands to consult — would put two
     * unrelated things on one row and invite a reader to compare them.
     */
    private static Component usageComponent(Plan plan, ChargeLine line) {
        var bands = BandComponent.bandsOf(plan);
        if (bands.isEmpty()) {
            return BandComponent.hasBlocks(plan) ? Component.BLOCK : Component.FLAT;
        }
        return BandComponent.bandFor(line.label(), bands)
                .map(band -> BandComponent.of(band, bands))
                .orElse(Component.SHOULDER);
    }

}
