package io.loyaltyhub.wallet.api;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import io.loyaltyhub.wallet.domain.LedgerEntry;
import io.loyaltyhub.wallet.infra.LedgerRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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

    private final LedgerRepository ledger;
    private final ObjectMapper mapper;

    public PortalActivityController(LedgerRepository ledger, ObjectMapper mapper) {
        this.ledger = ledger;
        this.mapper = mapper;
    }

    public record ActivityItem(String id, Instant occurredAt, String title, String subtitle,
                               long amount, String currency, boolean pending, String breakdown) {
    }

    @GetMapping("/{memberId}/activity")
    public List<ActivityItem> activity(
            @PathVariable String memberId,
            @RequestParam(required = false) String currency,
            @RequestParam(defaultValue = "30") int size) {
        return ledger.listByMember(memberId, currency, Math.min(Math.max(size, 1), 200)).stream()
                .map(this::toItem).toList();
    }

    private ActivityItem toItem(LedgerEntry e) {
        long signed = "-".equals(e.direction()) ? -e.amount() : e.amount();
        return new ActivityItem(e.id(), e.occurredAt(), title(e), subtitle(e), signed, e.currency(),
                false, breakdown(e));
    }

    private String title(LedgerEntry e) {
        return switch (e.type()) {
            case "EARN" -> e.currency().equals("STS") ? "Punti status guadagnati" : "Punti guadagnati";
            case "SPEND" -> "Premio richiesto";
            case "ADJUST" -> "Rettifica punti";
            case "EXPIRE" -> "Punti scaduti";
            case "REFUND" -> "Punti restituiti";
            case "RELEASE" -> "Punti sbloccati";
            default -> "Movimento";
        };
    }

    private String subtitle(LedgerEntry e) {
        if (e.description() != null && !e.description().isBlank()) {
            return e.description();
        }
        return e.campaignCode() != null ? "Campagna " + e.campaignCode() : null;
    }

    /** "130 punti base × 1,25 livello SILVER = 162" quando i metadata dell'accredito sono presenti. */
    private String breakdown(LedgerEntry e) {
        if (!"EARN".equals(e.type()) || !"PTS".equals(e.currency()) || e.metadataJson() == null) {
            return null;
        }
        try {
            JsonNode m = mapper.readTree(e.metadataJson());
            if (!m.hasNonNull("tierMultiplier") || !m.hasNonNull("baseAmount")) {
                return null;
            }
            double tierMult = m.get("tierMultiplier").asDouble();
            long base = m.get("baseAmount").asLong();
            double campaignMult = m.path("campaignMultiplier").asDouble(1.0);
            String tierCode = m.path("tierCode").asString("");
            if (tierMult == 1.0 && campaignMult == 1.0) {
                return null;
            }
            StringBuilder sb = new StringBuilder(base + " punti base");
            if (campaignMult != 1.0) {
                sb.append(" × ").append(trim(campaignMult)).append(" campagna");
            }
            if (tierMult != 1.0) {
                sb.append(" × ").append(trim(tierMult)).append(" livello ").append(tierCode);
            }
            return sb.append(" = ").append(e.amount()).toString();
        } catch (Exception ex) {
            return null;
        }
    }

    private static String trim(double d) {
        return d == Math.floor(d) ? String.valueOf((long) d) : String.valueOf(d).replace('.', ',');
    }
}
