package io.loyaltyhub.ingestion;

import io.loyaltyhub.testsupport.SourceActors;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
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

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Codici dei tipi azione sicuri (F-ING-06, BO-09, docs/servizi/ingestion-service.md §3 e §5, Q-439, Q-440).
 * <ul>
 *   <li>Un codice custom non può iniziare con i segmenti riservati {@code io} o {@code loyaltyhub}: altrimenti il
 *       {@code type} pubblicato uscirebbe dalla famiglia {@code io.loyaltyhub.action.} (topic sbagliato o {@code 500}).</li>
 *   <li>Un {@code type} in ingresso fuori da {@code io.loyaltyhub.action.} si rifiuta con un motivo esplicito e non si
 *       pubblica, anche se nel registro esiste una riga con quel codice (righe create prima della correzione).</li>
 *   <li>La creazione è atomica: di due creazioni concorrenti dello stesso codice una vince ({@code 201}) e l'altra
 *       riceve {@code 409 EVENT_TYPE_EXISTS}, senza sovrascrivere e senza una seconda voce di audit.</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EmbeddedKafka(partitions = 1, topics = {"lh.actions.v1", "lh.effects.v1", "lh.dlq.v1", "lh.audit.v1"})
@ActiveProfiles("demo")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EventTypeCodeSafetyIT {

    private static final EmbeddedPostgres PG = startPg();
    private static final String MARKETING = "MARKETING:luca.marketing";
    private static final String ADMIN = "ADMIN:marta.admin";
    private static final String SIMULATOR_SOURCE = "urn:loyaltyhub:source:simulator";

    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private DataSource dataSource;

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

    // ---------- prefissi riservati ----------

    @Test
    void customCodesWithAReservedFirstSegmentAreRejected() {
        for (String code : List.of("io.loyaltyhub.effect.x", "io.loyaltyhub.action.x", "io.loyaltyhub.foo",
                "io.custom", "loyaltyhub.action.x", "loyaltyhub.points")) {
            JsonNode problem = send("POST", "/v1/event-types", MARKETING, customBody(code, "Riservato"), 422);
            assertThat(problem.path("code").asString()).as(code).isEqualTo("EVENT_TYPE_INVALID");
            JsonNode codeError = fieldError(problem, "code");
            assertThat(codeError).as("errore sul campo code per " + code).isNotNull();
            assertThat(codeError.path("message").asString()).as(code).contains("io", "loyaltyhub");
            send("GET", "/v1/event-types/" + code, null, null, 404);
            assertThat(auditCount(code, "CREATE")).as("nessun audit per " + code).isZero();
        }
        // Un segmento che contiene soltanto le stesse lettere non è riservato.
        send("POST", "/v1/event-types", MARKETING, customBody("iot.sensor.read", "Lettura sensore"), 201);
        send("POST", "/v1/event-types", MARKETING, customBody("loyalty.card.linked", "Carta collegata"), 201);
    }

    /**
     * Righe con codice riservato create prima della correzione (inserite qui a mano, come le troverebbe un'istanza
     * esistente): l'azione in ingresso si rifiuta con {@code UNKNOWN_TYPE} e un motivo esplicito, nulla va in outbox.
     */
    @Test
    void inboundTypesOutsideTheActionFamilyAreRejectedAndNeverPublished() {
        insertLegacyType("io.loyaltyhub.effect.legacy");
        insertLegacyType("io.loyaltyhub.nofamily");

        for (String type : List.of("io.loyaltyhub.effect.legacy", "io.loyaltyhub.nofamily",
                "io.loyaltyhub.effect.points.credited")) {
            JsonNode result = send("POST", "/v1/events", null, inbound(type), 202);
            assertThat(result.path("status").asString()).as(type).isEqualTo("REJECTED");
            assertThat(result.path("rejectCode").asString()).as(type).isEqualTo("UNKNOWN_TYPE");
            assertThat(result.path("detail").asString()).as(type)
                    .contains("io.loyaltyhub.action.").contains(type);
            assertThat(outboxRows(type)).as("nessuna pubblicazione per " + type).isZero();
        }

        // Il type completo nella famiglia azioni resta accettato (nessuna regressione sul formato lungo).
        JsonNode full = send("POST", "/v1/events", null, inbound("io.loyaltyhub.action.app.login.daily"), 202);
        assertThat(full.path("status").asString()).isEqualTo("ACCEPTED");
    }

    /**
     * Un custom con codice riservato creato prima della correzione resta utilizzabile solo dentro la famiglia azioni
     * (Q-439): {@code io.old.custom} anche in forma breve, perché diventa {@code io.loyaltyhub.action.io.old.custom};
     * {@code io.loyaltyhub.effect.kept} solo in forma completa {@code io.loyaltyhub.action.<codice>}. In ogni caso
     * l'azione si pubblica su {@code lh.actions.v1} e mai su un altro topic.
     */
    @Test
    void legacyReservedCodesStayInsideTheActionFamily() {
        insertLegacyType("io.old.custom");
        insertLegacyType("io.loyaltyhub.effect.kept");

        for (String type : List.of("io.old.custom", "io.loyaltyhub.action.io.old.custom",
                "io.loyaltyhub.action.io.loyaltyhub.effect.kept")) {
            JsonNode result = send("POST", "/v1/events", null, inbound(type), 202);
            assertThat(result.path("status").asString()).as(type + " → " + result).isEqualTo("ACCEPTED");
        }
        assertThat(outboxRows("io.loyaltyhub.action.io.old.custom", "lh.actions.v1")).isEqualTo(2);
        assertThat(outboxRows("io.loyaltyhub.action.io.loyaltyhub.effect.kept", "lh.actions.v1")).isEqualTo(1);
        assertThat(jdbc.sql("""
                        SELECT count(*) FROM outbox
                        WHERE (type LIKE '%io.old.custom' OR type LIKE '%effect.kept') AND topic <> 'lh.actions.v1'
                        """).query(Long.class).single())
                .as("nessuna pubblicazione fuori da lh.actions.v1").isZero();

        // La forma breve di un codice io.loyaltyhub.* è un type completo fuori famiglia: rifiutata.
        JsonNode shortForm = send("POST", "/v1/events", null, inbound("io.loyaltyhub.effect.kept"), 202);
        assertThat(shortForm.path("rejectCode").asString()).isEqualTo("UNKNOWN_TYPE");
        assertThat(outboxRows("io.loyaltyhub.effect.kept")).isZero();
    }

    /**
     * Riprocessa DLQ di una riga {@code ACCEPTED} salvata prima della correzione con un type fuori famiglia: non si
     * ripubblica l'envelope salvato; la richiesta passa dalla pipeline, che la rifiuta.
     */
    @Test
    void reprocessDoesNotRepublishAnAcceptedRowOutsideTheActionFamily() {
        String type = "io.loyaltyhub.effect.replayed";
        String eventId = "EVT-LEGACY-" + UUID.randomUUID();
        Map<String, Object> event = new java.util.HashMap<>(inbound(type));
        event.put("id", eventId);
        jdbc.sql("""
                        INSERT INTO inbound_event (id, event_id, source_code, type_code, subject, member_id, event_time,
                                                   status, payload, correlation_id, origin)
                        VALUES (?, ?, 'simulator', ?, 'member:MBR-000002', 'MBR-000002', now(), 'ACCEPTED',
                                cast(? AS jsonb), ?, 'EXTERNAL')
                        """)
                .params("LEGACY-" + eventId, eventId, type,
                        mapper.writeValueAsString(Map.of("specversion", "1.0", "id", eventId,
                                "source", SIMULATOR_SOURCE, "type", type, "subject", "member:MBR-000002",
                                "time", Instant.now().toString(), "data", Map.of())), eventId)
                .update();

        JsonNode result = RestClient.create("http://localhost:" + port).post().uri("/v1/events")
                .header("X-LH-Actor", ADMIN).header("X-LH-Reprocess", "DLQ-LEGACY")
                .contentType(MediaType.APPLICATION_JSON).body(event)
                .exchange((req, res) -> {
                    assertThat(res.getStatusCode().value()).isEqualTo(202);
                    return mapper.readTree(new String(res.getBody().readAllBytes()));
                });
        assertThat(result.path("status").asString()).isEqualTo("REJECTED");
        assertThat(result.path("rejectCode").asString()).isEqualTo("UNKNOWN_TYPE");
        assertThat(outboxRows(type)).as("nessuna ripubblicazione").isZero();
    }

    /**
     * Prima della correzione un codice {@code io.loyaltyhub.<altro>} senza famiglia arrivava fino all'outbox, che
     * falliva ({@code 500}) dopo aver già salvato la riga {@code ACCEPTED}: azione registrata ma mai pubblicata. Ora
     * l'ingresso esterno è una sola transazione (Q-441): se la scrittura in outbox fallisce, non resta nessuna riga
     * {@code ACCEPTED}. Il guasto dell'outbox è simulato con un trigger che scatta solo sugli id di prova.
     */
    @Test
    void anOutboxFailureLeavesNoAcceptedRowBehind() {
        String eventId = "EVT-OUTBOX-FAIL-" + UUID.randomUUID();
        jdbc.sql("""
                CREATE OR REPLACE FUNCTION fail_test_outbox() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                  IF NEW.payload ->> 'id' LIKE 'EVT-OUTBOX-FAIL-%' THEN
                    RAISE EXCEPTION 'outbox non disponibile (test)';
                  END IF;
                  RETURN NEW;
                END $$
                """).update();
        jdbc.sql("CREATE TRIGGER fail_test_outbox BEFORE INSERT ON outbox FOR EACH ROW EXECUTE FUNCTION fail_test_outbox()")
                .update();
        try {
            Map<String, Object> event = new java.util.HashMap<>(inbound("app.login.daily"));
            event.put("id", eventId);
            assertThat(status("POST", "/v1/events", null, event)).isEqualTo(500);
        } finally {
            jdbc.sql("DROP TRIGGER fail_test_outbox ON outbox").update();
            jdbc.sql("DROP FUNCTION fail_test_outbox()").update();
        }
        assertThat(jdbc.sql("SELECT count(*) FROM inbound_event WHERE event_id = ? AND status = 'ACCEPTED'")
                .param(eventId).query(Long.class).single())
                .as("nessuna riga ACCEPTED senza pubblicazione").isZero();
    }

    // ---------- seed e tipi di sistema invariati ----------

    /**
     * Tutti i tipi del seed superano le regole del codice: ricreati con {@code POST} ricevono
     * {@code 409 EVENT_TYPE_EXISTS}, non {@code 422}, e restano invariati. Un'azione di ogni tipo di sistema del
     * registro, in forma breve e lunga, non viene rifiutata come fuori famiglia.
     */
    @Test
    void seedAndSystemTypesAreUnaffected() {
        List<String> seedCodes = new ArrayList<>();
        for (JsonNode t : readSeed("event-types.json")) {
            seedCodes.add(t.path("code").asString());
        }
        assertThat(seedCodes).isNotEmpty();
        List<String> system = jdbc.sql("SELECT code FROM event_type WHERE origin = 'SYSTEM' ORDER BY code")
                .query(String.class).list();
        assertThat(system).containsAll(seedCodes);

        for (String code : seedCodes) {
            String nameBefore = send("GET", "/v1/event-types/" + code, null, null, 200).path("name").asString();
            JsonNode problem = send("POST", "/v1/event-types", ADMIN, customBody(code, "Doppione"), 409);
            assertThat(problem.path("code").asString()).as(code).isEqualTo("EVENT_TYPE_EXISTS");
            assertThat(send("GET", "/v1/event-types/" + code, null, null, 200).path("name").asString())
                    .as(code).isEqualTo(nameBefore);
        }
        // Ogni tipo di sistema abilitato, con i dati di esempio del registro, in forma breve e completa: nessuno è
        // scambiato per un type fuori famiglia o sconosciuto (un filtro di famiglia troppo largo fallirebbe qui).
        List<Map<String, Object>> enabledSystem = jdbc.sql("""
                        SELECT code, sample_data::text AS sample FROM event_type
                        WHERE origin = 'SYSTEM' AND enabled ORDER BY code
                        """)
                .query().listOfRows();
        assertThat(enabledSystem).isNotEmpty();
        for (Map<String, Object> row : enabledSystem) {
            String code = (String) row.get("code");
            Object sample = row.get("sample") == null ? Map.of() : mapper.readTree((String) row.get("sample"));
            for (String type : List.of(code, "io.loyaltyhub.action." + code)) {
                Map<String, Object> event = new java.util.HashMap<>(inbound(type));
                event.put("data", sample);
                JsonNode result = send("POST", "/v1/events", null, event, 202);
                assertThat(result.path("rejectCode").asString("")).as(type + " → " + result)
                        .isNotEqualTo("UNKNOWN_TYPE");
            }
        }
        // Un tipo rappresentativo è accettato in entrambe le forme.
        for (String type : List.of("app.login.daily", "io.loyaltyhub.action.app.login.daily")) {
            assertThat(send("POST", "/v1/events", null, inbound(type), 202).path("status").asString())
                    .as(type).isEqualTo("ACCEPTED");
        }
    }

    // ---------- creazione atomica ----------

    /**
     * Interleaving deterministico: un'altra transazione ha già inserito lo stesso codice e non ha ancora confermato.
     * La richiesta attende il lock della riga; alla conferma dell'altra transazione riceve {@code 409} e non
     * sovrascrive il vincitore.
     */
    @Test
    void createWaitingOnAConcurrentInsertGets409AndDoesNotOverwrite() throws Exception {
        String code = "race.locked.one";
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection winner = dataSource.getConnection()) {
            winner.setAutoCommit(false);
            try (PreparedStatement ps = winner.prepareStatement("""
                    INSERT INTO event_type (code, name, origin, category, data_schema, sample_data, enabled)
                    VALUES (?, 'Vincitore', 'CUSTOM', 'ENGAGEMENT', '{"type":"object"}'::jsonb, '{}'::jsonb, true)
                    """)) {
                ps.setString(1, code);
                ps.executeUpdate();
            }
            CompletableFuture<Integer> loser = CompletableFuture.supplyAsync(
                    () -> status("POST", "/v1/event-types", MARKETING, customBody(code, "Perdente")), pool);
            awaitLockWait();
            assertThat(loser).as("la creazione attende la transazione concorrente").isNotDone();
            winner.commit();
            assertThat(loser.get(20, TimeUnit.SECONDS)).isEqualTo(409);
        } finally {
            pool.shutdownNow();
        }
        assertThat(send("GET", "/v1/event-types/" + code, null, null, 200).path("name").asString())
                .isEqualTo("Vincitore");
        assertThat(auditCount(code, "CREATE")).as("il perdente non lascia audit").isZero();
    }

    /**
     * Controllo di fumo: due POST concorrenti dello stesso codice, più volte; sempre un 201 e un 409, la riga è quella
     * del 201. Non è la guardia della regressione di Q-440, perché due richieste servite una dopo l'altra passavano
     * anche con il vecchio codice: la guardia deterministica è
     * {@link #createWaitingOnAConcurrentInsertGets409AndDoesNotOverwrite()}.
     */
    @Test
    void twoConcurrentCreatesYieldOne201AndOne409() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 8; round++) {
                String code = "race.round.r" + round;
                CountDownLatch start = new CountDownLatch(1);
                CompletableFuture<Integer> a = CompletableFuture.supplyAsync(() -> {
                    await(start);
                    return status("POST", "/v1/event-types", MARKETING, customBody(code, "Primo"));
                }, pool);
                CompletableFuture<Integer> b = CompletableFuture.supplyAsync(() -> {
                    await(start);
                    return status("POST", "/v1/event-types", ADMIN, customBody(code, "Secondo"));
                }, pool);
                start.countDown();
                int sa = a.get(30, TimeUnit.SECONDS);
                int sb = b.get(30, TimeUnit.SECONDS);
                assertThat(List.of(sa, sb)).as("round " + round).containsExactlyInAnyOrder(201, 409);
                String winnerName = sa == 201 ? "Primo" : "Secondo";
                assertThat(send("GET", "/v1/event-types/" + code, null, null, 200).path("name").asString())
                        .as("round " + round).isEqualTo(winnerName);
                assertThat(auditCount(code, "CREATE")).as("una sola voce di audit, round " + round).isEqualTo(1);
                assertThat(auditCreateName(code)).as("l'audit è del vincitore, round " + round).isEqualTo(winnerName);
            }
        } finally {
            pool.shutdownNow();
        }

        // PUT invariato: aggiorna il tipo esistente e lascia la sua voce di audit UPDATE.
        Map<String, Object> rename = new java.util.HashMap<>(customBody("race.round.r0", "Rinominato"));
        rename.remove("code");
        assertThat(send("PUT", "/v1/event-types/race.round.r0", MARKETING, rename, 200).path("name").asString())
                .isEqualTo("Rinominato");
        assertThat(auditCount("race.round.r0", "UPDATE")).isEqualTo(1);
    }

    // ---------- supporto ----------

    private JsonNode readSeed(String file) {
        try (var in = EventTypeCodeSafetyIT.class.getResourceAsStream("/seed/" + file)) {
            assertThat(in).as("seed " + file + " nel classpath").isNotNull();
            return mapper.readTree(in);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static Map<String, Object> customBody(String code, String name) {
        return Map.of("code", code, "name", name, "category", "ENGAGEMENT",
                "dataSchema", Map.of("type", "object"), "sampleData", Map.of());
    }

    private Map<String, Object> inbound(String type) {
        return Map.of("specversion", "1.0", "id", "EVT-" + UUID.randomUUID(), "source", SIMULATOR_SOURCE,
                "type", type, "subject", "member:MBR-000002", "time", Instant.now().toString(), "data", Map.of());
    }

    private void insertLegacyType(String code) {
        jdbc.sql("""
                        INSERT INTO event_type (code, name, origin, category, data_schema, sample_data, enabled)
                        VALUES (?, 'Creato prima della correzione', 'CUSTOM', 'ENGAGEMENT', '{"type":"object"}'::jsonb,
                                '{}'::jsonb, true)
                        ON CONFLICT (code) DO NOTHING
                        """)
                .param(code).update();
    }

    private long outboxRows(String type) {
        return jdbc.sql("SELECT count(*) FROM outbox WHERE type = ?").param(type).query(Long.class).single();
    }

    private long outboxRows(String type, String topic) {
        return jdbc.sql("SELECT count(*) FROM outbox WHERE type = ? AND topic = ?").params(type, topic)
                .query(Long.class).single();
    }

    private long auditCount(String code, String action) {
        return jdbc.sql("""
                        SELECT count(*) FROM outbox
                        WHERE topic = 'lh.audit.v1' AND msg_key = ? AND payload -> 'data' ->> 'action' = ?
                        """)
                .params("event_type:" + code, action).query(Long.class).single();
    }

    private String auditCreateName(String code) {
        return jdbc.sql("""
                        SELECT payload -> 'data' -> 'after' ->> 'name' FROM outbox
                        WHERE topic = 'lh.audit.v1' AND msg_key = ? AND payload -> 'data' ->> 'action' = 'CREATE'
                        """)
                .param("event_type:" + code).query(String.class).single();
    }

    /** Attende che un'altra sessione sia in attesa di un lock (la POST bloccata sulla riga non confermata). */
    private void awaitLockWait() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            long waiting = jdbc.sql("""
                            SELECT count(*) FROM pg_stat_activity
                            WHERE wait_event_type = 'Lock' AND query ILIKE '%INSERT INTO event_type%'
                            """)
                    .query(Long.class).single();
            if (waiting > 0) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("la creazione non è mai rimasta in attesa del lock della riga concorrente");
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static JsonNode fieldError(JsonNode problem, String field) {
        for (JsonNode e : problem.path("errors")) {
            if (field.equals(e.path("field").asString())) {
                return e;
            }
        }
        return null;
    }

    private int status(String method, String path, String actor, Object body) {
        actor = SourceActors.actorFor(method, path, actor, body); // Q-492: l'ingresso vuole SOURCE:src-<fonte>
        var spec = RestClient.create("http://localhost:" + port).method(org.springframework.http.HttpMethod.valueOf(method))
                .uri(path);
        if (actor != null) {
            spec = spec.header("X-LH-Actor", actor);
        }
        if (body != null) {
            spec = spec.contentType(MediaType.APPLICATION_JSON).body(body);
        }
        return spec.exchange((req, res) -> res.getStatusCode().value());
    }

    private JsonNode send(String method, String path, String actor, Object body, int expected) {
        actor = SourceActors.actorFor(method, path, actor, body); // Q-492: l'ingresso vuole SOURCE:src-<fonte>
        var spec = RestClient.create("http://localhost:" + port).method(org.springframework.http.HttpMethod.valueOf(method))
                .uri(path);
        if (actor != null) {
            spec = spec.header("X-LH-Actor", actor);
        }
        if (body != null) {
            spec = spec.contentType(MediaType.APPLICATION_JSON).body(body);
        }
        return spec.exchange((req, res) -> {
            String text = new String(res.getBody().readAllBytes());
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
