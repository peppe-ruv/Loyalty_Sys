package io.loyaltyhub.gamification.domain;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testbook TB-GAM — regole pure di obiettivi e classifiche: chiavi di periodo ({@code PER}), serie ({@code STK}),
 * metriche ({@code MET}), filtro sui dati dell'azione ({@code FLT}) (docs/testbook/TB-GAM-gioco.md §12). Oracolo:
 * docs/03 §8 (metriche, serie, periodi {@code NONE, DAY, WEEK, MONTH, EDITION}), §3.3 (condizioni su {@code data.*}),
 * Q-59 (chiave dell'edizione), docs/03 convenzioni (fuso Europe/Rome).
 */
class TestbookGamAchievementRulesTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @ParameterizedTest(name = "[{0}] {1}", quoteTextArguments = false)
    @CsvFileSource(resources = "/testbook/gam/periodi.csv", numLinesToSkip = 1)
    void chiaveDiPeriodo(String id, String desc, String kind, String period, String instant, String expected) {
        Instant at = Instant.parse(instant);
        String key = "LDB".equals(kind) ? Leaderboard.periodKey(period, at) : AchievementRules.periodKey(period, at);
        assertThat(key).isEqualTo(expected);
    }

    /** Periodi di docs/03 §8 senza formato fissato: si verifica solo se due istanti cadono nella stessa finestra. */
    @ParameterizedTest(name = "[{0}] {1}", quoteTextArguments = false)
    @CsvFileSource(resources = "/testbook/gam/finestre.csv", numLinesToSkip = 1)
    void finestraDiPeriodo(String id, String desc, String period, String t1, String t2, boolean expSame) {
        String k1 = AchievementRules.periodKey(period, Instant.parse(t1));
        String k2 = AchievementRules.periodKey(period, Instant.parse(t2));
        assertThat(k1.equals(k2)).as(k1 + " / " + k2).isEqualTo(expSame);
    }

    // TESTBOOK: ambiguo, vedi TB-GAM-STK-019 (azione fuori ordine)
    @ParameterizedTest(name = "[{0}] {1}", quoteTextArguments = false)
    @CsvFileSource(resources = "/testbook/gam/serie.csv", numLinesToSkip = 1)
    void serie(String id, String desc, String unit, String times, String expValues) {
        Achievement a = achievement("STREAK", null, unit);
        AchievementRules.Progress p = AchievementRules.Progress.EMPTY;
        List<Long> values = new ArrayList<>();
        for (String t : times.split(";")) {
            p = AchievementRules.advance(a, p, "app.login.daily", MAPPER.createObjectNode(), Instant.parse(t));
            values.add(p.value());
        }
        assertThat(values).isEqualTo(longs(expValues));
    }

    // TESTBOOK: ambiguo, vedi TB-GAM-MET-002, TB-GAM-MET-003, TB-GAM-MET-005, TB-GAM-MET-007, TB-GAM-MET-008, TB-GAM-MET-013
    @ParameterizedTest(name = "[{0}] {1}", quoteTextArguments = false)
    @CsvFileSource(resources = "/testbook/gam/metriche.csv", numLinesToSkip = 1)
    void metrica(String id, String desc, String metric, String sumField, String actions, String expValues) {
        Achievement a = achievement(metric, "-".equals(sumField) ? null : sumField, null);
        AchievementRules.Progress p = AchievementRules.Progress.EMPTY;
        List<Long> values = new ArrayList<>();
        for (String token : actions.split(";")) {
            String[] tv = token.split("=", 2);
            ObjectNode data = MAPPER.createObjectNode();
            if (tv.length > 1) {
                String v = tv[1];
                if (v.startsWith("s")) data.put("amount", v.substring(1));
                else if (v.startsWith("n")) data.putObject("order").put("total", new BigDecimal(v.substring(1)));
                else if (!"miss".equals(v)) data.put("amount", new BigDecimal(v));
            }
            p = AchievementRules.advance(a, p, tv[0], data, Instant.parse("2026-09-01T10:00:00Z"));
            values.add(p.value());
        }
        assertThat(values).isEqualTo(longs(expValues));
    }

    // TB-GAM-FLT-037 (comparatore sconosciuto): foglia falsa, Q-295 DECISA (cast tipizzato di lh-common, Q-215)
    @ParameterizedTest(name = "[{0}] {1}", quoteTextArguments = false)
    @CsvFileSource(resources = "/testbook/gam/filtri.csv", numLinesToSkip = 1, quoteCharacter = '\'')
    void filtro(String id, String desc, String filter, String data, boolean expected) {
        JsonNode f = "-".equals(filter) ? null : MAPPER.readTree(filter);
        assertThat(AchievementRules.matches(f, MAPPER.readTree(data))).isEqualTo(expected);
    }

    private static Achievement achievement(String metric, String sumField, String unit) {
        return new Achievement("A1", "ACH-TB", "Obiettivo", null, null, List.of("app.login.daily"), null, metric, sumField, unit,
                1000, "EVER", false, null, "ACTIVE");
    }

    private static List<Long> longs(String csv) {
        List<Long> out = new ArrayList<>();
        for (String s : csv.split(";")) out.add(Long.parseLong(s));
        return out;
    }
}
