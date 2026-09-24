package io.loyaltyhub.wallet.application;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import io.loyaltyhub.common.event.LhEvent;
import io.loyaltyhub.common.event.LhEventFactory;
import io.loyaltyhub.common.event.LhEventTypes;
import io.loyaltyhub.common.ids.Ulid;
import io.loyaltyhub.common.outbox.OutboxWriter;
import io.loyaltyhub.wallet.domain.ExpiryPolicy;
import io.loyaltyhub.wallet.domain.LedgerEntry;
import io.loyaltyhub.wallet.domain.PointsLot;
import io.loyaltyhub.wallet.domain.Tier;
import io.loyaltyhub.wallet.domain.TierHistory;
import io.loyaltyhub.wallet.infra.CurrencyRepository;
import io.loyaltyhub.wallet.infra.EditionRepository;
import io.loyaltyhub.wallet.infra.LedgerRepository;
import io.loyaltyhub.wallet.infra.MemberTierRepository;
import io.loyaltyhub.wallet.infra.PointsLotRepository;
import io.loyaltyhub.wallet.infra.TierHistoryRepository;
import io.loyaltyhub.wallet.infra.TierRepository;
import io.loyaltyhub.wallet.infra.WalletRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import io.loyaltyhub.common.audit.AuditEntry;
import io.loyaltyhub.common.audit.AuditPublisher;
import io.loyaltyhub.common.web.LhException;
import io.loyaltyhub.common.web.ActorHolder;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Applica gli effetti {@code points.grant} al libro mastro (docs/03 §4, docs/servizi/wallet-service.md §5).
 * Il wallet non decide <em>quanti</em> punti dare (lo fa il motore): applica solo il moltiplicatore di tier,
 * scrive il movimento {@code EARN} e produce {@code wallet.points.earned}. Idempotente su {@code effect_id};
 * se il membro non ha wallet lo crea "on the fly". Lotti, scadenze, salita di livello ed edizioni: M3.
 */
@Service
public class WalletService {

    private static final Logger log = LoggerFactory.getLogger(WalletService.class);
    private static final List<String> CURRENCIES = List.of("PTS", "STS");

    private final WalletRepository wallets;
    private final LedgerRepository ledger;
    private final TierRepository tiers;
    private final MemberTierRepository memberTiers;
    private final CurrencyRepository currencies;
    private final PointsLotRepository lots;
    private final TierHistoryRepository tierHistory;
    private final EditionRepository editions;
    private final LhEventFactory events;
    private final OutboxWriter outbox;
    private final AuditPublisher audit;
    private final ObjectMapper mapper;
    private final Clock clock;

    public WalletService(WalletRepository wallets, LedgerRepository ledger, TierRepository tiers,
                         MemberTierRepository memberTiers, CurrencyRepository currencies, PointsLotRepository lots,
                         TierHistoryRepository tierHistory, EditionRepository editions, LhEventFactory events,
                         OutboxWriter outbox, AuditPublisher audit, ObjectMapper mapper, Clock clock) {
        this.wallets = wallets;
        this.ledger = ledger;
        this.tiers = tiers;
        this.memberTiers = memberTiers;
        this.currencies = currencies;
        this.lots = lots;
        this.tierHistory = tierHistory;
        this.editions = editions;
        this.events = events;
        this.outbox = outbox;
        this.audit = audit;
        this.mapper = mapper;
        this.clock = clock;
    }

    /** Crea i due wallet e il livello BASE per un nuovo membro (idempotente). */
    @Transactional
    public void createWalletsForMember(String memberId) {
        for (String currency : CURRENCIES) {
            wallets.ensureExists(memberId, currency);
        }
        memberTiers.ensureBase(memberId);
    }

    @Transactional
    public void updateMemberStatus(String memberId, String status) {
        memberTiers.updateStatus(memberId, status);
    }

    /** Esito di una rettifica manuale: movimento creato e saldo attivo risultante. */
    public record AdjustmentResult(String ledgerEntryId, long balanceAfter) {
    }

