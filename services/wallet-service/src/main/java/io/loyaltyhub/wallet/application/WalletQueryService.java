package io.loyaltyhub.wallet.application;

import io.loyaltyhub.common.web.LhException;
import io.loyaltyhub.wallet.api.WalletView;
import io.loyaltyhub.wallet.domain.ExpiryPolicy;
import io.loyaltyhub.wallet.domain.MemberTier;
import io.loyaltyhub.wallet.domain.Tier;
import io.loyaltyhub.wallet.domain.WalletBalance;
import io.loyaltyhub.wallet.infra.MemberTierRepository;
import io.loyaltyhub.wallet.infra.PointsLotRepository;
import io.loyaltyhub.wallet.infra.TierRepository;
import io.loyaltyhub.wallet.infra.WalletRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.Month;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Letture del wallet (docs/servizi/wallet-service.md §3): saldi + livello con soglia successiva e avanzamento. */
@Service
public class WalletQueryService {

    /** Valuta spendibile con scadenza rolling: le scadenze imminenti riguardano i punti (docs §3). */
    private static final String SPENDABLE = "PTS";
    private static final int SOON_DAYS = 30;

    private final WalletRepository wallets;
    private final MemberTierRepository memberTiers;
    private final TierRepository tiers;
    private final PointsLotRepository lots;
    private final Clock clock;

    public WalletQueryService(WalletRepository wallets, MemberTierRepository memberTiers, TierRepository tiers,
                              PointsLotRepository lots, Clock clock) {
        this.wallets = wallets;
        this.memberTiers = memberTiers;
        this.tiers = tiers;
        this.lots = lots;
        this.clock = clock;
    }

    public WalletView wallet(String memberId) {
        List<WalletBalance> balances = wallets.findByMember(memberId);
        if (balances.isEmpty()) {
            throw LhException.notFound("Wallet non trovato per il membro " + memberId);
        }
        Map<String, WalletView.Balance> byCurrency = new LinkedHashMap<>();
        for (WalletBalance b : balances) {
            byCurrency.put(b.currency(), new WalletView.Balance(
                    b.balanceActive(), b.balancePending(), b.lifetimeEarned(), b.lifetimeSpent()));
        }
        return new WalletView(memberId, byCurrency, expiringSoon(memberId), tierView(memberId));
    }

    private WalletView.ExpiringSoon expiringSoon(String memberId) {
        Instant now = clock.instant();
        return lots.expiringSoon(memberId, SPENDABLE, now, now.plus(SOON_DAYS, ChronoUnit.DAYS))
                .map(e -> new WalletView.ExpiringSoon(e.amount(), e.amount() > 0, e.nextExpiryAt()))
                .orElse(new WalletView.ExpiringSoon(0, false, null));
    }

    public WalletView.TierView tierView(String memberId) {
        MemberTier mt = memberTiers.find(memberId)
                .orElse(new MemberTier(memberId, "BASE", null, 0, null, "ACTIVE"));
        List<Tier> scale = tiers.findAllByRank();
        Tier current = scale.stream().filter(t -> t.code().equals(mt.tierCode())).findFirst()
                .orElse(scale.isEmpty() ? null : scale.get(0));
        BigDecimal multiplier = current != null ? current.multiplier() : BigDecimal.ONE;

        Tier next = scale.stream().filter(t -> current != null && t.rank() == current.rank() + 1)
                .findFirst().orElse(null);
        WalletView.NextTier nextView = null;
        int progressPct = 100;
        if (next != null) {
            long missing = Math.max(next.thresholdSts() - mt.periodSts(), 0);
            nextView = new WalletView.NextTier(next.code(), next.thresholdSts(), missing);
            long floor = current != null ? current.thresholdSts() : 0;
            long span = Math.max(next.thresholdSts() - floor, 1);
            progressPct = (int) Math.min(100, Math.max(0, (mt.periodSts() - floor) * 100 / span));
        }
        return new WalletView.TierView(mt.tierCode(),
                current != null ? current.name() : mt.tierCode(), mt.since(), mt.periodSts(),
                multiplier, nextView, progressPct, keepWarning(current, mt.periodSts(), clock.instant()));
    }

    /** Primo mese (in {@code Europe/Rome}) in cui compare l'avviso di mantenimento (wallet-service §5: «da ottobre»). */
    static final Month KEEP_WARNING_FROM = Month.OCTOBER;

    /**
     * {@code keepWarning} (wallet-service §5): da ottobre a fine anno (le edizioni sono annuali e chiudono il 31/12,
     * PT-01 «entro il 31 dic»), se {@code periodSts} è sotto la soglia del livello attuale → {@code {tier, missing}}.
     * Un livello con soglia 0 (BASE) non si perde mai: nessun avviso.
     */
    static WalletView.KeepWarning keepWarning(Tier current, long periodSts, Instant now) {
        if (current == null) {
            return null;
        }
        Month month = now.atZone(ExpiryPolicy.ZONE).getMonth();
        if (month.compareTo(KEEP_WARNING_FROM) < 0 || periodSts >= current.thresholdSts()) {
            return null;
        }
        return new WalletView.KeepWarning(current.code(), current.thresholdSts() - periodSts);
    }

    public List<Tier> tierScale() {
        return tiers.findAllByRank();
    }
}
