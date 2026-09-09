package com.shivamsinha.payauth.outbox;

import com.shivamsinha.payauth.domain.Authorization;

import java.time.Instant;
import java.util.UUID;

/**
 * The event published when an authorization is decided.
 *
 * <p>{@code eventId} is what makes redelivery safe for consumers: the relay
 * guarantees at-least-once, so a consumer that has already seen an eventId can
 * drop the duplicate without having to reason about the payload.
 *
 * <p>{@code amountMinor} stays a long all the way onto the wire. Serializing it as
 * a decimal would reintroduce, at the integration boundary, exactly the rounding
 * problem minor units exist to prevent.
 */
public record AuthorizationEvent(
        UUID eventId,
        UUID authorizationId,
        String cardToken,
        long amountMinor,
        String currency,
        String merchantId,
        String status,
        String responseCode,
        Instant occurredAt) {

    public static final String TYPE_AUTHORIZED = "authorization.authorized";

    public static AuthorizationEvent authorized(Authorization authorization) {
        return new AuthorizationEvent(
                UUID.randomUUID(),
                authorization.getId(),
                authorization.getCardToken(),
                authorization.getAmountMinor(),
                authorization.getCurrency(),
                authorization.getMerchantId(),
                authorization.getStatus().name(),
                authorization.getResponseCode(),
                authorization.getCreatedAt());
    }
}
