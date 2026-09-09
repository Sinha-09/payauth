package com.shivamsinha.payauth.velocity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The card's recent history, in Redis.
 *
 * <p>Both the read and the write are single pipelined round trips. On the
 * authorization path a round trip is the expensive part, not the command, so three
 * sequential reads would triple the fraud check's contribution to p99 latency for
 * no benefit.
 *
 * <p>Keys are namespaced per card and every one of them carries a TTL. A fraud
 * store that only grows is a fraud store that eventually gets turned off.
 */
@Repository
public class VelocityRedisRepository {

    private static final Logger log = LoggerFactory.getLogger(VelocityRedisRepository.class);

    private static final String KEY_PREFIX = "payauth:velocity:card:";
    private static final int GEO_HISTORY_SIZE = 10;
    /** Amount history outlives any velocity window: the rolling average is a profile, not a burst. */
    private static final Duration AMOUNT_HISTORY_TTL = Duration.ofDays(30);

    private final StringRedisTemplate redis;

    public VelocityRedisRepository(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * Read everything the rules need, in one round trip.
     *
     * <p>Throws on any Redis failure. Deciding what to do about that is
     * {@link RuleEngine}'s job, not this class's — the repository's contract is
     * "these are the facts", and inventing empty facts here would hide an outage
     * from the layer responsible for reacting to it.
     */
    public VelocitySnapshot loadSnapshot(String cardToken, Instant now, RulesConfig config) {
        byte[] authsKey = key(cardToken, "auths");
        byte[] amountsKey = key(cardToken, "amounts");
        byte[] geoKey = key(cardToken, "geo");

        long velocityWindowStart = now.minusSeconds(config.cardVelocity().windowSeconds()).toEpochMilli();
        int sampleSize = config.amountAnomaly().sampleSize();

        List<Object> results = redis.executePipelined((RedisConnection connection) -> {
            connection.zSetCommands().zCount(authsKey, velocityWindowStart, Double.POSITIVE_INFINITY);
            connection.listCommands().lRange(amountsKey, 0, sampleSize - 1L);
            connection.listCommands().lRange(geoKey, 0, GEO_HISTORY_SIZE - 1L);
            return null;
        });

        long authorizationCount = results.get(0) instanceof Number count ? count.longValue() : 0L;
        List<Long> amounts = parseAmounts(results.get(1));
        List<GeoEvent> geoEvents = parseGeoEvents(results.get(2));

        return new VelocitySnapshot(authorizationCount, amounts, geoEvents, false);
    }

    /**
     * Record this authorization against the card, in one round trip.
     *
     * <p>Called after the decision, so a slow write never delays the response the
     * cardholder is waiting on.
     */
    public void record(String cardToken,
                       UUID authorizationId,
                       long amountMinor,
                       String countryCode,
                       Instant now,
                       RulesConfig config) {

        byte[] authsKey = key(cardToken, "auths");
        byte[] amountsKey = key(cardToken, "amounts");
        byte[] geoKey = key(cardToken, "geo");

        long nowMillis = now.toEpochMilli();
        int velocityWindowSeconds = config.cardVelocity().windowSeconds();
        int travelWindowSeconds = config.impossibleTravel().windowSeconds();
        int sampleSize = config.amountAnomaly().sampleSize();

        redis.executePipelined((RedisConnection connection) -> {
            connection.zSetCommands().zAdd(authsKey, nowMillis, bytes(authorizationId.toString()));
            // Trim the window on write rather than sweeping it on a timer: the set can
            // never hold more than one window's worth of members.
            connection.zSetCommands().zRemRangeByScore(authsKey, 0, nowMillis - (velocityWindowSeconds * 1000L));
            connection.keyCommands().expire(authsKey, velocityWindowSeconds * 2L);

            connection.listCommands().lPush(amountsKey, bytes(Long.toString(amountMinor)));
            connection.listCommands().lTrim(amountsKey, 0, sampleSize - 1L);
            connection.keyCommands().expire(amountsKey, AMOUNT_HISTORY_TTL.toSeconds());

            if (CountryGeography.isKnown(countryCode)) {
                connection.listCommands().lPush(geoKey, bytes(new GeoEvent(countryCode, now).encode()));
                connection.listCommands().lTrim(geoKey, 0, GEO_HISTORY_SIZE - 1L);
                connection.keyCommands().expire(geoKey, travelWindowSeconds * 2L);
            }
            return null;
        });
    }

    private List<Long> parseAmounts(Object raw) {
        if (!(raw instanceof List<?> values)) {
            return List.of();
        }
        List<Long> amounts = new ArrayList<>(values.size());
        for (Object value : values) {
            try {
                amounts.add(Long.parseLong(String.valueOf(value)));
            } catch (NumberFormatException ex) {
                log.warn("Skipping unparseable amount in velocity history: {}", value);
            }
        }
        return List.copyOf(amounts);
    }

    private List<GeoEvent> parseGeoEvents(Object raw) {
        if (!(raw instanceof List<?> values)) {
            return List.of();
        }
        List<GeoEvent> events = new ArrayList<>(values.size());
        for (Object value : values) {
            try {
                events.add(GeoEvent.decode(String.valueOf(value)));
            } catch (RuntimeException ex) {
                log.warn("Skipping unparseable geo entry in velocity history: {}", value);
            }
        }
        return List.copyOf(events);
    }

    private byte[] key(String cardToken, String suffix) {
        return bytes(KEY_PREFIX + cardToken + ":" + suffix);
    }

    private byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
