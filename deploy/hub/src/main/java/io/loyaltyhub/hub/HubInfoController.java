package io.loyaltyhub.hub;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Pagina radice del deployable consolidato: aprendo la URL base (Render fa lo stesso col suo probe) si vede
 * che la demo è viva, con i puntatori agli endpoint utili. Senza questo, {@code GET /} è un percorso non
 * mappato (ora un 404 pulito da {@code GlobalExceptionHandler}), che a occhio sembra un servizio rotto.
 */
@RestController
public class HubInfoController {

    @GetMapping("/")
    public Map<String, Object> root() {
        return Map.of(
                "service", "loyalty-hub",
                "status", "UP",
                "demo", "Club Aurora",
                "mode", "consolidato + bus in-process (ADR-023, ADR-024)",
                "endpoints", Map.of(
                        "health", "/actuator/health",
                        "portalWallet", "/v1/portal/wallets/MBR-000003",
                        "portalCampaigns", "/v1/portal/campaigns",
                        "portalCatalog", "/v1/portal/catalog?memberId=MBR-000003",
                        "members", "/v1/members",
                        "sendDemoEvent", "POST /v1/events"),
                "services", List.of("ingestion", "member", "campaign", "wallet", "insight", "reward"));
    }
}
