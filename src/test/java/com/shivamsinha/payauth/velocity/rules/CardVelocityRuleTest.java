package com.shivamsinha.payauth.velocity.rules;

import com.shivamsinha.payauth.velocity.RulesConfig;
import com.shivamsinha.payauth.velocity.Verdict;
import com.shivamsinha.payauth.velocity.VelocityContext;
import com.shivamsinha.payauth.velocity.VelocitySnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CardVelocityRuleTest {

    private static final Instant NOW = Instant.parse("2026-01-01T12:00:00Z");

    private CardVelocityRule ruleWithLimit(int maxAuthorizations) {
        RulesConfig config = new RulesConfig(
                new RulesConfig.CardVelocity(true, maxAuthorizations, 60, Verdict.DECLINE), null, null);
        return new CardVelocityRule(() -> config);
    }

    private VelocityContext contextWithCount(long recentCount) {
        return new VelocityContext("tok_1", 1000L, "INR", "m1", "IN", NOW,
                new VelocitySnapshot(recentCount, List.of(), List.of(), false));
    }

    @Test
    @DisplayName("Below the limit is allowed")
    void belowLimitAllows() {
        assertThat(ruleWithLimit(5).evaluate(contextWithCount(0))).isEqualTo(Verdict.ALLOW);
        assertThat(ruleWithLimit(5).evaluate(contextWithCount(3))).isEqualTo(Verdict.ALLOW);
    }

    @Test
    @DisplayName("The Nth authorization is still allowed; the N+1th is not")
    void boundaryIsExclusive() {
        // Four already in the window plus this one is five, which is the limit, so it
        // is allowed. The rule fires above N, not at it.
        assertThat(ruleWithLimit(5).evaluate(contextWithCount(4))).isEqualTo(Verdict.ALLOW);
        assertThat(ruleWithLimit(5).evaluate(contextWithCount(5))).isEqualTo(Verdict.DECLINE);
    }

    @Test
    @DisplayName("Well past the limit stays declined")
    void farPastLimitDeclines() {
        assertThat(ruleWithLimit(5).evaluate(contextWithCount(500))).isEqualTo(Verdict.DECLINE);
    }

    @Test
    @DisplayName("The configured verdict is honoured, not hardcoded to DECLINE")
    void verdictComesFromConfiguration() {
        RulesConfig config = new RulesConfig(
                new RulesConfig.CardVelocity(true, 2, 60, Verdict.REVIEW), null, null);

        assertThat(new CardVelocityRule(() -> config).evaluate(contextWithCount(9)))
                .isEqualTo(Verdict.REVIEW);
    }

    @Test
    @DisplayName("A disabled rule reports itself disabled")
    void disabledRule() {
        RulesConfig config = new RulesConfig(
                new RulesConfig.CardVelocity(false, 5, 60, Verdict.DECLINE), null, null);

        assertThat(new CardVelocityRule(() -> config).isEnabled()).isFalse();
    }
}
