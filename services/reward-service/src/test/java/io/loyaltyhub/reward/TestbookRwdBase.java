package io.loyaltyhub.reward;

import io.loyaltyhub.testsupport.ListenerGroups;
import io.loyaltyhub.testsupport.TopicReader;
import io.loyaltyhub.reward.infra.MemberSnapshotRepository;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringSerializer;
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
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Base comune dei test d'integrazione del testbook TB-RWD (docs/testbook/TB-RWD-premi.md). Un solo contesto Spring per
 * tutte le classi {@code TestbookRwd*IT} (stessa configurazione ⇒ contesto in cache), Postgres embedded e Kafka
 * embedded come gli altri IT del modulo. L'orologio dell'applicazione è un {@link MutableClock} impostato da ogni caso
 * (niente orologio di parete); ogni caso crea i propri membri, premi e pool con identificativi nuovi.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = {RewardApplication.class, TestbookRwdBase.ClockConfig.class})
@EmbeddedKafka(partitions = 1, topics = {"lh.effects.v1", "lh.facts.v1", "lh.audit.v1", "lh.dlq.v1"})
@ActiveProfiles("demo")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class TestbookRwdBase {

    static final EmbeddedPostgres PG = startPg();
    /** Istante di partenza dei casi: giovedì 24 settembre 2026, 10:00 a Roma. */
    static final Instant T0 = Instant.parse("2026-09-24T08:00:00Z");
    static final MutableClock CLOCK = new MutableClock(new AtomicReference<>(T0), ZoneOffset.UTC);
    static final Map<String, Object> ADDRESS = Map.of("name", "Membro Test", "street", "Via Roma 1", "city", "Torino", "zip", "10100");

    private static final AtomicInteger SEQ = new AtomicInteger();

    /** Record pubblicati dai casi per id dell'evento, per la barriera sugli offset ({@link #awaitHandled}). */
    private final Map<String, List<RecordMetadata>> sent = new ConcurrentHashMap<>();

    final ObjectMapper mapper = new ObjectMapper();

    @Value("${local.server.port}")
    int port;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    MemberSnapshotRepository members;

    @Autowired
    org.springframework.kafka.config.KafkaListenerEndpointRegistry listeners;

    /**
     * I due listener di reward (effetti e fatti) stanno nello stesso gruppo {@code lh-reward}: se un caso pubblica
     * prima che entrambi siano entrati, l'ingresso del secondo ribilancia il gruppo a metà elaborazione e un effetto
     * non ritentabile finisce due volte in DLQ (TB-RWD-EFF-004…007). Si parte solo a gruppo stabile: ogni consumer dei
     * container è membro, il coordinator lo vede {@code STABLE} e ogni container ha applicato la sua assegnazione
     * ({@link ListenerGroups}; {@code ContainerTestUtils.waitForAssignment} si sbloccava al primo consumer assegnato,
     * prima del ribilanciamento dei ritardatari).
     */
    @org.junit.jupiter.api.BeforeAll
    void waitForListenerAssignments() {
        ListenerGroups.awaitStable(listeners);
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        String base = PG.getJdbcUrl("postgres", "postgres");
        registry.add("spring.datasource.url", () -> base + "&currentSchema=reward");
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "");
        registry.add("spring.kafka.bootstrap-servers", () -> System.getProperty("spring.embedded.kafka.brokers"));
        // Il timeout della saga si prova con il job demo e un asOf esplicito: quello schedulato non deve interferire.
        registry.add("loyaltyhub.reward.redemption-timeout.enabled", () -> "false");
        registry.add("loyaltyhub.outbox.relay-interval-ms", () -> "100");
    }

    /** Orologio dell'applicazione sostituito da quello del testbook (il bean di lh-common è {@code ConditionalOnMissingBean}). */
    @TestConfiguration
    static class ClockConfig {
        @Bean
        @Primary
        Clock testbookClock() {
            return CLOCK;
        }
    }

    /** Orologio fermo e spostabile dai test; {@link #withZone} condivide lo stesso istante. */
    static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now;
        private final ZoneId zone;

        MutableClock(AtomicReference<Instant> now, ZoneId zone) {
            this.now = now;
            this.zone = zone;
        }

        void set(Instant instant) {
            now.set(instant);
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId z) {
            return new MutableClock(now, z);
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    }

    // ---------- identificativi e dati ----------

    /** Identificativo nuovo e univoco nell'intera esecuzione (maiuscolo, cifre). */
    static String fresh(String prefix) {
        return prefix + "-" + Long.toString(System.nanoTime() % 1_000_000_000L, 36).toUpperCase() + SEQ.incrementAndGet();
    }

    /** Membro nello snapshot locale (dati di prova, non il ramo sotto verifica). {@code status=null} ⇒ nessuno snapshot. */
    String member(String status, String tier, String... segments) {
        String id = fresh("MBR-TB");
        if (status != null) {
            members.seed(id, status, tier, "Test", "Membro");
            for (String s : segments) {
                members.addSegment(id, s);
            }
        }
        return id;
    }

    /** Crea un premio (ADMIN) con i campi dati sopra un premio DIGITAL/INSTANT in F1 e lo porta allo stato {@code state}. */
    JsonNode reward(String state, Map<String, Object> fields) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", fresh("RWD-TB"));
        body.put("name", "Premio testbook");
        body.put("type", "DIGITAL");
        body.put("band", "F1");
        body.put("category", "TEMPO");
        body.put("fulfilment", "INSTANT");
        body.putAll(fields);
        body.values().removeIf(v -> v == null);
        JsonNode created = call("POST", "/v1/rewards", "ADMIN:testbook", body);
        assertThat(created.path("status").asString()).as("creazione premio: " + created).isEqualTo("DRAFT");
        String id = created.path("id").asString();
        for (String action : pathTo(state)) {
            transition(id, action);
        }
        return rewardById(id);
    }

    static List<String> pathTo(String state) {
        return switch (state) {
            case "DRAFT" -> List.of();
            case "IN_REVIEW" -> List.of("SUBMIT");
            case "APPROVED" -> List.of("SUBMIT", "APPROVE");
            case "LIVE" -> List.of("SUBMIT", "APPROVE", "PUBLISH");
            case "PAUSED" -> List.of("SUBMIT", "APPROVE", "PUBLISH", "PAUSE");
            case "ENDED" -> List.of("SUBMIT", "APPROVE", "PUBLISH", "END");
            case "ARCHIVED" -> List.of("ARCHIVE");
            default -> throw new IllegalArgumentException("stato sconosciuto: " + state);
        };
    }

    void transition(String rewardId, String action) {
        Resp r = send("POST", "/v1/rewards/" + rewardId + "/transitions", "ADMIN:testbook",
                Map.of("action", action, "comment", "testbook"));
        assertThat(r.status()).as(action + " → " + r.body()).isEqualTo(200);
    }

    JsonNode rewardById(String id) {
        return get("/v1/rewards/" + id);
    }

    /** Pool con {@code codes} codici generati; ritorna l'id. */
    String pool(int codes, int validityDays) {
        String code = fresh("POOL-TB");
        String prefix = "T" + Long.toString(System.nanoTime() % 1_000_000L, 36).toUpperCase().replaceAll("[^A-Z0-9]", "");
        prefix = prefix.length() > 10 ? prefix.substring(0, 10) : prefix;
        JsonNode p = call("POST", "/v1/coupon-pools", "ADMIN:testbook",
                Map.of("code", code, "name", "Pool testbook", "prefix", prefix, "validityDays", validityDays));
        String id = p.path("id").asString();
        if (codes > 0) {
            call("POST", "/v1/coupon-pools/" + id + "/generate", "ADMIN:testbook", Map.of("count", codes));
        }
        return id;
    }

    // ---------- richieste premio ----------

    Resp requestRedemption(String memberId, String rewardCode, Object shipping) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("memberId", memberId);
        body.put("rewardCode", rewardCode);
        if (shipping != null) {
            body.put("shipping", shipping);
        }
        return send("POST", "/v1/portal/redemptions", null, body);
    }

    JsonNode redemption(String id) {
        return get("/v1/redemptions/" + id);
    }

    JsonNode awaitStatus(String redemptionId, String status) {
        long deadline = System.currentTimeMillis() + 15_000;
        JsonNode r = null;
        while (System.currentTimeMillis() < deadline) {
            r = redemption(redemptionId);
            if (status.equals(r.path("status").asString())) {
                return r;
            }
            pause(20);
        }
        throw new AssertionError("richiesta " + redemptionId + " non " + status + ": " + r);
    }

    int stock(String rewardId) {
        JsonNode r = rewardById(rewardId);
        return r.path("stockRemaining").isMissingNode() || r.path("stockRemaining").isNull() ? -1 : r.path("stockRemaining").asInt();
    }

    // ---------- Kafka ----------

    /** Pubblica un fatto (es. del wallet) sul membro e ritorna l'id dell'evento. */
    String publishFact(String type, String memberId, String correlationId, Map<String, Object> data) {
        return publishFactWithId("01TB" + System.nanoTime() + SEQ.incrementAndGet(), type, memberId, correlationId, data);
    }

    String publishFactWithId(String id, String type, String memberId, String correlationId, Map<String, Object> data) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("specversion", "1.0");
        event.put("id", id);
        event.put("source", "urn:loyaltyhub:service:wallet");
        event.put("type", type);
        event.put("subject", "member:" + memberId);
        event.put("time", CLOCK.instant().toString());
        event.put("lhcorrelationid", correlationId == null ? id : correlationId);
        event.put("lhcausationid", correlationId == null ? id : correlationId);
        event.put("lhhop", 0);
        event.put("data", data);
        produce("lh.facts.v1", memberId, event);
        return id;
    }

    /** Pubblica un effetto {@code coupon.issue} e ritorna l'id dell'evento. */
    String publishCouponIssue(String eventId, String subject, Map<String, Object> data) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("specversion", "1.0");
        event.put("id", eventId);
        event.put("source", "urn:loyaltyhub:service:campaign");
        event.put("type", "io.loyaltyhub.effect.coupon.issue");
        if (subject != null) {
            event.put("subject", subject);
        }
        event.put("time", CLOCK.instant().toString());
        event.put("lhcorrelationid", eventId);
        event.put("lhcausationid", eventId);
        event.put("lhhop", 0);
        event.put("data", data);
        produce("lh.effects.v1", subject == null ? eventId : subject, event);
        return eventId;
    }

    void produce(String topic, String key, Map<String, Object> event) {
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(Map.of(
                "bootstrap.servers", System.getProperty("spring.embedded.kafka.brokers"),
                "key.serializer", StringSerializer.class, "value.serializer", StringSerializer.class))) {
            RecordMetadata record = producer.send(new ProducerRecord<>(topic, key, mapper.writeValueAsString(event))).get();
            sent.computeIfAbsent(String.valueOf(event.get("id")), id -> new CopyOnWriteArrayList<>()).add(record);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Attende che il consumer di reward abbia elaborato l'evento (riga in {@code processed_event}, stessa transazione). */
    void awaitProcessed(String eventId) {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            long n = jdbc.sql("SELECT count(*) FROM processed_event WHERE consumer = 'lh-reward' AND event_id = ?")
                    .param(eventId).query(Long.class).single();
            if (n > 0) {
                return;
            }
            pause(20);
        }
        throw new AssertionError("evento non elaborato: " + eventId);
    }

    /**
     * Barriera di elaborazione per ogni invio dell'evento {@code eventId} (anche un doppione con lo stesso id, un effetto
     * finito in DLQ): il gruppo {@code lh-reward} ha confermato l'offset oltre ciascuno ({@link ListenerGroups}).
     */
    void awaitHandled(String eventId) {
        List<RecordMetadata> records = sent.get(eventId);
        if (records == null) {
            throw new AssertionError("evento mai pubblicato dal caso: " + eventId);
        }
        ListenerGroups.awaitCommitted(listeners, records);
    }

    record Rec(String topic, JsonNode event, Map<String, String> headers) {
    }

    /**
     * Record di {@code topic} che soddisfano {@code p}, esatti ({@link TopicReader}): prima il servizio deve aver
     * elaborato {@code consumedFirst} ({@code processed_event}; vuoto per chiamate HTTP concluse o dopo
     * {@link #awaitHandled}), poi il topic si legge dall'inizio fino in fondo a outbox svuotato, senza consumer group né
     * finestra di tempo. Un doppione resta visibile, un record assente è davvero assente.
     */
    List<Rec> matching(String topic, Collection<String> consumedFirst, Predicate<Rec> p) {
        List<Rec> out = new ArrayList<>();
        for (ConsumerRecord<String, String> r : new TopicReader(jdbc, mapper, topic).records(consumedFirst, r -> true)) {
            Map<String, String> h = new LinkedHashMap<>();
            for (Header header : r.headers()) {
                h.put(header.key(), header.value() == null ? null : new String(header.value(), StandardCharsets.UTF_8));
            }
            JsonNode e;
            try {
                e = mapper.readTree(r.value());
            } catch (Exception ex) {
                e = mapper.createObjectNode().put("raw", r.value());
            }
            Rec rec = new Rec(r.topic(), e, h);
            if (p.test(rec)) {
                out.add(rec);
            }
        }
        return out;
    }

    /** Il primo record di {@link #matching}; fallisce se non ce n'è. */
    Rec await(String topic, Collection<String> consumedFirst, Predicate<Rec> p) {
        List<Rec> m = matching(topic, consumedFirst, p);
        if (m.isEmpty()) {
            throw new AssertionError("nessun record su " + topic + " che soddisfi la condizione");
        }
        return m.get(0);
    }

    /**
     * Fatti emessi per la richiesta premio (campo {@code data.redemptionId}), letti dall'outbox: la produzione passa
     * solo da lì (docs/06 §5) e la riga è scritta nella stessa transazione del cambio di stato, quindi è visibile
     * appena la chiamata o l'evento sono stati elaborati. In ordine di scrittura.
     */
    List<JsonNode> emitted(String redemptionId) {
        return jdbc.sql("""
                        SELECT payload::text FROM outbox WHERE topic = 'lh.facts.v1' AND payload->'data'->>'redemptionId' = ?
                        ORDER BY created_at, id""")
                .param(redemptionId).query(String.class).list().stream().map(mapper::readTree).toList();
    }

    /** Voci di audit ({@code lh.audit.v1}, via outbox) sull'entità {@code entityType:entityId}, in ordine di scrittura. */
    List<JsonNode> audits(String entityType, String entityId) {
        return jdbc.sql("""
                        SELECT payload::text FROM outbox WHERE topic = 'lh.audit.v1' AND payload->>'subject' = ?
                        ORDER BY created_at, id""")
                .param(entityType + ":" + entityId).query(String.class).list().stream().map(mapper::readTree).toList();
    }

    /** Fatti ({@code lh.facts.v1}, via outbox) con subject {@code member:<id>}. */
    long memberFacts(String memberId) {
        return jdbc.sql("SELECT count(*) FROM outbox WHERE topic = 'lh.facts.v1' AND payload->>'subject' = ?")
                .param("member:" + memberId).query(Long.class).single();
    }

    /** Fatti del tipo dato emessi per la richiesta premio (outbox). */
    List<JsonNode> facts(String type, String redemptionId) {
        return emitted(redemptionId).stream().filter(e -> type.equals(e.path("type").asString())).toList();
    }

    JsonNode awaitFact(String type, String redemptionId) {
        return await("lh.facts.v1", List.of(), r -> type.equals(r.event().path("type").asString())
                && redemptionId.equals(r.event().path("data").path("redemptionId").asString())).event();
    }

    // ---------- HTTP ----------

    record Resp(int status, JsonNode body) {
        String code() {
            return body.path("code").asString();
        }
    }

    Resp send(String method, String path, String actor, Object body) {
        return send(method, path, actor, body, Map.of());
    }

    Resp send(String method, String path, String actor, Object body, Map<String, String> headers) {
        var spec = RestClient.create("http://localhost:" + port).method(HttpMethod.valueOf(method)).uri(path);
        if (actor != null) {
            spec = spec.header("X-LH-Actor", actor);
        }
        for (Map.Entry<String, String> h : headers.entrySet()) {
            spec = spec.header(h.getKey(), h.getValue());
        }
        if (body != null) {
            spec = spec.contentType(MediaType.APPLICATION_JSON).body(body);
        }
        return spec.exchange((req, res) -> {
            String text = new String(res.getBody().readAllBytes(), StandardCharsets.UTF_8);
            JsonNode json = text.isBlank() ? mapper.createObjectNode() : mapper.readTree(text);
            return new Resp(res.getStatusCode().value(), json);
        });
    }

    /** Chiamata che deve riuscire (2xx). */
    JsonNode call(String method, String path, String actor, Object body) {
        Resp r = send(method, path, actor, body);
        assertThat(r.status()).as(method + " " + path + " → " + r.body()).isBetween(200, 299);
        return r.body();
    }

    JsonNode get(String path) {
        return call("GET", path, null, null);
    }

    /** Valore dell'intestazione {@code X-LH-Actor} per il ruolo del CSV: {@code -} = assente, {@code INVALID} = malformata. */
    static String actorFor(String role) {
        return switch (role) {
            case "-" -> null;
            case "INVALID" -> "SUPERUSER:mallory";
            default -> role + ":testbook." + role.toLowerCase();
        };
    }

    /** Voce del catalogo portale per il premio, o {@code null} se esclusa. */
    JsonNode catalogEntry(String memberId, String rewardCode) {
        for (JsonNode band : get("/v1/portal/catalog?memberId=" + memberId).path("bands")) {
            for (JsonNode r : band.path("rewards")) {
                if (rewardCode.equals(r.path("code").asString())) {
                    ObjectNode copy = (ObjectNode) r.deepCopy();
                    copy.put("_band", band.path("code").asString());
                    return copy;
                }
            }
        }
        return null;
    }

    private static final Pattern OFFSET = Pattern.compile("([+-]?\\d+)(ms|s|m|h|d)");

    /** {@code -} = assente; {@code 0} = adesso; offset (+1d, -1ms) rispetto ad adesso; altrimenti istante ISO. */
    static String instant(String token, Instant now) {
        if ("-".equals(token)) {
            return null;
        }
        if ("0".equals(token)) {
            return now.toString();
        }
        if (token.contains("T")) {
            return token;
        }
        return now.plus(duration(token)).toString();
    }

    /** Somma di offset come {@code +10m-1ms}. */
    static Duration duration(String token) {
        Matcher m = OFFSET.matcher(token);
        Duration d = Duration.ZERO;
        int end = 0;
        while (m.find()) {
            long n = Long.parseLong(m.group(1));
            d = d.plus(switch (m.group(2)) {
                case "ms" -> Duration.ofMillis(n);
                case "s" -> Duration.ofSeconds(n);
                case "m" -> Duration.ofMinutes(n);
                case "h" -> Duration.ofHours(n);
                default -> Duration.ofDays(n);
            });
            end = m.end();
        }
        if (end != token.length()) {
            throw new IllegalArgumentException(token);
        }
        return d;
    }

    static List<String> texts(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.asString()));
        return out;
    }

    static List<String> split(String v) {
        return v == null || v.isBlank() || v.equals("-") ? List.of() : Arrays.asList(v.split("\\|"));
    }

    static void pause(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static EmbeddedPostgres startPg() {
        try {
            return EmbeddedPostgres.builder().start();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
