package io.loyaltyhub.wallet.application;

import io.loyaltyhub.common.web.LhException;
import io.loyaltyhub.wallet.api.WalletView;
import io.loyaltyhub.wallet.domain.MemberTier;
import io.loyaltyhub.wallet.domain.Tier;
import io.loyaltyhub.wallet.domain.WalletBalance;
import io.loyaltyhub.wallet.infra.MemberTierRepository;
import io.loyaltyhub.wallet.infra.TierRepository;
import io.loyaltyhub.wallet.infra.WalletRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Letture del wallet (docs/servizi/wallet-service.md §3): saldi + livello con soglia successiva e avanzamento. */
@Service
public class WalletQueryService {

    private final WalletRepository wallets;
    private final MemberTierRepository memberTiers;
    private final TierRepository tiers;

    public WalletQueryService(WalletRepository wallets, MemberTierRepository memberTiers, TierRepository tiers) {
        this.wallets = wallets;
        this.memberTiers = memberTiers;
        this.tiers = tiers;
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
        return new WalletView(memberId, byCurrency, tierView(memberId));
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
                multiplier, nextView, progressPct);
    }

    public List<Tier> tierScale() {
        return tiers.findAllByRank();
    }
}
