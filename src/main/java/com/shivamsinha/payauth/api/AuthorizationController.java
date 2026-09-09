package com.shivamsinha.payauth.api;

import com.shivamsinha.payauth.api.dto.AuthorizationOutcome;
import com.shivamsinha.payauth.api.dto.AuthorizationRequest;
import com.shivamsinha.payauth.api.dto.AuthorizationResponse;
import com.shivamsinha.payauth.service.AuthorizationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/v1/authorizations")
@Validated
public class AuthorizationController {

    static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    private final AuthorizationService authorizationService;

    public AuthorizationController(AuthorizationService authorizationService) {
        this.authorizationService = authorizationService;
    }

    /**
     * Create an authorization.
     *
     * <p>201 when this call did the work, 200 when the caller is being handed a
     * stored response for a key that has already completed. 409 when the same key
     * arrives with a different body, or while another call holds the key.
     */
    @PostMapping
    public ResponseEntity<AuthorizationResponse> authorize(
            @RequestHeader(IDEMPOTENCY_KEY_HEADER)
            @NotBlank(message = "Idempotency-Key must not be blank")
            @Size(max = 64, message = "Idempotency-Key must be at most 64 characters")
            String idempotencyKey,
            @Valid @RequestBody AuthorizationRequest request) {

        AuthorizationOutcome outcome = authorizationService.authorize(request, idempotencyKey);

        if (outcome.replayed()) {
            return ResponseEntity.ok(outcome.response());
        }
        URI location = URI.create("/v1/authorizations/" + outcome.response().authorizationId());
        return ResponseEntity.created(location).body(outcome.response());
    }

    @GetMapping("/{id}")
    public AuthorizationResponse get(@PathVariable UUID id) {
        return authorizationService.get(id);
    }

    @PostMapping("/{id}/capture")
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.OK)
    public AuthorizationResponse capture(@PathVariable UUID id) {
        return authorizationService.capture(id);
    }

    @PostMapping("/{id}/void")
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.OK)
    public AuthorizationResponse voidAuthorization(@PathVariable UUID id) {
        return authorizationService.voidAuthorization(id);
    }
}
