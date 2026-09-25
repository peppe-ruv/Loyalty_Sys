package io.loyaltyhub.wallet.application;

import io.loyaltyhub.common.event.LhEvent;
import io.loyaltyhub.common.event.LhEventFactory;
import io.loyaltyhub.common.event.LhEventTypes;
import io.loyaltyhub.common.ids.Ulid;
import io.loyaltyhub.common.outbox.OutboxWriter;
import io.loyaltyhub.wallet.domain.LedgerEntry;
import io.loyaltyhub.wallet.domain.MemberTier;
import io.loyaltyhub.wallet.domain.PointsLot;
import io.loyaltyhub.wallet.infra.LedgerRepository;
import io.loyaltyhub.wallet.infra.MemberTierRepository;
import io.loyaltyhub.wallet.infra.PointsLotRepository;
import io.loyaltyhub.wallet.infra.WalletRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Clock;
import java.time.Instant;

/**
 * Lato wallet della saga di richiesta premio (docs/03 §5, docs/servizi/wallet-service.md §4–5; F-WAL-04, F-WAL-08):
 * {@code reward.redemption.requested} → spesa FIFO sui lotti ({@code wallet.points.spent}) oppure rifiuto
 * ({@code wallet.spend.rejected}); {@code reward.redemption.cancelled} con {@code refund=true} → rimborso
 * ({@code wallet.points.refunded}). Sempre {@code PTS}, idempotente su {@code redemption_id}, sotto lock del wallet.
 */
@Service
public class RedemptionPayments {

    private static final Logger log = LoggerFactory.getLogger(RedemptionPayments.class);
    private static final String CURRENCY = "PTS";
    /** Validità minima del lotto di rimborso (docs/03 §4.2: "oggi + 30 giorni"). */
    static final java.time.Duration REFUND_MIN_VALIDITY = java.time.Duration.ofDays(30);

    private final WalletRepository wallets;
    private final LedgerRepository ledger;
    private final PointsLotRepository lots;
    private final MemberTierRepository memberTiers;
    private final LhEventFactory events;
    private final OutboxWriter outbox;
    private final ObjectMapper mapper;
    private final Clock clock;

    public RedemptionPayments(WalletRepository wallets, LedgerRepository ledger, PointsLotRepository lots,
                              MemberTierRepository memberTiers,
                              LhEventFactory events, OutboxWriter outbox, ObjectMapper mapper, Clock clock) {
        this.wallets = wallets;
        this.ledger = ledger;
        this.lots = lots;
        this.memberTiers = memberTiers;
        this.events = events;
        this.outbox = outbox;
        this.mapper = mapper;
        this.clock = clock;
    }

    /** Spesa per una richiesta premio: tutto o niente, i lotti che scadono prima vengono consumati per primi. */
    @Transactional
    public void spend(LhEvent<JsonNode> requested) {
        String memberId = requested.memberId();
        JsonNode d = requested.data();
        String redemptionId = d == null ? null : d.path("redemptionId").asString(null);
        long cost = d == null ? 0 : d.path("pointsCost").asLong(0);
        if (memberId == null || redemptionId == null || cost <= 0) {
            log.warn("reward.redemption.requested incompleto ({}): ignorato", requested.id());
            return;
        }
        wallets.ensureExists(memberId, CURRENCY);
        memberTiers.ensureBase(memberId);
        wallets.lock(memberId, CURRENCY);
        if (ledger.findByRedemption(redemptionId, "SPEND").isPresent()) {
            return; // già speso: stessa richiesta rielaborata
        }
        long available = wallets.find(memberId, CURRENCY).map(w -> w.balanceActive()).orElse(0L);
        String status = memberTiers.find(memberId).map(MemberTier::memberStatus).orElse("ACTIVE");
        if (status != null && !"ACTIVE".equals(status)) {
            reject(requested, redemptionId, "MEMBER_NOT_ACTIVE", cost, available);
            return;
        }
        if (available < cost) {
            reject(requested, redemptionId, "INSUFFICIENT_BALANCE", cost, available);
            return;
        }

        Instant now = clock.instant();
        String ledgerId = Ulid.next(clock);
        long balanceAfter = wallets.debit(memberId, CURRENCY, cost);
        ObjectNode meta = mapper.createObjectNode();
        meta.put("rewardCode", d.path("rewardCode").asString(""));
        LedgerEntry entry = new LedgerEntry(ledgerId, memberId, CURRENCY, "SPEND", cost, "-", balanceAfter, now,
                "REDEMPTION", null, "Premio: " + d.path("rewardName").asString(d.path("rewardCode").asString("")),
                meta.toString());
        ledger.insert(entry, null, null, requested.lhcorrelationid(), requested.lhactor(), redemptionId);
        lots.consumeFifo(memberId, CURRENCY, cost, ledgerId);

        ObjectNode fact = mapper.createObjectNode();
        fact.put("ledgerEntryId", ledgerId);
        fact.put("currency", CURRENCY);
        fact.put("amount", cost);
        fact.put("balanceAfter", balanceAfter);
        fact.put("redemptionId", redemptionId);
        outbox.write(events.childOf(requested, LhEventTypes.Fact.WALLET_POINTS_SPENT, fact));
    }

