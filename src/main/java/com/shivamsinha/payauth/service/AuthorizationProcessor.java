package com.shivamsinha.payauth.service;

import com.shivamsinha.payauth.api.dto.AuthorizationRequest;
import com.shivamsinha.payauth.api.dto.AuthorizationResponse;
import com.shivamsinha.payauth.config.IssuerProperties;
import com.shivamsinha.payauth.domain.Authorization;
import com.shivamsinha.payauth.domain.AuthorizationStatus;
import com.shivamsinha.payauth.domain.ResponseCode;
import com.shivamsinha.payauth.outbox.AuthorizationEvent;
import com.shivamsinha.payauth.outbox.OutboxWriter;
import com.shivamsinha.payauth.repository.AuthorizationRepository;
import com.shivamsinha.payauth.velocity.RuleEngine;
import com.shivamsinha.payauth.velocity.RuleEvaluation;
import com.shivamsinha.payauth.velocity.Verdict;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.util.UUID;

/**
 * The actual authorization decision and its persistence, in one transaction.
 *
 * <p>Separate bean rather than a private method on {@link AuthorizationService}
 * because it is invoked through a {@link java.util.function.Supplier} handed to
 * {@link IdempotencyService}. A private method would be a self-invocation and
 * Spring's proxy would never see it, so {@code @Transactional} would silently do
 * nothing — the classic way this pattern is got wrong.
 */
@Component
public class AuthorizationProcessor {

    private static final Logger log = LoggerFactory.getLogger(AuthorizationProcessor.class);

    private final AuthorizationRepository authorizationRepository;
    private final OutboxWriter outboxWriter;
    private final RuleEngine ruleEngine;
    private final IssuerProperties issuerProperties;
    private final Clock clock;

    public AuthorizationProcessor(AuthorizationRepository authorizationRepository,
                                  OutboxWriter outboxWriter,
                                  RuleEngine ruleEngine,
                                  IssuerProperties issuerProperties,
                                  Clock clock) {
        this.authorizationRepository = authorizationRepository;
        this.outboxWriter = outboxWriter;
        this.ruleEngine = ruleEngine;
        this.issuerProperties = issuerProperties;
        this.clock = clock;
    }

    /**
     * REQUIRES_NEW: this must be a transaction of its own, never joined to the
     * ledger's.
     *
     * <p>This method is the transaction boundary the outbox pattern depends on. The
     * authorization INSERT and the outbox INSERT both happen inside it, so they
     * commit together or not at all — there is no window in which the card is
     * authorized but the event is lost, or in which an event announces an
     * authorization that was rolled back.
     *
     * <p>{@link OutboxWriter#append} is {@code Propagation.MANDATORY}, which turns
     * that from a convention into something the container enforces: if this
     * annotation were removed, or the outbox write were moved out to a caller with
     * no transaction, the application would fail loudly instead of quietly
     * degrading into a dual write.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AuthorizationResponse process(AuthorizationRequest request) {

        RuleEvaluation evaluation = ruleEngine.evaluate(
                request.cardToken(),
                request.amountMinor(),
                request.currency(),
                request.merchantId(),
                request.countryCode());

        Decision decision = decide(request, evaluation);

        Authorization authorization = new Authorization(
                UUID.randomUUID(),
                request.cardToken(),
                request.amountMinor(),
                request.currency(),
                request.merchantId(),
                decision.status(),
                decision.responseCode(),
                clock.instant());

        authorizationRepository.saveAndFlush(authorization);

        // Same transaction as the line above. That is the entire guarantee.
        outboxWriter.append(
                authorization.getId(),
                AuthorizationEvent.TYPE_AUTHORIZED,
                AuthorizationEvent.authorized(authorization));

        recordAfterCommit(authorization, request.countryCode());

        if (decision.status() == AuthorizationStatus.DECLINED || evaluation.verdict() == Verdict.REVIEW) {
            log.info("Authorization {} decided: status={} code={} verdict={} rules={} degraded={}",
                    authorization.getId(), decision.status(), decision.responseCode().code(),
                    evaluation.verdict(), evaluation.perRule(), evaluation.degraded());
        }

        return AuthorizationResponse.from(authorization);
    }

    /**
     * Fraud screening first, then the issuer — the order a real authorization takes.
     *
     * <p>REVIEW approves. It means "worth a second look offline", not "refuse the
     * cardholder", and conflating the two is how a fraud system starts costing more
     * in lost sales than it saves in losses.
     */
    private Decision decide(AuthorizationRequest request, RuleEvaluation evaluation) {
        if (evaluation.verdict() == Verdict.DECLINE) {
            return new Decision(AuthorizationStatus.DECLINED, ResponseCode.DO_NOT_HONOUR);
        }
        if (request.amountMinor() > issuerProperties.declineAboveMinor()) {
            return new Decision(AuthorizationStatus.DECLINED, ResponseCode.INSUFFICIENT_FUNDS);
        }
        return new Decision(AuthorizationStatus.APPROVED, ResponseCode.APPROVED);
    }

    /**
     * Update the velocity counters only once the authorization is durable.
     *
     * <p>Doing it inline would mean a rolled-back authorization still counted
     * against the card, and would put a Redis round trip inside the database
     * transaction, holding a pooled connection open across a network call for no
     * reason.
     */
    private void recordAfterCommit(Authorization authorization, String countryCode) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            ruleEngine.record(authorization.getCardToken(), authorization.getId(),
                    authorization.getAmountMinor(), countryCode);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                ruleEngine.record(authorization.getCardToken(), authorization.getId(),
                        authorization.getAmountMinor(), countryCode);
            }
        });
    }

    private record Decision(AuthorizationStatus status, ResponseCode responseCode) {
    }
}
