package com.shivamsinha.payauth.console;

import com.shivamsinha.payauth.domain.Authorization;
import com.shivamsinha.payauth.domain.AuthorizationStatus;
import com.shivamsinha.payauth.domain.OutboxEvent;
import com.shivamsinha.payauth.repository.AuthorizationRepository;
import com.shivamsinha.payauth.repository.IdempotencyRepository;
import com.shivamsinha.payauth.repository.OutboxRepository;
import com.shivamsinha.payauth.velocity.RuleEngine;
import com.shivamsinha.payauth.velocity.RuleEvaluation;
import com.shivamsinha.payauth.velocity.RulesConfig;
import com.shivamsinha.payauth.velocity.RulesConfigSource;
import com.shivamsinha.payauth.velocity.VelocityRedisRepository;
import com.shivamsinha.payauth.velocity.VelocitySnapshot;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.search.Search;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only endpoints behind the demo console.
 *
 * <p>Nothing here participates in authorizing a payment. It exists so that the
 * parts of this system that are normally invisible — the idempotency ledger, the
 * outbox backlog, what the fraud rules actually saw — can be watched while
 * somebody clicks through the UI.
 */
@RestController
@RequestMapping("/v1/console")
@ConditionalOnProperty(prefix = "payauth.console", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ConsoleController {

    private final AuthorizationRepository authorizationRepository;
    private final IdempotencyRepository idempotencyRepository;
    private final OutboxRepository outboxRepository;
    private final VelocityRedisRepository velocityRepository;
    private final RuleEngine ruleEngine;
    private final RulesConfigSource rulesConfigSource;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    public ConsoleController(AuthorizationRepository authorizationRepository,
                             IdempotencyRepository idempotencyRepository,
                             OutboxRepository outboxRepository,
                             VelocityRedisRepository velocityRepository,
                             RuleEngine ruleEngine,
                             RulesConfigSource rulesConfigSource,
                             MeterRegistry meterRegistry,
                             Clock clock) {
        this.authorizationRepository = authorizationRepository;
        this.idempotencyRepository = idempotencyRepository;
        this.outboxRepository = outboxRepository;
        this.velocityRepository = velocityRepository;
        this.ruleEngine = ruleEngine;
        this.rulesConfigSource = rulesConfigSource;
        this.meterRegistry = meterRegistry;
        this.clock = clock;
    }

    @GetMapping("/summary")
    @Transactional(readOnly = true)
    public ConsoleView.Summary summary() {
        return new ConsoleView.Summary(
                authorizationRepository.count(),
                authorizationRepository.countByStatus(AuthorizationStatus.APPROVED),
                authorizationRepository.countByStatus(AuthorizationStatus.DECLINED),
                authorizationRepository.countByStatus(AuthorizationStatus.CAPTURED),
                authorizationRepository.countByStatus(AuthorizationStatus.VOIDED),
                idempotencyRepository.count(),
                outboxRepository.count(),
                outboxRepository.countByPublishedAtIsNull(),
                counter("payauth.events.processed"),
                counter("payauth.events.duplicate"),
                counter("payauth.idempotency.replay"),
                counter("payauth.idempotency.conflict"),
                counter("payauth.velocity.decline"),
                counter("payauth.velocity.review"),
                counter("payauth.velocity.fail_open"));
    }

    @GetMapping("/authorizations")
    @Transactional(readOnly = true)
    public List<ConsoleView.AuthorizationRow> authorizations(@RequestParam(defaultValue = "15") int limit) {
        return authorizationRepository
                .findAll(PageRequest.of(0, clamp(limit), Sort.by(Sort.Direction.DESC, "createdAt")))
                .map(this::toRow)
                .getContent();
    }

    @GetMapping("/outbox")
    @Transactional(readOnly = true)
    public List<ConsoleView.OutboxRow> outbox(@RequestParam(defaultValue = "15") int limit) {
        return outboxRepository
                .findAll(PageRequest.of(0, clamp(limit), Sort.by(Sort.Direction.DESC, "id")))
                .map(event -> new ConsoleView.OutboxRow(
                        event.getId(),
                        event.getAggregateId(),
                        event.getEventType(),
                        event.getPublishedAt() != null,
                        event.getCreatedAt(),
                        event.getPublishedAt()))
                .getContent();
    }

    /**
     * What the rules would say about this card and amount, right now, without
     * authorizing anything.
     *
     * <p>This is safe to call repeatedly precisely because rule evaluation has no
     * side effects: recording an authorization against the card is a separate
     * operation that only the real authorization path performs.
     */
    @GetMapping("/velocity")
    public ConsoleView.VelocityView velocity(@RequestParam String cardToken,
                                             @RequestParam(defaultValue = "1000") long amountMinor,
                                             @RequestParam(required = false) String countryCode) {

        RulesConfig config = rulesConfigSource.current();
        VelocitySnapshot snapshot;
        try {
            snapshot = velocityRepository.loadSnapshot(cardToken, clock.instant(), config);
        } catch (RuntimeException ex) {
            snapshot = VelocitySnapshot.unavailable();
        }

        RuleEvaluation evaluation = ruleEngine.evaluate(cardToken, amountMinor, "INR", "console", countryCode);

        Map<String, String> perRule = new LinkedHashMap<>();
        evaluation.perRule().forEach((rule, verdict) -> perRule.put(rule, verdict.name()));

        List<Long> amounts = snapshot.recentAmountsMinor();
        long average = amounts.isEmpty()
                ? 0
                : amounts.stream().mapToLong(Long::longValue).sum() / amounts.size();

        List<Map<String, String>> geo = snapshot.recentGeoEvents().stream()
                .map(event -> Map.of("countryCode", event.countryCode(), "at", event.at().toString()))
                .toList();

        return new ConsoleView.VelocityView(
                cardToken,
                amountMinor,
                countryCode,
                snapshot.recentAuthorizationCount(),
                amounts,
                geo,
                average,
                perRule,
                evaluation.verdict().name(),
                evaluation.degraded());
    }

    /** The thresholds currently in force, so a hot reload is visible in the UI. */
    @GetMapping("/rules")
    public RulesConfig rules() {
        return rulesConfigSource.current();
    }

    private ConsoleView.AuthorizationRow toRow(Authorization authorization) {
        return new ConsoleView.AuthorizationRow(
                authorization.getId(),
                authorization.getCardToken(),
                authorization.getAmountMinor(),
                authorization.getCurrency(),
                authorization.getMerchantId(),
                authorization.getStatus().name(),
                authorization.getResponseCode(),
                authorization.getVersion(),
                authorization.getCreatedAt());
    }

    private double counter(String name) {
        return Search.in(meterRegistry).name(name).counters().stream()
                .mapToDouble(io.micrometer.core.instrument.Counter::count)
                .sum();
    }

    private int clamp(int limit) {
        return Math.max(1, Math.min(limit, 100));
    }
}
