package io.loyaltyhub.wallet.api;

import io.loyaltyhub.common.web.MemberEndpoint;
import io.loyaltyhub.common.web.MemberPrincipal;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/**
 * Attività del portale (docs/servizi/wallet-service.md §3, PT-01/PT-07): movimenti in forma leggibile,
 * senza gergo tecnico. Il titolo e la scomposizione ("130 punti base × 1,25 livello SILVER = 162")
 * si ricavano dal tipo di movimento e dai {@code metadata} del ledger.
 */
@RestController
@RequestMapping("/v1/portal/wallets")
public class PortalActivityController {

    private final PortalActivityAssembler assembler;

    public PortalActivityController(PortalActivityAssembler assembler) {
        this.assembler = assembler;
    }

    /**
     * Voce dell'attività (wallet-service §3): {@code {id, occurredAt, title, subtitle, amount, direction, currency, icon,
     * pending, expiresAt?, breakdown?}}. {@code amount} è con segno (negativo in uscita), {@code direction} è
     * {@code +}/{@code -} come nel libro mastro; {@code pending} e {@code expiresAt} vengono dal lotto nato dal movimento.
     */
    public record ActivityItem(String id, Instant occurredAt, String title, String subtitle,
                               long amount, String currency, boolean pending, String breakdown,
                               String direction, String icon, Instant expiresAt) {
    }

    /**
     * Percorso legacy con l'id nel percorso, valido solo nel profilo {@code demo} (Q-410, ADR-048): l'id lo legge
     * l'interceptor ({@code demoPathVariable}) e arriva nel {@link MemberPrincipal}. In {@code enterprise} dà
     * {@code 403 MEMBER_FROM_TOKEN}: si usa {@code GET /v1/portal/me/wallet/activity}. Non si rimuove mai.
     *
     * @deprecated solo profilo demo; il membro viene dal token: {@code /v1/portal/me/wallet/activity}
     */
    @Deprecated
    @GetMapping("/{memberId}/activity")
    @MemberEndpoint(demoPathVariable = "memberId")
    @Parameter(name = "memberId", in = ParameterIn.PATH, required = true, deprecated = true,
            description = "solo profilo demo", schema = @Schema(type = "string"))
    public List<ActivityItem> activity(
            MemberPrincipal principal,
            @RequestParam(required = false) String currency,
            @RequestParam(defaultValue = "30") int size) {
        return assembler.activity(principal.requireParam(), currency, size);
    }
}
