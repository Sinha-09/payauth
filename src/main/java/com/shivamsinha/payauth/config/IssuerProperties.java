package com.shivamsinha.payauth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Stand-in for the issuer this service would really call.
 *
 * <p>There is no balance model here, and pretending otherwise would be worse than
 * being explicit: a single configured ceiling is enough to exercise the
 * insufficient-funds path end to end without inventing an account ledger the
 * project does not have.
 *
 * @param declineAboveMinor amounts strictly above this are answered with ISO 8583
 *                          response code 51
 */
@ConfigurationProperties(prefix = "payauth.issuer")
public record IssuerProperties(long declineAboveMinor) {

    public IssuerProperties {
        if (declineAboveMinor <= 0) {
            declineAboveMinor = 10_000_000L;
        }
    }
}
