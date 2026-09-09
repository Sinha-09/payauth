package com.shivamsinha.payauth.velocity;

import com.shivamsinha.payauth.api.dto.AuthorizationRequest;
import com.shivamsinha.payauth.api.dto.AuthorizationResponse;
import com.shivamsinha.payauth.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The fraud layer end to end, against a real Redis.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "payauth.issuer.decline-above-minor=1000000"
})
class VelocityIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private VelocityRedisRepository velocityRepository;

    @Autowired
    private RuleEngine ruleEngine;

    @Test
    @DisplayName("A card past its velocity limit is declined with 05")
    void cardVelocityDeclines() {
        String card = "tok_" + UUID.randomUUID();

        // rules.yml ships max-authorizations: 5, so the first five are fine.
        for (int i = 1; i <= 5; i++) {
            ResponseEntity<AuthorizationResponse> response = post(card, 1000L, "IN");
            assertThat(response.getStatusCode()).as("authorization %d", i).isEqualTo(HttpStatus.CREATED);
            assertThat(response.getBody().status()).as("authorization %d", i).isEqualTo("APPROVED");
        }

        ResponseEntity<AuthorizationResponse> sixth = post(card, 1000L, "IN");

        // Still 201: the request was processed and an authorization record exists.
        // The decline is in the payload, which is how card authorization works — a
        // declined authorization is a successful API call with a bad answer.
        assertThat(sixth.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(sixth.getBody().status()).isEqualTo("DECLINED");
        assertThat(sixth.getBody().responseCode()).isEqualTo("05");
    }

    @Test
    @DisplayName("Velocity is per card, so one hot card does not decline another")
    void velocityIsScopedToTheCard() {
        String hot = "tok_" + UUID.randomUUID();
        String cold = "tok_" + UUID.randomUUID();

        for (int i = 0; i < 6; i++) {
            post(hot, 1000L, "IN");
        }

        ResponseEntity<AuthorizationResponse> other = post(cold, 1000L, "IN");

        assertThat(other.getBody().status()).isEqualTo("APPROVED");
        assertThat(other.getBody().responseCode()).isEqualTo("00");
    }

    @Test
    @DisplayName("The same card in two distant countries within the window is declined")
    void impossibleTravelDeclines() {
        String card = "tok_" + UUID.randomUUID();

        assertThat(post(card, 1000L, "IN").getBody().status()).isEqualTo("APPROVED");

        ResponseEntity<AuthorizationResponse> fromUs = post(card, 1000L, "US");

        assertThat(fromUs.getBody().status()).isEqualTo("DECLINED");
        assertThat(fromUs.getBody().responseCode()).isEqualTo("05");
    }

    @Test
    @DisplayName("An anomalous amount is flagged for review but still approved")
    void amountAnomalyApprovesButFlags() {
        String card = "tok_" + UUID.randomUUID();
        Instant now = Instant.now();
        RulesConfig config = RulesConfig.defaults();

        // History is seeded five minutes back on purpose. Posting five authorizations
        // through the API would also fill the 60s velocity window, and card-velocity
        // would then DECLINE — which would prove nothing about the anomaly rule,
        // since the engine takes the strictest verdict.
        for (int i = 0; i < 5; i++) {
            velocityRepository.record(card, UUID.randomUUID(), 1000L, "IN", now.minusSeconds(300), config);
        }

        RuleEvaluation evaluation = ruleEngine.evaluate(card, 100_000L, "INR", "mrc_acme", "IN");

        assertThat(evaluation.perRule()).containsEntry("card-velocity", Verdict.ALLOW);
        assertThat(evaluation.perRule()).containsEntry("amount-anomaly", Verdict.REVIEW);
        assertThat(evaluation.verdict()).isEqualTo(Verdict.REVIEW);
        assertThat(evaluation.degraded()).isFalse();

        // And REVIEW approves: the cardholder is not refused for buying something big.
        ResponseEntity<AuthorizationResponse> response = post(card, 100_000L, "IN");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().status()).isEqualTo("APPROVED");
        assertThat(response.getBody().responseCode()).isEqualTo("00");
    }

    @Test
    @DisplayName("An amount above the issuer ceiling is declined with 51")
    void issuerCeilingDeclinesWithInsufficientFunds() {
        ResponseEntity<AuthorizationResponse> response =
                post("tok_" + UUID.randomUUID(), 1_000_001L, "IN");

        assertThat(response.getBody().status()).isEqualTo("DECLINED");
        assertThat(response.getBody().responseCode()).isEqualTo("51");
    }

    @Test
    @DisplayName("The snapshot is what Redis actually holds")
    void snapshotReflectsRecordedHistory() {
        String card = "tok_" + UUID.randomUUID();
        RulesConfig config = RulesConfig.defaults();
        Instant now = Instant.now();

        velocityRepository.record(card, UUID.randomUUID(), 1000L, "IN", now.minusSeconds(30), config);
        velocityRepository.record(card, UUID.randomUUID(), 2000L, "US", now.minusSeconds(10), config);

        VelocitySnapshot snapshot = velocityRepository.loadSnapshot(card, now, config);

        assertThat(snapshot.degraded()).isFalse();
        assertThat(snapshot.recentAuthorizationCount()).isEqualTo(2);
        assertThat(snapshot.recentAmountsMinor()).containsExactly(2000L, 1000L);
        assertThat(snapshot.recentGeoEvents()).extracting(GeoEvent::countryCode).containsExactly("US", "IN");
    }

    @Test
    @DisplayName("Entries older than the velocity window drop out of the count")
    void windowSlides() {
        String card = "tok_" + UUID.randomUUID();
        RulesConfig config = RulesConfig.defaults();
        Instant now = Instant.now();

        // Recorded as if it happened two minutes ago, well outside the 60s window.
        velocityRepository.record(card, UUID.randomUUID(), 1000L, "IN", now.minusSeconds(120), config);
        velocityRepository.record(card, UUID.randomUUID(), 1000L, "IN", now.minusSeconds(5), config);

        VelocitySnapshot snapshot = velocityRepository.loadSnapshot(card, now, config);

        assertThat(snapshot.recentAuthorizationCount()).isEqualTo(1);
        // The amount history is a profile, not a window, so both amounts remain.
        assertThat(snapshot.recentAmountsMinor()).hasSize(2);
    }

    @Test
    @DisplayName("An unseen card has an empty, non-degraded snapshot")
    void unknownCardSnapshot() {
        VelocitySnapshot snapshot = velocityRepository.loadSnapshot(
                "tok_" + UUID.randomUUID(), Instant.now(), RulesConfig.defaults());

        assertThat(snapshot.degraded()).isFalse();
        assertThat(snapshot.recentAuthorizationCount()).isZero();
        assertThat(snapshot.recentAmountsMinor()).isEmpty();
        assertThat(snapshot.recentGeoEvents()).isEmpty();
    }

    @Test
    @DisplayName("A declined authorization is still recorded and still counts")
    void declinedAuthorizationsCount() {
        String card = "tok_" + UUID.randomUUID();
        for (int i = 0; i < 6; i++) {
            post(card, 1000L, "IN");
        }

        VelocitySnapshot snapshot = velocityRepository.loadSnapshot(
                card, Instant.now(), RulesConfig.defaults());

        assertThat(snapshot.recentAuthorizationCount())
                .as("the declined attempt is history too")
                .isEqualTo(6);
    }

    @Test
    @DisplayName("Every rule reports a verdict for a normal authorization")
    void allRulesReportOnANormalAuthorization() {
        RuleEvaluation evaluation =
                ruleEngine.evaluate("tok_" + UUID.randomUUID(), 1000L, "INR", "mrc_acme", "IN");

        assertThat(evaluation.verdict()).isEqualTo(Verdict.ALLOW);
        assertThat(evaluation.perRule().keySet())
                .containsExactlyInAnyOrderElementsOf(
                        List.of("card-velocity", "amount-anomaly", "impossible-travel"));
    }

    private ResponseEntity<AuthorizationResponse> post(String cardToken, long amountMinor, String country) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", "key-" + UUID.randomUUID());
        AuthorizationRequest request =
                new AuthorizationRequest(cardToken, amountMinor, "INR", "mrc_acme", country);
        return restTemplate.exchange(url("/v1/authorizations"), HttpMethod.POST,
                new HttpEntity<>(request, headers), AuthorizationResponse.class);
    }
}