    /** Gestisce le rettifiche manuali del wallet (docs/servizi/wallet-service.md §3). */
    @Transactional
    public AdjustmentResult adjustBalance(String memberId, String currency, String direction, long amount, String reason, String note) {
        if (!"PTS".equals(currency)) {
            // SPEC-GAP: Q-46 - STS and other currencies not currently adjustable
            throw LhException.validation("CURRENCY_NOT_ADJUSTABLE", "La valuta specificata non è modificabile manualmente.");
        }
        if (amount <= 0) {
            throw LhException.validation("INVALID_AMOUNT", "L'importo deve essere maggiore di 0.");
        }
        if (note == null || note.trim().length() < 10) {
            throw LhException.validation("NOTE_TOO_SHORT", "La nota deve contenere almeno 10 caratteri.");
        }
        if (reason == null || !Set.of("GOODWILL", "CORRECTION", "COMPLAINT", "TEST").contains(reason)) {
            // SPEC-GAP: Q-45 - docs/03 §5 vs wallet-service.md list of reasons. Choosing wallet-service.md.
            throw LhException.validation("INVALID_REASON", "Motivo della rettifica non valido.");
        }
        if (direction == null || (!"CREDIT".equals(direction) && !"DEBIT".equals(direction))) {
            throw LhException.validation("INVALID_DIRECTION", "La direzione deve essere CREDIT o DEBIT.");
        }
        // SPEC-GAP: Q-B8 — F-MBR-05/docs/08 BO-03: dopo l'anonimizzazione "le azioni sono disabilitate"; il wallet
        // rifiuta anche le rettifiche (i movimenti esistenti restano). Gli altri stati non ACTIVE restano rettificabili.
        if (memberTiers.find(memberId).map(t -> "ANONYMIZED".equals(t.memberStatus())).orElse(false)) {
            throw LhException.conflict("MEMBER_ANONYMIZED", "Il membro " + memberId + " è anonimizzato: niente rettifiche.");
        }

        wallets.ensureExists(memberId, currency);
        memberTiers.ensureBase(memberId);
        wallets.lock(memberId, currency);

        String ledgerId = Ulid.next(clock);
        Instant asOf = clock.instant();
        String actorStr = ActorHolder.get() != null ? ActorHolder.get().asActorString() : "SYSTEM";
        long balanceAfter = 0;

        if ("CREDIT".equals(direction)) {
            balanceAfter = wallets.credit(memberId, currency, amount);
            Instant expiresAt = ExpiryPolicy.expiresAt(expiryPolicy(currency), asOf, editions::findContaining);

            lots.insert(new PointsLot(Ulid.next(clock), memberId, currency, amount, amount,
                    PointsLot.ACTIVE, asOf, null, expiresAt, ledgerId));

            LedgerEntry entry = new LedgerEntry(ledgerId, memberId, currency, "ADJUST_CREDIT", amount, "+",
                    balanceAfter, asOf, "MANUAL", null, reason, metadataAdjust(reason, note));
            ledger.insert(entry, null, null, null, actorStr);

        } else { // DEBIT
            long currentActive = wallets.find(memberId, currency).map(w -> w.balanceActive()).orElse(0L);
            if (amount > currentActive) {
                throw LhException.validation("INSUFFICIENT_BALANCE", "Saldo insufficiente per l'addebito.");
            }

            balanceAfter = wallets.debit(memberId, currency, amount);

            LedgerEntry entry = new LedgerEntry(ledgerId, memberId, currency, "ADJUST_DEBIT", amount, "-",
                    balanceAfter, asOf, "MANUAL", null, reason, metadataAdjust(reason, note));
            ledger.insert(entry, null, null, null, actorStr);

            lots.consumeFifo(memberId, currency, amount, ledgerId);
        }

        // Outbox Fact
        ObjectNode factData = mapper.createObjectNode();
        factData.put("direction", direction);
        factData.put("currency", currency);
        factData.put("amount", amount);
        factData.put("reason", reason);
        factData.put("note", note.trim());
        factData.put("balanceAfter", balanceAfter);
        outbox.write(events.newRoot(LhEventTypes.Fact.WALLET_POINTS_ADJUSTED, "member:" + memberId, factData));

        // Audit (docs/05 §6): stesso canale delle altre scritture del wallet.
        long balanceBefore = balanceAfter + ("CREDIT".equals(direction) ? -amount : amount);
        audit.record("wallet", memberId + ":" + currency, AuditEntry.Action.ADJUST,
                ("CREDIT".equals(direction) ? "Accredito" : "Addebito") + " manuale di " + amount + " " + currency + " (" + reason + ")",
                Map.of("balance", balanceBefore), Map.of("balance", balanceAfter));

        return new AdjustmentResult(ledgerId, balanceAfter);
    }

