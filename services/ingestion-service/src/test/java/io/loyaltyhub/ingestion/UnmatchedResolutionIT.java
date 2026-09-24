package io.loyaltyhub.ingestion;

import io.loyaltyhub.common.event.LhEvent;
import io.loyaltyhub.common.event.LhEventTypes;
import io.loyaltyhub.common.ids.Ulid;
import io.loyaltyhub.ingestion.domain.Source;
import io.loyaltyhub.ingestion.infra.MemberIndexRepository;
import io.loyaltyhub.ingestion.infra.SourceRepository;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.apache.kafka.clients.consumer.ConsumerConfig;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Eventi non abbinati: abbina e riprova (F-ING-04, F-ING-09, BO-26, M7.4; docs/servizi/ingestion-service.md §3, §5).
 * Ogni test usa id freschi (eventi, membri, fonti): nessuna dipendenza dallo stato demo modificato da altri test.
 * EmbeddedKafka + Postgres in-process (Zonky), come gli altri IT di ingestion.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("demo")
@EmbeddedKafka(partitions = 1, topics = {"lh.facts.v1", "lh.actions.v1", "lh.audit.v1", "lh.dlq.v1"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UnmatchedResolutionIT {

    private static final EmbeddedPostgres PG = startPg();
    private static final AtomicInteger SEQ = new AtomicInteger((int) (System.nanoTime() % 50_000));

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        String base = PG.getJdbcUrl("postgres", "postgres");
        registry.add("spring.datasource.url", () -> base + "&currentSchema=ingestion");
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "");
        registry.add("spring.kafka.bootstrap-servers", () -> System.getProperty("spring.embedded.kafka.brokers"));
    }

    @AfterAll
    void tearDown() throws Exception {
        PG.close();
    }

    @Value("${local.server.port}")
    private int port;

    @Autowired private ObjectMapper mapper;
    @Autowired private MemberIndexRepository members;
    @Autowired private SourceRepository sources;
    @Autowired private JdbcClient jdbc;

    // ---------- abbina (manuale) ----------

    @Test
    void manualMatchPublishesOneActionFlipsStatusAndIsAudited() throws Exception {
        String memberId = freshMember("ACTIVE");
        String eventId = Ulid.next(Clock.systemUTC());
        assertThat(postEvent(purchase(eventId, "external:NOPE-" + eventId, "ecommerce")).path("status").asString())
                .isEqualTo("UNMATCHED");
        String rowId = rowIdOf(eventId);

        try (KafkaConsumer<String, String> actions = consumer("lh.actions.v1");
             KafkaConsumer<String, String> audit = consumer("lh.audit.v1")) {
            // Sola lettura e ruoli senza inbound.handle → 403.
            assertThat(call("POST", "/v1/inbound-events/" + rowId + "/match", "ANALYST:anna", Map.of("memberId", memberId)).status)
                    .isEqualTo(403);
            assertThat(call("POST", "/v1/inbound-events/" + rowId + "/match", "MARKETING:luca", Map.of("memberId", memberId)).status)
                    .isEqualTo(403);

            Response ok = call("POST", "/v1/inbound-events/" + rowId + "/match", "CARE:carla.care", Map.of("memberId", memberId));
            assertThat(ok.status).isEqualTo(200);
            assertThat(ok.body.path("status").asString()).isEqualTo("ACCEPTED");
            assertThat(ok.body.path("memberId").asString()).isEqualTo(memberId);
            assertThat(ok.body.path("correlationId").asString()).isEqualTo(eventId);
            assertThat(ok.body.path("resolution").asString()).isEqualTo("MANUAL_MATCH");
            assertThat(ok.body.path("resolvedBy").asString()).isEqualTo("CARE:carla.care");
            assertThat(absent(ok.body.path("rejectDetail"))).isTrue();
            assertThat(ok.body.path("payload").path("subject").asString()).isEqualTo("member:" + memberId);

            // Riprovare o abbinare di nuovo un ACCEPTED → 409, mai una doppia pubblicazione.
            Response again = call("POST", "/v1/inbound-events/" + rowId + "/match", "CARE:carla.care", Map.of("memberId", memberId));
            assertThat(again.status).isEqualTo(409);
            assertThat(again.body.path("code").asString()).isEqualTo("INBOUND_NOT_UNMATCHED");
            Response retry = call("POST", "/v1/inbound-events/" + rowId + "/retry", "ADMIN:marta", null);
            assertThat(retry.status).isEqualTo(409);
            assertThat(retry.body.path("code").asString()).isEqualTo("INBOUND_NOT_RETRYABLE");

            List<JsonNode> published = collect(actions, r -> r.value().contains(eventId), Duration.ofSeconds(6));
            assertThat(published).as("una sola azione pubblicata").hasSize(1);
            JsonNode action = published.getFirst();
            assertThat(action.path("id").asString()).isEqualTo(eventId);
            assertThat(action.path("subject").asString()).isEqualTo("member:" + memberId);
            assertThat(action.path("lhcorrelationid").asString()).isEqualTo(eventId);
            assertThat(action.path("lhhop").asInt()).isZero();

            ConsumerRecord<String, String> entry = poll(audit, r -> r.key().equals("inbound_event:" + rowId), Duration.ofSeconds(15));
            assertThat(entry).as("voce di audit dell'abbinamento").isNotNull();
            JsonNode e = mapper.readTree(entry.value());
            assertThat(e.path("lhactor").asString()).isEqualTo("CARE:carla.care");
            assertThat(e.path("data").path("action").asString()).isEqualTo("TRANSITION");
            assertThat(e.path("data").path("after").path("status").asString()).isEqualTo("ACCEPTED");
        }
    }

    @Test
    void matchRequiresAnExistingActiveMember() {
        String eventId = Ulid.next(Clock.systemUTC());
        postEvent(purchase(eventId, "external:NOPE-" + eventId, "ecommerce"));
        String rowId = rowIdOf(eventId);

        Response unknown = call("POST", "/v1/inbound-events/" + rowId + "/match", "ADMIN:marta", Map.of("memberId", "MBR-999998"));
        assertThat(unknown.status).isEqualTo(422);
        assertThat(unknown.body.path("code").asString()).isEqualTo("MEMBER_NOT_FOUND");

        Response blocked = call("POST", "/v1/inbound-events/" + rowId + "/match", "ADMIN:marta",
                Map.of("memberId", freshMember("BLOCKED")));
        assertThat(blocked.status).isEqualTo(422);
        assertThat(blocked.body.path("code").asString()).isEqualTo("MEMBER_NOT_ACTIVE");

        Response missing = call("POST", "/v1/inbound-events/" + rowId + "/match", "ADMIN:marta", Map.of());
        assertThat(missing.status).isEqualTo(422);
        assertThat(missing.body.path("code").asString()).isEqualTo("MEMBER_REQUIRED");

        assertThat(status(rowId)).as("la riga resta parcheggiata").isEqualTo("UNMATCHED");
        assertThat(call("POST", "/v1/inbound-events/NOPE/match", "ADMIN:marta", Map.of("memberId", "MBR-000001")).status)
                .isEqualTo(404);
    }

    // ---------- riprova ----------

    @Test
    void retryOfSourceDisabledRejectionPublishesOnceAfterEnablingTheSource() {
        String memberId = freshMember("ACTIVE");
        String sourceCode = "itsrc" + SEQ.incrementAndGet();
        sources.upsert(new Source(sourceCode, "Fonte IT", "HTTP", false, List.of(), null));
        String eventId = Ulid.next(Clock.systemUTC());
        JsonNode first = postEvent(purchase(eventId, "member:" + memberId, sourceCode));
        assertThat(first.path("rejectCode").asString()).isEqualTo("SOURCE_DISABLED");
        String rowId = rowIdOf(eventId);

        try (KafkaConsumer<String, String> actions = consumer("lh.actions.v1")) {
            // Fonte ancora disabilitata: resta respinto (stesso esito), nulla pubblicato.
            Response still = call("POST", "/v1/inbound-events/" + rowId + "/retry", "CARE:carla.care", null);
            assertThat(still.status).isEqualTo(200);
            assertThat(still.body.path("status").asString()).isEqualTo("REJECTED");
            assertThat(still.body.path("rejectCode").asString()).isEqualTo("SOURCE_DISABLED");

            sources.upsert(new Source(sourceCode, "Fonte IT", "HTTP", true, List.of(), null));
            Response ok = call("POST", "/v1/inbound-events/" + rowId + "/retry", "CARE:carla.care", null);
            assertThat(ok.status).isEqualTo(200);
            assertThat(ok.body.path("status").asString()).isEqualTo("ACCEPTED");
            assertThat(ok.body.path("memberId").asString()).isEqualTo(memberId);
            assertThat(absent(ok.body.path("rejectCode"))).isTrue();
            assertThat(ok.body.path("resolution").asString()).isEqualTo("RETRY");

            assertThat(call("POST", "/v1/inbound-events/" + rowId + "/retry", "CARE:carla.care", null).status).isEqualTo(409);
            // Dopo l'accettazione la dedup vale anche per un nuovo invio della fonte.
            assertThat(postEvent(purchase(eventId, "member:" + memberId, sourceCode)).path("status").asString())
                    .isEqualTo("DUPLICATE");

            List<JsonNode> published = collect(actions, r -> r.value().contains(eventId), Duration.ofSeconds(6));
            assertThat(published).as("una sola azione malgrado i tentativi").hasSize(1);
            assertThat(published.getFirst().path("source").asString()).isEqualTo("urn:loyaltyhub:source:" + sourceCode);
        }
    }

    @Test
    void retryOfAnUnmatchedWithoutNewMemberStaysUnmatched() {
        String eventId = Ulid.next(Clock.systemUTC());
        postEvent(purchase(eventId, "external:NOPE-" + eventId, "ecommerce"));
        String rowId = rowIdOf(eventId);
        Response r = call("POST", "/v1/inbound-events/" + rowId + "/retry", "ADMIN:marta", null);
        assertThat(r.status).isEqualTo(200);
        assertThat(r.body.path("status").asString()).isEqualTo("UNMATCHED");
        assertThat(absent(r.body.path("resolution"))).isTrue();
    }

    // ---------- abbinamento automatico ----------

    @Test
    void memberRegisteredAutoMatchesParkedEmailEventsOnceEvenUnderRedelivery() throws Exception {
        int n = SEQ.incrementAndGet();
        String memberId = String.format("MBR-8%05d", n);
        String email = "Socio.Nuovo" + n + "@ClubAurora.example";
        String eventId = Ulid.next(Clock.systemUTC());
        assertThat(postEvent(purchase(eventId, "email:" + email, "ecommerce")).path("status").asString()).isEqualTo("UNMATCHED");
        String rowId = rowIdOf(eventId);

        try (KafkaConsumer<String, String> actions = consumer("lh.actions.v1")) {
            LhEvent<JsonNode> registered = fact(LhEventTypes.Fact.MEMBER_REGISTERED, memberId,
                    Map.of("email", email.toLowerCase(), "externalId", "CRM-IT-" + n, "status", "ACTIVE"));
            publish(registered);
            waitFor(() -> "ACCEPTED".equals(status(rowId)));

            JsonNode detail = call("GET", "/v1/inbound-events/" + rowId, null, null).body;
            assertThat(detail.path("memberId").asString()).isEqualTo(memberId);
            assertThat(detail.path("resolution").asString()).isEqualTo("AUTO_MATCH");
            assertThat(detail.path("resolvedBy").asString()).isEqualTo("system");
            assertThat(detail.path("subject").asString()).as("subject originale conservato").isEqualTo("email:" + email);

            // Riconsegna dello stesso fatto e un secondo member.registered per lo stesso membro: nulla di nuovo.
            publish(registered);
            LhEvent<JsonNode> again = fact(LhEventTypes.Fact.MEMBER_REGISTERED, memberId,
                    Map.of("email", email.toLowerCase(), "externalId", "CRM-IT-" + n, "status", "ACTIVE"));
            publish(again);
            waitFor(() -> processed(again.id()) == 1);

            List<JsonNode> published = collect(actions,
                    r -> r.value().contains(eventId) && r.value().contains("purchase.completed"), Duration.ofSeconds(6));
            assertThat(published).as("una sola azione per l'evento abbinato").hasSize(1);
            assertThat(published.getFirst().path("subject").asString()).isEqualTo("member:" + memberId);
        }
    }

    @Test
    void autoMatchIgnoresMembersThatAreNotActive() throws Exception {
        int n = SEQ.incrementAndGet();
        String memberId = String.format("MBR-8%05d", n);
        String external = "CRM-IT-BLK-" + n;
        String eventId = Ulid.next(Clock.systemUTC());
        postEvent(purchase(eventId, "external:" + external, "ecommerce"));
        String rowId = rowIdOf(eventId);

        LhEvent<JsonNode> registered = fact(LhEventTypes.Fact.MEMBER_REGISTERED, memberId,
                Map.of("externalId", external, "status", "BLOCKED"));
        publish(registered);
        waitFor(() -> processed(registered.id()) == 1);
        assertThat(status(rowId)).as("resta parcheggiato per l'operatore").isEqualTo("UNMATCHED");
    }

    // ---------- conteggi per esito (BO-26: "schede per esito con conteggi") ----------

    @Test
    void countsPerOutcomeFollowTheListFiltersAndIncludeDuplicates() {
        String sourceCode = "itcnt" + SEQ.incrementAndGet();
        sources.upsert(new Source(sourceCode, "Fonte conteggi IT", "HTTP", true, List.of(), null));
        String active = freshMember("ACTIVE");
        String blocked = freshMember("BLOCKED");

        JsonNode empty = counts("?source=" + sourceCode);
        assertThat(empty.propertyNames()).containsExactly("ACCEPTED", "DUPLICATE", "REJECTED", "UNMATCHED");
        assertThat(countsOf(empty)).containsExactly(0L, 0L, 0L, 0L);

        String acceptedId = Ulid.next(Clock.systemUTC());
        assertThat(postEvent(purchase(acceptedId, "member:" + active, sourceCode)).path("status").asString()).isEqualTo("ACCEPTED");
        // Due reinvii dalla fonte: due righe DUPLICATE nel monitor (docs/servizi/ingestion-service.md §5), nulla pubblicato.
        assertThat(postEvent(purchase(acceptedId, "member:" + active, sourceCode)).path("status").asString()).isEqualTo("DUPLICATE");
        assertThat(postEvent(purchase(acceptedId, "member:" + active, sourceCode)).path("status").asString()).isEqualTo("DUPLICATE");
        assertThat(postEvent(purchase(Ulid.next(Clock.systemUTC()), "member:" + blocked, sourceCode)).path("rejectCode").asString())
                .isEqualTo("MEMBER_NOT_ACTIVE");
        String unmatchedId = Ulid.next(Clock.systemUTC());
        assertThat(postEvent(purchase(unmatchedId, "external:NOPE-" + unmatchedId, sourceCode)).path("status").asString())
                .isEqualTo("UNMATCHED");

        assertThat(countsOf(counts("?source=" + sourceCode))).containsExactly(1L, 2L, 1L, 1L);
        // Stessi filtri dell'elenco: membro e tipo restringono; l'esito è la dimensione del conteggio (ignorato).
        assertThat(countsOf(counts("?source=" + sourceCode + "&memberId=" + active))).containsExactly(1L, 2L, 0L, 0L);
        assertThat(countsOf(counts("?source=" + sourceCode + "&type=purchase.completed&status=ACCEPTED")))
                .containsExactly(1L, 2L, 1L, 1L);
        assertThat(countsOf(counts("?source=" + sourceCode + "&type=survey.completed"))).containsExactly(0L, 0L, 0L, 0L);

        // Coerenti con l'elenco della scheda.
        Response duplicates = call("GET", "/v1/inbound-events?source=" + sourceCode + "&status=DUPLICATE", null, null);
        assertThat(duplicates.body.size()).isEqualTo(2);
        assertThat(duplicates.body.get(0).path("rejectDetail").asString()).contains("stessa fonte");
    }

    // ---------- helper ----------

    private JsonNode counts(String query) {
        Response r = call("GET", "/v1/inbound-events/counts" + query, null, null);
        assertThat(r.status).isEqualTo(200);
        return r.body;
    }

    private static List<Long> countsOf(JsonNode counts) {
        return List.of(counts.path("ACCEPTED").asLong(-1), counts.path("DUPLICATE").asLong(-1),
                counts.path("REJECTED").asLong(-1), counts.path("UNMATCHED").asLong(-1));
    }

    private record Response(int status, JsonNode body) {
    }

    /** Campo nullo: omesso dal JSON (NON_NULL) o esplicitamente {@code null}. */
    private static boolean absent(JsonNode n) {
        return n.isMissingNode() || n.isNull();
    }

    private Response call(String method, String path, String actor, Object body) {
        RestClient.RequestBodySpec spec = RestClient.create("http://localhost:" + port)
                .method(org.springframework.http.HttpMethod.valueOf(method)).uri(path);
        if (actor != null) {
            spec.header("X-LH-Actor", actor);
        }
        if (body != null) {
            spec.contentType(MediaType.APPLICATION_JSON).body(body);
        }
        return spec.exchange((req, res) -> {
            byte[] bytes = res.getBody().readAllBytes();
            return new Response(res.getStatusCode().value(), bytes.length == 0 ? mapper.getNodeFactory().nullNode() : mapper.readTree(bytes));
        });
    }

    private JsonNode postEvent(Map<String, Object> event) {
        Response r = call("POST", "/v1/events", null, event);
        assertThat(r.status).isEqualTo(202);
        return r.body;
    }

    private Map<String, Object> purchase(String id, String subject, String source) {
        Map<String, Object> event = new HashMap<>();
        event.put("specversion", "1.0");
        event.put("id", id);
        event.put("source", "urn:loyaltyhub:source:" + source);
        event.put("type", "purchase.completed");
        event.put("subject", subject);
        event.put("time", Instant.now().toString());
        event.put("data", Map.of("orderId", "ORD-" + id, "amount", 42, "currency", "EUR", "channel", "ONLINE"));
        return event;
    }

    private String freshMember(String status) {
        String id = String.format("MBR-7%05d", SEQ.incrementAndGet());
        members.upsert(id, null, null, status);
        return id;
    }

    private String rowIdOf(String eventId) {
        return jdbc.sql("SELECT id FROM inbound_event WHERE event_id = ? ORDER BY received_at DESC LIMIT 1")
                .param(eventId).query(String.class).single();
    }

    private String status(String rowId) {
        return jdbc.sql("SELECT status FROM inbound_event WHERE id = ?").param(rowId).query(String.class).single();
    }

    private long processed(String eventId) {
        return jdbc.sql("SELECT count(*) FROM processed_event WHERE event_id = ?").param(eventId).query(Long.class).single();
    }

    private LhEvent<JsonNode> fact(String type, String memberId, Map<String, Object> data) {
        String id = Ulid.next(Clock.systemUTC());
        return new LhEvent<>(LhEvent.SPEC_VERSION, id, "urn:loyaltyhub:service:member", type, "member:" + memberId,
                Instant.now().truncatedTo(ChronoUnit.MILLIS), LhEvent.DATA_CONTENT_TYPE, null,
                LhEvent.TENANT, "COR-" + id, null, 0, null, mapper.valueToTree(data));
    }

    private void publish(LhEvent<JsonNode> event) throws Exception {
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(Map.of(
                "bootstrap.servers", System.getProperty("spring.embedded.kafka.brokers"),
                "key.serializer", StringSerializer.class, "value.serializer", StringSerializer.class))) {
            producer.send(new ProducerRecord<>("lh.facts.v1", event.memberId(), mapper.writeValueAsString(event))).get();
        }
    }

    private KafkaConsumer<String, String> consumer(String topic) {
        KafkaConsumer<String, String> c = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, System.getProperty("spring.embedded.kafka.brokers"),
                ConsumerConfig.GROUP_ID_CONFIG, "it-unmatched-" + topic + "-" + System.nanoTime(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class));
        c.subscribe(List.of(topic));
        return c;
    }

    private ConsumerRecord<String, String> poll(KafkaConsumer<String, String> consumer,
                                                Predicate<ConsumerRecord<String, String>> match, Duration timeout) {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            for (ConsumerRecord<String, String> r : consumer.poll(Duration.ofMillis(400))) {
                if (match.test(r)) {
                    return r;
                }
            }
        }
        return null;
    }

    /** Tutti i record che soddisfano {@code match} entro {@code window} (per contare le pubblicazioni). */
    private List<JsonNode> collect(KafkaConsumer<String, String> consumer,
                                   Predicate<ConsumerRecord<String, String>> match, Duration window) {
        List<JsonNode> out = new ArrayList<>();
        long deadline = System.currentTimeMillis() + window.toMillis();
        while (System.currentTimeMillis() < deadline) {
            for (ConsumerRecord<String, String> r : consumer.poll(Duration.ofMillis(400))) {
                if (match.test(r)) {
                    out.add(mapper.readTree(r.value()));
                }
            }
        }
        return out;
    }

    private static void waitFor(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(200);
        }
        assertThat(condition.getAsBoolean()).as("condizione attesa entro 15 s").isTrue();
    }

    private static EmbeddedPostgres startPg() {
        try {
            return EmbeddedPostgres.builder().start();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
