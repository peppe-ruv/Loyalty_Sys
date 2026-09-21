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
import io.loyaltyhub.wallet.infra.CurrencyRepository;
import io.loyaltyhub.wallet.infra.LedgerRepository;
import io.loyaltyhub.wallet.infra.MemberTierRepository;
import io.loyaltyhub.wallet.infra.PointsLotRepository;
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
import java.time.temporal.ChronoUnit;
import java.util.List;

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
    private final LhEventFactory events;
    private final OutboxWriter outbox;
    private final ObjectMapper mapper;
    private final Clock clock;

    public WalletService(WalletRepository wallets, LedgerRepository ledger, TierRepository tiers,
                         MemberTierRepository memberTiers, CurrencyRepository currencies, PointsLotRepository lots,
                         LhEventFactory events, OutboxWriter outbox, ObjectMapper mapper, Clock clock) {
        this.wallets = wallets;
        this.ledger = ledger;
        this.tiers = tiers;
        this.memberTiers = memberTiers;
        this.currencies = currencies;
        this.lots = lots;
        this.events = events;
        this.outbox = outbox;
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
        Instant expiresAt = ExpiryPolicy.expiresAt(expiryPolicy(currency), occurredAt);

        long balanceAfter;
        if (pending) {
            wallets.creditPending(memberId, currency, finalAmount);
            balanceAfter = wallets.find(memberId, currency).map(w -> w.balanceActive()).orElse(0L);
        } else {
            balanceAfter = wallets.credit(memberId, currency, finalAmount);
            if (currency.equals("STS")) {
                memberTiers.addPeriodSts(memberId, finalAmount); // la salita di livello è M3.3
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

    /**
     * Rilascia i lotti {@code PENDING} il cui {@code available_at} è arrivato (docs §5, F-WAL-05). Sposta il
     * saldo da {@code pending} ad {@code active}, scrive un movimento {@code RELEASE} e produce
     * {@code wallet.points.released}. Idempotente: un lotto già {@code ACTIVE} non viene rilasciato due volte.
     * Il job schedulato e l'endpoint demo con {@code asOf} arrivano in M3.2.
     */
    @Transactional
    public int releasePending(Instant asOf) {
        List<PointsLot> due = lots.findDuePending(asOf);
        for (PointsLot lot : due) {
            wallets.lock(lot.memberId(), lot.currency());
            lots.markActive(lot.id());
            long balanceAfter = wallets.release(lot.memberId(), lot.currency(), lot.remaining());
            if (lot.currency().equals("STS")) {
                memberTiers.addPeriodSts(lot.memberId(), lot.remaining());
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
        }
        if (!due.isEmpty()) {
            log.info("Rilasciati {} lotti in attesa (asOf={})", due.size(), asOf);
        }
        return due.size();
    }

    private JsonNode expiryPolicy(String currency) {
        return currencies.findByCode(currency)
                .map(c -> c.expiryPolicyJson())
                .filter(j -> j != null && !j.isBlank())
                .map(mapper::readTree)
                .orElse(null);
    }

    // ---------- interni ----------

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
