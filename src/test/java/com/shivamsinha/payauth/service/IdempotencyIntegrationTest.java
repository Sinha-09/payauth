package com.shivamsinha.payauth.service;

import com.shivamsinha.payauth.api.dto.AuthorizationRequest;
import com.shivamsinha.payauth.api.dto.AuthorizationResponse;
import com.shivamsinha.payauth.config.IdempotencyProperties;
import com.shivamsinha.payauth.repository.AuthorizationRepository;
import com.shivamsinha.payauth.repository.IdempotencyRepository;
import com.shivamsinha.payauth.support.AbstractIntegrationTest;
import com.shivamsinha.payauth.support.MutableClock;
import com.shivamsinha.payauth.support.MutableClockTestConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The idempotency contract, exercised end to end over HTTP against real Postgres.
 */
@Import(MutableClockTestConfiguration.class)
class IdempotencyIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private AuthorizationRepository authorizationRepository;

    @Autowired
    private IdempotencyRepository idempotencyRepository;

    @Autowired
    private IdempotencyProperties idempotencyProperties;

    @Autowired
    private MutableClock clock;

    private static final AuthorizationRequest REQUEST =
            new AuthorizationRequest("tok_4111111111111111", 249900L, "INR", "mrc_acme", "IN");

    @Test
    @DisplayName("1. Same key twice sequentially: one authorization row, second call replays 200 with the identical body")
    void sameKeyTwiceSequentially() {
        String key = "key-" + UUID.randomUUID();

        ResponseEntity<AuthorizationResponse> first = post(key, REQUEST);
        ResponseEntity<AuthorizationResponse> second = post(key, REQUEST);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);

        // Byte-for-byte the same answer, not merely an equivalent one.
        assertThat(second.getBody()).isEqualTo(first.getBody());
        assertThat(second.getBody().authorizationId()).isEqualTo(first.getBody().authorizationId());

        assertThat(authorizationRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("2. Same key with a different request body: 409")
    void sameKeyDifferentBody() {
        String key = "key-" + UUID.randomUUID();
        post(key, REQUEST);

        AuthorizationRequest different =
                new AuthorizationRequest("tok_4111111111111111", 999900L, "INR", "mrc_acme", "IN");
        ResponseEntity<String> conflict = postRaw(key, different);

        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(conflict.getBody()).contains("REQUEST_MISMATCH");
        assertThat(authorizationRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("2b. Field order and whitespace do not change the request identity")
    void canonicalHashIgnoresFieldOrder() {
        String key = "key-" + UUID.randomUUID();
        post(key, REQUEST);

        String reordered = """
                {  "merchantId" : "mrc_acme",
                   "currency"   : "INR",
                   "countryCode": "IN",
                   "amountMinor": 249900,
                   "cardToken"  : "tok_4111111111111111" }
                """;
        ResponseEntity<String> replay = postRawJson(key, reordered);

        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(authorizationRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("3. 50 threads firing the same key together: exactly one authorization row")
    void fiftyConcurrentCallsWithTheSameKey() throws Exception {
        String key = "key-" + UUID.randomUUID();
        int threads = 50;

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch fire = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        ConcurrentLinkedQueue<HttpStatus> statuses = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<UUID> returnedIds = new ConcurrentLinkedQueue<>();
        AtomicInteger unexpected = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    // Every thread parks here, so they are released into the service
                    // within microseconds of each other rather than in a staggered
                    // ramp that would never exercise the race.
                    fire.await();
                    ResponseEntity<AuthorizationResponse> response = post(key, REQUEST);
                    statuses.add(HttpStatus.valueOf(response.getStatusCode().value()));
                    if (response.getBody() != null && response.getBody().authorizationId() != null) {
                        returnedIds.add(response.getBody().authorizationId());
                    }
                } catch (Exception ex) {
                    unexpected.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }

        assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
        fire.countDown();
        assertThat(done.await(60, TimeUnit.SECONDS)).isTrue();
        pool.shutdownNow();

        assertThat(unexpected.get()).as("no thread threw").isZero();

        // The load-bearing assertion: the card was authorized once.
        assertThat(authorizationRepository.count())
                .as("exactly one authorization row for %d concurrent calls with one key", threads)
                .isEqualTo(1);

        // Exactly one caller was told it created the authorization.
        long created = statuses.stream().filter(s -> s == HttpStatus.CREATED).count();
        assertThat(created).as("exactly one 201").isEqualTo(1);

        // Everyone else got either the stored response or an honest "in progress".
        assertThat(statuses).allMatch(
                s -> s == HttpStatus.CREATED || s == HttpStatus.OK || s == HttpStatus.CONFLICT,
                "every response is 201, 200 or 409");

        // Nobody was handed an id for an authorization that is not the one row.
        UUID persisted = authorizationRepository.findAll().getFirst().getId();
        assertThat(Set.copyOf(returnedIds)).isSubsetOf(Set.of(persisted));
    }

    @Test
    @DisplayName("4. An expired key is treated as a brand new key")
    void expiredKeyIsTreatedAsNew() {
        String key = "key-" + UUID.randomUUID();

        ResponseEntity<AuthorizationResponse> first = post(key, REQUEST);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        // Step past the TTL. Nothing sleeps; the service reads time from the clock.
        clock.advance(idempotencyProperties.ttl().plus(Duration.ofMinutes(1)));

        ResponseEntity<AuthorizationResponse> second = post(key, REQUEST);

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(second.getBody().authorizationId())
                .as("a new authorization, not a replay of the expired one")
                .isNotEqualTo(first.getBody().authorizationId());
        assertThat(authorizationRepository.count()).isEqualTo(2);
    }

    @Test
    @DisplayName("5. Different keys produce independent authorizations")
    void differentKeysAreIndependent() {
        List<ResponseEntity<AuthorizationResponse>> responses = List.of(
                post("key-" + UUID.randomUUID(), REQUEST),
                post("key-" + UUID.randomUUID(), REQUEST),
                post("key-" + UUID.randomUUID(), REQUEST));

        assertThat(responses).allMatch(r -> r.getStatusCode() == HttpStatus.CREATED);

        Set<UUID> ids = responses.stream()
                .map(r -> r.getBody().authorizationId())
                .collect(Collectors.toSet());

        assertThat(ids).hasSize(3);
        assertThat(authorizationRepository.count()).isEqualTo(3);
        assertThat(idempotencyRepository.count()).isEqualTo(3);
    }

    @Test
    @DisplayName("A completed key stores the response body it replayed from")
    void completedKeyStoresResponseBody() {
        String key = "key-" + UUID.randomUUID();
        ResponseEntity<AuthorizationResponse> first = post(key, REQUEST);

        var record = idempotencyRepository.findById(key).orElseThrow();
        assertThat(record.getStatus().name()).isEqualTo("COMPLETED");
        assertThat(record.getResponseBody()).contains(first.getBody().authorizationId().toString());
        assertThat(record.getRequestHash()).hasSize(64);
    }

    private ResponseEntity<AuthorizationResponse> post(String key, AuthorizationRequest request) {
        return restTemplate.exchange(url("/v1/authorizations"), HttpMethod.POST,
                new HttpEntity<>(request, headers(key)), AuthorizationResponse.class);
    }

    private ResponseEntity<String> postRaw(String key, AuthorizationRequest request) {
        return restTemplate.exchange(url("/v1/authorizations"), HttpMethod.POST,
                new HttpEntity<>(request, headers(key)), String.class);
    }

    private ResponseEntity<String> postRawJson(String key, String json) {
        return restTemplate.exchange(url("/v1/authorizations"), HttpMethod.POST,
                new HttpEntity<>(json, headers(key)), String.class);
    }

    private HttpHeaders headers(String key) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", key);
        return headers;
    }
}