    /** Applica un effetto {@code points.grant} (docs §5, EVT-EFF-01). */
    @Transactional
    public void applyGrant(LhEvent<JsonNode> effectEvent) {
        JsonNode d = effectEvent.data();
        String effectId = text(d, "effectId");
        if (effectId == null) {
            log.warn("points.grant senza effectId, ignorato");
            return;
        }
        if (ledger.existsByEffectId(effectId)) {
            return; // idempotenza di dominio
        }
        String memberId = effectEvent.memberId();
        if (memberId == null) {
            log.warn("points.grant senza membro nel subject, ignorato: {}", effectEvent.subject());
            return;
        }
        String currency = d.path("currency").asString("PTS");

        // Wallet "on the fly" se il fatto member.registered non è ancora arrivato (docs §5).
        wallets.ensureExists(memberId, currency);
        memberTiers.ensureBase(memberId);
        wallets.lock(memberId, currency);

        String tierCode = memberTiers.find(memberId).map(mt -> mt.tierCode()).orElse("BASE");
        Tier tier = tiers.findByCode(tierCode).orElse(null);
        BigDecimal multiplier = tier != null ? tier.multiplier() : BigDecimal.ONE;

        long grantedAmount = d.path("amount").asLong(0);
        boolean tierApplies = d.path("tierMultiplierApplies").asBoolean(false) && currency.equals("PTS");
        long finalAmount = tierApplies
                ? BigDecimal.valueOf(grantedAmount).multiply(multiplier).setScale(0, RoundingMode.FLOOR).longValueExact()
                : grantedAmount;
        if (finalAmount <= 0) {
            return; // niente da accreditare
        }

        // Lotto (docs/03 §4.2): pendingDays > 0 ⇒ PENDING fino ad availableAt, poi rilasciato ad ACTIVE.
        // Il consumo FIFO (lot_consumption) arriva con la spesa in M4.
        Instant occurredAt = effectEvent.time() != null ? effectEvent.time() : clock.instant();
        int pendingDays = d.path("pendingDays").asInt(0);
        boolean pending = pendingDays > 0;
        Instant availableAt = pending ? occurredAt.plus(pendingDays, ChronoUnit.DAYS) : null;
        Instant expiresAt = ExpiryPolicy.expiresAt(expiryPolicy(currency), occurredAt, editions::findContaining);

        long balanceAfter;
        if (pending) {
            wallets.creditPending(memberId, currency, finalAmount);
            balanceAfter = wallets.find(memberId, currency).map(w -> w.balanceActive()).orElse(0L);
        } else {
            balanceAfter = wallets.credit(memberId, currency, finalAmount);
            if (currency.equals("STS")) {
                memberTiers.addPeriodSts(memberId, finalAmount);
                applyTierUpgrade(memberId, effectEvent); // salita immediata nello stesso commit (docs §4.3)
            }
        }

        String ledgerId = Ulid.next(clock);
        LedgerEntry entry = new LedgerEntry(ledgerId, memberId, currency, "EARN", finalAmount, "+",
                balanceAfter, occurredAt, "CAMPAIGN", text(d, "campaignCode"), text(d, "description"),
                metadata(d, tierCode, multiplier));
        ledger.insert(entry, effectId, text(d, "actionId"), effectEvent.lhcorrelationid(), effectEvent.lhactor());

        lots.insert(new PointsLot(Ulid.next(clock), memberId, currency, finalAmount, finalAmount,
                pending ? PointsLot.PENDING : PointsLot.ACTIVE, occurredAt, availableAt, expiresAt, ledgerId));

        outbox.write(events.childSameBusinessTime(effectEvent, LhEventTypes.Fact.WALLET_POINTS_EARNED,
                earnedData(ledgerId, effectId, d, currency, tierCode, multiplier, finalAmount, balanceAfter,
                        pending, availableAt, expiresAt)));
    }

    /** Giorni entro cui un lotto in scadenza viene preavvisato (docs §5, EVT-FACT-25). */
    private static final int WARN_WINDOW_DAYS = 30;

    /** Esito di un job demo: lotti toccati, membri distinti, punti coinvolti. */
    public record JobOutcome(int lots, int members, long amount) {
    }

