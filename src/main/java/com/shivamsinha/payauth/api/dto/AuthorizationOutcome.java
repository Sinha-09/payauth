package com.shivamsinha.payauth.api.dto;

/**
 * What the service decided, plus whether the caller is seeing a stored replay.
 *
 * <p>The controller needs this distinction to choose 201 (we did the work now)
 * versus 200 (you are being handed the result of work already done).
 */
public record AuthorizationOutcome(AuthorizationResponse response, boolean replayed) {

    public static AuthorizationOutcome created(AuthorizationResponse response) {
        return new AuthorizationOutcome(response, false);
    }

    public static AuthorizationOutcome replayed(AuthorizationResponse response) {
        return new AuthorizationOutcome(response, true);
    }
}
