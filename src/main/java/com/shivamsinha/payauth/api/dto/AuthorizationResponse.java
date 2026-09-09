package com.shivamsinha.payauth.api.dto;

import com.shivamsinha.payauth.domain.Authorization;

import java.util.UUID;

public record AuthorizationResponse(UUID authorizationId, String status, String responseCode) {

    public static AuthorizationResponse from(Authorization authorization) {
        return new AuthorizationResponse(
                authorization.getId(),
                authorization.getStatus().name(),
                authorization.getResponseCode());
    }
}
