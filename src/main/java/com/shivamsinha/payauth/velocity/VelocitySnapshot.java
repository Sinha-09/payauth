package com.shivamsinha.payauth.velocity;

import java.util.List;

/**
 * Everything the rules are allowed to know about a card's recent history,
 * fetched in one pipelined round trip before any rule runs.
 *
 * <p>Rules receive this and nothing else. They have no access to Redis, which is
 * what makes "one round trip per authorization" a property of the design rather
 * than a thing to remember: a rule physically cannot issue its own query.
 *
 * @param recentAuthorizationCount authorizations for this card inside the velocity window
 * @param recentAmountsMinor       the card's last N amounts, newest first, in minor units
 * @param recentGeoEvents          the card's last N locations, newest first
 * @param degraded                 true when Redis could not be read and the fields above
 *                                 are empty defaults rather than facts
 */
public record VelocitySnapshot(long recentAuthorizationCount,
                               List<Long> recentAmountsMinor,
                               List<GeoEvent> recentGeoEvents,
                               boolean degraded) {

    /** The snapshot used when Redis could not be read: no facts, flagged as degraded. */
    public static VelocitySnapshot unavailable() {
        return new VelocitySnapshot(0, List.of(), List.of(), true);
    }
}
