package com.shivamsinha.payauth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "payauth.outbox")
public record OutboxProperties(String topic, Relay relay) {

    public OutboxProperties {
        if (topic == null || topic.isBlank()) {
            topic = "authorization-events";
        }
        if (relay == null) {
            relay = new Relay(true, Duration.ofMillis(500), 100);
        }
    }

    /**
     * @param batchSize how many events one relay pass may drain. Bounded because the
     *                  whole batch is published inside a single transaction, and an
     *                  unbounded batch would mean an unbounded lock hold.
     */
    public record Relay(boolean enabled, Duration fixedDelay, int batchSize) {

        public Relay {
            if (fixedDelay == null) {
                fixedDelay = Duration.ofMillis(500);
            }
            if (batchSize <= 0) {
                batchSize = 100;
            }
        }
    }
}
