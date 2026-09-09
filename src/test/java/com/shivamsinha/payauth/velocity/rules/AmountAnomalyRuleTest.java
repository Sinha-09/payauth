package com.shivamsinha.payauth.velocity.rules;

import com.shivamsinha.payauth.velocity.RulesConfig;
import com.shivamsinha.payauth.velocity.Verdict;
import com.shivamsinha.payauth.velocity.VelocityContext;
import com.shivamsinha.payauth.velocity.VelocitySnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AmountAnomalyRuleTest {

    private static final Instant NOW = Instant.parse("2026-01-01T12:00:00Z");

    private AmountAnomalyRule rule(String multiplier, int minHistory) {
        RulesConfig config = new RulesConfig(null,
                new RulesConfig.AmountAnomaly(true, new BigDecimal(multiplier), minHistory, 20, Verdict.REVIEW),
                null);
        return new AmountAnomalyRule(() -> config);
    }

    private VelocityContext context(long amountMinor, List<Long> history) {
        return new VelocityContext("tok_1", amountMinor, "INR", "m1", "IN", NOW,
                new VelocitySnapshot(0, history, List.of(), false));
    }

    @Test
    @DisplayName("Below the multiple of the rolling average is allowed")
    void normalAmountAllowed() {
        List<Long> history = List.of(1000L, 1000L, 1000L, 1000L, 1000L);

        assertThat(rule("5.0", 5).evaluate(context(4000L, history))).isEqualTo(Verdict.ALLOW);
    }

    @Test
    @DisplayName("Exactly the multiple is allowed; above it is flagged")
    void boundaryIsExclusive() {
        List<Long> history = List.of(1000L, 1000L, 1000L, 1000L, 1000L);

        assertThat(rule("5.0", 5).evaluate(context(5000L, history))).isEqualTo(Verdict.ALLOW);
        assertThat(rule("5.0", 5).evaluate(context(5001L, history))).isEqualTo(Verdict.REVIEW);
    }

    @Test
    @DisplayName("A fractional multiplier is exact, with no floating point drift")
    void fractionalMultiplierIsExact() {
        // Average 300; 2.5x is 750.
        List<Long> history = List.of(100L, 200L, 300L, 400L, 500L);

        assertThat(rule("2.5", 5).evaluate(context(750L, history))).isEqualTo(Verdict.ALLOW);
        assertThat(rule("2.5", 5).evaluate(context(751L, history))).isEqualTo(Verdict.REVIEW);
    }

    @Test
    @DisplayName("The rule abstains until there is enough history to average")
    void abstainsWithoutEnoughHistory() {
        // A single 100 paise purchase followed by a 100000 paise one is 1000x the
        // "average", and means nothing.
        assertThat(rule("5.0", 5).evaluate(context(100_000L, List.of(100L)))).isEqualTo(Verdict.ALLOW);
        assertThat(rule("5.0", 5).evaluate(context(100_000L, List.of()))).isEqualTo(Verdict.ALLOW);
    }

    @Test
    @DisplayName("Only the configured sample size counts towards the average")
    void averageIsBoundedBySampleSize() {
        RulesConfig config = new RulesConfig(null,
                new RulesConfig.AmountAnomaly(true, new BigDecimal("2.0"), 3, 3, Verdict.REVIEW), null);
        AmountAnomalyRule rule = new AmountAnomalyRule(() -> config);

        // Newest first: the sample is the three 100s. Older huge amounts are ignored.
        List<Long> history = List.of(100L, 100L, 100L, 1_000_000L, 1_000_000L);

        assertThat(rule.evaluate(context(201L, history))).isEqualTo(Verdict.REVIEW);
        assertThat(rule.evaluate(context(200L, history))).isEqualTo(Verdict.ALLOW);
    }

    @Test
    @DisplayName("Very large amounts do not overflow the comparison")
    void noOverflowOnLargeAmounts() {
        // 20 samples near the top of the long range: any 64-bit intermediate product
        // here would wrap, and the rule would silently invert.
        List<Long> history = Collections.nCopies(20, Long.MAX_VALUE / 32);

        assertThat(rule("5.0", 5).evaluate(context(Long.MAX_VALUE / 32, history)))
                .isEqualTo(Verdict.ALLOW);
    }

    @Test
    @DisplayName("An all-zero history cannot produce a ratio, so the rule abstains")
    void zeroHistoryAbstains() {
        List<Long> history = List.of(0L, 0L, 0L, 0L, 0L);

        assertThat(rule("5.0", 5).evaluate(context(100_000L, history))).isEqualTo(Verdict.ALLOW);
    }
}
