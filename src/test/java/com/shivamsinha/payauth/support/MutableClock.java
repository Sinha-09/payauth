package com.shivamsinha.payauth.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

/**
 * A clock the test drives by hand.
 *
 * <p>Idempotency expiry is a 24-hour window. Testing it by waiting is not an
 * option, and shortening the TTL to a second only tests a different, shorter
 * window with a sleep in it. Advancing time explicitly tests the real
 * configuration.
 */
public class MutableClock extends Clock {

    private volatile Instant instant;
    private final ZoneId zone;

    public MutableClock(Instant instant) {
        this(instant, ZoneId.of("UTC"));
    }

    private MutableClock(Instant instant, ZoneId zone) {
        this.instant = instant;
        this.zone = zone;
    }

    public void advance(Duration duration) {
        this.instant = this.instant.plus(duration);
    }

    public void setInstant(Instant newInstant) {
        this.instant = newInstant;
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId newZone) {
        return new MutableClock(instant, newZone);
    }

    @Override
    public Instant instant() {
        return instant;
    }
}
