package io.loyaltyhub.reward.application;

import io.loyaltyhub.common.audit.AuditEntry;
import io.loyaltyhub.common.audit.AuditPublisher;
import io.loyaltyhub.common.event.LhEvent;
import io.loyaltyhub.common.event.LhEventFactory;
import io.loyaltyhub.common.event.LhEventTypes;
import io.loyaltyhub.common.event.LhSource;
import io.loyaltyhub.common.ids.Ulid;
import io.loyaltyhub.common.outbox.OutboxWriter;
import io.loyaltyhub.common.web.ActorHolder;
import io.loyaltyhub.common.web.LhException;
import io.loyaltyhub.common.web.PageResponse;
import io.loyaltyhub.reward.domain.Band;
import io.loyaltyhub.reward.domain.Coupon;
import io.loyaltyhub.reward.domain.CouponStatus;
import io.loyaltyhub.reward.domain.MemberSnapshot;
import io.loyaltyhub.reward.domain.Redemption;
import io.loyaltyhub.reward.domain.RedemptionStatus;
import io.loyaltyhub.reward.domain.Reward;
import io.loyaltyhub.reward.domain.RewardStatus;
import io.loyaltyhub.reward.infra.CatalogRepository;
import io.loyaltyhub.reward.infra.CouponRepository;
import io.loyaltyhub.reward.infra.MemberSnapshotRepository;
import io.loyaltyhub.reward.infra.RedemptionRepository;
import io.loyaltyhub.reward.infra.RewardRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Saga di richiesta premio, lato reward (docs/03 §5, docs/servizi/reward-service.md §5; F-RWD-05, F-RWD-06):
 * <ol>
 *   <li>richiesta: validazioni immediate (422, nessun evento), stock prenotato con un {@code UPDATE} atomico,
 *       {@code redemption PENDING} e fatto {@code reward.redemption.requested} nella stessa transazione;</li>
 *   <li>{@code wallet.points.spent} → {@code CONFIRMED} e poi evasione secondo il premio (coupon, immediata o
 *       manuale); {@code wallet.spend.rejected} → {@code REJECTED} con stock ripristinato;</li>
 *   <li>timeout: {@code PENDING} da più di 10 minuti → {@code REJECTED (TIMEOUT)}; una spesa arrivata dopo si
 *       compensa con {@code reward.redemption.cancelled} e {@code refund=true}.</li>
 * </ol>
 * Tutti gli eventi della saga condividono il {@code correlationId} della richiesta: un solo tracciato in BO-25.
 */
@Service
public class RedemptionService {

    private static final Logger log = LoggerFactory.getLogger(RedemptionService.class);

    public record RequestResult(String redemptionId, RedemptionStatus status, String correlationId) {
    }

    public record RedemptionView(String id, String memberId, String rewardCode, String rewardName, long pointsCost,
                                 RedemptionStatus status, String rejectReason, boolean needsAttention, String couponCode,
                                 String fulfilmentNote, JsonNode shipping, String correlationId, Instant requestedAt,
                                 Instant confirmedAt, Instant closedAt, List<RedemptionRepository.HistoryItem> history) {
    }

    private final RedemptionRepository redemptions;
    private final RewardRepository rewards;
    private final CatalogRepository catalog;
    private final MemberSnapshotRepository members;
    private final CouponService coupons;
    private final CouponRepository couponRepository;
    private final LhEventFactory events;
    private final OutboxWriter outbox;
    private final AuditPublisher audit;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final Duration timeout;

    public RedemptionService(RedemptionRepository redemptions, RewardRepository rewards, CatalogRepository catalog,
                             MemberSnapshotRepository members, CouponService coupons, CouponRepository couponRepository,
                             LhEventFactory events, OutboxWriter outbox, AuditPublisher audit, ObjectMapper mapper, Clock clock,
                             @Value("${loyaltyhub.reward.redemption-timeout-minutes:10}") long timeoutMinutes) {
        this.redemptions = redemptions;
        this.rewards = rewards;
        this.catalog = catalog;
        this.members = members;
        this.coupons = coupons;
        this.couponRepository = couponRepository;
        this.events = events;
        this.outbox = outbox;
        this.audit = audit;
        this.mapper = mapper;
        this.clock = clock;
        this.timeout = Duration.ofMinutes(timeoutMinutes);
    }

