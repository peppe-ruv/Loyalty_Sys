package it.iren.loyalty.memberservice.domain;

import java.time.Instant;
import java.util.List;

/**
 * Consenso (RF-135, privacy by design): finalità, concesso/revocato, fonte, base giuridica, versione dell'informativa,
 * data e scadenza. Il decision-service non contatta senza il consenso della finalità richiesta dall'azione; la revoca è
 * immediata (CONSENT_V1 → Customer 360). Le finalità sono catalogate nel backoffice (collezione {@code consent-purposes}).
 */
public record Consent(String purpose, boolean granted, String source, String legalBasis, String version, Instant grantedAt, Instant expiresAt, String evidence) {
    public boolean activeAt(Instant now) { return granted && (expiresAt == null || now.isBefore(expiresAt)); }

    /** Finalità di consenso configurata nel backoffice. */
    public record Purpose(String code, String name, String defaultLegalBasis, Integer validityMonths, boolean required, String currentVersion) {}

    public interface PurposeSource { List<Purpose> purposes(); default Purpose byCode(String code) { return purposes().stream().filter(p -> p.code().equals(code)).findFirst().orElse(null); } }

    public static List<Purpose> defaultPurposes() {
        return List.of(
                new Purpose("program", "Partecipazione al programma", "contract", null, true, "1"),
                new Purpose("marketing", "Comunicazioni commerciali e offerte", "consent", 24, false, "1"),
                new Purpose("profiling", "Profilazione e personalizzazione", "consent", 24, false, "1"),
                new Purpose("newsletter", "Newsletter", "consent", 24, false, "1"),
                new Purpose("third-party", "Cessione a partner", "consent", 12, false, "1"));
    }
}
