package com.shivamsinha.payauth.velocity;

/**
 * One fraud rule.
 *
 * <p>Implementations must be pure functions of their {@link VelocityContext} and
 * their configuration: no I/O, no clock reads, no shared mutable state. That is
 * what lets the engine run them in any order, reason about their cost, and test
 * them without a broker or a database.
 */
public interface VelocityRule {

    /** Stable identifier, used in config, logs and metrics. */
    String name();

    boolean isEnabled();

    Verdict evaluate(VelocityContext context);
}
