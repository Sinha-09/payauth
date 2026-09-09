package com.shivamsinha.payauth.outbox;

import com.shivamsinha.payauth.config.OutboxProperties;
import com.shivamsinha.payauth.domain.OutboxEvent;
import com.shivamsinha.payauth.repository.OutboxRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;

/**
 * Drains the outbox onto Kafka.
 *
 * <p>Delivery is <strong>at-least-once</strong>, deliberately. The alternative —
 * marking rows published before the broker acknowledges them — would be
 * at-most-once, and silently losing an authorization event is worse than
 * delivering one twice to a consumer that can deduplicate.
 *
 * <p>The ordering inside {@link #publishPending()} is the whole correctness
 * argument, so it is worth stating plainly: rows are locked, sent, acknowledged,
 * and only then marked. A crash at any point before the commit leaves
 * {@code published_at} null and the batch is simply retried.
 */
@Component
@ConditionalOnProperty(prefix = "payauth.outbox.relay", name = "enabled", havingValue = "true", matchIfMissing = true)
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxRepository outboxRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final OutboxProperties properties;
    private final Clock clock;
    private final Counter publishedCounter;
    private final Counter failureCounter;

    public OutboxRelay(OutboxRepository outboxRepository,
                       KafkaTemplate<String, String> kafkaTemplate,
                       OutboxProperties properties,
                       Clock clock,
                       MeterRegistry meterRegistry) {
        this.outboxRepository = outboxRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.properties = properties;
        this.clock = clock;
        this.publishedCounter = Counter.builder("payauth.outbox.published")
                .description("Outbox events successfully published to Kafka")
                .register(meterRegistry);
        this.failureCounter = Counter.builder("payauth.outbox.publish_failed")
                .description("Outbox relay passes that failed and will be retried")
                .register(meterRegistry);
    }

    /**
     * Publish one batch of pending events.
     *
     * <p>Steps, in this order and no other:
     * <ol>
     *   <li>Claim a batch with {@code FOR UPDATE SKIP LOCKED}. The lock is what makes
     *       running several application instances safe: each relay takes a disjoint
     *       set of rows, and none of them waits behind another.</li>
     *   <li>Send each event to Kafka, keyed by aggregate id, and block on the broker
     *       acknowledgement. Blocking is the point — an async send would let us mark
     *       rows published that the broker never accepted.</li>
     *   <li>Mark the whole batch published.</li>
     *   <li>Commit, releasing the locks.</li>
     * </ol>
     *
     * <p>If step 2 throws, the transaction rolls back: nothing is marked, the locks
     * are released, and the next pass picks the same rows up again. Some of them may
     * already have reached Kafka, which is precisely why consumers deduplicate on
     * eventId.
     */
    @Scheduled(
            fixedDelayString = "${payauth.outbox.relay.fixed-delay:500ms}",
            initialDelayString = "${payauth.outbox.relay.fixed-delay:500ms}")
    @Transactional
    public void publishPending() {
        List<OutboxEvent> batch = outboxRepository.lockPendingBatch(properties.relay().batchSize());
        if (batch.isEmpty()) {
            return;
        }

        try {
            for (OutboxEvent event : batch) {
                kafkaTemplate
                        .send(properties.topic(), event.getAggregateId().toString(), event.getPayload())
                        .join();
            }
        } catch (RuntimeException ex) {
            failureCounter.increment();
            log.warn("Outbox relay failed to publish a batch of {} events; it will be retried", batch.size(), ex);
            // Rethrow so the transaction rolls back and published_at stays null.
            // Swallowing this would be the bug: the rows would be marked published
            // by the code below without ever having reached the broker.
            throw ex;
        }

        outboxRepository.markPublished(batch.stream().map(OutboxEvent::getId).toList(), clock.instant());
        publishedCounter.increment(batch.size());

        if (log.isDebugEnabled()) {
            log.debug("Outbox relay published {} events", batch.size());
        }
    }
}