    /**
     * Rimborso di una richiesta annullata dopo la spesa (docs/03 §4.2): tutto l'importo speso torna in un lotto
     * <b>nuovo</b> con {@code expiresAt = max(scadenza più lontana tra i lotti consumati, oggi + 30 giorni)}
     * ({@link #refundExpiry}). I lotti d'origine restano come sono. Supera il default di Q-54 (vedi Q-160).
     */
    @Transactional
    public void refund(LhEvent<JsonNode> cancelled) {
        JsonNode d = cancelled.data();
        if (d == null || !d.path("refund").asBoolean(false)) {
            return;
        }
        String redemptionId = d.path("redemptionId").asString(null);
        if (redemptionId == null) {
            return;
        }
        var spend = ledger.findByRedemption(redemptionId, "SPEND");
        if (spend.isEmpty()) {
            log.warn("Rimborso per la richiesta {} senza spesa registrata: niente da restituire", redemptionId);
            return;
        }
        String memberId = spend.get().memberId();
        wallets.lock(memberId, CURRENCY);
        if (ledger.findByRedemption(redemptionId, "REFUND").isPresent()) {
            return; // già rimborsata
        }
        long amount = spend.get().amount();
        Instant now = clock.instant();
        String ledgerId = Ulid.next(clock);
        Instant expiresAt = refundExpiry(lots.consumptions(spend.get().id()), now);
        lots.insert(new PointsLot(Ulid.next(clock), memberId, CURRENCY, amount, amount,
                PointsLot.ACTIVE, now, null, expiresAt, ledgerId));
        long balanceAfter = wallets.refund(memberId, CURRENCY, amount);
        ObjectNode meta = mapper.createObjectNode();
        meta.put("reason", d.path("reason").asString(""));
        LedgerEntry entry = new LedgerEntry(ledgerId, memberId, CURRENCY, "REFUND", amount, "+", balanceAfter, now,
                "REDEMPTION", null, "Rimborso richiesta premio", meta.toString());
        ledger.insert(entry, null, null, cancelled.lhcorrelationid(), cancelled.lhactor(), redemptionId);

        ObjectNode fact = mapper.createObjectNode();
        fact.put("redemptionId", redemptionId);
        fact.put("amount", amount);
        fact.put("balanceAfter", balanceAfter);
        outbox.write(events.childOf(cancelled, LhEventTypes.Fact.WALLET_POINTS_REFUNDED, fact));
    }

    /**
     * Scadenza del lotto di rimborso (docs/03 §4.2): {@code max(scadenza originaria più lontana, now + 30 giorni)}. Un
     * lotto consumato senza scadenza ({@code null}, policy {@code NEVER}) rende senza scadenza anche il rimborso. Nessun
     * consumo registrato (spese storiche del seed) → {@code now + 30 giorni}.
     */
    // SPEC-GAP: Q-160 — lotto consumato senza scadenza e spesa senza consumi non sono coperti da docs/03 §4.2.
    static Instant refundExpiry(java.util.List<PointsLotRepository.Consumption> consumed, Instant now) {
        Instant result = now.plus(REFUND_MIN_VALIDITY);
        for (PointsLotRepository.Consumption c : consumed) {
            if (c.expiresAt() == null) {
                return null;
            }
            if (c.expiresAt().isAfter(result)) {
                result = c.expiresAt();
            }
        }
        return result;
    }

    private void reject(LhEvent<JsonNode> requested, String redemptionId, String reason, long cost, long available) {
        ObjectNode fact = mapper.createObjectNode();
        fact.put("redemptionId", redemptionId);
        fact.put("reason", reason);
        fact.put("requested", cost);
        fact.put("available", available);
        outbox.write(events.childOf(requested, LhEventTypes.Fact.WALLET_SPEND_REJECTED, fact));
    }
}
