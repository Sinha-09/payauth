package com.shivamsinha.payauth.velocity.rules;

import com.shivamsinha.payauth.velocity.RulesConfig;
import com.shivamsinha.payauth.velocity.RulesConfigSource;
import com.shivamsinha.payauth.velocity.Verdict;
import com.shivamsinha.payauth.velocity.VelocityContext;
import com.shivamsinha.payauth.velocity.VelocityRule;
import org.springframework.stereotype.Component;

/**
 * More than N authorizations on one card inside a sliding window.
 *
 * <p>The window is a Redis sorted set scored by epoch millis, so it slides
 * continuously rather than resetting on a boundary. A fixed bucket would let an
 * attacker put N authorizations at 11:59:59 and another N at 12:00:01 and never
 * trip the rule; with a sorted set the count is always "in the last 60 seconds",
 * measured from now.
 *
 * <p>The count read from Redis excludes the authorization being judged, because
 * the card is only recorded after the decision. So the comparison is "would this
 * one take us past the limit", not "have we already gone past it".
 */
@Component
public class CardVelocityRule implements VelocityRule {

    private final RulesConfigSource rules;

    public CardVelocityRule(RulesConfigSource rules) {
        this.rules = rules;
    }

    @Override
    public String name() {
        return "card-velocity";
    }

    @Override
    public boolean isEnabled() {
        return rules.current().cardVelocity().enabled();
    }

    @Override
    public Verdict evaluate(VelocityContext context) {
        RulesConfig.CardVelocity config = rules.current().cardVelocity();

        // +1 counts the authorization in hand alongside the ones already in the window.
        long attemptsIncludingThisOne = context.snapshot().recentAuthorizationCount() + 1;

        return attemptsIncludingThisOne > config.maxAuthorizations()
                ? config.verdict()
                : Verdict.ALLOW;
    }
}
