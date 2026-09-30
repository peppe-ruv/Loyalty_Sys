package io.loyaltyhub.wallet.api;

import io.loyaltyhub.common.web.MemberEndpoint;
import io.loyaltyhub.common.web.MemberPrincipal;
import io.loyaltyhub.wallet.application.WalletQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Il wallet del membro del token (PT-01, PT-07, PT-08; Q-410, ADR-048, docs/06 §3.4): nessun {@code memberId} in
 * percorso, query o corpo. Il membro lo risolve {@code EndpointAccessInterceptor} dal token (profilo {@code enterprise}) o
 * da {@code X-LH-Member} (profilo {@code demo}) e lo consegna come {@link MemberPrincipal}. I percorsi con l'id
 * ({@code /v1/portal/wallets/{memberId}}) restano deprecati, validi solo in demo.
 */
@RestController
@RequestMapping("/v1/portal/me/wallet")
public class PortalMeWalletController {

    private final WalletQueryService query;
    private final PortalActivityAssembler activity;

    public PortalMeWalletController(WalletQueryService query, PortalActivityAssembler activity) {
        this.query = query;
        this.activity = activity;
    }

    /** Saldi, scadenze imminenti e livello del membro. */
    @GetMapping
    @MemberEndpoint
    public WalletView myWallet(MemberPrincipal principal) {
        return query.wallet(principal.requireParam());
    }

    /**
     * Movimenti del membro in forma leggibile. È {@code /me/wallet/activity} e non {@code /me/activity}: quest'ultimo
     * è riservato a «La mia attività» (PT-18, member-service).
     */
    @GetMapping("/activity")
    @MemberEndpoint
    public List<PortalActivityController.ActivityItem> myActivity(
            MemberPrincipal principal,
            @RequestParam(required = false) String currency,
            @RequestParam(defaultValue = "30") int size) {
        return activity.activity(principal.requireParam(), currency, size);
    }
}
