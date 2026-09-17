package io.loyaltyhub.fraudservice.app;

import io.loyaltyhub.common.event.CanonicalEvents;
import io.loyaltyhub.common.event.EventTypes;
import io.loyaltyhub.common.metrics.LoyaltyMetrics;
import io.loyaltyhub.fraudservice.domain.RiskEngine;
import io.loyaltyhub.fraudservice.domain.RiskPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Ciclo di valutazione (RF-131): osservazione → aggregati → motore → persistenza → RISK_V1 al cambio di livello →
 * blocco automatico sul ledger se previsto dalla policy. La policy arriva dal backoffice (collezione {@code fraud-rules}).
 */
@Service
public class RiskService {
    private static final Logger log = LoggerFactory.getLogger(RiskService.class);
    private static final String SOURCE = "urn:loyaltyhub:fraud-service";

    /** Porta verso il ledger per congelare/sbloccare le unità del membro (RF-31 blocco). */
    public interface LedgerBlocker { void block(String memberId, String reason, boolean unblock); }

    private final RiskStore store;
    private final RiskEngine engine = new RiskEngine();
    private final Supplier<RiskPolicy> policy;
    private final KafkaTemplate<String, byte[]> kafka;
    private final LedgerBlocker ledger;
    private final LoyaltyMetrics metrics;

    public RiskService(RiskStore store, Supplier<RiskPolicy> policy, KafkaTemplate<String, byte[]> kafka, LedgerBlocker ledger, LoyaltyMetrics metrics) {
        this.store = store; this.policy = policy; this.kafka = kafka; this.ledger = ledger; this.metrics = metrics;
    }

    @Transactional
    public RiskEngine.Assessment assess(String memberId, String triggerEvent) {
        Instant now = Instant.now();
        RiskPolicy p = policy.get();
        RiskEngine.Assessment a = engine.assess(store.activity(memberId, now), p, now);
        boolean wasBlocked = store.current(memberId).map(m -> Boolean.TRUE.equals(m.get("blocked"))).orElse(false);
        boolean block = a.blockedBy(p);
        String prev = store.save(a, block, triggerEvent);
        metrics.risk(a.level(), a.reasonCodes().isEmpty() ? null : a.reasonCodes().get(0));
        if (prev == null ? !"LOW".equals(a.level()) : !prev.equals(a.level())) {
            publish(a, prev);
            log.info("risk level for member {}: {} → {} score={} reasons={}", memberId, prev, a.level(), a.score(), a.reasonCodes());
        }
        if (block && !wasBlocked) safeBlock(memberId, "RISK:" + a.level() + ":" + String.join(",", a.reasonCodes()), false);
        else if (!block && wasBlocked) safeBlock(memberId, "RISK:cleared", true);
        return a;
    }

    /** Simulazione dal backoffice: osservazioni e policy in bozza, nessuna persistenza. */
    public RiskEngine.Assessment simulate(RiskEngine.MemberActivity activity, RiskPolicy draft) {
        return engine.assess(activity, draft == null ? policy.get() : draft, Instant.now());
    }

    private void safeBlock(String memberId, String reason, boolean unblock) {
        try { ledger.block(memberId, reason, unblock); }
        catch (Exception e) { log.error("ledger block for member {} failed: {}", memberId, e.toString()); }
    }

    private void publish(RiskEngine.Assessment a, String previousLevel) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("memberId", a.memberId());
        payload.put("score", a.score());
        payload.put("level", a.level());
        payload.put("previousLevel", previousLevel);
        payload.put("reasonCodes", a.reasonCodes());
        payload.put("reasons", a.reasons());
        payload.put("policyVersion", a.policyVersion());
        payload.put("assessedAt", a.assessedAt().toString());
        var ce = CanonicalEvents.of(EventTypes.RISK_V1, SOURCE, "member:" + a.memberId(), payload);
        kafka.send(EventTypes.TOPIC_RISK, a.memberId(), CanonicalEvents.serialize(ce));
    }

    /** Rivalutazione periodica dei membri non LOW (decadimento) e pulizia della finestra di osservazione (90 giorni). */
    @Scheduled(fixedDelayString = "${fraud.reassess-ms:900000}")
    public void reassess() {
        RiskPolicy p = policy.get();
        for (String memberId : store.membersToReassess(Instant.now().minus(Duration.ofHours(Math.max(1, p.decayHours() / 4))))) {
            try { assess(memberId, "scheduled"); } catch (Exception e) { log.warn("reassess {} failed: {}", memberId, e.toString()); }
        }
        int purged = store.purge(Instant.now().minus(Duration.ofDays(90)));
        if (purged > 0) log.info("purged {} observed events older than 90 days", purged);
    }
}
