package com.shivamsinha.payauth.api.exception;

import java.util.UUID;

public class AuthorizationNotFoundException extends RuntimeException {

    private final UUID authorizationId;

    public AuthorizationNotFoundException(UUID authorizationId) {
        super("No authorization with id " + authorizationId);
        this.authorizationId = authorizationId;
    }

    public UUID getAuthorizationId() {
        return authorizationId;
    }
}
