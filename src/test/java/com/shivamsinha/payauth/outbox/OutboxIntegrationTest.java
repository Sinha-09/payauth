package com.shivamsinha.payauth.outbox;

import com.shivamsinha.payauth.api.dto.AuthorizationRequest;
import com.shivamsinha.payauth.api.dto.AuthorizationResponse;
import com.shivamsinha.payauth.domain.OutboxEvent;
import com.shivamsinha.payauth.repository.AuthorizationRepository;
import com.shivamsinha.payauth.repository.OutboxRepository;
import com.shivamsinha.payauth.domain.Authorization;
import com.shivamsinha.payauth.domain.AuthorizationStatus;
import com.shivamsinha.payauth.domain.ResponseCode;
import com.shivamsinha.payauth.support.AbstractIntegrationTest;
import com.shivamsinha.payauth.support.FaultInjectingKafkaConfiguration;
import com.shivamsinha.payauth.support.FaultInjectingKafkaConfiguration.FaultInjectingKafkaTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * The transactional outbox, against real Postgres and a real Kafka broker.
 *
 * <p>The scheduled relay is pushed out to a one hour delay so that these tests
 * decide when it runs. Otherwise "exactly one unpublished row" is a race against
 * the scheduler rather than an assertion about the code.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "payauth.outbox.relay.fixed-delay=1h",
        "spring.kafka.listener.auto-startup=true"
})
@Import(FaultInjectingKafkaConfiguration.class)
class OutboxIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private OutboxRepository outboxRepository;

    @Autowired
    private AuthorizationRepository authorizationRepository;

    @Autowired
    private OutboxWriter outboxWriter;

    @Autowired
    private java.time.Clock clock;

    @Autowired
    private OutboxRelay relay;

    @Autowired
    private AuthorizationEventConsumer consumer;

    @Autowired
    private FaultInjectingKafkaTemplate kafkaTemplate;

    @Autowired
    private KafkaListenerEndpointRegistry listenerRegistry;

    private static final AuthorizationRequest REQUEST =
            new AuthorizationRequest("tok_4111111111111111", 150000L, "INR", "mrc_acme", "IN");

    @BeforeEach
    void resetFixtures() {
        kafkaTemplate.healBroker();
        consumer.reset();
        // Make sure the listener is actually assigned before a test publishes, so
        // 'earliest' has something to be earliest relative to.
        listenerRegistry.getListenerContainers().forEach(container -> {
            if (!container.isRunning()) {
                container.start();
            }
        });
    }

    @Test
    @DisplayName("1. A committed authorization leaves exactly one unpublished outbox row")
    void committedAuthorizationWritesOneOutboxRow() {
        ResponseEntity<AuthorizationResponse> response = post("key-" + UUID.randomUUID());
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        List<OutboxEvent> pending = outboxRepository.findAll();

        assertThat(pending).hasSize(1);
        OutboxEvent event = pending.getFirst();
        assertThat(event.getPublishedAt()).isNull();
        assertThat(event.getAggregateId()).isEqualTo(response.getBody().authorizationId());
        assertThat(event.getEventType()).isEqualTo(AuthorizationEvent.TYPE_AUTHORIZED);

        // Read the payload back as JSON rather than matching on its text: jsonb is
        // stored decomposed, so Postgres hands back a normalized rendering with its
        // own key order and spacing, not the bytes we wrote.
        AuthorizationEvent payload = readEvent(event.getPayload());
        assertThat(payload.amountMinor()).isEqualTo(150000L);
        assertThat(payload.currency()).isEqualTo("INR");
        assertThat(payload.authorizationId()).isEqualTo(response.getBody().authorizationId());
        assertThat(payload.eventId()).isNotNull();
    }

    @Test
    @DisplayName("2. A rolled-back authorization leaves zero outbox rows")
    void rolledBackAuthorizationWritesNoOutboxRow() {
        // Both writes are issued inside one transaction, exactly as
        // AuthorizationProcessor.process issues them, and then the transaction fails.
        //
        // The test drives the transaction itself rather than calling process(),
        // because process() is REQUIRES_NEW: it would open and commit its own
        // transaction and an outer rollback could not reach it. What has to be shown
        // is that the two inserts share a boundary, and that OutboxWriter can only
        // ever run inside one (see outboxWriteOutsideTransactionIsRejected).
        assertThatThrownBy(() -> transactionTemplate.execute(status -> {
            Authorization authorization = new Authorization(
                    UUID.randomUUID(), REQUEST.cardToken(), REQUEST.amountMinor(),
                    REQUEST.currency(), REQUEST.merchantId(),
                    AuthorizationStatus.APPROVED, ResponseCode.APPROVED, clock.instant());
            authorizationRepository.saveAndFlush(authorization);

            outboxWriter.append(authorization.getId(), AuthorizationEvent.TYPE_AUTHORIZED,
                    AuthorizationEvent.authorized(authorization));

            throw new IllegalStateException("simulated failure after both rows were written");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(outboxRepository.count()).as("no orphan event").isZero();
        assertThat(authorizationRepository.count()).as("no orphan authorization").isZero();
    }

    @Test
    @DisplayName("3. The relay publishes pending events and stamps published_at")
    void relayPublishesAndMarks() {
        ResponseEntity<AuthorizationResponse> response = post("key-" + UUID.randomUUID());
        UUID authorizationId = response.getBody().authorizationId();

        assertThat(outboxRepository.countByPublishedAtIsNull()).isEqualTo(1);

        relay.publishPending();

        assertThat(outboxRepository.countByPublishedAtIsNull()).isZero();
        assertThat(outboxRepository.findAll().getFirst().getPublishedAt()).isNotNull();

        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(consumer.hasSeen(authorizationId)).isTrue());
    }

    @Test
    @DisplayName("3b. A relay pass with nothing pending is a no-op")
    void relayWithNothingPendingDoesNothing() {
        relay.publishPending();

        assertThat(outboxRepository.count()).isZero();
        assertThat(consumer.processedCount()).isZero();
    }

    @Test
    @DisplayName("4. Relay dies mid-batch, restarts: the event is still delivered, and no duplicate authorization exists")
    void relayCrashMidBatchStillDelivers() {
        UUID first = post("key-" + UUID.randomUUID()).getBody().authorizationId();
        UUID second = post("key-" + UUID.randomUUID()).getBody().authorizationId();

        assertThat(outboxRepository.countByPublishedAtIsNull()).isEqualTo(2);

        // The relay manages one send and then the broker goes away.
        kafkaTemplate.failAfterSends(1);
        assertThatThrownBy(() -> relay.publishPending())
                .isInstanceOf(FaultInjectingKafkaTemplate.InjectedBrokerFailure.class);

        // Nothing was marked. The first event did reach Kafka, so it will be
        // delivered twice: at-least-once, on purpose.
        assertThat(outboxRepository.countByPublishedAtIsNull())
                .as("a failed pass marks nothing published")
                .isEqualTo(2);

        // Relay restarts.
        kafkaTemplate.healBroker();
        relay.publishPending();

        assertThat(outboxRepository.countByPublishedAtIsNull()).isZero();

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            assertThat(consumer.hasSeen(first)).isTrue();
            assertThat(consumer.hasSeen(second)).isTrue();
        });

        // The redelivery was absorbed by the consumer, not by luck.
        await().atMost(Duration.ofSeconds(15))
                .untilAsserted(() -> assertThat(consumer.duplicateCount()).isPositive());

        assertThat(consumer.processedCount())
                .as("each authorization handled exactly once despite redelivery")
                .isEqualTo(2);

        // And the crash never produced a second authorization row.
        assertThat(authorizationRepository.count()).isEqualTo(2);
    }

    @Test
    @DisplayName("The outbox writer refuses to run outside a transaction")
    void outboxWriteOutsideTransactionIsRejected() {
        // Propagation.MANDATORY is the structural half of the guarantee: an outbox
        // write simply cannot happen on its own connection, so nobody can
        // reintroduce a dual write by forgetting an annotation.
        assertThatThrownBy(() -> outboxWriter.append(
                UUID.randomUUID(), AuthorizationEvent.TYPE_AUTHORIZED, "{}"))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);

        assertThat(outboxRepository.count()).isZero();
    }

    private AuthorizationEvent readEvent(String json) {
        try {
            return objectMapper.readValue(json, AuthorizationEvent.class);
        } catch (Exception ex) {
            throw new AssertionError("outbox payload was not a readable AuthorizationEvent: " + json, ex);
        }
    }

    private ResponseEntity<AuthorizationResponse> post(String key) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", key);
        return restTemplate.exchange(url("/v1/authorizations"), HttpMethod.POST,
                new HttpEntity<>(REQUEST, headers), AuthorizationResponse.class);
    }
}
