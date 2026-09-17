package io.loyaltyhub.identitymapping;

import io.loyaltyhub.identitymapping.app.IdentityService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.client.RestClient;

import java.util.HashMap;
import java.util.Map;

/** Effetti del merge sugli altri domini: trasferimento unità sul ledger (RF-88) e chiusura del membro assorbito. */
@Configuration
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
                    ledger.post().uri("/v1/ledger/transfers").body(Map.of("fromMemberId", from, "toMemberId", into, "currency", wallet, "amount", active, "transferKey", "merge:" + mergeId + ":" + wallet, "comment", "merge identità")).retrieve().toBodilessEntity();
                    moved.put(wallet, active);
                });
                return moved;
            }
            @Override public void closeMember(String memberId, String reason) {
                members.post().uri("/v1/members/{id}/status", memberId).body(Map.of("status", "CLOSED", "reason", reason)).retrieve().toBodilessEntity();
            }
        };
    }
}
