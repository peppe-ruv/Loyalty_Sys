package it.iren.loyalty.memberservice.api;

import it.iren.loyalty.memberservice.app.MemberService;
import it.iren.loyalty.memberservice.domain.Anonymizer;
import it.iren.loyalty.memberservice.domain.Member;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * API membri (RF-68, RF-72, RF-73): adesione, profilo/etichette/consensi, codice referral, stato, anonimizzazione,
 * export portabilità, import massivo (CSV: memberId,channel,labels,consents) per il backoffice.
 */
@RestController
@RequestMapping("/v1/members")
public class MemberController {
    public record Enroll(@NotBlank String memberId, String channel, Map<String, Boolean> consents, String referralCode) {}
    public record Update(Map<String, String> labels, Map<String, Boolean> consents, Boolean profileCompleted) {}
    public record StatusChange(@NotBlank String status, @NotBlank String reason) {}

    private final MemberService members;
    private final RestClient ledger;
    private final RestClient readModel;

    public MemberController(MemberService members, RestClient.Builder builder) {
        this.members = members;
        this.ledger = builder.baseUrl(System.getenv().getOrDefault("LEDGER_URL", "http://ledger:8083")).build();
        this.readModel = builder.baseUrl(System.getenv().getOrDefault("READ_MODEL_URL", "http://read-model:8088")).build();
    }

    @PostMapping
    public Member enroll(@RequestBody Enroll e) { return members.enroll(e.memberId(), e.channel(), e.consents(), e.referralCode()); }

    @GetMapping("/{id}")
    public ResponseEntity<Member> get(@PathVariable String id) { return ResponseEntity.of(members.find(id)); }

    @PatchMapping("/{id}")
    public Member update(@PathVariable String id, @RequestBody Update u) { return members.update(id, u.labels(), u.consents(), u.profileCompleted()); }

    @GetMapping("/{id}/referral")
    public Map<String, Object> referral(@PathVariable String id) {
        Member m = members.find(id).orElseThrow();
        return Map.of("code", m.referralCode() == null ? "" : m.referralCode(), "shareUrl", System.getenv().getOrDefault("SITE_URL", "https://loyalty.gruppoiren.it") + "/aderisci?ref=" + m.referralCode());
    }

    @PostMapping("/{id}/status")
    public Member status(@PathVariable String id, @RequestBody StatusChange s) { return members.setStatus(id, Member.Status.valueOf(s.status())); }

    /** Diritto all'oblio: anonimizza qui e notifica il CRM via evento (RF-73). */
    @PostMapping("/{id}/anonymize")
    public Member anonymize(@PathVariable String id) { return members.setStatus(id, Member.Status.ANONYMIZED); }

    /** Portabilità (RF-73): profilo, saldi, movimenti, riscatti e giocate in un unico JSON. */
    @GetMapping("/{id}/export")
    public Anonymizer.Export export(@PathVariable String id) {
        Member m = members.find(id).orElseThrow();
        return new Anonymizer.Export(m,
                safe(() -> ledger.get().uri("/v1/ledger/members/{id}/balances", id).retrieve().body(Object.class)),
                safe(() -> ledger.get().uri("/v1/ledger/members/{id}/movements", id).retrieve().body(Object.class)),
                safe(() -> readModel.get().uri("/v1/read/members/{id}/redemptions", id).retrieve().body(Object.class)),
                safe(() -> readModel.get().uri("/v1/read/members/{id}/plays", id).retrieve().body(Object.class)),
                Instant.now());
    }

    /** Import massivo dal backoffice: CSV senza intestazione {@code memberId,channel,label=value;...,consent=true;...}. */
    @PostMapping(value = "/import", consumes = "text/csv")
    public Map<String, Integer> importCsv(@RequestBody String csv) {
        int ok = 0, skipped = 0;
        for (String line : csv.split("\r?\n")) {
            if (line.isBlank()) continue;
            String[] f = line.split(",", -1);
            try {
                Map<String, Boolean> consents = new java.util.HashMap<>();
                if (f.length > 3) for (String kv : f[3].split(";")) if (kv.contains("=")) consents.put(kv.split("=")[0].trim(), Boolean.parseBoolean(kv.split("=")[1].trim()));
                members.enroll(f[0].trim(), f.length > 1 ? f[1].trim() : "import", consents, null);
                Map<String, String> labels = new java.util.HashMap<>();
                if (f.length > 2) for (String kv : f[2].split(";")) if (kv.contains("=")) labels.put(kv.split("=")[0].trim(), kv.split("=")[1].trim());
                if (!labels.isEmpty()) members.update(f[0].trim(), labels, null, null);
                ok++;
            } catch (RuntimeException e) { skipped++; }
        }
        return Map.of("imported", ok, "skipped", skipped);
    }

    private static Object safe(java.util.function.Supplier<Object> s) { try { return s.get(); } catch (RuntimeException e) { return List.of(); } }
}
