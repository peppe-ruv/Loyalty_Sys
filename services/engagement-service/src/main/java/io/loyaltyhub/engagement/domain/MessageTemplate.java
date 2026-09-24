package io.loyaltyhub.engagement.domain;

import java.time.Instant;
import java.util.Set;

/**
 * Template di messaggio (docs/servizi/engagement-service.md §2; F-MSG-02): titolo e testo con segnaposto, icona, link
 * del portale, categoria. Canale {@code INAPP} reale (inbox di PT-12); {@code EMAIL_FAKE} produce solo il messaggio
 * consultabile da BO-19, nessun invio.
 */
public record MessageTemplate(String code, String name, String channel, String titleTpl, String bodyTpl, String icon,
                              String linkTarget, String category, long version, Instant updatedAt, String updatedBy) {

    public static final Set<String> CHANNELS = Set.of("INAPP", "EMAIL_FAKE");
    public static final Set<String> CATEGORIES = Set.of("POINTS", "TIER", "REWARD", "GAME", "PROGRAM");
}
