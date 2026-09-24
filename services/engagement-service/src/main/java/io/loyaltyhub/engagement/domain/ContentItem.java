package io.loyaltyhub.engagement.domain;

import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * Contenuto del CMS del programma (docs/servizi/engagement-service.md §2, F-CNT-01/02/03): card, pop-up, banner, card
 * vincita. {@code audience} e {@code style} restano JSON: {@code {tiers[], segments[], statuses[]}} (vuoto = tutti) e
 * {@code {tone, layout}}.
 */
public record ContentItem(
        String id,
        String code,
        String kind,
        String placement,
        String title,
        String body,
        String imageUrl,
        String ctaLabel,
        String ctaTarget,
        String linkType,
        String linkCode,
        JsonNode audience,
        Instant startAt,
        Instant endAt,
        int priority,
        String frequency,
        boolean dismissible,
        JsonNode style,
        String status,
        long version,
        Instant updatedAt
) {
    public static final List<String> KINDS = List.of("CARD", "POPUP", "BANNER");
    public static final List<String> PLACEMENTS = List.of("HOME_HERO", "HOME_GRID", "CATALOG_TOP", "CONTEST", "WIN");
    public static final List<String> LINK_TYPES = List.of("NONE", "CONTEST", "CAMPAIGN", "REWARD", "PRIZE");
    public static final List<String> FREQUENCIES = List.of("ONCE", "ONCE_PER_DAY", "ALWAYS");
    public static final List<String> TONES = List.of("PRIMARY", "SECONDARY", "COIN", "NIGHT");
    public static final Set<String> STATUSES = Set.of("DRAFT", "LIVE", "PAUSED", "ENDED", "ARCHIVED");
}
