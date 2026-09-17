package io.loyaltyhub.memberservice.api;

import io.loyaltyhub.memberservice.app.ConsentService;
import io.loyaltyhub.memberservice.domain.Consent;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;

/** API consensi (RF-135): stato corrente, registrazione con prova e base giuridica, storico, catalogo finalità. */
@RestController
@RequestMapping("/v1/members")
public class ConsentController {
    public record Change(@NotBlank String purpose, boolean granted, String source, String legalBasis, String version, Instant expiresAt, String evidence) {}

    private final ConsentService consents;
    private final Consent.PurposeSource purposes;
    public ConsentController(ConsentService consents, Consent.PurposeSource purposes) { this.consents = consents; this.purposes = purposes; }

    @GetMapping("/{id}/consents")
    public List<Consent> current(@PathVariable String id) { return consents.current(id); }

    @PostMapping("/{id}/consents")
    public Consent change(@PathVariable String id, @RequestBody Change c) { return consents.record(id, c.purpose(), c.granted(), c.source(), c.legalBasis(), c.version(), c.expiresAt(), c.evidence()); }

    @GetMapping("/{id}/consents/history")
    public List<Consent> history(@PathVariable String id) { return consents.history(id); }

    @GetMapping("/consent-purposes")
    public List<Consent.Purpose> purposes() { return purposes.purposes(); }
}
