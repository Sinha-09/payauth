package com.shivamsinha.payauth.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shivamsinha.payauth.api.exception.IdempotencyConflictException;
import com.shivamsinha.payauth.domain.IdempotencyRecord;
import com.shivamsinha.payauth.domain.IdempotencyStatus;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * Executes a unit of work at most once per Idempotency-Key.
 *
 * <p>Contract, from the caller's point of view:
 * <ul>
 *   <li>first call with a key → the work runs, its response is stored, {@code replayed=false}</li>
 *   <li>later call, same key, same body → the stored response, {@code replayed=true}</li>
 *   <li>same key, different body → {@link IdempotencyConflictException} REQUEST_MISMATCH</li>
 *   <li>same key while the first call is still running → CONCURRENT_REQUEST</li>
 *   <li>same key after the record expired → treated as a brand new key</li>
 * </ul>
 *
 * <p>This class holds no transaction of its own. Ownership is decided by
 * {@link IdempotencyLedger} in a committed transaction before the work starts, and
 * the work supplies its own transaction. Those two boundaries must stay separate:
 * see the class comment on the ledger.
 */
@Service
public class IdempotencyService {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);

    /**
     * How many times we go round the claim/inspect loop before giving up. A second
     * pass is only needed in the narrow window where the record we were about to
     * inspect got purged between our failed claim and our read.
     */
    private static final int MAX_CLAIM_ATTEMPTS = 3;

    private final IdempotencyLedger ledger;
    private final ObjectMapper objectMapper;
    private final Counter replayCounter;
    private final Counter conflictCounter;
    private final Counter takeoverCounter;

    public IdempotencyService(IdempotencyLedger ledger,
                              ObjectMapper objectMapper,
                              MeterRegistry meterRegistry) {
        this.ledger = ledger;
        this.objectMapper = objectMapper;
        this.replayCounter = Counter.builder("payauth.idempotency.replay")
                .description("Requests served from a stored idempotent response")
                .register(meterRegistry);
        this.conflictCounter = Counter.builder("payauth.idempotency.conflict")
                .description("Requests rejected as an idempotency conflict")
                .register(meterRegistry);
        this.takeoverCounter = Counter.builder("payauth.idempotency.takeover")
                .description("Stale in-progress claims taken over after an owner died")
                .register(meterRegistry);
    }

    /**
     * Run {@code work} at most once for {@code key}.
     *
     * @param key          the client's Idempotency-Key
     * @param requestHash  SHA-256 of the canonical request body
     * @param responseType type used to deserialize a stored response
     * @param work         the business operation; must be transactional in its own right
     */
    public <T> IdempotencyOutcome<T> executeIdempotent(String key,
                                                       String requestHash,
                                                       Class<T> responseType,
                                                       Supplier<T> work) {

        for (int attempt = 1; attempt <= MAX_CLAIM_ATTEMPTS; attempt++) {

            // 1. Try to become the owner. One atomic INSERT ... ON CONFLICT decides
            //    this for every concurrent caller at once.
            if (ledger.claim(key, requestHash)) {
                return runOwnedWork(key, responseType, work);
            }

            // 2. We lost. Someone else owns or owned this key: find out which.
            Optional<IdempotencyRecord> existing = ledger.find(key);
            if (existing.isEmpty()) {
                // Purged between our claim and our read. Go round again.
                continue;
            }
            IdempotencyRecord record = existing.get();

            // 3. Same key, different request. This is a client bug or a key
            //    collision, and it is never safe to guess which response they want.
            if (!record.getRequestHash().equals(requestHash)) {
                conflictCounter.increment();
                throw new IdempotencyConflictException(
                        IdempotencyConflictException.Reason.REQUEST_MISMATCH,
                        "Idempotency-Key '" + key + "' was already used with a different request body.");
            }

            if (record.getStatus() == IdempotencyStatus.COMPLETED) {
                // 4. The happy replay: hand back exactly what we returned the first time.
                replayCounter.increment();
                return new IdempotencyOutcome<>(deserialize(record.getResponseBody(), responseType), true);
            }

            // 5. IN_PROGRESS. Either a live caller is mid-flight, or its process died
            //    after claiming. Only the second case is ours to take.
            if (ledger.takeOverStaleClaim(key, requestHash)) {
                takeoverCounter.increment();
                log.warn("Took over stale in-progress idempotency claim for key {}", key);
                return runOwnedWork(key, responseType, work);
            }

            conflictCounter.increment();
            throw new IdempotencyConflictException(
                    IdempotencyConflictException.Reason.CONCURRENT_REQUEST,
                    "A request with Idempotency-Key '" + key + "' is already in progress. Retry shortly.");
        }

        throw new IdempotencyConflictException(
                IdempotencyConflictException.Reason.CONCURRENT_REQUEST,
                "Could not obtain a stable idempotency claim for key '" + key + "'. Retry shortly.");
    }

    /**
     * We own the key. Do the work, then record the answer.
     *
     * <p>If the work throws we release the claim rather than leaving it
     * IN_PROGRESS: nothing was persisted, so the client's retry should be allowed
     * straight through instead of being told 409 for a request that never happened.
     *
     * <p>If the work succeeds but storing the response fails, we deliberately do
     * not release: the authorization exists, and letting a retry create a second
     * one would be far worse than making the client wait out the in-progress
     * timeout.
     */
    private <T> IdempotencyOutcome<T> runOwnedWork(String key, Class<T> responseType, Supplier<T> work) {
        T result;
        try {
            result = work.get();
        } catch (RuntimeException ex) {
            ledger.release(key);
            throw ex;
        }
        ledger.complete(key, serialize(result));
        return new IdempotencyOutcome<>(result, false);
    }

    private String serialize(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Could not serialize idempotent response", ex);
        }
    }

    private <T> T deserialize(String body, Class<T> type) {
        if (body == null) {
            throw new IllegalStateException("Idempotency record is COMPLETED but has no stored response");
        }
        try {
            return objectMapper.readValue(body, type);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Could not deserialize stored idempotent response", ex);
        }
    }
}