    // ---------- richiesta ----------

    /**
     * Richiesta dal portale → {@code PENDING}. Il saldo non si controlla qui: lo decide il wallet nella saga.
     * La riga del membro è bloccata per tutta la transazione, così due richieste concorrenti dello stesso membro
     * non superano insieme il limite per membro.
     */
    @Transactional
    public RequestResult request(String memberId, String rewardCode, JsonNode shipping) {
        if (memberId == null || memberId.isBlank() || rewardCode == null || rewardCode.isBlank()) {
            throw LhException.badRequest("memberId e rewardCode sono obbligatori");
        }
        MemberSnapshot member = members.lock(memberId)
                .orElseThrow(() -> LhException.validation("MEMBER_NOT_ACTIVE", "Membro sconosciuto o non attivo."));
        if (!"ACTIVE".equals(member.status())) {
            throw LhException.validation("MEMBER_NOT_ACTIVE", "Il membro non è attivo (" + member.status() + ").");
        }
        Instant now = clock.instant();
        Reward reward = rewards.findByCode(rewardCode)
                .filter(r -> r.status() == RewardStatus.LIVE && r.validAt(now) && PortalCatalogService.inSegment(r, member))
                .orElseThrow(() -> LhException.validation("REWARD_NOT_AVAILABLE", "Premio non disponibile: " + rewardCode));
        if (PortalCatalogService.lock(reward, member) != null) {
            throw LhException.validation("TIER_NOT_ELIGIBLE",
                    "Premio riservato ai livelli " + String.join(", ", reward.eligibleTiers()) + ".");
        }
        if ("PHYSICAL".equals(reward.type()) && (shipping == null || shipping.isNull() || shipping.isEmpty())) {
            throw LhException.validation("SHIPPING_REQUIRED", "Per un premio fisico serve l'indirizzo di spedizione.");
        }
        if (reward.perMemberLimit() != null && redemptions.countActive(memberId, rewardCode) >= reward.perMemberLimit()) {
            throw LhException.validation("MEMBER_LIMIT_REACHED",
                    "Hai già richiesto questo premio " + reward.perMemberLimit() + " volte.");
        }
        if (!rewards.reserveStock(rewardCode)) {
            throw LhException.validation("REWARD_SOLD_OUT", "Premio esaurito.");
        }
        long cost = catalog.band(reward.bandCode()).map(Band::pointsThreshold)
                .orElseThrow(() -> new IllegalStateException("fascia inesistente: " + reward.bandCode()));

        String id = Ulid.next(clock);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("redemptionId", id);
        data.put("rewardCode", reward.code());
        data.put("rewardName", reward.name());
        data.put("currency", "PTS");
        data.put("pointsCost", cost);
        String actor = "MEMBER:" + memberId;
        LhEvent<Map<String, Object>> requested = events.newRoot(LhEventTypes.Fact.REWARD_REDEMPTION_REQUESTED,
                "member:" + memberId, data, LhSource.service("reward"), actor);

        redemptions.insert(new Redemption(id, memberId, reward.code(), reward.name(), cost, RedemptionStatus.PENDING,
                null, false, null, null, shipping == null || shipping.isNull() ? null : shipping.toString(),
                requested.id(), now, null, null, actor));
        redemptions.addHistory(Ulid.next(clock), id, RedemptionStatus.PENDING, "Richiesta di " + cost + " PTS", actor, now);
        outbox.write(requested);
        return new RequestResult(id, RedemptionStatus.PENDING, requested.id());
    }

    // ---------- esiti del wallet ----------

