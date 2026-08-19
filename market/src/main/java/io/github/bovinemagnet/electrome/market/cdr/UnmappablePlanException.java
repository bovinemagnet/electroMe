package io.github.bovinemagnet.electrome.market.cdr;

/** Thrown when a published plan cannot be represented in the tariff model. */
public class UnmappablePlanException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String planId;
    private final String reason;

    public UnmappablePlanException(String planId, String reason) {
        super("Cannot map plan " + planId + ": " + reason);
        this.planId = planId;
        this.reason = reason;
    }

    public String planId() {
        return planId;
    }

    public String reason() {
        return reason;
    }
}
