package io.loyaltyhub.wallet.api;

import io.loyaltyhub.common.web.RequiresRole;
import io.loyaltyhub.common.web.Role;
import io.loyaltyhub.wallet.application.TierAdminService;
import io.loyaltyhub.wallet.application.TierAdminService.TierCount;
import io.loyaltyhub.wallet.application.TierAdminService.TierUpdate;
import io.loyaltyhub.wallet.domain.Tier;
import io.loyaltyhub.wallet.domain.TierHistory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Amministrazione livelli (docs/servizi/wallet-service.md §3, BO-07, F-TIER-01/06): distribuzione, modifica
 * (ruolo {@code program.config} = ADMIN) e storico dei passaggi di livello.
 */
@RestController
@RequestMapping("/v1")
public class TiersController {

    private final TierAdminService tierAdmin;

    public TiersController(TierAdminService tierAdmin) {
        this.tierAdmin = tierAdmin;
    }

    @GetMapping("/tiers/distribution")
    public List<TierCount> distribution() {
        return tierAdmin.distribution();
    }

    @PutMapping("/tiers/{code}")
    @RequiresRole({Role.ADMIN})
    public Tier update(@PathVariable String code, @RequestBody TierUpdate body) {
        return tierAdmin.update(code.toUpperCase(), body);
    }

    @GetMapping("/members/{memberId}/tier-history")
    public List<TierHistory> tierHistory(@PathVariable String memberId) {
        return tierAdmin.history(memberId);
    }
}
