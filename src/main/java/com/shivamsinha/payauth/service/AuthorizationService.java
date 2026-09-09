package com.shivamsinha.payauth.service;

import com.shivamsinha.payauth.api.dto.AuthorizationOutcome;
import com.shivamsinha.payauth.api.dto.AuthorizationRequest;
import com.shivamsinha.payauth.api.dto.AuthorizationResponse;
import com.shivamsinha.payauth.api.exception.AuthorizationNotFoundException;
import com.shivamsinha.payauth.api.exception.InvalidStateTransitionException;
import com.shivamsinha.payauth.domain.Authorization;
import com.shivamsinha.payauth.repository.AuthorizationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class AuthorizationService {

    private final AuthorizationRepository authorizationRepository;
    private final AuthorizationProcessor processor;
    private final IdempotencyService idempotencyService;
    private final CanonicalRequestHasher hasher;

    public AuthorizationService(AuthorizationRepository authorizationRepository,
                                AuthorizationProcessor processor,
                                IdempotencyService idempotencyService,
                                CanonicalRequestHasher hasher) {
        this.authorizationRepository = authorizationRepository;
        this.processor = processor;
        this.idempotencyService = idempotencyService;
        this.hasher = hasher;
    }

    /**
     * Deliberately not {@code @Transactional}.
     *
     * <p>An outer transaction here would swallow the ledger's REQUIRES_NEW
     * boundaries into one long-lived unit of work, which is precisely the design we
     * are avoiding: the claim has to be committed and visible before the
     * authorization is attempted.
     */
    public AuthorizationOutcome authorize(AuthorizationRequest request, String idempotencyKey) {
        String requestHash = hasher.hash(request);

        IdempotencyOutcome<AuthorizationResponse> outcome = idempotencyService.executeIdempotent(
                idempotencyKey,
                requestHash,
                AuthorizationResponse.class,
                () -> processor.process(request));

        return outcome.replayed()
                ? AuthorizationOutcome.replayed(outcome.value())
                : AuthorizationOutcome.created(outcome.value());
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
        // triggers the @Version check. A stale version becomes an
        // OptimisticLockingFailureException, which the handler renders as 409.
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
