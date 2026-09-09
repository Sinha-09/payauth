package com.shivamsinha.payauth.velocity;

/**
 * Where a rule gets its current thresholds.
 *
 * <p>An interface rather than the loader itself so that a rule can be unit tested
 * against a literal {@link RulesConfig}, with no file, no scheduler and no Spring
 * context. The rules are the part of this system most likely to be edited under
 * time pressure; they should be the cheapest part to test.
 */
@FunctionalInterface
public interface RulesConfigSource {

    RulesConfig current();
}