    /** {@code wallet.points.spent}: conferma ed evade; se la richiesta è già chiusa, compensa col rimborso. */
    @Transactional
    public void onPointsSpent(LhEvent<JsonNode> spent) {
        String id = redemptionIdOf(spent);
        Optional<Redemption> found = id == null ? Optional.empty() : redemptions.lock(id);
        if (found.isEmpty()) {
            log.warn("wallet.points.spent per una richiesta sconosciuta ({}): ignorato", id);
            return;
        }
        Redemption r = found.get();
        Instant now = clock.instant();
        switch (r.status()) {
            case PENDING -> {
                redemptions.confirm(r.id(), now);
                redemptions.addHistory(Ulid.next(clock), r.id(), RedemptionStatus.CONFIRMED,
                        "Punti spesi: " + r.pointsCost() + " PTS", "SYSTEM", now);
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("redemptionId", r.id());
                data.put("rewardCode", r.rewardCode());
                data.put("rewardName", r.rewardName());
                data.put("pointsCost", r.pointsCost());
                LhEvent<Map<String, Object>> confirmed = events.childOf(spent, LhEventTypes.Fact.REWARD_REDEMPTION_CONFIRMED, data);
                outbox.write(confirmed);
                fulfil(r, confirmed);
            }
            case REJECTED, CANCELLED -> {
                // Spesa arrivata a richiesta già chiusa (tipicamente dopo il timeout): i punti vanno restituiti.
                log.warn("Richiesta {} già {}: la spesa arrivata in ritardo viene compensata con un rimborso", r.id(), r.status());
                redemptions.addHistory(Ulid.next(clock), r.id(), r.status(), "Spesa arrivata in ritardo: rimborso", "SYSTEM", now);
                outbox.write(events.childOf(spent, LhEventTypes.Fact.REWARD_REDEMPTION_CANCELLED,
                        cancelledData(r, "LATE_SPEND", true)));
            }
            case CONFIRMED, FULFILLED -> { } // spesa già registrata: niente da fare
        }
    }

    /** {@code wallet.spend.rejected}: {@code PENDING → REJECTED} con il motivo del wallet, stock ripristinato. */
    @Transactional
    public void onSpendRejected(LhEvent<JsonNode> rejected) {
        String id = redemptionIdOf(rejected);
        Optional<Redemption> found = id == null ? Optional.empty() : redemptions.lock(id);
        if (found.isEmpty()) {
            log.warn("wallet.spend.rejected per una richiesta sconosciuta ({}): ignorato", id);
            return;
        }
        Redemption r = found.get();
        if (r.status() != RedemptionStatus.PENDING) {
            return;
        }
        String reason = rejected.data().path("reason").asString("REJECTED");
        reject(r, reason, rejected);
    }

    /**
     * Timeout della saga: le richieste {@code PENDING} da oltre {@code timeout} (10 minuti) diventano
     * {@code REJECTED (TIMEOUT)} con stock ripristinato. Restituisce quante.
     */
    @Transactional
    public int timeoutPending(Instant asOf) {
        int n = 0;
        for (String id : redemptions.pendingBefore(asOf.minus(timeout))) {
            Optional<Redemption> r = redemptions.lock(id);
            if (r.isPresent() && r.get().status() == RedemptionStatus.PENDING) {
                reject(r.get(), "TIMEOUT", requestedRef(r.get()));
                n++;
            }
        }
        return n;
    }

