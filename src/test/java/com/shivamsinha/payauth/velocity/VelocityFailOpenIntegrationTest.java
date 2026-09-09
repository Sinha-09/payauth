package com.shivamsinha.payauth.velocity;

import com.shivamsinha.payauth.api.dto.AuthorizationRequest;
import com.shivamsinha.payauth.api.dto.AuthorizationResponse;
import com.shivamsinha.payauth.support.IntegrationTestContainers;
import io.micrometer.core.instrument.MeterRegistry;
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
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Redis is pointed at a port with nothing on it, which is the closest a test can
 * get to "the fraud store is down" without taking it down for every other test.
 *
 * <p>The assertion is the one that matters most in this whole project: payments
 * keep working. A fraud layer that can decline traffic by being unavailable has
 * turned an advisory check into a single point of failure on the money path.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class VelocityFailOpenIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private RuleEngine ruleEngine;

    @Autowired
    private MeterRegistry meterRegistry;

    /**
     * Registered here rather than inherited, because a @DynamicPropertySource always
     * wins over @SpringBootTest properties: pointing Redis at a dead port has to
     * happen in the same place the working values would otherwise be registered.
     */
    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        IntegrationTestContainers.startAll();

        registry.add("spring.datasource.url", IntegrationTestContainers.POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", IntegrationTestContainers.POSTGRES::getUsername);
        registry.add("spring.datasource.password", IntegrationTestContainers.POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", IntegrationTestContainers.KAFKA::getBootstrapServers);

        // Nothing is listening here. That is the point.
        registry.add("spring.data.redis.host", () -> "127.0.0.1");
        registry.add("spring.data.redis.port", () -> 6399);
        registry.add("spring.data.redis.connect-timeout", () -> "100ms");
        registry.add("spring.data.redis.timeout", () -> "100ms");
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    @Test
    @DisplayName("With Redis unreachable, authorizations are still approved")
    void authorizationsSucceedWithoutRedis() {
        ResponseEntity<AuthorizationResponse> response = post("tok_" + UUID.randomUUID(), 1000L);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().status()).isEqualTo("APPROVED");
        assertThat(response.getBody().responseCode()).isEqualTo("00");
    }

    @Test
    @DisplayName("Even a card that would be well past its velocity limit is approved")
    void repeatedAuthorizationsAllSucceed() {
        String card = "tok_" + UUID.randomUUID();

        for (int i = 0; i < 10; i++) {
            assertThat(post(card, 1000L).getBody().status())
                    .as("authorization %d", i + 1)
                    .isEqualTo("APPROVED");
        }
    }

    @Test
    @DisplayName("Failing open is reported as degraded, not silently")
    void failOpenIsVisible() {
        RuleEvaluation evaluation =
                ruleEngine.evaluate("tok_" + UUID.randomUUID(), 1000L, "INR", "mrc_acme", "IN");

        assertThat(evaluation.verdict()).isEqualTo(Verdict.ALLOW);
        assertThat(evaluation.degraded()).isTrue();
        assertThat(evaluation.perRule()).isEmpty();

        // An outage has to show up as a metric. Otherwise the only symptom is a
        // decline rate that quietly goes to zero, which nobody alerts on.
        assertThat(meterRegistry.counter("payauth.velocity.fail_open").count()).isPositive();
    }

    private ResponseEntity<AuthorizationResponse> post(String cardToken, long amountMinor) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", "key-" + UUID.randomUUID());
        AuthorizationRequest request =
                new AuthorizationRequest(cardToken, amountMinor, "INR", "mrc_acme", "IN");
        return restTemplate.exchange(url("/v1/authorizations"), HttpMethod.POST,
                new HttpEntity<>(request, headers), AuthorizationResponse.class);
    }
}
