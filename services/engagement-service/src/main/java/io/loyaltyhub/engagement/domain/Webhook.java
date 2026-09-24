package io.loyaltyhub.engagement.domain;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

/**
 * Webhook in uscita (docs/servizi/engagement-service.md §2; F-WBH-01, BO-23): URL, tipi di fatto sottoscritti (forma
 * breve), attivo. Il {@code secret} compare solo nella risposta alla creazione ({@code null} altrimenti, quindi omesso).
 * {@code stats} riassume le consegne conservate (14 giorni) per l'elenco di BO-23.
 */
public record Webhook(String id, String code, String name, String url, List<String> factTypes, boolean enabled, long version,
                      Instant createdAt, String createdBy, Instant updatedAt, String updatedBy, Stats stats,
                      @JsonInclude(JsonInclude.Include.NON_NULL) String secret) {

    public record Stats(long total, long ok, long failed, long gaveUp, long pending, Instant lastDeliveryAt,
                        String lastStatus) {
    }

    public Webhook withSecret(String s) {
        return new Webhook(id, code, name, url, factTypes, enabled, version, createdAt, createdBy, updatedAt, updatedBy, stats, s);
    }
}
