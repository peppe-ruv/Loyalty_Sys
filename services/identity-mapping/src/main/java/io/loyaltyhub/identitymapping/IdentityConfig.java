package io.loyaltyhub.identitymapping;

import io.loyaltyhub.identitymapping.app.IdentityService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.client.RestClient;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/** Effetti del merge sugli altri domini: trasferimento unità sul ledger (RF-88) e stato del membro assorbito. */
@Configuration
@EnableScheduling
public class IdentityConfig {

    /** Saldi per wallet come li restituisce il ledger: {@code {wallet: {active, earned, …}}}. */
    private static final ParameterizedTypeReference<Map<String, Map<String, Number>>> WALLETS = new ParameterizedTypeReference<>() { };

    @Bean @ConditionalOnMissingBean
    IdentityService.MergeEffects mergeEffects(RestClient.Builder builder) {
        RestClient ledger = builder.baseUrl(System.getenv().getOrDefault("LEDGER_URL", "http://ledger:8083")).build();
        RestClient members = builder.baseUrl(System.getenv().getOrDefault("MEMBER_URL", "http://member-service:8091")).build();
        boolean transfer = Boolean.parseBoolean(System.getenv().getOrDefault("IDENTITY_MERGE_TRANSFER_UNITS", "true"));
        return new IdentityService.MergeEffects() {

            @Override public Map<String, Long> transferUnits(String from, String into, String mergeId) {
                Map<String, Long> moved = new HashMap<>();
                if (!transfer) return moved;
                Map<String, Map<String, Number>> wallets = ledger.get().uri("/v1/ledger/members/{id}/wallets", from).retrieve().body(WALLETS);
                if (wallets == null) return moved;
                wallets.forEach((wallet, v) -> {
                    long active = v.get("active").longValue();
                    if (active <= 0) return;
                    move(from, into, wallet, active, "merge:" + mergeId + ":" + wallet, "merge identità");
                    moved.put(wallet, active);
                });
                return moved;
            }

            /**
             * Ritrasferisce solo ciò che c'è ancora: il registro resta l'unica verità sui saldi (nessuna unità creata
             * per far tornare i conti). Se il membro sopravvissuto ha speso, la differenza resta un ammanco dichiarato.
             */
            @Override public Map<String, Long> restoreUnits(String into, String from, Map<String, Long> units, String mergeId) {
                Map<String, Long> back = new LinkedHashMap<>();
                if (!transfer || units == null || units.isEmpty()) return back;
                Map<String, Map<String, Number>> wallets = ledger.get().uri("/v1/ledger/members/{id}/wallets", into).retrieve().body(WALLETS);
                units.forEach((wallet, moved) -> {
                    long available = wallets == null || wallets.get(wallet) == null ? 0L : wallets.get(wallet).get("active").longValue();
                    long amount = Math.min(moved == null ? 0L : moved, Math.max(available, 0L));
                    if (amount <= 0) return;
                    move(into, from, wallet, amount, "unmerge:" + mergeId + ":" + wallet, "annullamento merge identità");
                    back.put(wallet, amount);
                });
                return back;
            }

            @Override public void closeMember(String memberId, String reason) { status(memberId, "CLOSED", reason); }

            @Override public void reopenMember(String memberId, String reason) { status(memberId, "ACTIVE", reason); }

            private void move(String from, String into, String wallet, long amount, String transferKey, String comment) {
                ledger.post().uri("/v1/ledger/transfers")
                        .body(Map.of("fromMemberId", from, "toMemberId", into, "currency", wallet, "amount", amount,
                                "transferKey", transferKey, "comment", comment))
                        .retrieve().toBodilessEntity();
            }

            private void status(String memberId, String status, String reason) {
                members.post().uri("/v1/members/{id}/status", memberId).body(Map.of("status", status, "reason", reason)).retrieve().toBodilessEntity();
            }
        };
    }

    /**
     * Ripresa dei merge rimasti a metà (RF-136): un errore di rete fra il trasferimento delle unità e la chiusura del
     * membro assorbito non deve lasciare uno stato incoerente in attesa di un intervento manuale.
     */
    @Bean @ConditionalOnProperty(name = "identity.merge.reconcile.enabled", havingValue = "true", matchIfMissing = true)
    MergeReconciler mergeReconciler(IdentityService identities) { return new MergeReconciler(identities); }

    public static class MergeReconciler {
        private final IdentityService identities;
        MergeReconciler(IdentityService identities) { this.identities = identities; }

        @Scheduled(fixedDelayString = "${identity.merge.reconcile-ms:60000}", initialDelayString = "${identity.merge.reconcile-ms:60000}")
        public void run() { identities.reconcile(); }
    }
}
