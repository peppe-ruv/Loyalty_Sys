package io.loyaltyhub.wallet.api;

import io.loyaltyhub.common.web.LhException;
import io.loyaltyhub.wallet.infra.CurrencyRepository;
import io.loyaltyhub.wallet.infra.LiabilityRepository;
import io.loyaltyhub.wallet.infra.LiabilityRepository.MonthAmount;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * Passività (F-WAL-09, docs/servizi/wallet-service.md §3): {@code GET /v1/liability?currency=PTS} →
 * {@code {currency, outstanding, pending, byExpiryMonth[{month, amount}], asOf}}. {@code outstanding}/{@code pending}
 * sono i saldi attivi/in attesa di tutti i wallet; {@code byExpiryMonth} ripartisce i lotti attivi per mese di
 * scadenza ({@code YYYY-MM}, Europe/Rome; {@code month = null} per i lotti che non scadono). Letta da BO-01 e BO-08.
 */
@RestController
@RequestMapping("/v1")
public class LiabilityController {

    public record LiabilityView(String currency, long outstanding, long pending, List<MonthAmount> byExpiryMonth, Instant asOf) {
    }

    private final LiabilityRepository liability;
    private final CurrencyRepository currencies;
    private final Clock clock;

    public LiabilityController(LiabilityRepository liability, CurrencyRepository currencies, Clock clock) {
        this.liability = liability;
        this.currencies = currencies;
        this.clock = clock;
    }

    /** Totali e ripartizione dallo stesso snapshot: la somma per mese coincide con {@code outstanding}. */
    @GetMapping("/liability")
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public LiabilityView liability(@RequestParam(defaultValue = "PTS") String currency) {
        String code = currency.toUpperCase();
        if (currencies.findByCode(code).isEmpty()) {
            throw LhException.notFound("Valuta non trovata: " + code);
        }
        LiabilityRepository.Totals totals = liability.totals(code);
        return new LiabilityView(code, totals.outstanding(), totals.pending(), liability.byExpiryMonth(code), clock.instant());
    }
}
