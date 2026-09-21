package io.loyaltyhub.wallet.api;

import io.loyaltyhub.common.web.RequiresRole;
import io.loyaltyhub.common.web.Role;
import io.loyaltyhub.wallet.application.WalletService;
import io.loyaltyhub.wallet.application.WalletService.JobOutcome;
import io.loyaltyhub.wallet.domain.ExpiryPolicy;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * Job demo del wallet (docs/servizi/wallet-service.md §3, BO-30 "Macchina del tempo"): scadenze, preavvisi e
 * rilascio dei pending con una <em>data di riferimento</em> {@code asOf}. Riservati ad {@code ADMIN}; attivi
 * solo col profilo {@code demo}. Fuori demo gli stessi job girano schedulati ({@link io.loyaltyhub.wallet.application.WalletJobs}).
 */
@RestController
@RequestMapping("/v1/demo/jobs")
@Profile("demo")
public class WalletJobsController {

    private final WalletService wallet;
    private final Clock clock;

    public WalletJobsController(WalletService wallet, Clock clock) {
        this.wallet = wallet;
        this.clock = clock;
    }

    @PostMapping("/expire-points")
    @RequiresRole({Role.ADMIN})
    public JobOutcome expirePoints(@RequestParam(required = false) String asOf) {
        return wallet.expirePoints(asOf(asOf));
    }

    @PostMapping("/release-pending")
    @RequiresRole({Role.ADMIN})
    public JobOutcome releasePending(@RequestParam(required = false) String asOf) {
        return wallet.releasePending(asOf(asOf));
    }

    @PostMapping("/expiry-warnings")
    @RequiresRole({Role.ADMIN})
    public JobOutcome expiryWarnings(@RequestParam(required = false) String asOf) {
        return wallet.expiryWarnings(asOf(asOf));
    }

    /**
     * {@code asOf} assente → adesso; con la {@code T} è un istante ISO; una data pura (yyyy-MM-dd) è la fine
     * di quel giorno in {@code Europe/Rome}, così "entro questa data" include i lotti che scadono quel giorno.
     */
    private Instant asOf(String value) {
        if (value == null || value.isBlank()) {
            return clock.instant();
        }
        if (value.contains("T")) {
            return Instant.parse(value);
        }
        return LocalDate.parse(value).atTime(LocalTime.MAX).atZone(ExpiryPolicy.ZONE).toInstant();
    }
}
