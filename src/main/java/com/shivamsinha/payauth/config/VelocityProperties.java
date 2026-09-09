package com.shivamsinha.payauth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * @param rulesFile       Spring resource location of the rule definitions. A
 *                        {@code file:} location is what makes hot reload useful in
 *                        production; {@code classpath:} is the packaged default.
 * @param refreshInterval how often that file is re-read
 */
@ConfigurationProperties(prefix = "payauth.velocity")
public record VelocityProperties(boolean enabled,
                                 String rulesFile,
                                 Duration refreshInterval,
                                 Duration redisTimeout) {

    public VelocityProperties {
        if (rulesFile == null || rulesFile.isBlank()) {
            rulesFile = "classpath:rules.yml";
        }
        if (refreshInterval == null) {
            refreshInterval = Duration.ofSeconds(10);
        }
        if (redisTimeout == null) {
            redisTimeout = Duration.ofMillis(200);
        }
    }
}
