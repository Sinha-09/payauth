package com.shivamsinha.payauth.velocity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class VerdictTest {

    @Test
    @DisplayName("The strictest verdict wins, in either order")
    void strictestWins() {
        assertThat(Verdict.ALLOW.strictest(Verdict.REVIEW)).isEqualTo(Verdict.REVIEW);
        assertThat(Verdict.REVIEW.strictest(Verdict.ALLOW)).isEqualTo(Verdict.REVIEW);
        assertThat(Verdict.REVIEW.strictest(Verdict.DECLINE)).isEqualTo(Verdict.DECLINE);
        assertThat(Verdict.DECLINE.strictest(Verdict.ALLOW)).isEqualTo(Verdict.DECLINE);
        assertThat(Verdict.ALLOW.strictest(Verdict.ALLOW)).isEqualTo(Verdict.ALLOW);
    }

    @Test
    @DisplayName("Declaration order is the strictness order the engine relies on")
    void declarationOrderIsStrictnessOrder() {
        assertThat(Verdict.values()).containsExactly(Verdict.ALLOW, Verdict.REVIEW, Verdict.DECLINE);
    }
}
