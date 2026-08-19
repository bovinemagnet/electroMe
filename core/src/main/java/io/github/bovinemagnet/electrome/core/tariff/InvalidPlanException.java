package io.github.bovinemagnet.electrome.core.tariff;

import java.util.List;

/** Thrown when a plan definition is structurally unusable. */
public class InvalidPlanException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient List<String> problems;

    public InvalidPlanException(String planId, List<String> problems) {
        super("Plan '" + planId + "' is invalid: " + String.join("; ", problems));
        this.problems = List.copyOf(problems);
    }

    public List<String> problems() {
        return problems;
    }
}
