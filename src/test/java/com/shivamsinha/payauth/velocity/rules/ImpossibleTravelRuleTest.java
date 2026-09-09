package com.shivamsinha.payauth.velocity.rules;

import com.shivamsinha.payauth.velocity.GeoEvent;
import com.shivamsinha.payauth.velocity.RulesConfig;
import com.shivamsinha.payauth.velocity.Verdict;
import com.shivamsinha.payauth.velocity.VelocityContext;
import com.shivamsinha.payauth.velocity.VelocitySnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ImpossibleTravelRuleTest {

    private static final Instant NOW = Instant.parse("2026-01-01T12:00:00Z");

    private ImpossibleTravelRule rule(int windowSeconds, int minDistanceKm) {
        RulesConfig config = new RulesConfig(null, null,
                new RulesConfig.ImpossibleTravel(true, windowSeconds, minDistanceKm, Verdict.DECLINE));
        return new ImpossibleTravelRule(() -> config);
    }

    private VelocityContext context(String countryCode, List<GeoEvent> history) {
        return new VelocityContext("tok_1", 1000L, "INR", "m1", countryCode, NOW,
                new VelocitySnapshot(0, List.of(), history, false));
    }

    private GeoEvent minutesAgo(String country, long minutes) {
        return new GeoEvent(country, NOW.minus(Duration.ofMinutes(minutes)));
    }

    @Test
    @DisplayName("Two distant countries inside the window is declined")
    void distantCountriesInsideWindowDeclines() {
        assertThat(rule(600, 500).evaluate(context("US", List.of(minutesAgo("IN", 5)))))
                .isEqualTo(Verdict.DECLINE);
    }

    @Test
    @DisplayName("The same country is never impossible travel, however fast")
    void sameCountryAllowed() {
        assertThat(rule(600, 500).evaluate(context("IN", List.of(minutesAgo("IN", 1)))))
                .isEqualTo(Verdict.ALLOW);
    }

    @Test
    @DisplayName("Distant countries outside the window are allowed")
    void distantCountriesOutsideWindowAllowed() {
        assertThat(rule(600, 500).evaluate(context("US", List.of(minutesAgo("IN", 30)))))
                .isEqualTo(Verdict.ALLOW);
    }

    @Test
    @DisplayName("Neighbouring countries inside the window are allowed: crossing a border is not fraud")
    void nearbyCountriesAllowed() {
        // France to Germany is roughly 600km centroid to centroid, so a 2000km
        // threshold treats it as ordinary travel.
        assertThat(rule(600, 2000).evaluate(context("DE", List.of(minutesAgo("FR", 5)))))
                .isEqualTo(Verdict.ALLOW);
    }

    @Test
    @DisplayName("An unknown or missing country makes the rule abstain, not decline")
    void unknownCountryAbstains() {
        assertThat(rule(600, 500).evaluate(context(null, List.of(minutesAgo("IN", 1)))))
                .isEqualTo(Verdict.ALLOW);
        assertThat(rule(600, 500).evaluate(context("ZZ", List.of(minutesAgo("IN", 1)))))
                .isEqualTo(Verdict.ALLOW);
        assertThat(rule(600, 500).evaluate(context("US", List.of(minutesAgo("ZZ", 1)))))
                .isEqualTo(Verdict.ALLOW);
    }

    @Test
    @DisplayName("No history at all is allowed")
    void noHistoryAllowed() {
        assertThat(rule(600, 500).evaluate(context("US", List.of()))).isEqualTo(Verdict.ALLOW);
    }

    @Test
    @DisplayName("A recent same-country entry does not mask an older distant one still inside the window")
    void scansPastMatchingEntries() {
        List<GeoEvent> history = List.of(minutesAgo("US", 1), minutesAgo("IN", 8));

        assertThat(rule(600, 500).evaluate(context("US", history))).isEqualTo(Verdict.DECLINE);
    }

    @Test
    @DisplayName("Scanning stops at the first entry older than the window")
    void stopsScanningPastTheWindow() {
        // Newest first. The IN entry is 20 minutes old, well outside a 10 minute
        // window, so it must not be considered even though something older follows.
        List<GeoEvent> history = List.of(minutesAgo("US", 1), minutesAgo("IN", 20), minutesAgo("IN", 25));

        assertThat(rule(600, 500).evaluate(context("US", history))).isEqualTo(Verdict.ALLOW);
    }
}
