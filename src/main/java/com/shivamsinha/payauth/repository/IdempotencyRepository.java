package com.shivamsinha.payauth.repository;

import com.shivamsinha.payauth.domain.IdempotencyRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;

@Repository
public interface IdempotencyRepository extends JpaRepository<IdempotencyRecord, String> {

    /**
     * Atomically claim a key.
     *
     * <p>Returns 1 if this caller won the claim, 0 if the key already exists.
     * There is deliberately no read-then-write here: {@code ON CONFLICT DO NOTHING}
     * makes the decision inside a single statement, so 50 concurrent callers with
     * the same key produce exactly one winner without any of them taking a row
     * lock and holding it for the duration of the downstream work.
     *
     * <p>The {@code WHERE expires_at < now()} branch on the conflict target is what
     * makes an expired key behave like a brand new one: rather than a second insert
     * (which the primary key forbids) we overwrite the dead row in place.
     */
    @Modifying
    @Query(value = """
            INSERT INTO idempotency_key (key, request_hash, response_body, status, created_at, expires_at)
            VALUES (:key, :requestHash, NULL, 'IN_PROGRESS', :now, :expiresAt)
            ON CONFLICT (key) DO UPDATE
                SET request_hash  = EXCLUDED.request_hash,
                    response_body = NULL,
                    status        = 'IN_PROGRESS',
                    created_at    = EXCLUDED.created_at,
                    expires_at    = EXCLUDED.expires_at
                WHERE idempotency_key.expires_at < :now
            """, nativeQuery = true)
    int tryClaim(@Param("key") String key,
                 @Param("requestHash") String requestHash,
                 @Param("now") Instant now,
                 @Param("expiresAt") Instant expiresAt);

    /**
     * Store the final response and flip the record to COMPLETED.
     *
     * <p>Written as a native statement so the JSONB cast is explicit and so the
     * update does not depend on the entity being managed in the current
     * persistence context.
     */
    @Modifying
    @Query(value = """
            UPDATE idempotency_key
               SET response_body = CAST(:responseBody AS jsonb),
                   status        = 'COMPLETED'
             WHERE key = :key
            """, nativeQuery = true)
    int complete(@Param("key") String key, @Param("responseBody") String responseBody);

    /**
     * Release a claim whose work failed, so the caller can retry immediately
     * instead of waiting out the TTL.
     */
    @Modifying
    @Query(value = "DELETE FROM idempotency_key WHERE key = :key AND status = 'IN_PROGRESS'",
            nativeQuery = true)
    int releaseInProgress(@Param("key") String key);

    @Modifying
    @Query(value = "DELETE FROM idempotency_key WHERE expires_at < :now", nativeQuery = true)
    int deleteExpired(@Param("now") Instant now);
}