    /**
     * Rilascia i lotti {@code PENDING} il cui {@code available_at} è arrivato (docs §5, F-WAL-05). Sposta il
     * saldo da {@code pending} ad {@code active}, scrive un movimento {@code RELEASE} e produce
     * {@code wallet.points.released}. Idempotente: un lotto già {@code ACTIVE} non viene rilasciato due volte.
     */
    @Transactional
    public JobOutcome releasePending(Instant asOf) {
        List<PointsLot> due = lots.findDuePending(asOf);
        java.util.Set<String> members = new java.util.HashSet<>();
        long total = 0;
        for (PointsLot lot : due) {
            wallets.lock(lot.memberId(), lot.currency());
            lots.markActive(lot.id());
            long balanceAfter = wallets.release(lot.memberId(), lot.currency(), lot.remaining());
            if (lot.currency().equals("STS")) {
                memberTiers.addPeriodSts(lot.memberId(), lot.remaining());
                applyTierUpgrade(lot.memberId(), null);
            }
            String ledgerId = Ulid.next(clock);
            LedgerEntry entry = new LedgerEntry(ledgerId, lot.memberId(), lot.currency(), "RELEASE",
                    lot.remaining(), "+", balanceAfter, asOf, "SYSTEM", null,
                    "Rilascio punti in attesa", null);
            ledger.insert(entry, null, null, null, "system:jobs");

            ObjectNode data = mapper.createObjectNode();
            data.put("ledgerEntryId", ledgerId);
            data.put("lotId", lot.id());
            data.put("currency", lot.currency());
            data.put("amount", lot.remaining());
            data.put("balanceAfter", balanceAfter);
            outbox.write(events.newRoot(LhEventTypes.Fact.WALLET_POINTS_RELEASED, "member:" + lot.memberId(), data));
            members.add(lot.memberId());
            total += lot.remaining();
        }
        if (!due.isEmpty()) {
            log.info("Rilasciati {} lotti ({} punti) in attesa (asOf={})", due.size(), total, asOf);
        }
        return new JobOutcome(due.size(), members.size(), total);
    }

    /**
     * Scade i lotti {@code ACTIVE} con {@code expires_at ≤ asOf} (docs §5, F-WAL-06). Riduce il saldo attivo,
     * incrementa {@code lifetime_expired}, scrive un movimento {@code EXPIRE} e produce
     * {@code wallet.points.expired}. Idempotente: un lotto già {@code EXPIRED} non viene scaduto due volte.
     */
    @Transactional
    public JobOutcome expirePoints(Instant asOf) {
        List<PointsLot> expired = lots.findActiveExpired(asOf);
        java.util.Set<String> members = new java.util.HashSet<>();
        long total = 0;
        for (PointsLot lot : expired) {
            wallets.lock(lot.memberId(), lot.currency());
            long amount = lot.remaining();
            lots.markExpired(lot.id());
            long balanceAfter = wallets.expire(lot.memberId(), lot.currency(), amount);
            String ledgerId = Ulid.next(clock);
            LedgerEntry entry = new LedgerEntry(ledgerId, lot.memberId(), lot.currency(), "EXPIRE",
                    amount, "-", balanceAfter, asOf, "SYSTEM", null, "Scadenza punti", null);
            ledger.insert(entry, null, null, null, "system:jobs");

            ObjectNode data = mapper.createObjectNode();
            data.put("ledgerEntryId", ledgerId);
            data.put("lotId", lot.id());
            data.put("currency", lot.currency());
            data.put("amount", amount);
            data.put("balanceAfter", balanceAfter);
            outbox.write(events.newRoot(LhEventTypes.Fact.WALLET_POINTS_EXPIRED, "member:" + lot.memberId(), data));
            members.add(lot.memberId());
            total += amount;
        }
        if (!expired.isEmpty()) {
            log.info("Scaduti {} lotti ({} punti) per {} membri (asOf={})", expired.size(), total, members.size(), asOf);
        }
        return new JobOutcome(expired.size(), members.size(), total);
    }

    /**
     * Preavvisa i lotti {@code ACTIVE} in scadenza entro {@value #WARN_WINDOW_DAYS} giorni da {@code asOf}
     * (docs §5, EVT-FACT-25). Una volta per lotto (flag {@code warned}); produce {@code wallet.points.expiring}.
     */
    @Transactional
    public JobOutcome expiryWarnings(Instant asOf) {
        Instant until = asOf.plus(WARN_WINDOW_DAYS, ChronoUnit.DAYS);
        List<PointsLot> warnable = lots.findWarnable(asOf, until);
        java.util.Set<String> members = new java.util.HashSet<>();
        long total = 0;
        for (PointsLot lot : warnable) {
            lots.markWarned(lot.id());
            ObjectNode data = mapper.createObjectNode();
            data.put("lotId", lot.id());
            data.put("currency", lot.currency());
            data.put("amount", lot.remaining());
            if (lot.expiresAt() != null) {
                data.put("expiresAt", lot.expiresAt().toString());
            }
            outbox.write(events.newRoot(LhEventTypes.Fact.WALLET_POINTS_EXPIRING, "member:" + lot.memberId(), data));
            members.add(lot.memberId());
            total += lot.remaining();
        }
        if (!warnable.isEmpty()) {
            log.info("Preavvisati {} lotti ({} punti) in scadenza per {} membri (asOf={})",
                    warnable.size(), total, members.size(), asOf);
        }
        return new JobOutcome(warnable.size(), members.size(), total);
    }

