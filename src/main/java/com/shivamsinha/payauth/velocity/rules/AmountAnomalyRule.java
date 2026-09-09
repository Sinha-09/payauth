package com.shivamsinha.payauth.velocity.rules;

import com.shivamsinha.payauth.velocity.RulesConfig;
import com.shivamsinha.payauth.velocity.RulesConfigSource;
import com.shivamsinha.payauth.velocity.Verdict;
import com.shivamsinha.payauth.velocity.VelocityContext;
import com.shivamsinha.payauth.velocity.VelocityRule;
import org.springframework.stereotype.Component;

import java.math.BigInteger;
import java.util.List;

/**
 * The amount is more than K times the card's rolling average.
 *
 * <p>Below {@code minHistory} past authorizations the rule abstains. An average
 * over one or two data points is not an average, and declining a customer's second
 * ever purchase because it was larger than their first is a worse outcome than
 * missing one fraudulent transaction.
 */
@Component
public class AmountAnomalyRule implements VelocityRule {

    private static final BigInteger BASIS_POINTS = BigInteger.valueOf(10_000);

    private final RulesConfigSource rules;

    public AmountAnomalyRule(RulesConfigSource rules) {
        this.rules = rules;
    }

    @Override
    public String name() {
        return "amount-anomaly";
    }

    @Override
    public boolean isEnabled() {
        return rules.current().amountAnomaly().enabled();
    }

    @Override
    public Verdict evaluate(VelocityContext context) {
        RulesConfig.AmountAnomaly config = rules.current().amountAnomaly();

        List<Long> history = context.snapshot().recentAmountsMinor();
        if (history.size() < config.minHistory()) {
            return Verdict.ALLOW;
        }

        List<Long> sample = history.subList(0, Math.min(history.size(), config.sampleSize()));

        BigInteger sum = BigInteger.ZERO;
        for (Long amount : sample) {
            sum = sum.add(BigInteger.valueOf(amount));
        }
        if (sum.signum() <= 0) {
            // An all-zero history cannot produce a meaningful ratio.
            return Verdict.ALLOW;
        }

        // The test is: amount > multiplier * (sum / count).
        //
        // Written that way it needs a division, and a division needs either a
        // rounding decision or a floating point value on the money path. Both are
        // avoided by cross-multiplying instead:
        //
        //     amount * count * 10000 > multiplierBasisPoints * sum
        //
        // which is the same comparison in exact integers. BigInteger rather than long
        // because the products, unlike the amounts themselves, can exceed 64 bits.
        BigInteger left = BigInteger.valueOf(context.amountMinor())
                .multiply(BigInteger.valueOf(sample.size()))
                .multiply(BASIS_POINTS);
        BigInteger right = BigInteger.valueOf(config.multiplierBasisPoints()).multiply(sum);

        return left.compareTo(right) > 0 ? config.verdict() : Verdict.ALLOW;
    }
}
