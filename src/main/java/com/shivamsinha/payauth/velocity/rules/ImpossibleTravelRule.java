package com.shivamsinha.payauth.velocity.rules;

import com.shivamsinha.payauth.velocity.CountryGeography;
import com.shivamsinha.payauth.velocity.GeoEvent;
import com.shivamsinha.payauth.velocity.RulesConfig;
import com.shivamsinha.payauth.velocity.RulesConfigSource;
import com.shivamsinha.payauth.velocity.Verdict;
import com.shivamsinha.payauth.velocity.VelocityContext;
import com.shivamsinha.payauth.velocity.VelocityRule;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/**
 * The same card used in two distant countries within a short window.
 *
 * <p>Not a speed calculation. "Distant countries inside ten minutes" is the
 * physical impossibility worth flagging, and turning it into an implied velocity
 * would only add a threshold nobody can calibrate.
 *
 * <p>The rule abstains whenever it cannot be sure: no country on this
 * authorization, no country in history, or a country it has no coordinates for.
 * Declining on missing data would punish exactly the acquirers whose data is
 * incomplete.
 */
@Component
public class ImpossibleTravelRule implements VelocityRule {

    private final RulesConfigSource rules;

    public ImpossibleTravelRule(RulesConfigSource rules) {
        this.rules = rules;
    }

    @Override
    public String name() {
        return "impossible-travel";
    }

    @Override
    public boolean isEnabled() {
        return rules.current().impossibleTravel().enabled();
    }

    @Override
    public Verdict evaluate(VelocityContext context) {
        RulesConfig.ImpossibleTravel config = rules.current().impossibleTravel();

        String currentCountry = context.countryCode();
        if (!CountryGeography.isKnown(currentCountry)) {
            return Verdict.ALLOW;
        }

        Duration window = Duration.ofSeconds(config.windowSeconds());

        for (GeoEvent previous : context.snapshot().recentGeoEvents()) {
            Duration elapsed = Duration.between(previous.at(), context.now());

            // History is newest first, so the first entry outside the window means
            // every remaining entry is older still.
            if (elapsed.isNegative() || elapsed.compareTo(window) > 0) {
                break;
            }
            if (previous.countryCode().equals(currentCountry)) {
                continue;
            }

            Optional<Double> distanceKm = CountryGeography.distanceKm(previous.countryCode(), currentCountry);
            if (distanceKm.isPresent() && distanceKm.get() >= config.minDistanceKm()) {
                return config.verdict();
            }
        }

        return Verdict.ALLOW;
    }
}
