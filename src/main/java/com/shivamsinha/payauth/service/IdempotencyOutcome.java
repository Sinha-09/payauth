package com.shivamsinha.payauth.service;

/**
 * @param value    the result the caller should be handed
 * @param replayed true when the value came out of the ledger rather than being
 *                 computed by this call
 */
public record IdempotencyOutcome<T>(T value, boolean replayed) {
}
