package com.shivamsinha.payauth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * @param ttl               how long a COMPLETED key stays replayable
 * @param inProgressTimeout how long an IN_PROGRESS claim is respected before another
 *                          caller is allowed to take it over. Without this, a process
 *                          that dies between claiming a key and storing its response
 *                          would poison that key for the whole TTL.
 */
@ConfigurationProperties(prefix = "payauth.idempotency")
public record IdempotencyProperties(Duration ttl, Duration inProgressTimeout) {

    public IdempotencyProperties {
        if (ttl == null) {
            ttl = Duration.ofHours(24);
        }
        if (inProgressTimeout == null) {
            inProgressTimeout = Duration.ofSeconds(30);
        }
    }
}
