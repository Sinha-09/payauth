package com.shivamsinha.payauth.api.exception;

/**
 * The Idempotency-Key was reused in a way we cannot honour:
 * either the body differs from the original request for that key, or another
 * caller is still in flight with it.
 */
public class IdempotencyConflictException extends RuntimeException {

    public enum Reason {
        /** Same key, different canonical request body. */
        REQUEST_MISMATCH,
        /** Same key, a concurrent call is still IN_PROGRESS. */
        CONCURRENT_REQUEST
    }

    private final Reason reason;

    public IdempotencyConflictException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}