    /**
     * Salita immediata di livello (docs/03 §4.3, F-TIER-02): dopo un accredito {@code STS}, se {@code periodSts}
     * raggiunge la soglia di un livello di rank superiore, porta il membro al <em>più alto</em> livello raggiunto,
     * scrive lo storico ({@code UPGRADE}) e produce {@code tier.upgraded}. Nessuna discesa qui (è a chiusura
     * edizione, M3.4). {@code parent} presente ⇒ il fatto resta nell'albero dell'azione che l'ha generato.
     */
    private void applyTierUpgrade(String memberId, LhEvent<JsonNode> parent) {
        var mt = memberTiers.find(memberId).orElse(null);
        if (mt == null) {
            return;
        }
        List<Tier> scale = tiers.findAllByRank();
        Tier current = scale.stream().filter(t -> t.code().equals(mt.tierCode())).findFirst().orElse(null);
        Tier earned = scale.stream()
                .filter(t -> t.thresholdSts() <= mt.periodSts())
                .max(java.util.Comparator.comparingInt(Tier::rank)).orElse(null);
        if (current == null || earned == null || earned.rank() <= current.rank()) {
            return;
        }
        memberTiers.upgrade(memberId, earned.code(), current.code());
        tierHistory.insert(new TierHistory(Ulid.next(clock), memberId, current.code(), earned.code(),
                TierHistory.UPGRADE, null, clock.instant()));

        ObjectNode data = mapper.createObjectNode();
        data.put("previousTier", current.code());
        data.put("newTier", earned.code());
        data.put("periodSts", mt.periodSts());
        LhEvent<ObjectNode> fact = parent != null
                ? events.childSameBusinessTime(parent, LhEventTypes.Fact.TIER_UPGRADED, data)
                : events.newRoot(LhEventTypes.Fact.TIER_UPGRADED, "member:" + memberId, data);
        outbox.write(fact);
        log.info("Salita livello {} → {} per {} (periodSts={})", current.code(), earned.code(), memberId, mt.periodSts());
    }

    private JsonNode expiryPolicy(String currency) {
        return currencies.findByCode(currency)
                .map(c -> c.expiryPolicyJson())
                .filter(j -> j != null && !j.isBlank())
                .map(mapper::readTree)
                .orElse(null);
    }

    // ---------- interni ----------

    private String metadataAdjust(String reason, String note) {
        ObjectNode m = mapper.createObjectNode();
        m.put("reason", reason);
        m.put("note", note.trim());
        return m.toString();
    }

    private String metadata(JsonNode d, String tierCode, BigDecimal multiplier) {
        ObjectNode m = mapper.createObjectNode();
        m.put("baseAmount", d.path("baseAmount").asLong(0));
        m.put("tierCode", tierCode);
        m.put("tierMultiplier", multiplier);
        m.put("campaignMultiplier", d.path("campaignMultiplier").asDouble(1.0));
        return m.toString();
    }

    private ObjectNode earnedData(String ledgerId, String effectId, JsonNode d, String currency,
                                  String tierCode, BigDecimal multiplier, long amount, long balanceAfter,
                                  boolean pending, Instant availableAt, Instant expiresAt) {
        ObjectNode data = mapper.createObjectNode();
        data.put("ledgerEntryId", ledgerId);
        data.put("effectId", effectId);
        if (d.hasNonNull("campaignCode")) {
            data.put("campaignCode", d.get("campaignCode").asString());
        }
        data.put("currency", currency);
        data.put("baseAmount", d.path("baseAmount").asLong(0));
        data.put("tierCode", tierCode);
        data.put("tierMultiplier", multiplier);
        data.put("amount", amount);
        data.put("balanceAfter", balanceAfter);
        data.put("pending", pending);
        if (availableAt != null) {
            data.put("availableAt", availableAt.toString());
        }
        if (expiresAt != null) {
            data.put("expiresAt", expiresAt.toString());
        }
        return data;
    }

    private static String text(JsonNode d, String field) {
        return d != null && d.hasNonNull(field) ? d.get(field).asString() : null;
    }
}
