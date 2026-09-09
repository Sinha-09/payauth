package com.shivamsinha.payauth.service;

import com.shivamsinha.payauth.api.dto.AuthorizationRequest;
import com.shivamsinha.payauth.api.dto.AuthorizationResponse;
import com.shivamsinha.payauth.domain.Authorization;
import com.shivamsinha.payauth.domain.AuthorizationStatus;
import com.shivamsinha.payauth.domain.ResponseCode;
import com.shivamsinha.payauth.outbox.AuthorizationEvent;
import com.shivamsinha.payauth.outbox.OutboxWriter;
import com.shivamsinha.payauth.repository.AuthorizationRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.UUID;

/**
 * The actual authorization decision and its persistence, in one transaction.
 *
 * <p>Separate bean rather than a private method on {@link AuthorizationService}
 * because it is invoked through a {@link java.util.function.Supplier} handed to
 * {@link IdempotencyService}. A private method would be a self-invocation and
 * Spring's proxy would never see it, so {@code @Transactional} would silently do
 * nothing — the classic way this pattern is got wrong.
 */
@Component
public class AuthorizationProcessor {

    private final AuthorizationRepository authorizationRepository;
    private final OutboxWriter outboxWriter;
    private final Clock clock;

    public AuthorizationProcessor(AuthorizationRepository authorizationRepository,
                                  OutboxWriter outboxWriter,
                                  Clock clock) {
        this.authorizationRepository = authorizationRepository;
        this.outboxWriter = outboxWriter;
        this.clock = clock;
    }

    /**
     * REQUIRES_NEW: this must be a transaction of its own, never joined to the
     * ledger's.
     *
     * <p>This method is the transaction boundary the outbox pattern depends on. The
     * authorization INSERT and the outbox INSERT both happen inside it, so they
     * commit together or not at all — there is no window in which the card is
     * authorized but the event is lost, or in which an event announces an
     * authorization that was rolled back.
     *
     * <p>{@link OutboxWriter#append} is {@code Propagation.MANDATORY}, which turns
     * that from a convention into something the container enforces: if this
     * annotation were removed, or the outbox write were moved out to a caller with
     * no transaction, the application would fail loudly instead of quietly
     * degrading into a dual write.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AuthorizationResponse process(AuthorizationRequest request) {
        Authorization authorization = new Authorization(
                UUID.randomUUID(),
                request.cardToken(),
                request.amountMinor(),
                request.currency(),
                request.merchantId(),
                AuthorizationStatus.APPROVED,
                ResponseCode.APPROVED,
                clock.instant());

        authorizationRepository.saveAndFlush(authorization);

        // Same transaction as the line above. That is the entire guarantee.
        outboxWriter.append(
                authorization.getId(),
                AuthorizationEvent.TYPE_AUTHORIZED,
                AuthorizationEvent.authorized(authorization));

        return AuthorizationResponse.from(authorization);
    }
}