    /** Annullo del membro dal portale: solo {@code PENDING}; nessun punto è ancora stato speso. */
    @Transactional
    public RedemptionView cancelByMember(String id, String memberId) {
        Redemption r = redemptions.lock(id)
                .filter(x -> memberId == null || x.memberId().equals(memberId))
                .orElseThrow(() -> LhException.notFound("Richiesta non trovata: " + id));
        if (r.status() != RedemptionStatus.PENDING) {
            throw LhException.conflict("REDEMPTION_NOT_CANCELLABLE",
                    "Si annulla solo una richiesta in attesa; questa è " + r.status() + ".");
        }
        Instant now = clock.instant();
        redemptions.close(r.id(), RedemptionStatus.CANCELLED, "MEMBER", now);
        rewards.releaseStock(r.rewardCode());
        redemptions.addHistory(Ulid.next(clock), r.id(), RedemptionStatus.CANCELLED, "Annullata dal membro", "MEMBER:" + r.memberId(), now);
        outbox.write(events.childOf(requestedRef(r), LhEventTypes.Fact.REWARD_REDEMPTION_CANCELLED,
                cancelledData(r, "MEMBER", false)));
        return view(r.id());
    }

    // ---------- operatore (BO-13) ----------

    /**
     * Evasione manuale ({@code F-RWD-06}): solo una richiesta {@code CONFIRMED} di un premio {@code MANUAL}; nota
     * obbligatoria, tracking facoltativo.
     */
    @Transactional
    public RedemptionView fulfilManually(String id, String note, String tracking) {
        Redemption r = redemptions.lock(id).orElseThrow(() -> LhException.notFound("Richiesta non trovata: " + id));
        String fulfilment = rewards.findByCode(r.rewardCode()).map(Reward::fulfilment).orElse("MANUAL");
        if (r.status() != RedemptionStatus.CONFIRMED || !"MANUAL".equals(fulfilment)) {
            throw LhException.conflict("REDEMPTION_NOT_FULFILLABLE",
                    "Si evade a mano solo una richiesta CONFIRMED di un premio a evasione manuale (questa è " + r.status() + ").");
        }
        if (note == null || note.isBlank()) {
            throw LhException.validation("NOTE_REQUIRED", "Serve una nota di evasione (es. corriere, data di spedizione).");
        }
        String fullNote = tracking == null || tracking.isBlank() ? note.trim() : note.trim() + " · tracking " + tracking.trim();
        String actor = ActorHolder.get().asActorString();
        completeFulfilment(r, null, fullNote, requestedRef(r, actor), clock.instant(), actor);
        audit.record("REDEMPTION", r.id(), AuditEntry.Action.UPDATE, "Evasa a mano la richiesta " + r.id() + " (" + r.rewardName() + ")",
                Map.of("status", r.status().name()), Map.of("status", "FULFILLED", "note", fullNote));
        return view(r.id());
    }

    /**
     * Annullo con rimborso ({@code F-RWD-07}) da {@code CONFIRMED}: stock ripristinato, coupon eventualmente emesso
     * {@code VOID}, fatto {@code cancelled} con {@code refund=true} → il wallet restituisce i punti.
     */
    @Transactional
    public RedemptionView cancelWithRefund(String id, String reason) {
        Redemption r = redemptions.lock(id).orElseThrow(() -> LhException.notFound("Richiesta non trovata: " + id));
        if (r.status() != RedemptionStatus.CONFIRMED) {
            throw LhException.conflict("REDEMPTION_NOT_CANCELLABLE",
                    "Si annulla con rimborso solo una richiesta CONFIRMED, prima dell'evasione (questa è " + r.status() + ").");
        }
        if (reason == null || reason.isBlank()) {
            throw LhException.validation("REASON_REQUIRED", "Serve il motivo dell'annullo.");
        }
        Instant now = clock.instant();
        String actor = ActorHolder.get().asActorString();
        redemptions.close(r.id(), RedemptionStatus.CANCELLED, reason.trim(), now);
        rewards.releaseStock(r.rewardCode());
        couponRepository.findByRedemption(r.id())
                .filter(c -> c.status() == CouponStatus.ISSUED || c.status() == CouponStatus.AVAILABLE)
                .ifPresent(c -> couponRepository.markVoid(c.code(), now));
        redemptions.flagAttention(r.id(), false);
        redemptions.addHistory(Ulid.next(clock), r.id(), RedemptionStatus.CANCELLED, "Annullata con rimborso: " + reason.trim(), actor, now);
        outbox.write(events.childOf(requestedRef(r, actor), LhEventTypes.Fact.REWARD_REDEMPTION_CANCELLED,
                cancelledData(r, reason.trim(), true)));
        audit.record("REDEMPTION", r.id(), AuditEntry.Action.UPDATE, "Annullata con rimborso la richiesta " + r.id() + " (" + reason.trim() + ")",
                Map.of("status", r.status().name()), Map.of("status", "CANCELLED", "refund", true));
        return view(r.id());
    }

