package io.loyaltyhub.engagement;

import io.loyaltyhub.common.event.JsonSchemaValidator;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M6.1 contenuti (docs/servizi/engagement-service.md §3, §5, §7; docs/03 §9; F-CNT-01…04; BO-18, EVT-FACT-61): selezione
 * per posizionamento dai seed, pubblico per livello (Anna no, Davide sì) con il motivo in anteprima, ciclo di vita senza
 * approvazione e fatto {@code content.status.changed} valido per il contratto, validazioni, ruoli, duplicazione, fine
 * automatica, card vincita per premio. EmbeddedKafka + Zonky.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EmbeddedKafka(partitions = 1, topics = {"lh.actions.v1", "lh.effects.v1", "lh.facts.v1", "lh.audit.v1", "lh.dlq.v1"})
@ActiveProfiles("demo")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ContentIT {

    private static final EmbeddedPostgres PG = startPg();
    private static final String MARKETING = "MARKETING:luca.marketing";

    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private JsonSchemaValidator validator;

    @Autowired
    private io.loyaltyhub.engagement.application.ContentService contents;

    @Autowired
    private JdbcClient jdbc;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        String base = PG.getJdbcUrl("postgres", "postgres");
        registry.add("spring.datasource.url", () -> base + "&currentSchema=engagement");
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "");
        registry.add("spring.kafka.bootstrap-servers", () -> System.getProperty("spring.embedded.kafka.brokers"));
    }

    @AfterAll
    void tearDown() throws Exception {
        PG.close();
    }

    @Test
    void seedFillsThePortalPlacements() {
        JsonNode hero = get("/v1/portal/content?memberId=MBR-000002&placement=HOME_HERO");
        assertThat(hero.size()).as("HOME_HERO ne mostra uno").isEqualTo(1);
        List<String> grid = codes(get("/v1/portal/content?memberId=MBR-000002&placement=HOME_GRID"));
        // Segmenti non ancora calcolati (M6.6): le card riservate a SEG-* restano fuori.
        assertThat(grid).containsExactly("CNT-FRIEND", "CNT-SELF-READING");
        assertThat(codes(get("/v1/portal/content?memberId=MBR-000002&placement=CATALOG_TOP"))).containsExactly("BNR-CATALOG");
        assertThat(codes(get("/v1/portal/content?memberId=MBR-000010&placement=CONTEST"))).containsExactly("CNT-CONTEST-RULES");
        assertThat(codes(get("/v1/portal/content?memberId=MBR-000010&placement=WIN&prizeCode=COFFEE"))).containsExactly("WIN-COFFEE");
        // In anteprima (BO-18) le card vincita si vedono tutte, una per premio; nel portale solo quella del premio vinto.
        assertThat(codes(get("/v1/contents/preview?memberId=MBR-000010&placement=WIN").path("shown")))
                .containsExactlyInAnyOrder("WIN-POINTS-50", "WIN-POINTS-100", "WIN-COFFEE", "WIN-POWERBANK");
        JsonNode card = get("/v1/portal/content?memberId=MBR-000002&placement=HOME_GRID").get(0);
        assertThat(card.path("ctaTarget").asString()).isEqualTo("/portal/invite");
        assertThat(card.has("audience")).as("il portale non vede pubblico né stato").isFalse();
        // Gli altri test possono creare contenuti *-IT-*: si contano solo quelli del seed.
        assertThat(codes(get("/v1/contents?kind=POPUP")).stream().filter(c -> !c.contains("-IT-")).count()).isEqualTo(3);
    }

    /** docs/servizi/engagement-service.md §7: pubblico GOLD/PLATINUM → assente per Anna, presente per Davide; preview lo spiega. */
    @Test
    void tierAudienceHidesTheCardFromAnnaAndPreviewExplainsWhy() {
        JsonNode created = send("POST", "/v1/contents", MARKETING, Map.of(
                "code", "CNT-IT-GOLD", "kind", "CARD", "placement", "HOME_GRID", "title", "Solo per GOLD e PLATINUM",
                "linkType", "REWARD", "linkCode", "RWD-WEEKEND", "priority", 95,
                "audience", Map.of("tiers", List.of("gold", "PLATINUM"))), 201);
        assertThat(created.path("status").asString()).isEqualTo("DRAFT");
        assertThat(created.path("audience").path("tiers").toString()).isEqualTo("[\"GOLD\",\"PLATINUM\"]");
        send("POST", "/v1/contents/CNT-IT-GOLD/transitions", MARKETING, Map.of("action", "PUBLISH"), 200);

        assertThat(codes(get("/v1/portal/content?memberId=MBR-000001&placement=HOME_GRID"))).doesNotContain("CNT-IT-GOLD");
        assertThat(codes(get("/v1/portal/content?memberId=MBR-000004&placement=HOME_GRID"))).first().isEqualTo("CNT-IT-GOLD");

        JsonNode preview = get("/v1/contents/preview?memberId=MBR-000001&placement=HOME_GRID");
        assertThat(codes(preview.path("shown"))).doesNotContain("CNT-IT-GOLD");
        JsonNode why = find(preview.path("excluded"), e -> e.path("code").asString().equals("CNT-IT-GOLD"));
        assertThat(why.path("reason").asString()).isEqualTo("NOT_IN_AUDIENCE");
        assertThat(find(preview.path("excluded"), e -> e.path("code").asString().equals("CNT-EBILL")).path("reason").asString())
                .isEqualTo("NOT_IN_AUDIENCE");
    }

    @Test
    void lifecycleEmitsTheStatusFactAndOnlyValidTransitions() throws Exception {
        send("POST", "/v1/contents", MARKETING, Map.of("code", "CNT-IT-LIFE", "kind", "CARD", "placement", "HOME_HERO",
                "title", "Ciclo di vita", "ctaLabel", "Vai", "ctaTarget", "/portal/earn"), 201);
        JsonNode draftPreview = get("/v1/contents/preview?memberId=MBR-000002&placement=HOME_HERO");
        assertThat(find(draftPreview.path("excluded"), e -> e.path("code").asString().equals("CNT-IT-LIFE")).path("reason").asString())
                .isEqualTo("NOT_LIVE");
        assertThat(send("POST", "/v1/contents/CNT-IT-LIFE/transitions", MARKETING, Map.of("action", "PAUSE"), 409)
                .path("code").asString()).isEqualTo("INVALID_TRANSITION");
        JsonNode live = send("POST", "/v1/contents/CNT-IT-LIFE/transitions", MARKETING, Map.of("action", "PUBLISH"), 200);
        assertThat(live.path("status").asString()).isEqualTo("LIVE");
        send("POST", "/v1/contents/CNT-IT-LIFE/transitions", MARKETING, Map.of("action", "PAUSE"), 200);
        send("POST", "/v1/contents/CNT-IT-LIFE/transitions", MARKETING, Map.of("action", "RESUME"), 200);
        send("POST", "/v1/contents/CNT-IT-LIFE/transitions", MARKETING, Map.of("action", "END"), 200);
        JsonNode archived = send("POST", "/v1/contents/CNT-IT-LIFE/transitions", MARKETING, Map.of("action", "ARCHIVE"), 200);
        assertThat(send("PUT", "/v1/contents/CNT-IT-LIFE", MARKETING, Map.of("title", "x", "placement", "HOME_HERO",
                "version", archived.path("version").asLong()), 409).path("code").asString()).isEqualTo("CONTENT_NOT_EDITABLE");

        JsonNode copy = send("POST", "/v1/contents/CNT-IT-LIFE/duplicate", MARKETING, null, 201);
        assertThat(copy.path("code").asString()).isEqualTo("CNT-IT-LIFE-COPIA");
        assertThat(copy.path("status").asString()).isEqualTo("DRAFT");
        assertThat(copy.path("title").asString()).isEqualTo("Ciclo di vita (copia)");

        List<JsonNode> facts = facts("content:CNT-IT-LIFE", "io.loyaltyhub.fact.content.status.changed", 5);
        assertThat(facts).extracting(f -> f.path("data").path("newStatus").asString())
                .containsExactly("LIVE", "PAUSED", "LIVE", "ENDED", "ARCHIVED");
        String envelope = new ClassPathResource("contracts/events/envelope.schema.json").getContentAsString(StandardCharsets.UTF_8);
        String data = new ClassPathResource("contracts/events/fact/content.status.changed.schema.json").getContentAsString(StandardCharsets.UTF_8);
        assertThat(validator.validate("it-envelope", envelope, facts.get(0).toString())).isEmpty();
        assertThat(validator.validate("it-content", data, facts.get(0).path("data").toString())).isEmpty();
        assertThat(facts.get(0).path("lhactor").asString()).isEqualTo(MARKETING);
    }

    @Test
    void updateReplacesTheContentWithOptimisticLock() {
        JsonNode c = send("POST", "/v1/contents", MARKETING, Map.of("code", "CNT-IT-EDIT", "kind", "CARD", "placement", "HOME_GRID",
                "title", "Prima", "body", "testo", "imageUrl", "/demo/contents/a.webp", "linkType", "CAMPAIGN", "linkCode", "CMP-EBILL"), 201);
        JsonNode updated = send("PUT", "/v1/contents/" + c.path("id").asString(), MARKETING, Map.of("title", "Dopo",
                "placement", "HOME_GRID", "version", c.path("version").asLong()), 200);
        assertThat(updated.path("title").asString()).isEqualTo("Dopo");
        assertThat(updated.path("body").isNull() || updated.path("body").isMissingNode()).as("PUT sostituisce: il testo si svuota").isTrue();
        assertThat(updated.path("linkType").asString()).isEqualTo("NONE");
        assertThat(send("PUT", "/v1/contents/CNT-IT-EDIT", MARKETING, Map.of("title", "Tardi", "placement", "HOME_GRID",
                "version", c.path("version").asLong()), 409).path("code").asString()).isEqualTo("VERSION_CONFLICT");
        assertThat(send("PUT", "/v1/contents/CNT-IT-EDIT", MARKETING, Map.of("kind", "POPUP", "title", "x",
                "version", updated.path("version").asLong()), 409).path("code").asString()).isEqualTo("KIND_IMMUTABLE");
    }

    @Test
    void validationAndRoles() {
        Map<String, Object> ok = Map.of("code", "CNT-IT-ROLE", "kind", "CARD", "placement", "HOME_GRID", "title", "Ruoli");
        assertThat(status("POST", "/v1/contents", null, ok)).isEqualTo(403);
        assertThat(status("POST", "/v1/contents", "ANALYST:sara.analyst", ok)).isEqualTo(403);
        assertThat(status("POST", "/v1/contents", "CARE:paolo.care", ok)).isEqualTo(403);
        send("POST", "/v1/contents", "ADMIN:marta.admin", ok, 201);
        assertThat(send("POST", "/v1/contents", MARKETING, ok, 409).path("code").asString()).isEqualTo("CODE_TAKEN");

        assertThat(fields(send("POST", "/v1/contents", MARKETING, Map.of("code", "CNT-IT-BAD", "kind", "CARD", "placement", "WIN",
                "title", "Vincita senza premio"), 422))).contains("linkType");
        assertThat(fields(send("POST", "/v1/contents", MARKETING, Map.of("code", "CNT-IT-BAD", "kind", "CARD", "placement", "HOME_GRID",
                "title", "Link pericoloso", "ctaTarget", "javascript:alert(1)"), 422))).contains("ctaTarget");
        assertThat(fields(send("POST", "/v1/contents", MARKETING, Map.of("code", "CNT-IT-BAD", "kind", "BANNER", "placement", "HOME_GRID",
                "title", "Banner fuori posto"), 422))).contains("placement");
        assertThat(fields(send("POST", "/v1/contents", MARKETING, Map.of("code", "CNT-IT-BAD", "kind", "POPUP", "title", "Pop-up",
                "frequency", "SEMPRE"), 422))).contains("frequency");
        assertThat(fields(send("POST", "/v1/contents", MARKETING, Map.of("code", "bad code", "kind", "CARD", "placement", "HOME_GRID",
                "title", "x"), 422))).contains("code");
        assertThat(status("GET", "/v1/contents/preview?memberId=MBR-000001&placement=ALTROVE", null, null)).isEqualTo(400);
    }

    @Test
    void expiredContentsEndAutomatically() {
        send("POST", "/v1/contents", MARKETING, Map.of("code", "CNT-IT-OLD", "kind", "CARD", "placement", "CONTEST", "title", "Scaduto",
                "startAt", Instant.now().minusSeconds(7200).toString(), "endAt", Instant.now().minusSeconds(60).toString()), 201);
        send("POST", "/v1/contents/CNT-IT-OLD/transitions", MARKETING, Map.of("action", "PUBLISH"), 200);
        assertThat(find(get("/v1/contents/preview?memberId=MBR-000010&placement=CONTEST").path("excluded"),
                e -> e.path("code").asString().equals("CNT-IT-OLD")).path("reason").asString()).isEqualTo("OUT_OF_SCHEDULE");
        assertThat(contents.endExpired(Instant.now())).isGreaterThanOrEqualTo(1);
        assertThat(get("/v1/contents/CNT-IT-OLD").path("status").asString()).isEqualTo("ENDED");
    }

    // ---------- pop-up (M6.2) ----------

    /** docs/10 §7: Anna, iscritta ieri, vede POP-WELCOME; chiuso (ONCE) non ricompare. Davide è iscritto da anni. */
    @Test
    void welcomePopupShowsOnceToNewMembers() {
        JsonNode first = get("/v1/portal/popups/next?memberId=MBR-000001");
        assertThat(first.path("code").asString()).isEqualTo("POP-WELCOME");
        assertThat(first.path("dismissible").asBoolean()).isTrue();
        assertThat(first.path("id").asString()).isNotBlank();
        // Leggere non consuma il pop-up: la vista si registra con seen.
        assertThat(get("/v1/portal/popups/next?memberId=MBR-000001").path("code").asString()).isEqualTo("POP-WELCOME");

        JsonNode davide = get("/v1/contents/preview?memberId=MBR-000004&placement=POPUP");
        assertThat(find(davide.path("excluded"), e -> e.path("code").asString().equals("POP-WELCOME")).path("reason").asString())
                .isEqualTo("NOT_IN_AUDIENCE");
        assertThat(find(davide.path("excluded"), e -> e.path("code").asString().equals("POP-COMEBACK")).path("reason").asString())
                .isEqualTo("NOT_LIVE");

        send("POST", "/v1/portal/popups/" + first.path("id").asString() + "/seen", null,
                Map.of("memberId", "MBR-000001", "dismissed", true), 204);
        assertThat(status("GET", "/v1/portal/popups/next?memberId=MBR-000001", null, null) == 204
                || !get("/v1/portal/popups/next?memberId=MBR-000001").path("code").asString().equals("POP-WELCOME"))
                .as("POP-WELCOME non ricompare").isTrue();
        JsonNode anna = get("/v1/contents/preview?memberId=MBR-000001&placement=POPUP");
        assertThat(find(anna.path("excluded"), e -> e.path("code").asString().equals("POP-WELCOME")).path("reason").asString())
                .isEqualTo("FREQUENCY");
        assertThat(count("SELECT count(*) FROM popup_view WHERE member_id = 'MBR-000001' AND dismissed_at IS NOT NULL")).isEqualTo(1);
    }

    /** docs/servizi/engagement-service.md §7: ONCE_PER_DAY visto oggi → sparisce; il giorno dopo ricompare. */
    @Test
    void dailyPopupComesBackTheNextDay() {
        JsonNode created = send("POST", "/v1/contents", MARKETING, Map.of("code", "POP-IT-DAILY", "kind", "POPUP",
                "title", "Una volta al giorno", "frequency", "ONCE_PER_DAY", "priority", 999, "dismissible", false,
                "audience", Map.of("tiers", List.of("SILVER"))), 201);
        send("POST", "/v1/contents/POP-IT-DAILY/transitions", MARKETING, Map.of("action", "PUBLISH"), 200);
        JsonNode next = get("/v1/portal/popups/next?memberId=MBR-000010");
        assertThat(next.path("code").asString()).isEqualTo("POP-IT-DAILY");
        assertThat(next.path("dismissible").asBoolean()).isFalse();

        send("POST", "/v1/portal/popups/POP-IT-DAILY/seen", null, Map.of("memberId", "MBR-000010", "dismissed", false), 204);
        send("POST", "/v1/portal/popups/POP-IT-DAILY/seen", null, Map.of("memberId", "MBR-000010", "dismissed", true), 204);
        assertThat(count("SELECT count(*) FROM popup_view WHERE member_id = 'MBR-000010'")).as("una riga per giorno").isEqualTo(1);
        assertThat(status("GET", "/v1/portal/popups/next?memberId=MBR-000010", null, null)).isEqualTo(204);

        // Il giorno dopo: la vista registrata è di ieri.
        jdbc.sql("UPDATE popup_view SET view_date = view_date - 1 WHERE content_id = ? AND member_id = 'MBR-000010'")
                .param(created.path("id").asString()).update();
        assertThat(get("/v1/portal/popups/next?memberId=MBR-000010").path("code").asString()).isEqualTo("POP-IT-DAILY");
    }

    @Test
    void popupEndpointsValidateTheirInput() {
        assertThat(status("GET", "/v1/portal/popups/next", null, null)).isEqualTo(400);
        assertThat(status("POST", "/v1/portal/popups/CNT-FRIEND/seen", null, Map.of("memberId", "MBR-000002"))).isEqualTo(404);
        assertThat(status("POST", "/v1/portal/popups/POP-WEEKEND/seen", null, Map.of())).isEqualTo(400);
    }

    // ---------- tema (M6.5) ----------

    /** docs/servizi/engagement-service.md §7: PUT /v1/theme con nuovo primary → GET /v1/portal/theme lo restituisce. */
    @Test
    void themeChangesAtRuntimeWithContrastCheck() {
        JsonNode aurora = RestClient.create("http://localhost:" + port).get().uri("/v1/portal/theme")
                .exchange((req, res) -> {
                    assertThat(res.getHeaders().getCacheControl()).contains("max-age=60");
                    return mapper.readTree(res.getBody());
                });
        assertThat(aurora.path("programName").asString()).isEqualTo("Club Aurora");
        assertThat(aurora.path("colors").path("primary").asString()).isEqualTo("#1FB98F");
        assertThat(aurora.path("heroTitle").asString()).isEqualTo("Ogni gesto conta");
        long version = get("/v1/theme").path("version").asLong();

        Map<String, Object> colors = new HashMap<>(Map.of("primary", "#2BC4A0", "secondary", "#7A5CFA", "coin", "#FFB547",
                "night", "#0E1B2C", "bg", "#F3F7F9"));
        Map<String, Object> body = new HashMap<>(Map.of("programName", "Club Aurora", "colors", colors,
                "heroTitle", "Ogni gesto conta", "version", version));
        assertThat(status("PUT", "/v1/theme", "ANALYST:sara.analyst", body)).isEqualTo(403);
        JsonNode saved = send("PUT", "/v1/theme", MARKETING, body, 200);
        assertThat(saved.path("version").asLong()).isEqualTo(version + 1);
        assertThat(get("/v1/portal/theme").path("colors").path("primary").asString()).isEqualTo("#2BC4A0");
        assertThat(send("PUT", "/v1/theme", MARKETING, body, 409).path("code").asString()).isEqualTo("VERSION_CONFLICT");

        colors.put("primary", "#2A3A55");
        body.put("version", version + 1);
        JsonNode low = send("PUT", "/v1/theme", MARKETING, body, 422);
        assertThat(low.path("code").asString()).isEqualTo("THEME_CONTRAST_TOO_LOW");
        assertThat(fields(low)).containsExactly("colors.primary");
        colors.put("primary", "verde");
        assertThat(send("PUT", "/v1/theme", MARKETING, body, 422).path("code").asString()).isEqualTo("THEME_INVALID");
        assertThat(get("/v1/portal/theme").path("colors").path("primary").asString()).as("invariato dopo i rifiuti")
                .isEqualTo("#2BC4A0");

        colors.put("primary", "#1FB98F");
        send("PUT", "/v1/theme", "ADMIN:marta.admin", body, 200);
        assertThat(count("SELECT count(*) FROM theme")).isEqualTo(1);
    }

    private long count(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }

    // ---------- helper ----------

    private List<JsonNode> facts(String subject, String type, int expected) {
        List<JsonNode> out = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(Map.of(
                "bootstrap.servers", System.getProperty("spring.embedded.kafka.brokers"),
                "group.id", "content-it-" + UUID.randomUUID(), "auto.offset.reset", "earliest",
                "key.deserializer", StringDeserializer.class, "value.deserializer", StringDeserializer.class))) {
            consumer.subscribe(List.of("lh.facts.v1"));
            long deadline = System.currentTimeMillis() + 15_000;
            while (System.currentTimeMillis() < deadline && out.size() < expected) {
                for (ConsumerRecord<String, String> r : consumer.poll(Duration.ofMillis(400))) {
                    JsonNode e = mapper.readTree(r.value());
                    if (subject.equals(e.path("subject").asString()) && type.equals(e.path("type").asString())) {
                        out.add(e);
                    }
                }
            }
        }
        return out;
    }

    private static List<String> fields(JsonNode problem) {
        List<String> out = new ArrayList<>();
        problem.path("errors").forEach(e -> out.add(e.path("field").asString()));
        return out;
    }

    private int status(String method, String path, String actor, Object body) {
        var spec = RestClient.create("http://localhost:" + port).method(HttpMethod.valueOf(method)).uri(path);
        if (actor != null) spec = spec.header("X-LH-Actor", actor);
        if (body != null) spec = spec.contentType(MediaType.APPLICATION_JSON).body(body);
        return spec.exchange((req, res) -> res.getStatusCode().value());
    }

    private static List<String> codes(JsonNode list) {
        List<String> out = new ArrayList<>();
        list.forEach(n -> out.add(n.path("code").asString()));
        return out;
    }

    private static JsonNode find(JsonNode items, Predicate<JsonNode> match) {
        for (JsonNode i : items) {
            if (match.test(i)) {
                return i;
            }
        }
        throw new AssertionError("nessun elemento corrispondente in " + items);
    }

    private JsonNode get(String path) {
        return RestClient.create("http://localhost:" + port).get().uri(path).retrieve().body(JsonNode.class);
    }

    private JsonNode send(String method, String path, String actor, Object body, int expected) {
        var spec = RestClient.create("http://localhost:" + port).method(HttpMethod.valueOf(method)).uri(path);
        if (actor != null) spec = spec.header("X-LH-Actor", actor);
        if (body != null) spec = spec.contentType(MediaType.APPLICATION_JSON).body(body);
        return spec.exchange((req, res) -> {
            String text = new String(res.getBody().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(res.getStatusCode().value()).as(method + " " + path + " → " + text).isEqualTo(expected);
            return text.isBlank() ? mapper.createObjectNode() : mapper.readTree(text);
        });
    }

    private static EmbeddedPostgres startPg() {
        try {
            return EmbeddedPostgres.builder().start();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
