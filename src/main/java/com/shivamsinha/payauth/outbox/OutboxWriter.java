package com.shivamsinha.payauth.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shivamsinha.payauth.domain.OutboxEvent;
import com.shivamsinha.payauth.repository.OutboxRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.UUID;

/**
 * Appends an event to the outbox.
 *
 * <p>{@code Propagation.MANDATORY} is the structural enforcement the design needs:
 * this method <em>cannot</em> be called outside an existing transaction. If someone
 * later calls it from a plain service method with no transaction, the application
 * throws {@code IllegalTransactionStateException} immediately and loudly, rather
 * than quietly committing the event on its own connection.
 *
 * <p>Where this would break if it were got wrong: if this were
 * {@code REQUIRES_NEW}, the outbox insert would commit in its own transaction. An
 * authorization that then failed to commit would leave an event announcing an
 * authorization that does not exist — a dual write with extra steps, and the exact
 * failure mode the outbox pattern is meant to eliminate. If it were
 * {@code SUPPORTS} or unannotated and the caller forgot {@code @Transactional},
 * the same thing happens by accident instead of by design.
 */
@Component
public class OutboxWriter {

    private final OutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public OutboxWriter(OutboxRepository outboxRepository, ObjectMapper objectMapper, Clock clock) {
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void append(UUID aggregateId, String eventType, Object payload) {
        outboxRepository.save(new OutboxEvent(aggregateId, eventType, serialize(payload), clock.instant()));
    }

    private String serialize(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Could not serialize outbox payload", ex);
        }
    }
}
