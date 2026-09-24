package io.loyaltyhub.engagement.application;

import io.loyaltyhub.common.audit.AuditEntry;
import io.loyaltyhub.common.audit.AuditPublisher;
import io.loyaltyhub.common.web.ActorHolder;
import io.loyaltyhub.common.web.LhException;
import io.loyaltyhub.engagement.domain.Theme;
import io.loyaltyhub.engagement.infra.ThemeRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Tema del portale (docs/servizi/engagement-service.md §3, F-THM-01, BO-20): lettura (col ripiego Aurora se manca) e
 * sostituzione validata. Colori esadecimali; contrasto del testo ({@code night}) su {@code primary} e su {@code bg}
 * almeno 4,5:1, altrimenti {@code 422 THEME_CONTRAST_TOO_LOW}.
 * SPEC-GAP: Q-79 — "contrasto testo/primario" letto come il testo del portale ({@code night}) su {@code primary}, più
 * lo stesso testo sullo sfondo {@code bg}.
 */
@Service
public class ThemeService {

    public record ThemeRequest(String programName, String tagline, String logoUrl, Map<String, String> colors,
                               String heroTitle, String heroSubtitle, String fontDisplay, Map<String, String> currencyNames,
                               Long version) {
    }

    private final ThemeRepository themes;
    private final AuditPublisher audit;

    public ThemeService(ThemeRepository themes, AuditPublisher audit) {
        this.themes = themes;
        this.audit = audit;
    }

    public Theme get() {
        return themes.find().orElseGet(ThemeService::aurora);
    }

    @Transactional
    public Theme update(ThemeRequest r) {
        Theme before = get();
        Theme next = validated(r);
        if (!themes.save(next, r.version(), ActorHolder.get().asActorString())) {
            throw LhException.conflict("VERSION_CONFLICT", "Il tema è stato modificato nel frattempo: ricarica e riprova.");
        }
        Map<String, Object> b = new LinkedHashMap<>();
        Map<String, Object> a = new LinkedHashMap<>();
        TemplateAdminService.diff(snapshot(before), snapshot(next), b, a);
        audit.record("THEME", "default", AuditEntry.Action.UPDATE, "Modificato il tema del portale", b, a);
        return get();
    }

    /** Aurora (docs/07 §5.3, docs/10 §7): usato se la riga manca. */
    public static Theme aurora() {
        return new Theme("Club Aurora", "Il programma fedeltà che premia ogni gesto", null, Theme.auroraColors(),
                "Ogni gesto conta", "Accumula punti, sali di livello, scegli il tuo premio.", null,
                Map.of("PTS", "punti", "STS", "punti status"), 0, null);
    }

    private static Theme validated(ThemeRequest r) {
        List<LhException.FieldError> errors = new ArrayList<>();
        String name = trim(r.programName());
        if (name == null) {
            errors.add(new LhException.FieldError("programName", "obbligatorio"));
        } else if (name.length() > 40) {
            errors.add(new LhException.FieldError("programName", "al massimo 40 caratteri"));
        }
        Map<String, String> colors = new LinkedHashMap<>();
        for (String key : Theme.COLOR_KEYS) {
            String v = r.colors() == null ? null : trim(r.colors().get(key));
            if (!Theme.isHex(v)) {
                errors.add(new LhException.FieldError("colors." + key, "colore esadecimale #RRGGBB"));
            } else {
                colors.put(key, v.toUpperCase(Locale.ROOT));
            }
        }
        String logo = trim(r.logoUrl());
        if (logo != null && !(logo.startsWith("/") || logo.matches("^https://\\S+$"))) {
            errors.add(new LhException.FieldError("logoUrl", "percorso /… o indirizzo https://"));
        }
        Map<String, String> currencies = new LinkedHashMap<>();
        for (String c : List.of("PTS", "STS")) {
            String v = r.currencyNames() == null ? null : trim(r.currencyNames().get(c));
            currencies.put(c, v != null ? v : "PTS".equals(c) ? "punti" : "punti status");
        }
        if (!errors.isEmpty()) {
            throw LhException.validation("THEME_INVALID", "Tema non valido: controlla i campi evidenziati.", errors);
        }
        List<LhException.FieldError> contrast = new ArrayList<>();
        double onPrimary = Theme.contrast(colors.get("night"), colors.get("primary"));
        double onBg = Theme.contrast(colors.get("night"), colors.get("bg"));
        if (onPrimary < Theme.MIN_CONTRAST) {
            contrast.add(new LhException.FieldError("colors.primary",
                    String.format(Locale.ITALIAN, "contrasto col testo %.2f:1, serve almeno 4,5:1", onPrimary)));
        }
        if (onBg < Theme.MIN_CONTRAST) {
            contrast.add(new LhException.FieldError("colors.bg",
                    String.format(Locale.ITALIAN, "contrasto col testo %.2f:1, serve almeno 4,5:1", onBg)));
        }
        if (!contrast.isEmpty()) {
            throw LhException.validation("THEME_CONTRAST_TOO_LOW",
                    "Contrasto insufficiente tra testo e colori del tema (serve almeno 4,5:1).", contrast);
        }
        return new Theme(name, trim(r.tagline()), logo, colors, trim(r.heroTitle()), trim(r.heroSubtitle()),
                trim(r.fontDisplay()), currencies, 0, null);
    }

    private static Map<String, Object> snapshot(Theme t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("programName", t.programName());
        m.put("tagline", t.tagline());
        m.put("logoUrl", t.logoUrl());
        m.put("colors", String.valueOf(t.colors()));
        m.put("heroTitle", t.heroTitle());
        m.put("heroSubtitle", t.heroSubtitle());
        m.put("fontDisplay", t.fontDisplay());
        m.put("currencyNames", String.valueOf(t.currencyNames()));
        return m;
    }

    private static String trim(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
