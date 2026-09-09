package com.shivamsinha.payauth.velocity;

import java.time.Instant;

/**
 * Where and when a card was previously used.
 *
 * @param countryCode ISO 3166-1 alpha-2
 */
public record GeoEvent(String countryCode, Instant at) {

    static final String SEPARATOR = "|";

    public String encode() {
        return countryCode + SEPARATOR + at.toEpochMilli();
    }

    public static GeoEvent decode(String encoded) {
        int separator = encoded.lastIndexOf(SEPARATOR);
        if (separator < 0) {
            throw new IllegalArgumentException("Malformed geo entry: " + encoded);
        }
        return new GeoEvent(
                encoded.substring(0, separator),
                Instant.ofEpochMilli(Long.parseLong(encoded.substring(separator + 1))));
    }
}
