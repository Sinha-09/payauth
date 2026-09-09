package com.shivamsinha.payauth.velocity;

import java.math.BigDecimal;

/**
 * The contents of {@code rules.yml}, immutable and swapped wholesale on reload.
 *
 * <p>Every field has a defensive default. A rules file that is malformed, missing a
 * section, or half-edited by an operator at 3am must not be able to turn the fraud
 * layer into something that declines everything.
 */
public record RulesConfig(CardVelocity cardVelocity,
                          AmountAnomaly amountAnomaly,
                          ImpossibleTravel impossibleTravel) {

    public RulesConfig {
        if (cardVelocity == null) {
            cardVelocity = CardVelocity.defaults();
        }
        if (amountAnomaly == null) {
            amountAnomaly = AmountAnomaly.defaults();
        }
        if (impossibleTravel == null) {
            impossibleTravel = ImpossibleTravel.defaults();
        }
    }

    public static RulesConfig defaults() {
        return new RulesConfig(null, null, null);
    }

    /**
     * @param maxAuthorizations the largest number of authorizations that is still fine
     *                          inside the window; the rule fires above this, not at it
     */
    public record CardVelocity(boolean enabled, int maxAuthorizations, int windowSeconds, Verdict verdict) {

        public CardVelocity {
            if (maxAuthorizations <= 0) {
                maxAuthorizations = 5;
            }
            if (windowSeconds <= 0) {
                windowSeconds = 60;
            }
            if (verdict == null) {
                verdict = Verdict.DECLINE;
            }
        }

        public static CardVelocity defaults() {
            return new CardVelocity(true, 5, 60, Verdict.DECLINE);
        }
    }

    /**
     * @param multiplier  how many times the rolling average counts as anomalous
     * @param minHistory  how many past amounts are needed before the average means
     *                    anything; below this the rule abstains rather than guessing
     * @param sampleSize  how many past authorizations the average is taken over
     */
    public record AmountAnomaly(boolean enabled,
                                BigDecimal multiplier,
                                int minHistory,
                                int sampleSize,
                                Verdict verdict) {

        public AmountAnomaly {
            if (multiplier == null || multiplier.signum() <= 0) {
                multiplier = new BigDecimal("5.0");
            }
            if (minHistory <= 0) {
                minHistory = 5;
            }
            if (sampleSize <= 0) {
                sampleSize = 20;
            }
            if (verdict == null) {
                verdict = Verdict.REVIEW;
            }
        }

        public static AmountAnomaly defaults() {
            return new AmountAnomaly(true, new BigDecimal("5.0"), 5, 20, Verdict.REVIEW);
        }

        /**
         * The multiplier as basis points, so the rule can compare in exact integer
         * arithmetic and never touch a floating point value on the money path.
         */
        public long multiplierBasisPoints() {
            return multiplier.movePointRight(4).longValueExact();
        }
    }

    public record ImpossibleTravel(boolean enabled, int windowSeconds, int minDistanceKm, Verdict verdict) {

        public ImpossibleTravel {
            if (windowSeconds <= 0) {
                windowSeconds = 600;
            }
            if (minDistanceKm <= 0) {
                minDistanceKm = 500;
            }
            if (verdict == null) {
                verdict = Verdict.DECLINE;
            }
        }

        public static ImpossibleTravel defaults() {
            return new ImpossibleTravel(true, 600, 500, Verdict.DECLINE);
        }
    }
}