    /** Nuovo tentativo di evasione automatica dopo una nuova generazione di codici (pool era vuoto, {@code needsAttention}). */
    @Transactional
    public RedemptionView retryFulfilment(String id) {
        Redemption r = redemptions.lock(id).orElseThrow(() -> LhException.notFound("Richiesta non trovata: " + id));
        if (r.status() != RedemptionStatus.CONFIRMED || !r.needsAttention()) {
            throw LhException.conflict("REDEMPTION_NOT_RETRYABLE", "Si ritenta solo una richiesta CONFIRMED da verificare.");
        }
        String actor = ActorHolder.get().asActorString();
        fulfil(r, requestedRef(r, actor));
        Redemption after = redemptions.find(r.id()).orElseThrow();
        if (after.status() != RedemptionStatus.FULFILLED) {
            throw LhException.conflict("COUPON_POOL_EMPTY", "Il pool coupon del premio è ancora vuoto: genera nuovi codici in «Coupon».");
        }
        audit.record("REDEMPTION", r.id(), AuditEntry.Action.UPDATE, "Nuovo tentativo di evasione riuscito per " + r.id(),
                Map.of("needsAttention", true), Map.of("status", "FULFILLED"));
        return view(r.id());
    }

    // ---------- evasione ----------

    /**
     * Evasione dopo la conferma: {@code AUTO_COUPON} emette un codice del pool (pool vuoto → resta {@code CONFIRMED}
     * con {@code needsAttention}); {@code INSTANT} è subito {@code FULFILLED}; {@code MANUAL} resta in coda a BO-13.
     */
    void fulfil(Redemption r, LhEvent<?> cause) {
        Reward reward = rewards.findByCode(r.rewardCode()).orElse(null);
        String fulfilment = reward == null ? "MANUAL" : reward.fulfilment();
        Instant now = clock.instant();
        switch (fulfilment) {
            case "AUTO_COUPON" -> {
                Optional<Coupon> coupon = couponRepository.findByRedemption(r.id());
                if (coupon.isEmpty() && reward.couponPoolId() != null) {
                    coupon = coupons.issue(reward.couponPoolId(), r.memberId(), r.rewardCode(), "REDEMPTION", r.id(), null, cause);
                }
                if (coupon.isEmpty()) {
                    redemptions.flagAttention(r.id(), true);
                    redemptions.addHistory(Ulid.next(clock), r.id(), RedemptionStatus.CONFIRMED,
                            "Pool coupon esaurito: da evadere dopo una nuova generazione", "SYSTEM", now);
                    log.warn("Richiesta {}: pool coupon del premio {} esaurito, needsAttention", r.id(), r.rewardCode());
                    return;
                }
                completeFulfilment(r, coupon.get().code(), null, cause, now, "SYSTEM");
            }
            case "INSTANT" -> completeFulfilment(r, null, null, cause, now, "SYSTEM");
            default -> { } // MANUAL: evasione da BO-13 (fulfilManually)
        }
    }

    private void completeFulfilment(Redemption r, String couponCode, String note, LhEvent<?> cause, Instant now, String actor) {
        redemptions.fulfil(r.id(), couponCode, note, now);
        redemptions.addHistory(Ulid.next(clock), r.id(), RedemptionStatus.FULFILLED,
                couponCode != null ? "Coupon emesso: " + couponCode : note != null ? "Evasa: " + note : "Evasa", actor, now);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("redemptionId", r.id());
        data.put("rewardCode", r.rewardCode());
        if (couponCode != null) {
            data.put("couponCode", couponCode);
        }
        if (note != null) {
            data.put("note", note);
        }
        outbox.write(events.childOf(cause, LhEventTypes.Fact.REWARD_REDEMPTION_FULFILLED, data));
    }

