package io.loyaltyhub.engagement;

import io.loyaltyhub.engagement.TestbookApi.Resp;
import io.loyaltyhub.engagement.application.ContentService;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.aggregator.ArgumentsAccessor;
import org.junit.jupiter.params.provider.CsvFileSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testbook TB-ENG (docs/testbook/TB-ENG-engagement.md), contenuti e tema via API: ciclo di vita (LIF), creazione e
 * modifica (EDT, EDL), duplicazione (DUP), anteprima e portale (PRV), pop-up del portale (POPA), fine automatica (END),
 * ruoli (ROL), tema (THA). Dati propri per ogni riga (codici e membri nuovi, pubblico ristretto a un segmento proprio);
 * i pop-up del seed sono archiviati all'avvio per non dipendere dal giorno della settimana. Orologio con scostamento
 * regolabile per la mezzanotte di Roma. EmbeddedKafka + Zonky, un solo contesto.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EmbeddedKafka(partitions = 1, topics = {"lh.actions.v1", "lh.effects.v1", "lh.facts.v1", "lh.audit.v1", "lh.dlq.v1"})
@ActiveProfiles("demo")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TestbookEngContentIT {

    private static final EmbeddedPostgres PG = startPg();
    private static final ZoneId ROME = ZoneId.of("Europe/Rome");
    private static final String MKT = "MARKETING:luca.marketing";
    private static final String ADMIN = "ADMIN:marta.admin";
    private static final String STATUS_FACT = "io.loyaltyhub.fact.content.status.changed";

    /** Orologio di sistema con uno scostamento regolabile dal test (docs/servizi/engagement-service.md §7: orologio iniettato). */
    static final class OffsetClock extends Clock {
        private volatile Duration offset = Duration.ZERO;

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.now().plus(offset);
        }

        void setNow(Instant target) {
            offset = Duration.between(Instant.now(), target);
        }

        void reset() {
            offset = Duration.ZERO;
        }
    }

    @TestConfiguration
    static class ClockConfig {
        @Bean
        OffsetClock testbookClock() {
            return new OffsetClock();
        }
    }

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private ContentService contents;

    @Autowired
    private OffsetClock clock;

    private TestbookApi api;
    private final AtomicInteger seq = new AtomicInteger();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        String base = PG.getJdbcUrl("postgres", "postgres");
        registry.add("spring.datasource.url", () -> base + "&currentSchema=engagement");
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "");
        registry.add("spring.kafka.bootstrap-servers", () -> System.getProperty("spring.embedded.kafka.brokers"));
    }

    @BeforeAll
    void setUp() {
        api = new TestbookApi(port, jdbc);
        // I pop-up del seed (POP-WEEKEND vale per tutti nel fine settimana) non devono interferire con le righe.
        jdbc.sql("UPDATE content_item SET status = 'ARCHIVED' WHERE kind = 'POPUP'").update();
    }

    @AfterAll
    void tearDown() throws Exception {
        api.close();
        PG.close();
    }

    // ================================================================= LIF

    @ParameterizedTest(name = "[{0}] {1}", quoteTextArguments = false)
    @CsvFileSource(resources = "/testbook/engagement/lif.csv", numLinesToSkip = 1, delimiter = '\t', quoteCharacter = '~',
            maxCharsPerColumn = 8192)
    void lifecycle(ArgumentsAccessor row) throws Exception {
        // TESTBOOK: ambiguo, vedi TB-ENG-LIF-009/010, -019/020, -029/030, -039/040, -049/050 (azione sconosciuta o
        // mancante: 409 oggi, 400 "parametri errati" altrettanto plausibile).
        String[] c = TestbookRows.columns(row);
        String state = c[2];
        String action = c[3];
        String code = create(card(code("CNT"))).path("code").asString();
        moveTo(code, state);
        Resp r = api.send("POST", "/v1/contents/" + code + "/transitions", MKT,
                "<missing>".equals(action) ? Map.of() : Map.of("action", action));
        assertThat(r.status()).as(r.body().toString()).isEqualTo(Integer.parseInt(c[4]));
        if (r.status() == 409) {
            assertThat(r.code()).isEqualTo("INVALID_TRANSITION");
        } else {
            assertThat(r.body().path("status").asString()).isEqualTo(c[5]);
        }
        assertThat(content(code).path("status").asString()).isEqualTo(c[5]);
    }

    @Test
    @DisplayName("[TB-ENG-LIF-051] transizione valida: fatto content.status.changed con stati e attore")
    void statusFactOnTransition() {
        String code = create(card(code("CNT"))).path("code").asString();
        transition(code, "PUBLISH");
        List<JsonNode> facts = statusFacts(code);
        assertThat(facts).hasSize(1);
        JsonNode f = facts.getFirst();
        assertThat(f.path("data").path("previousStatus").asString()).isEqualTo("DRAFT");
        assertThat(f.path("data").path("newStatus").asString()).isEqualTo("LIVE");
        assertThat(f.path("data").path("contentCode").asString()).isEqualTo(code);
        assertThat(f.path("lhactor").asString()).isEqualTo(MKT);
    }

    @Test
    @DisplayName("[TB-ENG-LIF-052] transizione rifiutata: nessun fatto")
    void noFactOnRefusedTransition() {
        String code = create(card(code("CNT"))).path("code").asString();
        assertThat(api.send("POST", "/v1/contents/" + code + "/transitions", MKT, Map.of("action", "PAUSE")).status()).isEqualTo(409);
        assertThat(statusFacts(code)).isEmpty();
    }

    // ================================================================= EDT

    @ParameterizedTest(name = "[{0}] {1}", quoteTextArguments = false)
    @CsvFileSource(resources = "/testbook/engagement/edt.csv", numLinesToSkip = 1, delimiter = '\t', quoteCharacter = '~',
            maxCharsPerColumn = 8192)
    void createValidation(ArgumentsAccessor row) throws Exception {
        // TESTBOOK: ambiguo, vedi le righe EDT marcate AMBIGUO (vincoli del codice senza fonte: banner solo CATALOG_TOP,
        // PRIZE solo WIN, linkCode obbligatorio, lunghezze, priorità 0..1000 e 50 di default, http:// nella CTA, codice
        // in minuscolo, chiudibile di default).
        String[] c = TestbookRows.columns(row);
        String body = c[2].replace("{CODE}", code("CNT"));
        int http = Integer.parseInt(c[3]);
        String detail = c[4];
        Resp r = api.send("POST", "/v1/contents", MKT, body);
        assertThat(r.status()).as(r.body().toString()).isEqualTo(http);
        JsonNode b = r.body();
        if (http == 422) {
            assertThat(r.code()).isEqualTo("CONTENT_INVALID");
            assertThat(r.fields()).contains(detail);
            return;
        }
        if (detail.startsWith("link:")) {
            assertThat(b.path("linkType").asString()).isEqualTo(detail.substring("link:".length()));
            return;
        }
        switch (detail) {
            case "DRAFT" -> {
                assertThat(b.path("status").asString()).isEqualTo("DRAFT");
                assertThat(b.path("version").asLong()).isZero();
            }
            case "HOME_HERO", "CONTEST", "CATALOG_TOP", "WIN" -> assertThat(b.path("placement").asString()).isEqualTo(detail);
            case "POPUP" -> {
                assertThat(b.path("placement").isNull() || b.path("placement").isMissingNode()).isTrue();
                assertThat(b.path("frequency").asString()).isEqualTo("ONCE");
            }
            case "ONCE_PER_DAY", "ALWAYS" -> assertThat(b.path("frequency").asString()).isEqualTo(detail);
            case "freq-null" -> assertThat(b.path("frequency").isNull() || b.path("frequency").isMissingNode()).isTrue();
            case "not-dismissible" -> assertThat(b.path("dismissible").asBoolean()).isFalse();
            case "dismissible" -> assertThat(b.path("dismissible").asBoolean()).isTrue();
            case "NIGHT" -> assertThat(b.path("style").path("tone").asString()).isEqualTo("NIGHT");
            case "ABC", "CNT-MINUSCOLO" -> assertThat(b.path("code").asString()).isEqualTo(detail);
            case "C40" -> assertThat(b.path("code").asString()).isEqualTo("C".repeat(40));
            case "P0" -> assertThat(b.path("priority").asInt()).isZero();
            case "P1000" -> assertThat(b.path("priority").asInt()).isEqualTo(1000);
            case "P50" -> assertThat(b.path("priority").asInt()).isEqualTo(50);
            case "/portal/earn" -> assertThat(b.path("ctaTarget").asString()).isEqualTo("/portal/earn");
            case "https" -> assertThat(b.path("ctaTarget").asString()).startsWith("https://");
            default -> assertThat(b.path("id").asString()).isNotBlank();
        }
    }

    @Test
    @DisplayName("[TB-ENG-EDT-053] creazione con codice già usato: 409 CODE_TAKEN")
    void duplicateCode() {
        Map<String, Object> body = card(code("CNT"));
        create(body);
        Resp r = api.send("POST", "/v1/contents", MKT, body);
        assertThat(r.status()).isEqualTo(409);
        assertThat(r.code()).isEqualTo("CODE_TAKEN");
    }

    @Test
    @DisplayName("[TB-ENG-EDT-054] modifica con versione superata: 409 VERSION_CONFLICT")
    void versionConflict() {
        Map<String, Object> body = card(code("CNT"));
        create(body);
        String code = (String) body.get("code");
        body.put("version", 0);
        body.put("title", "Prima modifica");
        api.ok("PUT", "/v1/contents/" + code, MKT, body, 200);
        body.put("title", "Modifica tardiva");
        Resp r = api.send("PUT", "/v1/contents/" + code, MKT, body);
        assertThat(r.status()).isEqualTo(409);
        assertThat(r.code()).isEqualTo("VERSION_CONFLICT");
    }

    @Test
    @DisplayName("[TB-ENG-EDT-055] modifica che cambia il codice: 409 CODE_IMMUTABLE")
    void codeImmutable() {
        // TESTBOOK: ambiguo, vedi TB-ENG-EDT-055.
        Map<String, Object> body = card(code("CNT"));
        create(body);
        String code = (String) body.get("code");
        body.put("code", code + "-ALTRO");
        Resp r = api.send("PUT", "/v1/contents/" + code, MKT, body);
        assertThat(r.status()).isEqualTo(409);
        assertThat(r.code()).isEqualTo("CODE_IMMUTABLE");
    }

    @Test
    @DisplayName("[TB-ENG-EDT-056] modifica che cambia il tipo di una bozza: 409 KIND_IMMUTABLE")
    void kindImmutable() {
        // TESTBOOK: ambiguo, vedi TB-ENG-EDT-056.
        Map<String, Object> body = card(code("CNT"));
        create(body);
        body.put("kind", "POPUP");
        Resp r = api.send("PUT", "/v1/contents/" + body.get("code"), MKT, body);
        assertThat(r.status()).isEqualTo(409);
        assertThat(r.code()).isEqualTo("KIND_IMMUTABLE");
    }

    @Test
    @DisplayName("[TB-ENG-EDT-057] modifica di un contenuto ARCHIVED: 409 CONTENT_NOT_EDITABLE")
    void archivedNotEditable() {
        // TESTBOOK: ambiguo, vedi TB-ENG-EDT-057.
        Map<String, Object> body = card(code("CNT"));
        create(body);
        String code = (String) body.get("code");
        transition(code, "ARCHIVE");
        Resp r = api.send("PUT", "/v1/contents/" + code, MKT, body);
        assertThat(r.status()).isEqualTo(409);
        assertThat(r.code()).isEqualTo("CONTENT_NOT_EDITABLE");
    }

    @Test
    @DisplayName("[TB-ENG-EDT-058] modifica di un contenuto inesistente: 404")
    void updateNotFound() {
        assertThat(api.send("PUT", "/v1/contents/CNT-NON-ESISTE", MKT, card("CNT-NON-ESISTE")).status()).isEqualTo(404);
    }

    @Test
    @DisplayName("[TB-ENG-EDT-059] modifica di una bozza: posizionamento e pubblico cambiano")
    void draftFullyEditable() {
        Map<String, Object> body = card(code("CNT"));
        create(body);
        body.put("placement", "HOME_HERO");
        body.put("audience", Map.of("tiers", List.of("GOLD")));
        body.put("version", 0);
        JsonNode updated = api.ok("PUT", "/v1/contents/" + body.get("code"), MKT, body, 200);
        assertThat(updated.path("placement").asString()).isEqualTo("HOME_HERO");
        assertThat(updated.path("audience").path("tiers").toString()).isEqualTo("[\"GOLD\"]");
        assertThat(updated.path("version").asLong()).isEqualTo(1);
    }

    @Test
    @DisplayName("[TB-ENG-EDT-060] modifica di un contenuto ENDED: 200")
    void endedEditable() {
        // TESTBOOK: ambiguo, vedi TB-ENG-EDT-060 (docs/17 US-E07-01 indica 409, le fonti tacciono).
        Map<String, Object> body = card(code("CNT"));
        String code = create(body).path("code").asString();
        transition(code, "PUBLISH");
        transition(code, "END");
        body.put("title", "Dopo la fine");
        body.put("version", content(code).path("version").asLong());
        assertThat(api.send("PUT", "/v1/contents/" + code, MKT, body).status()).isEqualTo(200);
    }

    // ================================================================= EDL

    @ParameterizedTest(name = "[{0}] {1}", quoteTextArguments = false)
    @CsvFileSource(resources = "/testbook/engagement/edl.csv", numLinesToSkip = 1, delimiter = '\t', quoteCharacter = '~',
            maxCharsPerColumn = 8192)
    void liveEdits(ArgumentsAccessor row) throws Exception {
        String[] c = TestbookRows.columns(row);
        String kind = c[2];
        String field = c[3];
        String value = c[4];
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Map<String, Object> body = "POPUP".equals(kind) ? popup(code("POP"), "ONCE", List.of()) : card(code("CNT"));
        body.putAll(Map.of("body", "Testo", "imageUrl", "/demo/contents/a.webp", "linkType", "CAMPAIGN", "linkCode", "CMP-EBILL",
                "audience", Map.of(), "startAt", now.minus(Duration.ofDays(30)).toString(),
                "endAt", now.plus(Duration.ofDays(365)).toString(), "priority", 50, "style", Map.of("tone", "PRIMARY")));
        if ("POPUP".equals(kind)) {
            body.remove("linkType");
            body.remove("linkCode");
        }
        String code = (String) create(body).path("code").asString();
        transition(code, "PUBLISH");
        body.put("version", content(code).path("version").asLong());
        if ("link".equals(field)) {
            String[] link = TestbookApi.MAPPER.readTree(value).asString().split(":");
            body.put("linkType", link[0]);
            body.put("linkCode", link[1]);
        } else {
            body.put(field, TestbookApi.MAPPER.readTree(value));
        }
        Resp r = api.send("PUT", "/v1/contents/" + code, MKT, body);
        try {
            assertThat(r.status()).as("docs/03 §3.6: un oggetto LIVE si modifica solo nei campi sicuri → " + r.body())
                    .isEqualTo(Integer.parseInt(c[5]));
        } finally {
            transition(code, "END"); // non resta LIVE per le altre righe
        }
    }

    // ================================================================= DUP

    @Test
    @DisplayName("[TB-ENG-DUP-001] duplica una bozza: nuovo id e codice, DRAFT, versione 0, stessi campi")
    void duplicateDraft() {
        Map<String, Object> body = card(code("CNT"));
        body.putAll(Map.of("body", "Testo", "linkType", "CAMPAIGN", "linkCode", "CMP-EBILL", "priority", 70,
                "audience", Map.of("tiers", List.of("GOLD"))));
        JsonNode original = create(body);
        JsonNode copy = api.ok("POST", "/v1/contents/" + original.path("code").asString() + "/duplicate", MKT, null, 201);
        assertThat(copy.path("id").asString()).isNotEqualTo(original.path("id").asString());
        assertThat(copy.path("code").asString()).isNotEqualTo(original.path("code").asString());
        assertThat(copy.path("status").asString()).isEqualTo("DRAFT");
        assertThat(copy.path("version").asLong()).isZero();
        for (String f : List.of("kind", "placement", "body", "linkType", "linkCode", "priority", "audience")) {
            assertThat(copy.path(f)).as(f).isEqualTo(original.path(f));
        }
    }

    @Test
    @DisplayName("[TB-ENG-DUP-002] codice e titolo della copia e della seconda copia")
    void duplicateNaming() {
        // TESTBOOK: ambiguo, vedi TB-ENG-DUP-002.
        String code = create(card(code("CNT"))).path("code").asString();
        JsonNode first = api.ok("POST", "/v1/contents/" + code + "/duplicate", MKT, null, 201);
        JsonNode second = api.ok("POST", "/v1/contents/" + code + "/duplicate", MKT, null, 201);
        assertThat(first.path("code").asString()).isEqualTo(code + "-COPIA");
        assertThat(second.path("code").asString()).isEqualTo(code + "-COPIA-2");
        assertThat(first.path("title").asString()).isEqualTo("Testbook (copia)");
    }

    @Test
    @DisplayName("[TB-ENG-DUP-003] duplica un contenuto LIVE: copia in DRAFT, originale LIVE")
    void duplicateLive() {
        String code = create(card(code("CNT"))).path("code").asString();
        transition(code, "PUBLISH");
        JsonNode copy = api.ok("POST", "/v1/contents/" + code + "/duplicate", MKT, null, 201);
        assertThat(copy.path("status").asString()).isEqualTo("DRAFT");
        assertThat(content(code).path("status").asString()).isEqualTo("LIVE");
        transition(code, "END");
    }

    @Test
    @DisplayName("[TB-ENG-DUP-004] duplica un contenuto ARCHIVED: copia in DRAFT")
    void duplicateArchived() {
        String code = create(card(code("CNT"))).path("code").asString();
        transition(code, "ARCHIVE");
        JsonNode copy = api.ok("POST", "/v1/contents/" + code + "/duplicate", MKT, null, 201);
        assertThat(copy.path("status").asString()).isEqualTo("DRAFT");
    }

    @Test
    @DisplayName("[TB-ENG-DUP-005] duplica un pop-up: frequenza e chiudibilità conservate")
    void duplicatePopup() {
        Map<String, Object> body = popup(code("POP"), "ONCE_PER_DAY", List.of("SEG-TB-DUP"));
        body.put("dismissible", false);
        String code = create(body).path("code").asString();
        JsonNode copy = api.ok("POST", "/v1/contents/" + code + "/duplicate", MKT, null, 201);
        assertThat(copy.path("frequency").asString()).isEqualTo("ONCE_PER_DAY");
        assertThat(copy.path("dismissible").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("[TB-ENG-DUP-006] duplica un contenuto con codice di 40 caratteri: codice della copia valido")
    void duplicateLongCode() {
        // TESTBOOK: ambiguo, vedi TB-ENG-DUP-006.
        String code = "L".repeat(40);
        create(card(code));
        String copy = api.ok("POST", "/v1/contents/" + code + "/duplicate", MKT, null, 201).path("code").asString();
        assertThat(copy).matches("^[A-Z][A-Z0-9-]{2,39}$");
    }

    @Test
    @DisplayName("[TB-ENG-DUP-007] duplica un contenuto inesistente: 404")
    void duplicateNotFound() {
        assertThat(api.send("POST", "/v1/contents/CNT-NON-ESISTE/duplicate", MKT, null).status()).isEqualTo(404);
    }

    // ================================================================= PRV

    @Test
    @DisplayName("[TB-ENG-PRV-001] pubblico tiers=[GOLD,PLATINUM]: assente per il BASE, presente per il GOLD, anteprima NOT_IN_AUDIENCE")
    void tierAudienceAcceptance() {
        Map<String, Object> body = card(code("CNT"));
        body.put("priority", 1000);
        body.put("audience", Map.of("tiers", List.of("gold", "PLATINUM")));
        String code = create(body).path("code").asString();
        transition(code, "PUBLISH");
        String base = member("BASE", List.of(), "ACTIVE");
        String gold = member("GOLD", List.of(), "ACTIVE");
        try {
            assertThat(codes(api.get("/v1/portal/content?placement=HOME_GRID&memberId=" + base).body())).doesNotContain(code);
            assertThat(codes(api.get("/v1/portal/content?placement=HOME_GRID&memberId=" + gold).body())).contains(code);
            assertThat(reason(preview(base, "HOME_GRID"), code)).isEqualTo("NOT_IN_AUDIENCE");
        } finally {
            transition(code, "END");
        }
    }

    @Test
    @DisplayName("[TB-ENG-PRV-002] anteprima di una bozza: NOT_LIVE")
    void previewNotLive() {
        String code = create(card(code("CNT"))).path("code").asString();
        assertThat(reason(preview(member("GOLD", List.of(), "ACTIVE"), "HOME_GRID"), code)).isEqualTo("NOT_LIVE");
    }

    @Test
    @DisplayName("[TB-ENG-PRV-003] anteprima di un LIVE con inizio futuro: OUT_OF_SCHEDULE")
    void previewOutOfSchedule() {
        Map<String, Object> body = card(code("CNT"));
        body.put("startAt", Instant.now().plus(Duration.ofDays(1)).toString());
        String code = create(body).path("code").asString();
        transition(code, "PUBLISH");
        try {
            assertThat(reason(preview(member("GOLD", List.of(), "ACTIVE"), "HOME_GRID"), code)).isEqualTo("OUT_OF_SCHEDULE");
        } finally {
            transition(code, "END");
        }
    }

    @Test
    @DisplayName("[TB-ENG-PRV-004] anteprima dei pop-up dopo la vista di un ONCE: FREQUENCY")
    void previewFrequency() {
        String segment = segment();
        String code = livePopup("ONCE", segment, 900);
        String m = member("GOLD", List.of(segment), "ACTIVE");
        api.ok("POST", "/v1/portal/popups/" + code + "/seen", null, Map.of("memberId", m, "dismissed", true), 204);
        assertThat(reason(preview(m, "POPUP"), code)).isEqualTo("FREQUENCY");
    }

    @Test
    @DisplayName("[TB-ENG-PRV-005] anteprima senza memberId: 400")
    void previewWithoutMember() {
        assertThat(api.get("/v1/contents/preview?placement=HOME_GRID").status()).isEqualTo(400);
    }

    @Test
    @DisplayName("[TB-ENG-PRV-006] anteprima con posizionamento sconosciuto: 400")
    void previewBadPlacement() {
        assertThat(api.get("/v1/contents/preview?memberId=MBR-000001&placement=ALTROVE").status()).isEqualTo(400);
    }

    @Test
    @DisplayName("[TB-ENG-PRV-007] portale WIN con prizeCode: solo la card del premio vinto")
    void winCardByPrize() {
        List<String> created = new ArrayList<>();
        for (String prize : List.of("TBPRIZE-A", "TBPRIZE-B")) {
            Map<String, Object> body = card(code("WIN"));
            body.putAll(Map.of("placement", "WIN", "linkType", "PRIZE", "linkCode", prize));
            String code = create(body).path("code").asString();
            transition(code, "PUBLISH");
            created.add(code);
        }
        String m = member("GOLD", List.of(), "ACTIVE");
        assertThat(codes(api.get("/v1/portal/content?placement=WIN&prizeCode=TBPRIZE-B&memberId=" + m).body()))
                .containsExactly(created.get(1));
    }

    @Test
    @DisplayName("[TB-ENG-PRV-008] portale con posizionamento sconosciuto: 400")
    void portalBadPlacement() {
        assertThat(api.get("/v1/portal/content?memberId=MBR-000001&placement=ALTROVE").status()).isEqualTo(400);
    }

    @Test
    @DisplayName("[TB-ENG-PRV-009] portale senza memberId: 400 (docs/06 §2)")
    void portalWithoutMember() {
        Resp r = api.get("/v1/portal/content?placement=HOME_GRID");
        assertThat(r.status()).as("docs/06 §2: il portale è sempre con memberId esplicito").isEqualTo(400);
    }

    @Test
    @DisplayName("[TB-ENG-PRV-010] membro sconosciuto: vede i contenuti per tutti, non quelli con pubblico")
    void unknownMember() {
        Map<String, Object> forAll = card(code("CNT"));
        forAll.putAll(Map.of("placement", "CONTEST", "priority", 999));
        Map<String, Object> restricted = card(code("CNT"));
        restricted.putAll(Map.of("placement", "CONTEST", "priority", 998, "audience", Map.of("segments", List.of(segment()))));
        String all = create(forAll).path("code").asString();
        String seg = create(restricted).path("code").asString();
        transition(all, "PUBLISH");
        transition(seg, "PUBLISH");
        try {
            JsonNode p = preview("MBR-UNKNOWN-TB", "CONTEST");
            assertThat(codes(p.path("shown"))).contains(all).doesNotContain(seg);
            assertThat(reason(p, seg)).isEqualTo("NOT_IN_AUDIENCE");
        } finally {
            transition(all, "END");
            transition(seg, "END");
        }
    }

    @Test
    @DisplayName("[TB-ENG-PRV-011] risposta del portale senza pubblico, stato, versione")
    void portalDisplayOnly() {
        // TESTBOOK: ambiguo, vedi TB-ENG-PRV-011.
        JsonNode items = api.get("/v1/portal/content?placement=CATALOG_TOP&memberId=MBR-000001").body();
        assertThat(items.size()).isPositive();
        for (JsonNode i : items) {
            assertThat(i.has("audience") || i.has("status") || i.has("version")).isFalse();
        }
    }

    @Test
    @DisplayName("[TB-ENG-PRV-012] anteprima di WIN con due card vincita: entrambe mostrate")
    void winPreviewShowsAll() {
        // TESTBOOK: ambiguo, vedi TB-ENG-PRV-012.
        List<String> created = new ArrayList<>();
        for (String prize : List.of("TBPRV-A", "TBPRV-B")) {
            Map<String, Object> body = card(code("WIN"));
            body.putAll(Map.of("placement", "WIN", "linkType", "PRIZE", "linkCode", prize, "priority", 1000));
            String code = create(body).path("code").asString();
            transition(code, "PUBLISH");
            created.add(code);
        }
        String m = member("GOLD", List.of(), "ACTIVE");
        assertThat(codes(preview(m, "WIN").path("shown"))).containsAll(created);
        assertThat(api.get("/v1/portal/content?placement=WIN&memberId=" + m).body().size()).isEqualTo(1);
    }

    // ================================================================= AUDT

    @Test
    @DisplayName("[TB-ENG-AUDT-001] creazione di un contenuto: audit CREATE con l'attore")
    void auditContentCreate() {
        String code = create(card(code("CNT"))).path("code").asString();
        List<JsonNode> audits = audits("CONTENT:" + code);
        assertThat(audits).hasSize(1);
        assertThat(audits.getFirst().path("data").path("action").asString()).isEqualTo("CREATE");
        assertThat(audits.getFirst().path("lhactor").asString()).isEqualTo(MKT);
    }

    @Test
    @DisplayName("[TB-ENG-AUDT-002] transizione di un contenuto: audit TRANSITION con stato prima/dopo")
    void auditContentTransition() {
        String code = create(card(code("CNT"))).path("code").asString();
        transition(code, "PUBLISH");
        JsonNode t = audits("CONTENT:" + code).stream()
                .filter(a -> a.path("data").path("action").asString().equals("TRANSITION")).findFirst().orElseThrow();
        assertThat(t.path("data").path("before").path("status").asString()).isEqualTo("DRAFT");
        assertThat(t.path("data").path("after").path("status").asString()).isEqualTo("LIVE");
        transition(code, "END");
    }

    @Test
    @DisplayName("[TB-ENG-AUDT-003] creazione rifiutata: nessun audit")
    void auditNotOnRefusal() {
        String code = code("CNT");
        Map<String, Object> body = card(code);
        body.put("kind", "VIDEO");
        assertThat(api.send("POST", "/v1/contents", MKT, body).status()).isEqualTo(422);
        assertThat(audits("CONTENT:" + code)).isEmpty();
    }

    @Test
    @DisplayName("[TB-ENG-AUDT-004] modifica del tema: audit UPDATE su THEME:default")
    void auditTheme() {
        int before = audits("THEME:default").size();
        api.ok("PUT", "/v1/theme", MKT, theme("#1FB98F", "#0E1B2C", "#F3F7F9"), 200);
        List<JsonNode> after = audits("THEME:default");
        assertThat(after).hasSize(before + 1);
        assertThat(after.getLast().path("data").path("action").asString()).isEqualTo("UPDATE");
    }

    // ================================================================= POPA

    @Test
    @DisplayName("[TB-ENG-POPA-001] nessun pop-up idoneo: 204")
    void noPopupIs204() {
        assertThat(api.get("/v1/portal/popups/next?memberId=" + member("GOLD", List.of(segment()), "ACTIVE")).status()).isEqualTo(204);
    }

    @Test
    @DisplayName("[TB-ENG-POPA-002] popups/next senza memberId: 400")
    void nextWithoutMember() {
        assertThat(api.get("/v1/portal/popups/next").status()).isEqualTo(400);
    }

    @Test
    @DisplayName("[TB-ENG-POPA-003] lettura ripetuta senza seen: stesso pop-up")
    void readDoesNotConsume() {
        String segment = segment();
        String code = livePopup("ONCE", segment, 900);
        String m = member("GOLD", List.of(segment), "ACTIVE");
        assertThat(nextPopup(m)).isEqualTo(code);
        assertThat(nextPopup(m)).isEqualTo(code);
    }

    @Test
    @DisplayName("[TB-ENG-POPA-004] ONCE visto e chiuso: non ricompare")
    void onceSeenAndClosed() {
        String segment = segment();
        String code = livePopup("ONCE", segment, 900);
        String m = member("GOLD", List.of(segment), "ACTIVE");
        String id = api.get("/v1/portal/popups/next?memberId=" + m).body().path("id").asString();
        api.ok("POST", "/v1/portal/popups/" + id + "/seen", null, Map.of("memberId", m, "dismissed", true), 204);
        assertThat(api.get("/v1/portal/popups/next?memberId=" + m).status()).isEqualTo(204);
        assertThat(code).isNotBlank();
    }

    @Test
    @DisplayName("[TB-ENG-POPA-005] ONCE visto senza chiuderlo: non ricompare")
    void onceSeenNotClosed() {
        String segment = segment();
        String code = livePopup("ONCE", segment, 900);
        String m = member("GOLD", List.of(segment), "ACTIVE");
        api.ok("POST", "/v1/portal/popups/" + code + "/seen", null, Map.of("memberId", m, "dismissed", false), 204);
        assertThat(api.get("/v1/portal/popups/next?memberId=" + m).status()).isEqualTo(204);
    }

    @Test
    @DisplayName("[TB-ENG-POPA-006] ONCE_PER_DAY visto alle 23:59 di Roma: escluso fino a mezzanotte, poi ricompare")
    void dailyPopupRomeMidnight() {
        String segment = segment();
        String code = livePopup("ONCE_PER_DAY", segment, 900);
        String m = member("GOLD", List.of(segment), "ACTIVE");
        LocalDate day = LocalDate.now(ROME).plusDays(1);
        try {
            clock.setNow(day.atTime(23, 59, 55).atZone(ROME).toInstant());
            assertThat(nextPopup(m)).isEqualTo(code);
            api.ok("POST", "/v1/portal/popups/" + code + "/seen", null, Map.of("memberId", m, "dismissed", true), 204);
            assertThat(api.get("/v1/portal/popups/next?memberId=" + m).status()).as("stesso giorno di Roma").isEqualTo(204);
            assertThat(api.count("SELECT count(*) FROM popup_view WHERE member_id = ? AND view_date = ?", m, java.sql.Date.valueOf(day)))
                    .as("vista registrata nel giorno di Roma").isEqualTo(1);
            clock.setNow(day.plusDays(1).atStartOfDay(ROME).toInstant().plusSeconds(1));
            assertThat(nextPopup(m)).as("il giorno dopo ricompare").isEqualTo(code);
        } finally {
            clock.reset();
        }
    }

    @Test
    @DisplayName("[TB-ENG-POPA-007] ALWAYS visto: ricompare")
    void alwaysPopupAgain() {
        String segment = segment();
        String code = livePopup("ALWAYS", segment, 900);
        String m = member("GOLD", List.of(segment), "ACTIVE");
        api.ok("POST", "/v1/portal/popups/" + code + "/seen", null, Map.of("memberId", m, "dismissed", true), 204);
        assertThat(nextPopup(m)).isEqualTo(code);
    }

    @Test
    @DisplayName("[TB-ENG-POPA-008] seen due volte nello stesso giorno: una vista, chiusura registrata")
    void seenTwiceSameDay() {
        String segment = segment();
        String code = livePopup("ONCE_PER_DAY", segment, 900);
        String m = member("GOLD", List.of(segment), "ACTIVE");
        api.ok("POST", "/v1/portal/popups/" + code + "/seen", null, Map.of("memberId", m, "dismissed", false), 204);
        api.ok("POST", "/v1/portal/popups/" + code + "/seen", null, Map.of("memberId", m, "dismissed", true), 204);
        assertThat(api.count("SELECT count(*) FROM popup_view WHERE member_id = ?", m)).isEqualTo(1);
        assertThat(api.count("SELECT count(*) FROM popup_view WHERE member_id = ? AND dismissed_at IS NOT NULL", m)).isEqualTo(1);
    }

    @Test
    @DisplayName("[TB-ENG-POPA-009] seen su una card: 404")
    void seenOnCard() {
        String code = create(card(code("CNT"))).path("code").asString();
        assertThat(api.send("POST", "/v1/portal/popups/" + code + "/seen", null, Map.of("memberId", "MBR-000001")).status())
                .isEqualTo(404);
    }

    @Test
    @DisplayName("[TB-ENG-POPA-010] seen senza memberId: 400")
    void seenWithoutMember() {
        String code = livePopup("ONCE", segment(), 900);
        assertThat(api.send("POST", "/v1/portal/popups/" + code + "/seen", null, Map.of()).status()).isEqualTo(400);
    }

    @Test
    @DisplayName("[TB-ENG-POPA-011] seen su un id inesistente: 404")
    void seenUnknown() {
        assertThat(api.send("POST", "/v1/portal/popups/POP-NON-ESISTE/seen", null, Map.of("memberId", "MBR-000001")).status())
                .isEqualTo(404);
    }

    @Test
    @DisplayName("[TB-ENG-POPA-012] due pop-up idonei (900 e 800): next restituisce quello a 900")
    void nextByPriority() {
        String segment = segment();
        livePopup("ALWAYS", segment, 800);
        String high = livePopup("ALWAYS", segment, 900);
        assertThat(nextPopup(member("GOLD", List.of(segment), "ACTIVE"))).isEqualTo(high);
    }

    // ================================================================= END

    @Test
    @DisplayName("[TB-ENG-END-001] LIVE con endAt passato: ENDED e fatto con attore system")
    void autoEndLive() {
        String code = scheduled(Duration.ofHours(-2), Duration.ofHours(-1), "LIVE");
        contents.endExpired(Instant.now());
        assertThat(content(code).path("status").asString()).isEqualTo("ENDED");
        JsonNode last = statusFacts(code).getLast();
        assertThat(last.path("data").path("newStatus").asString()).isEqualTo("ENDED");
        assertThat(last.path("lhactor").asString()).isEqualTo("system");
    }

    @Test
    @DisplayName("[TB-ENG-END-002] PAUSED con endAt passato: ENDED")
    void autoEndPaused() {
        String code = scheduled(Duration.ofHours(-2), Duration.ofHours(-1), "PAUSED");
        contents.endExpired(Instant.now());
        assertThat(content(code).path("status").asString()).isEqualTo("ENDED");
    }

    @Test
    @DisplayName("[TB-ENG-END-003] DRAFT con endAt passato: resta DRAFT")
    void autoEndDraft() {
        String code = scheduled(Duration.ofHours(-2), Duration.ofHours(-1), "DRAFT");
        contents.endExpired(Instant.now());
        assertThat(content(code).path("status").asString()).isEqualTo("DRAFT");
    }

    @Test
    @DisplayName("[TB-ENG-END-004] LIVE con endAt futuro: resta LIVE")
    void autoEndFuture() {
        String code = scheduled(Duration.ofHours(-2), Duration.ofDays(1), "LIVE");
        contents.endExpired(Instant.now());
        assertThat(content(code).path("status").asString()).isEqualTo("LIVE");
        transition(code, "END");
    }

    @Test
    @DisplayName("[TB-ENG-END-005] LIVE con endAt uguale all'istante del job: ENDED")
    void autoEndAtInstant() {
        // TESTBOOK: ambiguo, vedi TB-ENG-END-005 ("end_at passato" all'istante esatto).
        Instant end = Instant.now().plus(Duration.ofMinutes(30)).truncatedTo(ChronoUnit.SECONDS);
        Map<String, Object> body = card(code("CNT"));
        body.put("placement", "CATALOG_TOP");
        body.put("endAt", end.toString());
        String code = create(body).path("code").asString();
        transition(code, "PUBLISH");
        contents.endExpired(end);
        assertThat(content(code).path("status").asString()).isEqualTo("ENDED");
    }

    @Test
    @DisplayName("[TB-ENG-END-006] LIVE senza endAt: resta LIVE")
    void autoEndNoEnd() {
        Map<String, Object> body = card(code("CNT"));
        body.put("placement", "CATALOG_TOP");
        String code = create(body).path("code").asString();
        transition(code, "PUBLISH");
        contents.endExpired(Instant.now().plus(Duration.ofDays(3650)));
        assertThat(content(code).path("status").asString()).isEqualTo("LIVE");
        transition(code, "END");
    }

    // ================================================================= ROL

    @ParameterizedTest(name = "[{0}] {1}", quoteTextArguments = false)
    @CsvFileSource(resources = "/testbook/engagement/rol.csv", numLinesToSkip = 1, delimiter = '\t', quoteCharacter = '~',
            maxCharsPerColumn = 8192)
    void roles(ArgumentsAccessor row) throws Exception {
        // TESTBOOK: ambiguo, vedi le righe ROL con LEGAL, CARE e intestazione non valida (content.write senza ● in
        // docs/08 §2: il rifiuto lato servizio non è prescritto).
        String[] c = TestbookRows.columns(row);
        String actor = "<none>".equals(c[3]) ? null : c[3];
        Object body = switch (c[2]) {
            case "CONTENT" -> card(code("CNT"));
            case "TEMPLATE" -> Map.of("code", code("MSG"), "name", "Ruoli", "channel", "INAPP", "category", "PROGRAM",
                    "titleTpl", "Titolo", "bodyTpl", "Testo");
            case "RULE" -> Map.of("code", code("NR"), "factType", "coupon.used", "templateCode", "MSG-WELCOME");
            default -> theme("#1FB98F", "#0E1B2C", "#F3F7F9");
        };
        String path = switch (c[2]) {
            case "CONTENT" -> "/v1/contents";
            case "TEMPLATE" -> "/v1/message-templates";
            case "RULE" -> "/v1/notification-rules";
            default -> "/v1/theme";
        };
        Resp r = api.send("THEME".equals(c[2]) ? "PUT" : "POST", path, actor, body);
        assertThat(r.status()).as(r.body().toString()).isEqualTo(Integer.parseInt(c[4]));
        if (r.status() == 403) {
            assertThat(r.code()).isEqualTo("FORBIDDEN_ROLE");
        }
    }

    @Test
    @DisplayName("[TB-ENG-ROL-029] letture del portale senza intestazione: 200")
    void portalWithoutHeader() {
        assertThat(api.get("/v1/portal/theme").status()).isEqualTo(200);
        assertThat(api.get("/v1/portal/content?memberId=MBR-000001&placement=HOME_GRID").status()).isEqualTo(200);
    }

    @Test
    @DisplayName("[TB-ENG-ROL-030] transizione con ANALYST: 403")
    void transitionAnalyst() {
        String code = create(card(code("CNT"))).path("code").asString();
        Resp r = api.send("POST", "/v1/contents/" + code + "/transitions", "ANALYST:sara.analyst", Map.of("action", "PUBLISH"));
        assertThat(r.status()).isEqualTo(403);
        assertThat(r.code()).isEqualTo("FORBIDDEN_ROLE");
        assertThat(content(code).path("status").asString()).isEqualTo("DRAFT");
    }

    @Test
    @DisplayName("[TB-ENG-ROL-031] duplicazione con ANALYST: 403")
    void duplicateAnalyst() {
        String code = create(card(code("CNT"))).path("code").asString();
        Resp r = api.send("POST", "/v1/contents/" + code + "/duplicate", "ANALYST:sara.analyst", null);
        assertThat(r.status()).isEqualTo(403);
        assertThat(r.code()).isEqualTo("FORBIDDEN_ROLE");
    }

    // ================================================================= THA

    @ParameterizedTest(name = "[{0}] {1}", quoteTextArguments = false)
    @CsvFileSource(resources = "/testbook/engagement/tha.csv", numLinesToSkip = 1, delimiter = '\t', quoteCharacter = '~',
            maxCharsPerColumn = 8192)
    void themeContrast(ArgumentsAccessor row) throws Exception {
        // TESTBOOK: ambiguo, vedi TB-ENG-THA-012 (#FFF a 3 cifre).
        String[] c = TestbookRows.columns(row);
        JsonNode before = api.get("/v1/theme").body().path("colors");
        Resp r = api.send("PUT", "/v1/theme", MKT, theme(c[2], c[3], c[4]));
        int http = Integer.parseInt(c[5]);
        assertThat(r.status()).as(r.body().toString()).isEqualTo(http);
        if (http == 200) {
            assertThat(api.get("/v1/portal/theme").body().path("colors").path("primary").asString()).isEqualToIgnoringCase(c[2]);
            return;
        }
        if (c[6].startsWith("!")) {
            assertThat(r.code()).isNotEqualTo(c[6].substring(1));
        } else {
            assertThat(r.code()).isEqualTo(c[6]);
        }
        assertThat(r.fields()).containsExactlyInAnyOrder(c[7].split(" "));
        assertThat(api.get("/v1/theme").body().path("colors")).isEqualTo(before);
    }

    @Test
    @DisplayName("[TB-ENG-THA-013] secondary e coin a contrasto 1:1 col testo: 200")
    void secondaryCoinNotChecked() {
        Map<String, Object> t = theme("#1FB98F", "#0E1B2C", "#F3F7F9");
        colors(t).put("secondary", "#0E1B2C");
        colors(t).put("coin", "#0E1B2C");
        assertThat(api.send("PUT", "/v1/theme", MKT, t).status()).isEqualTo(200);
    }

    @Test
    @DisplayName("[TB-ENG-THA-014] colore coin assente: 422 colors.coin")
    void missingColor() {
        Map<String, Object> t = theme("#1FB98F", "#0E1B2C", "#F3F7F9");
        colors(t).remove("coin");
        Resp r = api.send("PUT", "/v1/theme", MKT, t);
        assertThat(r.status()).isEqualTo(422);
        assertThat(r.fields()).contains("colors.coin");
    }

    @Test
    @DisplayName("[TB-ENG-THA-015] PUT con nuovo primary: il portale lo restituisce subito")
    void themeAppliedAtRuntime() {
        api.ok("PUT", "/v1/theme", MKT, theme("#2BC4A0", "#0E1B2C", "#F3F7F9"), 200);
        assertThat(api.get("/v1/portal/theme").body().path("colors").path("primary").asString()).isEqualTo("#2BC4A0");
    }

    @Test
    @DisplayName("[TB-ENG-THA-016] /v1/portal/theme con Cache-Control max-age=60")
    void portalThemeCache() {
        assertThat(api.get("/v1/portal/theme").headers().getCacheControl()).contains("max-age=60");
    }

    @Test
    @DisplayName("[TB-ENG-THA-017] PUT con versione superata: 409 VERSION_CONFLICT")
    void themeVersionConflict() {
        long version = api.get("/v1/theme").body().path("version").asLong();
        Map<String, Object> t = theme("#1FB98F", "#0E1B2C", "#F3F7F9");
        t.put("version", version);
        api.ok("PUT", "/v1/theme", MKT, t, 200);
        Resp r = api.send("PUT", "/v1/theme", MKT, t);
        assertThat(r.status()).isEqualTo(409);
        assertThat(r.code()).isEqualTo("VERSION_CONFLICT");
    }

    @Test
    @DisplayName("[TB-ENG-THA-018] PUT rifiutato per contrasto: tema invariato")
    void rejectedThemeUnchanged() {
        api.ok("PUT", "/v1/theme", MKT, theme("#1FB98F", "#0E1B2C", "#F3F7F9"), 200);
        JsonNode before = api.get("/v1/portal/theme").body();
        assertThat(api.send("PUT", "/v1/theme", MKT, theme("#2A3A55", "#0E1B2C", "#F3F7F9")).status()).isEqualTo(422);
        JsonNode after = api.get("/v1/portal/theme").body();
        assertThat(after.path("colors")).isEqualTo(before.path("colors"));
        assertThat(after.path("version")).isEqualTo(before.path("version"));
    }

    @Test
    @DisplayName("[TB-ENG-THA-019] PUT senza nomi delle valute: punti / punti status")
    void currencyDefaults() {
        JsonNode saved = api.ok("PUT", "/v1/theme", MKT, theme("#1FB98F", "#0E1B2C", "#F3F7F9"), 200);
        assertThat(saved.path("currencyNames").path("PTS").asString()).isEqualTo("punti");
        assertThat(saved.path("currencyNames").path("STS").asString()).isEqualTo("punti status");
    }

    @Test
    @DisplayName("[TB-ENG-THA-020] PUT senza programName: 422 programName")
    void programNameRequired() {
        // TESTBOOK: ambiguo, vedi TB-ENG-THA-020.
        Map<String, Object> t = theme("#1FB98F", "#0E1B2C", "#F3F7F9");
        t.remove("programName");
        Resp r = api.send("PUT", "/v1/theme", MKT, t);
        assertThat(r.status()).isEqualTo(422);
        assertThat(r.fields()).contains("programName");
    }

    @Test
    @DisplayName("[TB-ENG-THA-021] programName di 41 caratteri: 422 programName")
    void programNameTooLong() {
        // TESTBOOK: ambiguo, vedi TB-ENG-THA-021.
        Map<String, Object> t = theme("#1FB98F", "#0E1B2C", "#F3F7F9");
        t.put("programName", "P".repeat(41));
        Resp r = api.send("PUT", "/v1/theme", MKT, t);
        assertThat(r.status()).isEqualTo(422);
        assertThat(r.code()).isEqualTo("THEME_INVALID");
        assertThat(r.fields()).contains("programName");
    }

    @Test
    @DisplayName("[TB-ENG-THA-022] logo javascript: 422 logoUrl")
    void logoJavascript() {
        // TESTBOOK: ambiguo, vedi TB-ENG-THA-022.
        Map<String, Object> t = theme("#1FB98F", "#0E1B2C", "#F3F7F9");
        t.put("logoUrl", "javascript:alert(1)");
        Resp r = api.send("PUT", "/v1/theme", MKT, t);
        assertThat(r.status()).isEqualTo(422);
        assertThat(r.fields()).contains("logoUrl");
    }

    @Test
    @DisplayName("[TB-ENG-THA-023] logo /demo/logo.svg: 200")
    void logoPath() {
        // TESTBOOK: ambiguo, vedi TB-ENG-THA-023.
        Map<String, Object> t = theme("#1FB98F", "#0E1B2C", "#F3F7F9");
        t.put("logoUrl", "/demo/logo.svg");
        assertThat(api.ok("PUT", "/v1/theme", MKT, t, 200).path("logoUrl").asString()).isEqualTo("/demo/logo.svg");
    }

    // ================================================================= helper

    private String code(String prefix) {
        return prefix + "-TB-" + seq.incrementAndGet();
    }

    private String segment() {
        return "SEG-TB-" + seq.incrementAndGet();
    }

    private static Map<String, Object> card(String code) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", code);
        m.put("kind", "CARD");
        m.put("placement", "HOME_GRID");
        m.put("title", "Testbook");
        return m;
    }

    private static Map<String, Object> popup(String code, String frequency, List<String> segments) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", code);
        m.put("kind", "POPUP");
        m.put("title", "Pop-up testbook");
        m.put("frequency", frequency);
        m.put("audience", Map.of("segments", segments));
        return m;
    }

    /** Pop-up LIVE riservato al segmento (così non tocca le altre righe). */
    private String livePopup(String frequency, String segment, int priority) {
        Map<String, Object> body = popup(code("POP"), frequency, List.of(segment));
        body.put("priority", priority);
        String code = create(body).path("code").asString();
        transition(code, "PUBLISH");
        return code;
    }

    /** Card in CATALOG_TOP con calendario relativo ad adesso, portata allo stato voluto. */
    private String scheduled(Duration start, Duration end, String state) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Map<String, Object> body = card(code("CNT"));
        body.put("placement", "CATALOG_TOP");
        body.put("startAt", now.plus(start).toString());
        body.put("endAt", now.plus(end).toString());
        String code = create(body).path("code").asString();
        moveTo(code, state);
        return code;
    }

    private static Map<String, Object> theme(String primary, String night, String bg) {
        Map<String, Object> colors = new HashMap<>();
        colors.put("primary", primary);
        colors.put("secondary", "#7A5CFA");
        colors.put("coin", "#FFB547");
        colors.put("night", night);
        colors.put("bg", bg);
        Map<String, Object> t = new HashMap<>();
        t.put("programName", "Club Aurora");
        t.put("tagline", "Il programma fedeltà che premia ogni gesto");
        t.put("heroTitle", "Ogni gesto conta");
        t.put("colors", colors);
        return t;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> colors(Map<String, Object> theme) {
        return (Map<String, Object>) theme.get("colors");
    }

    private JsonNode create(Map<String, Object> body) {
        return api.ok("POST", "/v1/contents", MKT, body, 201);
    }

    private void transition(String code, String action) {
        api.ok("POST", "/v1/contents/" + code + "/transitions", MKT, Map.of("action", action), 200);
    }

    private void moveTo(String code, String state) {
        switch (state) {
            case "LIVE" -> transition(code, "PUBLISH");
            case "PAUSED" -> {
                transition(code, "PUBLISH");
                transition(code, "PAUSE");
            }
            case "ENDED" -> {
                transition(code, "PUBLISH");
                transition(code, "END");
            }
            case "ARCHIVED" -> transition(code, "ARCHIVE");
            default -> {
                // DRAFT: appena creato
            }
        }
    }

    private JsonNode content(String code) {
        return api.ok("GET", "/v1/contents/" + code, null, null, 200);
    }

    private JsonNode preview(String memberId, String placement) {
        return api.ok("GET", "/v1/contents/preview?memberId=" + memberId + "&placement=" + placement, null, null, 200);
    }

    private String nextPopup(String memberId) {
        Resp r = api.get("/v1/portal/popups/next?memberId=" + memberId);
        assertThat(r.status()).isEqualTo(200);
        return r.body().path("code").asString();
    }

    private static String reason(JsonNode preview, String code) {
        for (JsonNode e : preview.path("excluded")) {
            if (code.equals(e.path("code").asString())) {
                return e.path("reason").asString();
            }
        }
        return "(non escluso)";
    }

    private static List<String> codes(JsonNode list) {
        List<String> out = new ArrayList<>();
        list.forEach(n -> out.add(n.path("code").asString()));
        return out;
    }

    private List<JsonNode> statusFacts(String code) {
        List<JsonNode> out = new ArrayList<>();
        jdbc.sql("SELECT payload::text AS p FROM outbox WHERE type = ? AND payload->>'subject' = ? ORDER BY created_at")
                .params(STATUS_FACT, "content:" + code)
                .query((rs, n) -> TestbookApi.MAPPER.readTree(rs.getString("p")))
                .list().forEach(out::add);
        return out;
    }

    private List<JsonNode> audits(String subject) {
        return jdbc.sql("SELECT payload::text AS p FROM outbox WHERE type = 'io.loyaltyhub.audit.entry' AND msg_key = ? ORDER BY created_at")
                .param(subject)
                .query((rs, n) -> TestbookApi.MAPPER.readTree(rs.getString("p"))).list();
    }

    /** Membro nuovo nello snapshot locale di engagement (livello, segmenti, stato). */
    private String member(String tier, List<String> segments, String status) {
        String id = "MBR-9" + String.format("%05d", seq.incrementAndGet());
        jdbc.sql("INSERT INTO engagement_member_snapshot (member_id, first_name, status, tier_code, segments, registered_at) "
                        + "VALUES (?, 'Test', ?, ?, string_to_array(?, ','), ?)")
                .params(id, status, tier, String.join(",", segments), Timestamp.from(Instant.now().minus(Duration.ofDays(400))))
                .update();
        return id;
    }

    private static EmbeddedPostgres startPg() {
        try {
            return EmbeddedPostgres.builder().start();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
