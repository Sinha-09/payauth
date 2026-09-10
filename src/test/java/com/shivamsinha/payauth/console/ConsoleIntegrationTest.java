package com.shivamsinha.payauth.console;

import com.shivamsinha.payauth.api.dto.AuthorizationRequest;
import com.shivamsinha.payauth.api.dto.AuthorizationResponse;
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

class ConsoleIntegrationTest extends AbstractIntegrationTest {

    @Test
    @DisplayName("The summary reflects what the system actually holds")
    void summaryCountsRealRows() {
        authorize("tok_" + UUID.randomUUID(), 1000L);
        authorize("tok_" + UUID.randomUUID(), 2000L);

        ConsoleView.Summary summary = get("/v1/console/summary", ConsoleView.Summary.class);

        assertThat(summary.authorizations()).isEqualTo(2);
        assertThat(summary.approved()).isEqualTo(2);
        assertThat(summary.idempotencyKeys()).isEqualTo(2);
        assertThat(summary.outboxTotal()).isEqualTo(2);
    }

    @Test
    @DisplayName("Recent authorizations come back newest first")
    void authorizationsAreListed() {
        authorize("tok_first", 1000L);
        authorize("tok_second", 2000L);

        ConsoleView.AuthorizationRow[] rows =
                get("/v1/console/authorizations?limit=10", ConsoleView.AuthorizationRow[].class);

        assertThat(rows).hasSize(2);
        assertThat(rows[0].cardToken()).isEqualTo("tok_second");
    }

    @Test
    @DisplayName("The velocity dry run reports per-rule verdicts and creates nothing")
    void velocityDryRunHasNoSideEffects() {
        String card = "tok_" + UUID.randomUUID();
        authorize(card, 1000L);

        long before = get("/v1/console/summary", ConsoleView.Summary.class).authorizations();

        ConsoleView.VelocityView view = get(
                "/v1/console/velocity?cardToken=" + card + "&amountMinor=5000&countryCode=IN",
                ConsoleView.VelocityView.class);

        assertThat(view.verdict()).isEqualTo("ALLOW");
        assertThat(view.perRuleVerdict()).containsKeys("card-velocity", "amount-anomaly", "impossible-travel");
        assertThat(view.recentAuthorizationCount()).isEqualTo(1);
        assertThat(view.degraded()).isFalse();

        // The dry run must not have authorized anything.
        assertThat(get("/v1/console/summary", ConsoleView.Summary.class).authorizations()).isEqualTo(before);
    }

    @Test
    @DisplayName("The console reports the rules currently in force")
    void rulesAreVisible() {
        ResponseEntity<String> response =
                restTemplate.exchange(url("/v1/console/rules"), HttpMethod.GET, null, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("cardVelocity").contains("maxAuthorizations");
    }

    @Test
    @DisplayName("Outbox rows are visible with their published state")
    void outboxIsVisible() {
        authorize("tok_" + UUID.randomUUID(), 1000L);

        ConsoleView.OutboxRow[] rows = get("/v1/console/outbox?limit=10", ConsoleView.OutboxRow[].class);

        assertThat(rows).hasSize(1);
        assertThat(rows[0].eventType()).isEqualTo("authorization.authorized");
    }

    private <T> T get(String path, Class<T> type) {
        return restTemplate.exchange(url(path), HttpMethod.GET, null, type).getBody();
    }

    private void authorize(String cardToken, long amountMinor) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", "key-" + UUID.randomUUID());
        ResponseEntity<AuthorizationResponse> response = restTemplate.exchange(
                url("/v1/authorizations"), HttpMethod.POST,
                new HttpEntity<>(new AuthorizationRequest(cardToken, amountMinor, "INR", "mrc_acme", "IN"), headers),
                AuthorizationResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }
}
