package io.loyaltyhub.wallet.api;

import io.loyaltyhub.wallet.application.WalletQueryService;
import io.loyaltyhub.common.web.LhException;
import io.loyaltyhub.wallet.domain.ExpiryPolicy;
import io.loyaltyhub.wallet.domain.LedgerEntry;
import io.loyaltyhub.wallet.domain.PointsLot;
import io.loyaltyhub.wallet.domain.Tier;
import io.loyaltyhub.common.web.RequiresRole;
import io.loyaltyhub.common.web.Role;
import io.loyaltyhub.wallet.application.WalletService;
import io.loyaltyhub.wallet.infra.LedgerRepository;
import io.loyaltyhub.wallet.infra.PointsLotRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/** Gestione wallet (docs/servizi/wallet-service.md §3): saldi, movimenti, scala dei livelli. */
@RestController
@RequestMapping("/v1")
public class WalletsController {

    private final WalletQueryService query;
    private final WalletService walletService;
    private final LedgerRepository ledger;
    private final PointsLotRepository lots;

    public WalletsController(WalletQueryService query, WalletService walletService, LedgerRepository ledger, PointsLotRepository lots) {
        this.query = query;
        this.walletService = walletService;
        this.ledger = ledger;
        this.lots = lots;
    }

    /** Lotto esposto (docs §3): senza {@code ledger_entry_id} e {@code member_id}, interni. */
    public record LotView(String id, String currency, long amount, long remaining, String status,
                          Instant earnedAt, Instant availableAt, Instant expiresAt) {
    }

    @GetMapping("/wallets/{memberId}")
    public WalletView wallet(@PathVariable String memberId) {
        return query.wallet(memberId);
    }

    /**
     * Movimento esposto (wallet-service §3, F-WAL-02): i campi del libro mastro più azione e attore, già salvati
     * ({@code action_id}, {@code actor}) e mostrati da BO-03.
     */
    public record LedgerEntryView(String id, String memberId, String currency, String type, long amount,
                                  String direction, long balanceAfter, Instant occurredAt, String sourceType,
                                  String campaignCode, String description, String metadataJson,
                                  String actionId, String actor) {
        static LedgerEntryView of(LedgerRepository.LedgerLine line) {
            LedgerEntry e = line.entry();
            return new LedgerEntryView(e.id(), e.memberId(), e.currency(), e.type(), e.amount(), e.direction(),
                    e.balanceAfter(), e.occurredAt(), e.sourceType(), e.campaignCode(), e.description(),
                    e.metadataJson(), line.actionId(), line.actor());
        }
    }

    /**
     * Libro mastro con i filtri di wallet-service §3: {@code currency}, {@code type} (uno o più, separati da virgola),
     * {@code from}/{@code to} sulla data di business (istante ISO o data {@code yyyy-MM-dd} in Europe/Rome, estremi
     * inclusi); ordinamento {@code occurredAt desc}.
     */
    @GetMapping("/wallets/{memberId}/ledger")
    public List<LedgerEntryView> ledger(
            @PathVariable String memberId,
            @RequestParam(required = false) String currency,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "100") int limit) {
        LedgerRepository.LedgerFilter filter = new LedgerRepository.LedgerFilter(currency, types(type),
                bound(from, "from", false), bound(to, "to", true));
        return ledger.search(memberId, filter, Math.min(Math.max(limit, 1), 500)).stream()
                .map(LedgerEntryView::of).toList();
    }

    private static List<String> types(String type) {
        if (type == null || type.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(type.split(",")).map(String::trim).filter(t -> !t.isEmpty()).toList();
    }

    /** Istante ISO, oppure data pura: inizio ({@code from}) o ultimo istante ({@code to}) del giorno a Roma. */
    private static Instant bound(String value, String name, boolean endOfDay) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            if (value.contains("T")) {
                return Instant.parse(value.trim());
            }
            java.time.LocalDate day = java.time.LocalDate.parse(value.trim());
            return endOfDay
                    ? day.atTime(ExpiryPolicy.LAST_INSTANT).atZone(ExpiryPolicy.ZONE).toInstant()
                    : day.atStartOfDay(ExpiryPolicy.ZONE).toInstant();
        } catch (java.time.format.DateTimeParseException e) {
            throw LhException.badRequest("Parametro " + name + " non valido: atteso un istante ISO o una data yyyy-MM-dd.");
        }
    }

    @GetMapping("/wallets/{memberId}/lots")
    public List<LotView> lots(@PathVariable String memberId) {
        return lots.findOpenByMember(memberId).stream().map(WalletsController::toView).toList();
    }

    @GetMapping("/tiers")
    public List<Tier> tiers() {
        return query.tierScale();
    }

    public record AdjustmentRequest(String currency, String direction, long amount, String reason, String note) {
    }

    @PostMapping("/wallets/{memberId}/adjustments")
    @RequiresRole({Role.CARE, Role.ADMIN})
    public WalletService.AdjustmentResult adjust(
            @PathVariable String memberId,
            @RequestBody AdjustmentRequest request) {
        return walletService.adjustBalance(memberId, request.currency(), request.direction(), request.amount(), request.reason(), request.note());
    }

    private static LotView toView(PointsLot l) {
        return new LotView(l.id(), l.currency(), l.amount(), l.remaining(), l.status(),
                l.earnedAt(), l.availableAt(), l.expiresAt());
    }
}
