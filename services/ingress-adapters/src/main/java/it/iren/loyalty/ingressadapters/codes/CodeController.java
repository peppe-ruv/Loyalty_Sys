package it.iren.loyalty.ingressadapters.codes;

import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** Uso di un codice promozionale/QR da sito, app o postazione operatore (RF-69). */
@RestController
@RequestMapping("/v1/codes")
public class CodeController {
    public record Redeem(@NotBlank String memberId, @NotBlank String code, String channel) {}

    public record Definition(@NotBlank String code, @NotBlank String campaign, @NotBlank String kind, java.time.Instant validFrom, java.time.Instant validTo, int maxUses, int maxUsesPerMember, boolean active) {}

    private final CodeRedemptionService service;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    public CodeController(CodeRedemptionService service, org.springframework.jdbc.core.JdbcTemplate jdbc) { this.service = service; this.jdbc = jdbc; }

    /** Pubblicazione dal backoffice (hook della collezione promo-codes): upsert della definizione. */
    @PutMapping
    public Map<String, String> upsert(@RequestBody Definition d) {
        jdbc.update("""
                INSERT INTO ingressadapters.promo_code(code, campaign, kind, valid_from, valid_to, max_uses, max_uses_per_member, active) VALUES (?,?,?,?,?,?,?,?)
                ON CONFLICT (code) DO UPDATE SET campaign = EXCLUDED.campaign, kind = EXCLUDED.kind, valid_from = EXCLUDED.valid_from, valid_to = EXCLUDED.valid_to,
                max_uses = EXCLUDED.max_uses, max_uses_per_member = EXCLUDED.max_uses_per_member, active = EXCLUDED.active""",
                PromoCode.normalize(d.code()), d.campaign(), d.kind(), d.validFrom() == null ? null : java.sql.Timestamp.from(d.validFrom()),
                d.validTo() == null ? null : java.sql.Timestamp.from(d.validTo()), d.maxUses(), d.maxUsesPerMember(), d.active());
        return Map.of("code", PromoCode.normalize(d.code()), "status", "PUBLISHED");
    }

    @PostMapping("/redeem")
    public ResponseEntity<Map<String, String>> redeem(@RequestBody Redeem r) {
        var out = service.redeem(r.memberId(), r.code(), r.channel());
        var body = Map.of("verdict", out.verdict().name(), "campaign", out.campaign() == null ? "" : out.campaign());
        return ResponseEntity.status(out.verdict() == PromoCode.Verdict.OK ? HttpStatus.ACCEPTED : HttpStatus.UNPROCESSABLE_ENTITY).body(body);
    }
}
