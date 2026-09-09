package com.shivamsinha.payauth.service;

import com.shivamsinha.payauth.api.dto.AuthorizationOutcome;
import com.shivamsinha.payauth.api.dto.AuthorizationRequest;
import com.shivamsinha.payauth.api.dto.AuthorizationResponse;
import com.shivamsinha.payauth.api.exception.AuthorizationNotFoundException;
import com.shivamsinha.payauth.api.exception.InvalidStateTransitionException;
import com.shivamsinha.payauth.domain.Authorization;
import com.shivamsinha.payauth.domain.AuthorizationStatus;
import com.shivamsinha.payauth.domain.ResponseCode;
import com.shivamsinha.payauth.repository.AuthorizationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.UUID;

/**
 * PHASE 1 STUB.
 *
 * <p>Approves everything, with no idempotency and no event publication. The
 * Idempotency-Key is accepted and ignored so the API surface is already final;
 * phase 2 puts the real ledger behind it without changing the controller.
 */
@Service
public class AuthorizationService {

    private final AuthorizationRepository authorizationRepository;
    private final Clock clock;

    public AuthorizationService(AuthorizationRepository authorizationRepository, Clock clock) {
        this.authorizationRepository = authorizationRepository;
        this.clock = clock;
    }

    @Transactional
    public AuthorizationOutcome authorize(AuthorizationRequest request, String idempotencyKey) {
        Authorization authorization = new Authorization(
                UUID.randomUUID(),
                request.cardToken(),
                request.amountMinor(),
                request.currency(),
                request.merchantId(),
                AuthorizationStatus.APPROVED,
                ResponseCode.APPROVED,
                clock.instant());

        authorizationRepository.save(authorization);
        return AuthorizationOutcome.created(AuthorizationResponse.from(authorization));
    }

    @Transactional(readOnly = true)
    public AuthorizationResponse get(UUID id) {
        return AuthorizationResponse.from(load(id));
    }

    @Transactional
    public AuthorizationResponse capture(UUID id) {
        Authorization authorization = load(id);
        try {
            authorization.capture();
        } catch (IllegalStateException ex) {
            throw new InvalidStateTransitionException(ex.getMessage());
        }
        // No explicit save: the entity is managed, and the flush at commit is what
        // triggers the @Version check. If that check fails Spring translates it into
        // an OptimisticLockingFailureException, which the handler renders as 409.
        return AuthorizationResponse.from(authorization);
    }

    @Transactional
    public AuthorizationResponse voidAuthorization(UUID id) {
        Authorization authorization = load(id);
        try {
            authorization.voidAuthorization();
        } catch (IllegalStateException ex) {
            throw new InvalidStateTransitionException(ex.getMessage());
        }
        return AuthorizationResponse.from(authorization);
    }

    private Authorization load(UUID id) {
        return authorizationRepository.findById(id)
                .orElseThrow(() -> new AuthorizationNotFoundException(id));
    }
}
