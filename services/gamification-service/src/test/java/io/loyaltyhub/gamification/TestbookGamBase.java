package io.loyaltyhub.gamification;

import io.loyaltyhub.common.event.LhEvent;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
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
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Base comune dei test d'integrazione del testbook TB-GAM (docs/testbook/TB-GAM-gioco.md, docs/16 §1bis).
 * Un solo contesto Spring (EmbeddedKafka + Postgres Zonky, profilo {@code demo}) condiviso da tutte le classi
 * {@code TestbookGam*IT}: stesse annotazioni e stessa sorgente di proprietà, quindi la cache dei contesti di Spring lo
 * riusa. L'orologio dell'applicazione è {@link TestbookClock}: ogni caso fissa il proprio "adesso" e lo rilascia alla
 * fine, così i confini di tempo (mezzanotte di Roma, cambio d'ora, fine periodo) non dipendono dall'orologio reale.
 * Ogni caso usa codici e membri propri (mai lo stato mutabile dei seed).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = {GamificationApplication.class, TestbookGamBase.ClockConfig.class})
@EmbeddedKafka(partitions = 1, topics = {"lh.actions.v1", "lh.effects.v1", "lh.facts.v1", "lh.audit.v1", "lh.dlq.v1"})
@ActiveProfiles("demo")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class TestbookGamBase {

    static final EmbeddedPostgres PG = startPg();
    static final TestbookClock CLOCK = new TestbookClock();
    static final ZoneId ROME = ZoneId.of("Europe/Rome");
    /** Adesso di riferimento: martedì 10 marzo 2026, 11:00 a Roma (lontano da mezzanotte e dal cambio d'ora). */
    static final Instant T0 = Instant.parse("2026-03-10T10:00:00Z");

    static final String FACT = "io.loyaltyhub.fact.";
    static final String ACTION = "io.loyaltyhub.action.";
    static final String EFFECT = "io.loyaltyhub.effect.";
    static final String AUDIT = "io.loyaltyhub.audit.entry";

    private static final AtomicInteger SEQ = new AtomicInteger();

    protected final ObjectMapper mapper = new ObjectMapper();

    @Value("${local.server.port}")
    protected int port;

    @Autowired
    protected JdbcClient jdbc;

    @TestConfiguration(proxyBeanMethods = false)
    static class ClockConfig {
        @Bean
        @Primary
        Clock testbookClock() {
            return CLOCK;
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        String base = PG.getJdbcUrl("postgres", "postgres");
        registry.add("spring.datasource.url", () -> base + "&currentSchema=gamification");
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "");
        registry.add("spring.kafka.bootstrap-servers", () -> System.getProperty("spring.embedded.kafka.brokers"));
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "10");
    }

    @AfterEach
    void releaseClock() {
        CLOCK.reset();
    }

    // ---------- HTTP ----------

    /** Risposta HTTP: stato e corpo JSON (vuoto se assente). */
    record Resp(int status, JsonNode body, String text) {
        String code() {
            return body.path("code").asString("");
        }
    }

    /** Attore dall'etichetta di ruolo: {@code NONE} = senza intestazione, {@code INVALID} = intestazione non valida. */
    static String actor(String role) {
        return switch (role) {
            case "NONE", "-" -> null;
            case "INVALID" -> "GUEST:ospite";
            case "ADMIN" -> "ADMIN:ada.admin";
            case "MARKETING" -> "MARKETING:luca.marketing";
            case "LEGAL" -> "LEGAL:elena.legal";
            case "CARE" -> "CARE:carla.care";
            case "ANALYST" -> "ANALYST:andrea.analyst";
            default -> role;
        };
    }

    Resp call(String method, String path, String actor, Object body) {
        var spec = RestClient.create("http://localhost:" + port).method(HttpMethod.valueOf(method)).uri(path);
        if (actor != null) {
            spec = spec.header("X-LH-Actor", actor);
        }
        if (body != null) {
            spec = spec.contentType(MediaType.APPLICATION_JSON).body(body);
        }
        return spec.exchange((req, res) -> {
            String text = new String(res.getBody().readAllBytes(), StandardCharsets.UTF_8);
            JsonNode node;
            try {
                node = text.isBlank() || !text.trim().startsWith("{") && !text.trim().startsWith("[")
                        ? mapper.createObjectNode() : mapper.readTree(text);
            } catch (RuntimeException e) {
                node = mapper.createObjectNode();
            }
            return new Resp(res.getStatusCode().value(), node, text);
        });
    }

    JsonNode ok(String method, String path, String actor, Object body, int expected) {
        Resp r = call(method, path, actor, body);
        assertThat(r.status()).as(method + " " + path + " → " + r.text()).isEqualTo(expected);
        return r.body();
    }

    JsonNode get(String path) {
        return ok("GET", path, actor("ADMIN"), null, 200);
    }

    // ---------- dati di prova ----------

    static int next() {
        return SEQ.incrementAndGet();
    }

    /** Codice concorso univoco derivato dall'ID di riga ({@code TB-GAM-PLY-001} → {@code IW-PLY-001-7}). */
    static String contestCode(String rowId) {
        return "IW-" + rowId.substring("TB-GAM-".length()) + "-" + next();
    }

    /** Membro nuovo con lo stato indicato nello snapshot locale; {@code UNKNOWN} = nessuno snapshot. */
    String member(String status) {
        String id = String.format("MBR-8%05d", next());
        if (!"UNKNOWN".equals(status)) {
            jdbc.sql("INSERT INTO gamification_member_snapshot (member_id, nickname, status) VALUES (?, ?, ?)")
                    .params(id, "Socio " + id.substring(4), status).update();
        }
        return id;
    }

    static Map<String, Object> prize(String code, String type, Long points, String rewardCode, Integer qty) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("code", code);
        p.put("name", "Premio " + code);
        p.put("type", type);
        if (points != null) p.put("points", points);
        if (rewardCode != null) p.put("rewardCode", rewardCode);
        if (qty != null) p.put("quantity", qty);
        return p;
    }

    /** Corpo valido di un concorso: WHEEL, UNIFORM, gratuita giornaliera, un premio da 10 punti in 5 pezzi. */
    static Map<String, Object> contestBody(String code, Instant start, Instant end) {
        Map<String, Object> body = new HashMap<>();
        body.put("code", code);
        body.put("name", "Concorso " + code);
        body.put("description", "Concorso di prova del testbook");
        body.put("rulesText", "Regolamento di prova");
        body.put("mechanic", "WHEEL");
        body.put("startAt", start.toString());
        body.put("endAt", end.toString());
        body.put("freePlayDaily", true);
        body.put("distribution", "UNIFORM");
        body.put("prizes", new ArrayList<>(List.of(prize("PTS-10", "POINTS", 10L, null, 5))));
        return body;
    }

    /** Crea il concorso (MARKETING) e ne restituisce l'id. */
    String createContest(Map<String, Object> body) {
        return ok("POST", "/v1/contests", actor("MARKETING"), body, 201).path("id").asString();
    }

    void generate(String id) {
        ok("POST", "/v1/contests/" + id + "/instants/generate", actor("MARKETING"), Map.of(), 200);
    }

    /** Porta il concorso nello stato indicato senza passare dalle transizioni (preparazione del caso). */
    void setStatus(String id, String status) {
        jdbc.sql("UPDATE contest SET status = ? WHERE id = ?").params(status, id).update();
    }

    String status(String id) {
        return jdbc.sql("SELECT status FROM contest WHERE id = ?").param(id).query(String.class).single();
    }

    /** Concorso in stato {@code status} con istanti generati e tutti spostati a {@code instantsAt} (nessuna vincita casuale). */
    String contestIn(String code, String status, Instant start, Instant end, Instant instantsAt, Map<String, Object> overrides) {
        Map<String, Object> body = contestBody(code, start, end);
        if (overrides != null) body.putAll(overrides);
        String id = createContest(body);
        generate(id);
        if (instantsAt != null) {
            jdbc.sql("UPDATE winning_instant SET instant_at = ? WHERE contest_id = ?").params(ts(instantsAt), id).update();
        }
        if (!"DRAFT".equals(status)) setStatus(id, status);
        return id;
    }

    /** Porta un istante aperto del premio (o del concorso) all'istante {@code at}: restituisce il suo id. */
    String matureOne(String contestId, String prizeCode, Instant at) {
        String instantId = jdbc.sql("""
                        SELECT w.id FROM winning_instant w JOIN prize p ON p.id = w.prize_id
                        WHERE w.contest_id = ? AND w.status = 'OPEN' AND (CAST(? AS text) IS NULL OR p.code = ?)
                        ORDER BY w.instant_at DESC, w.id LIMIT 1
                        """)
                .params(contestId, prizeCode, prizeCode).query(String.class).single();
        jdbc.sql("UPDATE winning_instant SET instant_at = ? WHERE id = ?").params(ts(at), instantId).update();
        return instantId;
    }

    String instantStatus(String instantId) {
        return jdbc.sql("SELECT status FROM winning_instant WHERE id = ?").param(instantId).query(String.class).single();
    }

    String prizeId(String contestId, String prizeCode) {
        return jdbc.sql("SELECT id FROM prize WHERE contest_id = ? AND code = ?").params(contestId, prizeCode)
                .query(String.class).single();
    }

    int remaining(String contestId, String prizeCode) {
        return jdbc.sql("SELECT quantity_remaining FROM prize WHERE contest_id = ? AND code = ?").params(contestId, prizeCode)
                .query(Integer.class).single();
    }

    long count(String sql, Object... params) {
        return jdbc.sql(sql).params(params).query(Long.class).single();
    }

    void grant(String memberId, String contestId, int count) {
        if (count <= 0) return;
        jdbc.sql("""
                        INSERT INTO play_grant (id, member_id, contest_id, count, effect_id, campaign_code, granted_at)
                        VALUES (?, ?, ?, ?, ?, 'CMP-TB', ?)
                        """)
                .params("GR-" + UUID.randomUUID(), memberId, contestId, count, "EFF-TB-" + UUID.randomUUID(), ts(CLOCK.instant()))
                .update();
    }

    /** Giocata già avvenuta (preparazione): data di Roma calcolata dall'istante. */
    void priorPlay(String contestId, String memberId, String kind, String outcome, String prizeId, Instant at) {
        jdbc.sql("""
                        INSERT INTO play (id, contest_id, member_id, kind, outcome, prize_id, played_at, play_date, delivery_status)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'NA')
                        """)
                .params("PL-" + UUID.randomUUID(), contestId, memberId, kind, outcome, prizeId, ts(at),
                        LocalDate.ofInstant(at, ROME))
                .update();
    }

    Resp play(String code, String memberId) {
        return call("POST", "/v1/portal/contests/" + code + "/play", null, memberId == null ? Map.of() : Map.of("memberId", memberId));
    }

    long plays(String contestId, String memberId) {
        return count("SELECT count(*) FROM play WHERE contest_id = ? AND member_id = ?", contestId, memberId);
    }

    /** Concorso del portale per il membro, o {@code null} se non è in elenco. */
    JsonNode portalContest(String memberId, String code) {
        for (JsonNode c : ok("GET", "/v1/portal/contests?memberId=" + memberId, null, null, 200)) {
            if (code.equals(c.path("code").asString())) return c;
        }
        return null;
    }

    // ---------- eventi (outbox: i fatti scritti nella stessa transazione) ----------

    List<JsonNode> outbox(String type, String subject) {
        List<JsonNode> out = new ArrayList<>();
        for (String p : jdbc.sql("SELECT payload::text FROM outbox WHERE type = ? AND payload->>'subject' = ? ORDER BY created_at")
                .params(type, subject).query(String.class).list()) {
            out.add(mapper.readTree(p));
        }
        return out;
    }

    List<JsonNode> facts(String shortType, String memberId) {
        return outbox(FACT + shortType, "member:" + memberId);
    }

    List<JsonNode> audits(String subject) {
        return outbox(AUDIT, subject);
    }

    /** Evento in ingresso (azione, fatto o effetto) come lo consegnerebbe Kafka. */
    LhEvent<JsonNode> event(String type, String memberId, Instant time, JsonNode data) {
        String id = "EVT-TB-" + UUID.randomUUID();
        return new LhEvent<>("1.0", id, "urn:loyaltyhub:service:testbook", type, memberId == null ? null : "member:" + memberId,
                time, "application/json", null, "aurora", id, null, 1, "system", data);
    }

    JsonNode json(String text) {
        return mapper.readTree(text);
    }

    static Timestamp ts(Instant i) {
        return Timestamp.from(i);
    }

    static Instant plus(Instant i, long millis) {
        return i.plus(Duration.ofMillis(millis));
    }

    static String opt(String s) {
        return s == null || "-".equals(s) ? null : s;
    }

    private static EmbeddedPostgres startPg() {
        try {
            return EmbeddedPostgres.builder().start();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** Orologio dell'applicazione nei test: fisso quando un caso lo imposta, di sistema altrimenti. */
    static final class TestbookClock extends Clock {
        private volatile Instant fixed;

        void set(Instant at) {
            fixed = at;
        }

        void reset() {
            fixed = null;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            TestbookClock self = this;
            return new Clock() {
                @Override
                public ZoneId getZone() {
                    return zone;
                }

                @Override
                public Clock withZone(ZoneId z) {
                    return self.withZone(z);
                }

                @Override
                public Instant instant() {
                    return self.instant();
                }
            };
        }

        @Override
        public Instant instant() {
            Instant f = fixed;
            return f != null ? f : Instant.now();
        }
    }
}
