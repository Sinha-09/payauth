package com.shivamsinha.payauth.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Time is injected, never read from {@code Instant.now()} inside business logic.
 * Idempotency expiry and the velocity windows are both time-dependent, and a test
 * that has to sleep for ten minutes is a test nobody runs.
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
