package com.shivamsinha.payauth.console;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Read models for the console. Separate from the API DTOs on purpose: these can
 * change freely to make a demo clearer without touching the contract real clients
 * depend on.
 */
public final class ConsoleView {

    private ConsoleView() {
    }

    public record Summary(long authorizations,
                          long approved,
                          long declined,
                          long captured,
                          long voided,
                          long idempotencyKeys,
                          long outboxTotal,
                          long outboxUnpublished,
                          double eventsProcessed,
                          double eventsDuplicate,
                          double idempotencyReplays,
                          double idempotencyConflicts,
                          double velocityDeclines,
                          double velocityReviews,
                          double velocityFailOpen) {
    }

    public record AuthorizationRow(UUID id,
                                   String cardToken,
                                   long amountMinor,
                                   String currency,
                                   String merchantId,
                                   String status,
                                   String responseCode,
                                   long version,
                                   Instant createdAt) {
    }

    public record OutboxRow(long id,
                            UUID aggregateId,
                            String eventType,
                            boolean published,
                            Instant createdAt,
                            Instant publishedAt) {
    }

    /**
     * A dry run of the rule engine: what would happen to this authorization, without
     * creating one. This is the endpoint that makes the fraud layer legible.
     */
    public record VelocityView(String cardToken,
                               long amountMinorTested,
                               String countryCodeTested,
                               long recentAuthorizationCount,
                               List<Long> recentAmountsMinor,
                               List<Map<String, String>> recentGeoEvents,
                               long rollingAverageMinor,
                               Map<String, String> perRuleVerdict,
                               String verdict,
                               boolean degraded) {
    }
}
