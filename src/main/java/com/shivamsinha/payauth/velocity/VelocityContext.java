package com.shivamsinha.payauth.velocity;

import java.time.Instant;

/**
 * The authorization being judged, plus the card's recent history.
 */
public record VelocityContext(String cardToken,
                              long amountMinor,
                              String currency,
                              String merchantId,
                              String countryCode,
                              Instant now,
                              VelocitySnapshot snapshot) {
}
