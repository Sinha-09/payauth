package com.shivamsinha.payauth.service;

import com.shivamsinha.payauth.api.dto.AuthorizationRequest;
import com.shivamsinha.payauth.api.dto.AuthorizationResponse;
import com.shivamsinha.payauth.domain.Authorization;
import com.shivamsinha.payauth.domain.AuthorizationStatus;
import com.shivamsinha.payauth.domain.ResponseCode;
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
    private final Clock clock;

    public AuthorizationProcessor(AuthorizationRepository authorizationRepository, Clock clock) {
        this.authorizationRepository = authorizationRepository;
        this.clock = clock;
    }

    /**
     * REQUIRES_NEW: this must be a transaction of its own, never joined to the
     * ledger's. Phase 3 adds the outbox insert here, and the guarantee that matters
     * is that the authorization row and its event row share exactly this boundary.
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
        return AuthorizationResponse.from(authorization);
    }
}
