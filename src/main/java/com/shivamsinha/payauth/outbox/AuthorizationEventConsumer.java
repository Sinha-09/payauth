package com.shivamsinha.payauth.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A downstream consumer, standing in for whatever really listens to
 * authorizations (ledger, fraud analytics, customer notifications).
 *
 * <p>It exists here mainly to make the delivery guarantee visible. The relay is
 * at-least-once, so this consumer must be idempotent, and it is: an
 * authorizationId it has already handled is counted and dropped.
 *
 * <p>The dedupe set is an in-memory LRU, which is honest only because this is a
 * demonstration consumer. A real one would deduplicate in the same transaction as
 * its own state change — a unique constraint on the processed event id — because
 * an in-memory set forgets everything on restart and is not shared between
 * instances.
 */
@Component
public class AuthorizationEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(AuthorizationEventConsumer.class);
    private static final int DEDUPE_CAPACITY = 10_000;

    private final ObjectMapper objectMapper;
    private final Counter processedCounter;
    private final Counter duplicateCounter;
    private final AtomicLong processed = new AtomicLong();
    private final AtomicLong duplicates = new AtomicLong();

    private final Set<UUID> seenAuthorizationIds = Collections.newSetFromMap(
            Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<UUID, Boolean> eldest) {
                    return size() > DEDUPE_CAPACITY;
                }
            }));

    public AuthorizationEventConsumer(ObjectMapper objectMapper, MeterRegistry meterRegistry) {
        this.objectMapper = objectMapper;
        this.processedCounter = Counter.builder("payauth.events.processed")
                .description("Authorization events handled by the consumer")
                .register(meterRegistry);
        this.duplicateCounter = Counter.builder("payauth.events.duplicate")
                .description("Redelivered authorization events dropped by the consumer")
                .register(meterRegistry);
    }

    @KafkaListener(
            topics = "${payauth.outbox.topic:authorization-events}",
            groupId = "${spring.kafka.consumer.group-id:payauth-events}")
    public void onAuthorizationEvent(String payload) {
        AuthorizationEvent event;
        try {
            event = objectMapper.readValue(payload, AuthorizationEvent.class);
        } catch (Exception ex) {
            // A payload we cannot parse will never become parseable. Log it and let the
            // offset advance rather than blocking the partition forever.
            log.error("Discarding unparseable authorization event: {}", payload, ex);
            return;
        }

        if (!seenAuthorizationIds.add(event.authorizationId())) {
            duplicates.incrementAndGet();
            duplicateCounter.increment();
            log.debug("Dropping redelivered event for authorization {}", event.authorizationId());
            return;
        }

        processed.incrementAndGet();
        processedCounter.increment();
        log.info("Authorization event received: id={} merchant={} amountMinor={} {} status={} code={}",
                event.authorizationId(), event.merchantId(), event.amountMinor(),
                event.currency(), event.status(), event.responseCode());
    }

    public long processedCount() {
        return processed.get();
    }

    public long duplicateCount() {
        return duplicates.get();
    }

    public boolean hasSeen(UUID authorizationId) {
        return seenAuthorizationIds.contains(authorizationId);
    }

    public void reset() {
        seenAuthorizationIds.clear();
        processed.set(0);
        duplicates.set(0);
    }
}
