package com.shivamsinha.payauth.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Instant;

/**
 * Replaces the application {@code Clock} with one the test controls.
 *
 * <p>{@link MutableClock} is itself a {@code Clock}, so marking this single bean
 * {@code @Primary} is enough for every injection point to pick it over
 * {@code ClockConfig#clock()}.
 */
@TestConfiguration
public class MutableClockTestConfiguration {

    @Bean
    @Primary
    public MutableClock mutableClock() {
        return new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
    }
}
