package com.shivamsinha.payauth.api;

import com.shivamsinha.payauth.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The error surface of the API.
 *
 * <p>These exist because a catch-all {@code @ExceptionHandler(Exception.class)} will
 * happily turn anything it was not told about into a 500. Every status below was
 * once a 500, and the only way to keep them honest is to assert them.
 */
class ApiErrorIntegrationTest extends AbstractIntegrationTest {

    @Test
    @DisplayName("The root path serves the demo console")
    void rootServesTheConsole() {
        ResponseEntity<String> response =
                restTemplate.exchange(url("/"), HttpMethod.GET, null, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("payauth console");
    }

    @Test
    @DisplayName("An unmapped path is 404, not 500")
    void unmappedPathIsNotFound() {
        // "/" is deliberately absent: it serves the console. Everything else that is
        // not mapped must be a 404 rather than falling through to the catch-all.
        for (String path : new String[]{"/nope", "/favicon.ico", "/v1", "/v1/authorization"}) {
            ResponseEntity<String> response =
                    restTemplate.exchange(url(path), HttpMethod.GET, null, String.class);

            assertThat(response.getStatusCode())
                    .as("GET %s", path)
                    .isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(response.getBody()).contains("no-such-endpoint");
        }
    }

    @Test
    @DisplayName("A known path with the wrong verb is 405 and says which verbs work")
    void wrongMethodIsMethodNotAllowed() {
        ResponseEntity<String> response =
                restTemplate.exchange(url("/v1/authorizations"), HttpMethod.DELETE, null, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(response.getBody()).contains("method-not-allowed").contains("POST");
        assertThat(response.getHeaders().getAllow()).contains(HttpMethod.POST);
    }

    @Test
    @DisplayName("A non-JSON body is 415, not 500")
    void wrongContentTypeIsUnsupportedMediaType() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.TEXT_PLAIN);
        headers.set("Idempotency-Key", "key-" + UUID.randomUUID());

        ResponseEntity<String> response = restTemplate.exchange(url("/v1/authorizations"),
                HttpMethod.POST, new HttpEntity<>("not json", headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        assertThat(response.getBody()).contains("unsupported-media-type");
    }

    @Test
    @DisplayName("A malformed path variable is 400, not 500")
    void malformedUuidIsBadRequest() {
        ResponseEntity<String> response =
                restTemplate.exchange(url("/v1/authorizations/not-a-uuid"), HttpMethod.GET, null, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("malformed-request");
    }

    @Test
    @DisplayName("Unparseable JSON is 400, not 500")
    void malformedJsonIsBadRequest() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", "key-" + UUID.randomUUID());

        ResponseEntity<String> response = restTemplate.exchange(url("/v1/authorizations"),
                HttpMethod.POST, new HttpEntity<>("{ this is not json", headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("malformed-request");
    }

    @Test
    @DisplayName("A blank Idempotency-Key is rejected, not treated as a key")
    void blankIdempotencyKeyIsRejected() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", "   ");

        ResponseEntity<String> response = restTemplate.exchange(url("/v1/authorizations"),
                HttpMethod.POST,
                new HttpEntity<>("""
                        {"cardToken":"tok_1","amountMinor":1000,"currency":"INR","merchantId":"m1"}
                        """, headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("validation-failed");
    }

    @Test
    @DisplayName("An unknown authorization id is 404")
    void unknownAuthorizationIsNotFound() {
        ResponseEntity<String> response = restTemplate.exchange(
                url("/v1/authorizations/" + UUID.randomUUID()), HttpMethod.GET, null, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).contains("authorization-not-found");
    }

    @Test
    @DisplayName("Every error is problem+json with a stable type URI")
    void everyErrorIsProblemJson() {
        ResponseEntity<String> response =
                restTemplate.exchange(url("/nope"), HttpMethod.GET, null, String.class);

        assertThat(response.getHeaders().getContentType())
                .isNotNull()
                .satisfies(type -> assertThat(type.toString()).startsWith("application/problem+json"));
        assertThat(response.getBody())
                .contains("\"type\":\"https://payauth.shivamsinha.com/problems/")
                .contains("\"status\":404")
                .contains("\"instance\":\"/nope\"");
    }

    @Test
    @DisplayName("The health endpoint is reachable and reports UP")
    void healthIsUp() {
        ResponseEntity<String> response =
                restTemplate.exchange(url("/actuator/health"), HttpMethod.GET, null, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"status\":\"UP\"");
    }
}
