package io.loyaltyhub.engagement.domain;

import io.loyaltyhub.engagement.TestbookRows;
import io.loyaltyhub.engagement.domain.ContentSelection.Viewer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.aggregator.ArgumentsAccessor;
import org.junit.jupiter.params.provider.CsvFileSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testbook TB-ENG (docs/testbook/TB-ENG-engagement.md): selezione dei contenuti e dei pop-up, logica pura
 * ({@link ContentSelection}). Righe SEL (idoneità: stato × calendario × pubblico), AUD (pubblico), EXT (estensioni
 * Q-71), ORD (ordine e limiti per posizionamento), POP (frequenza dei pop-up e giorno di Roma). Orologio fisso.
 */
class TestbookEngContentSelectionTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Instant NOW = Instant.parse("2026-09-24T10:00:00Z");
    private static final Viewer GOLD = new Viewer("GOLD", List.of("SEG-A"), "ACTIVE", NOW.minus(Duration.ofDays(100)));

    // ---------------------------------------------------------------- SEL

    @ParameterizedTest(name = "[{0}] {1}", quoteTextArguments = false)
    @CsvFileSource(resources = "/testbook/engagement/sel.csv", numLinesToSkip = 1, delimiter = '\t', quoteCharacter = '~',
            maxCharsPerColumn = 8192)
    void eligibility(ArgumentsAccessor row) throws Exception {
        String[] c = TestbookRows.columns(row);
        eligibility(c[0], c[1], c[2], c[3], c[4], c[5]);
    }

    private void eligibility(String id, String description, String status, String schedule, String audience, String expected) {
        Instant start = switch (schedule) {
            case "BEFORE" -> NOW.plus(Duration.ofHours(1));
            case "IN" -> NOW.minus(Duration.ofHours(1));
            case "AFTER" -> NOW.minus(Duration.ofHours(2));
            default -> null;
        };
        Instant end = switch (schedule) {
            case "IN" -> NOW.plus(Duration.ofHours(1));
            case "AFTER" -> NOW.minus(Duration.ofHours(1));
            default -> null;
        };
        String aud = switch (audience) {
            case "MATCH" -> "{\"tiers\":[\"GOLD\"]}";
            case "NOMATCH" -> "{\"tiers\":[\"BASE\"]}";
            default -> "{}";
        };
        ContentItem c = item("CNT-" + id, "CARD", status, start, end, json(aud), 50, null);
        String reason = ContentSelection.exclusion(c, GOLD, NOW);
        List<ContentItem> shown = ContentSelection.select(List.of(c), GOLD, NOW, 6).shown();
        if ("SHOWN".equals(expected)) {
            assertThat(reason).as("nessun motivo di esclusione").isNull();
            assertThat(shown).containsExactly(c);
        } else {
            assertThat(reason).as("motivo tra quelli delle condizioni violate").isIn((Object[]) expected.split("\\+"));
            assertThat(shown).isEmpty();
        }
    }

    // ---------------------------------------------------------------- CAL

    @ParameterizedTest(name = "[{0}] {1}", quoteTextArguments = false)
    @CsvFileSource(resources = "/testbook/engagement/cal.csv", numLinesToSkip = 1, delimiter = '\t', quoteCharacter = '~',
            maxCharsPerColumn = 8192)
    void scheduleBoundaries(ArgumentsAccessor row) throws Exception {
        // TESTBOOK: ambiguo, vedi TB-ENG-CAL-001 e TB-ENG-CAL-004 (istante esatto di inizio e di fine).
        String[] c = TestbookRows.columns(row);
        Instant start = "-".equals(c[2]) ? null : NOW.plusSeconds(Long.parseLong(c[2]));
        Instant end = "-".equals(c[3]) ? null : NOW.plusSeconds(Long.parseLong(c[3]));
        ContentItem item = item("CNT-" + c[0], "CARD", "LIVE", start, end, json("{}"), 50, null);
        assertThat(ContentSelection.exclusion(item, GOLD, NOW)).isEqualTo("SHOWN".equals(c[4]) ? null : c[4]);
    }

    // ---------------------------------------------------------------- AUD

    @ParameterizedTest(name = "[{0}] {1}", quoteTextArguments = false)
    @CsvFileSource(resources = "/testbook/engagement/aud.csv", numLinesToSkip = 1, delimiter = '\t', quoteCharacter = '~',
            maxCharsPerColumn = 8192)
    void audience(ArgumentsAccessor row) throws Exception {
        String[] c = TestbookRows.columns(row);
        audience(c[0], c[1], c[2], c[3], c[4], c[5], c[6]);
    }

    // TESTBOOK: ambiguo, vedi le righe AUD marcate AMBIGUO (una dimensione del pubblico soddisfatta e un'altra no:
    // engagement §2 non dice se livelli, segmenti e stati vanno in AND, docs/17 US-E07-03 lo segnala da decidere).
    private void audience(String id, String description, String viewer, String tiers, String segments, String statuses, String expected) {
        Viewer v;
        if ("UNKNOWN".equals(viewer)) {
            v = Viewer.UNKNOWN;
        } else if (viewer.startsWith("S:")) {
            v = new Viewer("GOLD", List.of(), viewer.substring(2), NOW.minus(Duration.ofDays(100)));
        } else {
            v = new Viewer("GOLD", List.of("SEG-A", "SEG-B"), "ACTIVE", NOW.minus(Duration.ofDays(100)));
        }
        JsonNode audience;
        if ("NULL".equals(tiers)) {
            audience = MAPPER.nullNode();
        } else {
            ObjectNode a = MAPPER.createObjectNode();
            a.set("tiers", array(tiers));
            a.set("segments", array(segments));
            a.set("statuses", array(statuses));
            audience = a;
        }
        assertThat(ContentSelection.inAudience(audience, v, NOW)).isEqualTo("IN".equals(expected));
        ContentItem c = item("CNT-" + id, "CARD", "LIVE", null, null, audience, 50, null);
        assertThat(ContentSelection.exclusion(c, v, NOW)).isEqualTo("IN".equals(expected) ? null : "NOT_IN_AUDIENCE");
    }

    // ---------------------------------------------------------------- EXT (Q-71)

    @ParameterizedTest(name = "[{0}] {1}", quoteTextArguments = false)
    @CsvFileSource(resources = "/testbook/engagement/ext.csv", numLinesToSkip = 1, delimiter = '\t', quoteCharacter = '~',
            maxCharsPerColumn = 8192)
    void audienceExtensions(ArgumentsAccessor row) throws Exception {
        String[] c = TestbookRows.columns(row);
        audienceExtensions(c[0], c[1], c[2], c[3], c[4], c[5], c[6]);
    }

    private void audienceExtensions(String id, String description, String kind, String audience, String registeredAt, String now,
                            String expected) {
        // TESTBOOK: ambiguo, vedi TB-ENG-EXT-005 (iscrizione ignota) e TB-ENG-EXT-016 (chiavi Q-71 su una card).
        ObjectNode a = MAPPER.createObjectNode();
        if (audience.startsWith("within=")) {
            a.put("registeredWithinDays", Integer.parseInt(audience.substring("within=".length())));
        } else if (audience.startsWith("days=")) {
            ArrayNode days = a.putArray("daysOfWeek");
            String list = audience.substring("days=".length());
            if (!list.isBlank()) {
                Arrays.stream(list.split("\\+")).forEach(days::add);
            }
        }
        Instant at = Instant.parse(now);
        Viewer v = new Viewer("GOLD", List.of(), "ACTIVE", "-".equals(registeredAt) ? null : Instant.parse(registeredAt));
        if ("-".equals(registeredAt) && !audience.startsWith("within=")) {
            v = new Viewer("GOLD", List.of(), "ACTIVE", at.minus(Duration.ofDays(3)));
        }
        ContentItem c = item("POP-" + id, kind, "LIVE", null, null, a, 50, "POPUP".equals(kind) ? "ALWAYS" : null);
        boolean in = "IN".equals(expected);
        assertThat(ContentSelection.inAudience(a, v, at)).isEqualTo(in);
        if ("POPUP".equals(kind)) {
            assertThat(ContentSelection.selectPopup(List.of(c), v, at, Map.of()).shown()).hasSize(in ? 1 : 0);
        } else {
            assertThat(ContentSelection.select(List.of(c), v, at, 6).shown()).hasSize(in ? 1 : 0);
        }
    }

    // ---------------------------------------------------------------- ORD

    @ParameterizedTest(name = "[{0}] {1}", quoteTextArguments = false)
    @CsvFileSource(resources = "/testbook/engagement/ord.csv", numLinesToSkip = 1, delimiter = '\t', quoteCharacter = '~',
            maxCharsPerColumn = 8192)
    void orderAndLimits(ArgumentsAccessor row) throws Exception {
        String[] c = TestbookRows.columns(row);
        orderAndLimits(c[0], c[1], c[2], c[3], c[4]);
    }

    private void orderAndLimits(String id, String description, String placement, String items, String expected) {
        // TESTBOOK: ambiguo, vedi TB-ENG-ORD-002 (spareggio a parità di priorità per codice).
        List<ContentItem> candidates = new ArrayList<>();
        if (!"-".equals(items)) {
            for (String token : items.split(" ")) {
                boolean draft = token.startsWith("!");
                String[] parts = (draft ? token.substring(1) : token).split(":");
                candidates.add(item(parts[0], "CARD", draft ? "DRAFT" : "LIVE", null, null, json("{}"),
                        Integer.parseInt(parts[1]), null));
            }
        }
        List<String> shown = ContentSelection.select(candidates, GOLD, NOW, ContentSelection.LIMITS.get(placement)).shown()
                .stream().map(ContentItem::code).toList();
        assertThat(shown).containsExactlyElementsOf("-".equals(expected) ? List.of() : List.of(expected.split(" ")));
    }

    // ---------------------------------------------------------------- POP

    @ParameterizedTest(name = "[{0}] {1}", quoteTextArguments = false)
    @CsvFileSource(resources = "/testbook/engagement/pop.csv", numLinesToSkip = 1, delimiter = '\t', quoteCharacter = '~',
            maxCharsPerColumn = 8192)
    void popupFrequency(ArgumentsAccessor row) throws Exception {
        String[] c = TestbookRows.columns(row);
        popupFrequency(c[0], c[1], c[2], c[3], Boolean.parseBoolean(c[4]), c[5], c[6]);
    }

    private void popupFrequency(String id, String description, String frequency, String lastSeen, boolean dismissible, String now,
                        String expected) {
        Instant at = "-".equals(now) ? NOW : Instant.parse(now);
        LocalDate today = LocalDate.ofInstant(at, ContentSelection.ZONE);
        LocalDate seen = switch (lastSeen) {
            case "NEVER" -> null;
            case "TODAY" -> today;
            case "YESTERDAY" -> today.minusDays(1);
            case "D30" -> today.minusDays(30);
            default -> LocalDate.parse(lastSeen);
        };
        ContentItem popup = new ContentItem("ID-" + id, "POP-" + id, "POPUP", null, "Pop-up", null, null, null, null, "NONE",
                null, json("{}"), null, null, 50, frequency, dismissible, json("{}"), "LIVE", 0, null);
        Map<String, LocalDate> views = new HashMap<>();
        if (seen != null) {
            views.put(popup.id(), seen);
        }
        ContentSelection.Result r = ContentSelection.selectPopup(List.of(popup), GOLD, at, views);
        if ("SHOWN".equals(expected)) {
            assertThat(r.shown()).containsExactly(popup);
        } else {
            assertThat(r.shown()).isEmpty();
            assertThat(r.excluded()).extracting(ContentSelection.Excluded::reason).containsExactly(expected);
        }
    }

    @Test
    @DisplayName("[TB-ENG-POP-035] due pop-up idonei, priorità 90 e 80: al più uno, quello a 90")
    void atMostOnePopup() {
        ContentItem high = popup("POP-HIGH", 90, "ALWAYS");
        ContentItem low = popup("POP-LOW", 80, "ALWAYS");
        ContentSelection.Result r = ContentSelection.selectPopup(List.of(low, high), GOLD, NOW, Map.of());
        assertThat(r.shown()).containsExactly(high);
    }

    @Test
    @DisplayName("[TB-ENG-POP-036] pop-up a 90 già visto (ONCE) e pop-up a 80 mai visto: si vede quello a 80")
    void frequencySkipsToNext() {
        ContentItem high = popup("POP-HIGH", 90, "ONCE");
        ContentItem low = popup("POP-LOW", 80, "ONCE");
        ContentSelection.Result r = ContentSelection.selectPopup(List.of(high, low), GOLD, NOW,
                Map.of(high.id(), LocalDate.of(2026, 9, 1)));
        assertThat(r.shown()).containsExactly(low);
        assertThat(r.excluded()).extracting(e -> e.item().code() + ":" + e.reason()).containsExactly("POP-HIGH:FREQUENCY");
    }

    @Test
    @DisplayName("[TB-ENG-POP-037] nessun pop-up idoneo: nessun pop-up")
    void noPopup() {
        assertThat(ContentSelection.selectPopup(List.of(), GOLD, NOW, Map.of()).shown()).isEmpty();
        ContentItem gold = new ContentItem("ID-X", "POP-X", "POPUP", null, "x", null, null, null, null, "NONE", null,
                json("{\"tiers\":[\"PLATINUM\"]}"), null, null, 50, "ALWAYS", true, json("{}"), "LIVE", 0, null);
        assertThat(ContentSelection.selectPopup(List.of(gold), GOLD, NOW, Map.of()).shown()).isEmpty();
    }

    @Test
    @DisplayName("[TB-ENG-POP-038] pop-up DRAFT già visto: escluso con NOT_LIVE o FREQUENCY")
    void draftAndSeen() {
        ContentItem draft = new ContentItem("ID-D", "POP-D", "POPUP", null, "x", null, null, null, null, "NONE", null,
                json("{}"), null, null, 50, "ONCE", true, json("{}"), "DRAFT", 0, null);
        ContentSelection.Result r = ContentSelection.selectPopup(List.of(draft), GOLD, NOW, Map.of("ID-D", LocalDate.of(2026, 9, 1)));
        assertThat(r.shown()).isEmpty();
        assertThat(r.excluded()).extracting(ContentSelection.Excluded::reason).allMatch(x -> x.equals("NOT_LIVE") || x.equals("FREQUENCY"));
    }

    // ---------------------------------------------------------------- helper

    private static ContentItem popup(String code, int priority, String frequency) {
        return new ContentItem("ID-" + code, code, "POPUP", null, code, null, null, null, null, "NONE", null, json("{}"),
                null, null, priority, frequency, true, json("{}"), "LIVE", 0, null);
    }

    private static ContentItem item(String code, String kind, String status, Instant start, Instant end, JsonNode audience,
                                    int priority, String frequency) {
        return new ContentItem("ID-" + code, code, kind, "POPUP".equals(kind) ? null : "HOME_GRID", code, null, null, null,
                null, "NONE", null, audience, start, end, priority, frequency, true, json("{}"), status, 0, null);
    }

    private static ArrayNode array(String spaceSeparated) {
        ArrayNode a = MAPPER.createArrayNode();
        if (!"-".equals(spaceSeparated)) {
            Arrays.stream(spaceSeparated.split(" ")).forEach(a::add);
        }
        return a;
    }

    private static JsonNode json(String s) {
        return MAPPER.readTree(s);
    }
}
