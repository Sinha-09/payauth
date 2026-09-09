package com.shivamsinha.payauth.velocity;

import com.shivamsinha.payauth.config.VelocityProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Runs every enabled rule and takes the strictest verdict.
 *
 * <p><strong>Fail-open.</strong> Any failure reading Redis — timeout, connection
 * refused, a malformed value, an outage — is logged, counted, and turns into
 * {@link Verdict#ALLOW}. This is a deliberate business decision, not an oversight:
 * the fraud layer is an advisory check on the payment path, and if it is down, the
 * correct behaviour is to take the fraud loss rather than to decline every
 * cardholder in the world. Losses from a fraud outage are bounded and insurable;
 * declining all traffic is neither.
 *
 * <p>The corollary is that degraded evaluations must be visible. Every fail-open
 * increments {@code payauth.velocity.fail_open} and logs at WARN, so an outage
 * shows up as a metric rather than as an unexplained drop in decline rate.
 */
@Service
public class RuleEngine {

    private static final Logger log = LoggerFactory.getLogger(RuleEngine.class);

    private final List<VelocityRule> rules;
    private final VelocityRedisRepository repository;
    private final RulesConfigSource rulesConfigSource;
    private final VelocityProperties properties;
    private final Clock clock;
    private final Counter failOpenCounter;
    private final Counter declineCounter;
    private final Counter reviewCounter;
    private final Timer evaluationTimer;

    public RuleEngine(List<VelocityRule> rules,
                      VelocityRedisRepository repository,
                      RulesConfigSource rulesConfigSource,
                      VelocityProperties properties,
                      Clock clock,
                      MeterRegistry meterRegistry) {
        this.rules = List.copyOf(rules);
        this.repository = repository;
        this.rulesConfigSource = rulesConfigSource;
        this.properties = properties;
        this.clock = clock;
        this.failOpenCounter = Counter.builder("payauth.velocity.fail_open")
                .description("Rule evaluations that allowed the payment because the fraud store was unreachable")
                .register(meterRegistry);
        this.declineCounter = Counter.builder("payauth.velocity.decline")
                .description("Authorizations declined by a velocity rule")
                .register(meterRegistry);
        this.reviewCounter = Counter.builder("payauth.velocity.review")
                .description("Authorizations flagged for review by a velocity rule")
                .register(meterRegistry);
        this.evaluationTimer = Timer.builder("payauth.velocity.evaluation")
                .description("Time to load the velocity snapshot and run every rule")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meterRegistry);
    }

    public RuleEvaluation evaluate(String cardToken,
                                   long amountMinor,
                                   String currency,
                                   String merchantId,
                                   String countryCode) {

        if (!properties.enabled()) {
            return RuleEvaluation.allowedByDefault();
        }

        Timer.Sample sample = Timer.start();
        try {
            return doEvaluate(cardToken, amountMinor, currency, merchantId, countryCode);
        } finally {
            sample.stop(evaluationTimer);
        }
    }

    private RuleEvaluation doEvaluate(String cardToken,
                                      long amountMinor,
                                      String currency,
                                      String merchantId,
                                      String countryCode) {

        RulesConfig config = rulesConfigSource.current();
        var now = clock.instant();

        VelocitySnapshot snapshot;
        try {
            snapshot = repository.loadSnapshot(cardToken, now, config);
        } catch (RuntimeException ex) {
            // Fail open. Catching RuntimeException rather than a specific Redis
            // exception is intentional: the guarantee we are making is "nothing in
            // this layer can decline a payment by failing", and a narrower catch would
            // be a promise about which exceptions Lettuce throws.
            failOpenCounter.increment();
            log.warn("Velocity store unavailable; allowing authorization without a fraud check", ex);
            return RuleEvaluation.allowedByDefault();
        }

        Map<String, Verdict> perRule = new LinkedHashMap<>();
        Verdict strictest = Verdict.ALLOW;

        for (VelocityRule rule : rules) {
            if (!rule.isEnabled()) {
                continue;
            }
            Verdict verdict;
            try {
                verdict = rule.evaluate(new VelocityContext(
                        cardToken, amountMinor, currency, merchantId, countryCode, now, snapshot));
            } catch (RuntimeException ex) {
                // One broken rule must not take the others, or the payment, with it.
                failOpenCounter.increment();
                log.warn("Velocity rule {} threw; treating it as ALLOW", rule.name(), ex);
                verdict = Verdict.ALLOW;
            }
            perRule.put(rule.name(), verdict);
            strictest = strictest.strictest(verdict);
        }

        if (strictest == Verdict.DECLINE) {
            declineCounter.increment();
        } else if (strictest == Verdict.REVIEW) {
            reviewCounter.increment();
        }

        return new RuleEvaluation(strictest, Map.copyOf(perRule), false);
    }

    /**
     * Record the authorization so it counts towards the next one's velocity.
     *
     * <p>Also fail-open: if the fraud store cannot be written, the payment that has
     * already been decided must not be undone.
     */
    public void record(String cardToken,
                       UUID authorizationId,
                       long amountMinor,
                       String countryCode) {
        if (!properties.enabled()) {
            return;
        }
        try {
            repository.record(cardToken, authorizationId, amountMinor, countryCode,
                    clock.instant(), rulesConfigSource.current());
        } catch (RuntimeException ex) {
            failOpenCounter.increment();
            log.warn("Could not record authorization {} in the velocity store", authorizationId, ex);
        }
    }
}
