package io.loyaltyhub.engagement.domain;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Tema del portale (docs/servizi/engagement-service.md §2, F-THM-01; docs/07 §5.3). I colori sono esadecimali
 * {@code #RRGGBB}; il testo del portale usa {@code night} su {@code bg} e sui pulsanti {@code primary}.
 */
public record Theme(
        String programName,
        String tagline,
        String logoUrl,
        Map<String, String> colors,
        String heroTitle,
        String heroSubtitle,
        String fontDisplay,
        Map<String, String> currencyNames,
        long version,
        Instant updatedAt
) {
    public static final List<String> COLOR_KEYS = List.of("primary", "secondary", "coin", "night", "bg");
    public static final double MIN_CONTRAST = 4.5;
    private static final Pattern HEX = Pattern.compile("^#[0-9A-Fa-f]{6}$");

    /** Tema "Aurora" (docs/07 §5.3): il ripiego del portale e il valore del seed. */
    public static Map<String, String> auroraColors() {
        Map<String, String> c = new LinkedHashMap<>();
        c.put("primary", "#1FB98F");
        c.put("secondary", "#7A5CFA");
        c.put("coin", "#FFB547");
        c.put("night", "#0E1B2C");
        c.put("bg", "#F3F7F9");
        return c;
    }

    public static boolean isHex(String value) {
        return value != null && HEX.matcher(value).matches();
    }

    /** Rapporto di contrasto WCAG 2.x tra due colori {@code #RRGGBB} (1…21). */
    public static double contrast(String a, String b) {
        double la = luminance(a);
        double lb = luminance(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    static double luminance(String hex) {
        int rgb = Integer.parseInt(hex.substring(1), 16);
        return 0.2126 * channel((rgb >> 16) & 0xFF) + 0.7152 * channel((rgb >> 8) & 0xFF) + 0.0722 * channel(rgb & 0xFF);
    }

    private static double channel(int v) {
        double c = v / 255.0;
        return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }
}