    // ---------- letture ----------

    @Transactional(readOnly = true)
    public RedemptionView view(String id) {
        Redemption r = redemptions.find(id).orElseThrow(() -> LhException.notFound("Richiesta non trovata: " + id));
        return toView(r, redemptions.history(r.id()));
    }

    @Transactional(readOnly = true)
    public List<RedemptionView> byMember(String memberId) {
        return redemptions.byMember(memberId).stream().map(r -> toView(r, null)).toList();
    }

    @Transactional(readOnly = true)
    public PageResponse<RedemptionView> search(String status, String fulfilment, String memberId, String rewardCode,
                                               Boolean needsAttention, Instant from, Instant to, int page, int size) {
        int s = Math.max(1, Math.min(size, 200));
        int p = Math.max(0, page);
        List<RedemptionView> items = redemptions.search(status, fulfilment, memberId, rewardCode, needsAttention, from, to, p, s)
                .stream().map(r -> toView(r, null)).toList();
        return PageResponse.of(items, p, s,
                redemptions.count(status, fulfilment, memberId, rewardCode, needsAttention, from, to));
    }

    // ---------- interni ----------

    private void reject(Redemption r, String reason, LhEvent<?> cause) {
        Instant now = clock.instant();
        redemptions.close(r.id(), RedemptionStatus.REJECTED, reason, now);
        rewards.releaseStock(r.rewardCode());
        redemptions.addHistory(Ulid.next(clock), r.id(), RedemptionStatus.REJECTED, "Respinta: " + reason, "SYSTEM", now);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("redemptionId", r.id());
        data.put("reason", reason);
        outbox.write(events.childOf(cause, LhEventTypes.Fact.REWARD_REDEMPTION_REJECTED, data));
    }

    private static Map<String, Object> cancelledData(Redemption r, String reason, boolean refund) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("redemptionId", r.id());
        data.put("reason", reason);
        data.put("refund", refund);
        data.put("pointsCost", r.pointsCost());
        return data;
    }

    /**
     * Riferimento al fatto di richiesta originale (radice della saga: id = correlationId), per legare allo stesso
     * tracciato gli esiti che non nascono da un evento ricevuto (timeout, annullo del membro).
     */
    private static LhEvent<Void> requestedRef(Redemption r) {
        return requestedRef(r, r.actor());
    }

    /** Come {@link #requestedRef(Redemption)} ma con l'attore dell'azione in corso (es. l'operatore di BO-13). */
    private static LhEvent<Void> requestedRef(Redemption r, String actor) {
        return new LhEvent<>(LhEvent.SPEC_VERSION, r.correlationId(), LhSource.service("reward"),
                LhEventTypes.Fact.REWARD_REDEMPTION_REQUESTED, "member:" + r.memberId(), r.requestedAt(),
                LhEvent.DATA_CONTENT_TYPE, null, LhEvent.TENANT, r.correlationId(), null, 0, actor, null);
    }

    private static String redemptionIdOf(LhEvent<JsonNode> e) {
        return e.data() == null ? null : e.data().path("redemptionId").asString(null);
    }

    private RedemptionView toView(Redemption r, List<RedemptionRepository.HistoryItem> history) {
        JsonNode shipping = r.shippingJson() == null ? null : mapper.readTree(r.shippingJson());
        return new RedemptionView(r.id(), r.memberId(), r.rewardCode(), r.rewardName(), r.pointsCost(), r.status(),
                r.rejectReason(), r.needsAttention(), r.couponCode(), r.fulfilmentNote(), shipping, r.correlationId(),
                r.requestedAt(), r.confirmedAt(), r.closedAt(), history);
    }
}
