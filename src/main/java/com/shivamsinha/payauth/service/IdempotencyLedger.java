package com.shivamsinha.payauth.service;

import com.shivamsinha.payauth.config.IdempotencyProperties;
import com.shivamsinha.payauth.domain.IdempotencyRecord;
import com.shivamsinha.payauth.repository.IdempotencyRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

/**
 * The durable side of idempotency, deliberately separated from
 * {@link IdempotencyService} so that every method here runs in its own committed
 * transaction.
 *
 * <p>This separation is the whole trick. The claim has to be visible to other
 * processes <em>before</em> the business work starts, which means it must be in a
 * transaction that has already committed. If the claim shared a transaction with
 * the authorization insert, a second caller with the same key would block on the
 * uncommitted row for the entire duration of the downstream call — under load
 * that is a connection-pool outage dressed up as correctness.
 *
 * <p>{@code REQUIRES_NEW} rather than {@code REQUIRED} because Spring's
 * self-invocation rules mean an inner {@code @Transactional} call only gets its
 * own boundary if it crosses a proxy; this is a separate bean for exactly that
 * reason.
 */
@Component
public class IdempotencyLedger {

    private final IdempotencyRepository repository;
    private final IdempotencyProperties properties;
    private final Clock clock;

    public IdempotencyLedger(IdempotencyRepository repository,
                             IdempotencyProperties properties,
                             Clock clock) {
        this.repository = repository;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Try to become the single owner of this key.
     *
     * @return true if this caller owns the key and should do the work
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean claim(String key, String requestHash) {
        Instant now = clock.instant();
        return repository.tryClaim(key, requestHash, now, now.plus(properties.ttl())) == 1;
    }

    /**
     * Take over a claim whose owner appears to have died.
     *
     * <p>Bounded by {@code inProgressTimeout}: a claim younger than that is assumed
     * to belong to a live caller and is left alone.
     *
     * @return true if this caller has taken ownership
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean takeOverStaleClaim(String key, String requestHash) {
        Instant now = clock.instant();
        Instant staleBefore = now.minus(properties.inProgressTimeout());
        return repository.takeOverStale(key, requestHash, staleBefore, now, now.plus(properties.ttl())) == 1;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Optional<IdempotencyRecord> find(String key) {
        return repository.findById(key);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void complete(String key, String responseBody) {
        repository.complete(key, responseBody);
    }

    /**
     * Give the key back after failed work, so the client's retry is not punished
     * with a 409 for something that never happened.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void release(String key) {
        repository.releaseInProgress(key);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int purgeExpired() {
        return repository.deleteExpired(clock.instant());
    }
}
