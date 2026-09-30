package io.loyaltyhub.ingestion;

import io.loyaltyhub.testsupport.SourceActors;
import io.loyaltyhub.common.audit.AuditPublisher;
import io.loyaltyhub.ingestion.api.InboundEventRequest;
import io.loyaltyhub.ingestion.application.ImportFieldTypes;
import io.loyaltyhub.ingestion.application.ImportWorker;
import io.loyaltyhub.ingestion.application.IngestionService;
import io.loyaltyhub.ingestion.infra.ImportRepository;
import io.loyaltyhub.ingestion.infra.MemberErasureRepository;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;

/**
 * Robustezza dell'ingresso batch e dell'import (M8.7, F2-ING-01/02): righe e elementi «velenosi» (NUL, id oltre il
 * limite dell'indice, errori imprevisti) non fermano il lavoro né producono un {@code 500} dopo scritture parziali;
 * lavoratori in gara non pubblicano due volte; {@code Idempotency-Key} per autore e con controllo del contenuto, anche
 * in parallelo; il soggetto dei non abbinati sparisce con l'anonimizzazione; «Riprova non abbinati» a blocchi; pulizia;
 * limite di frequenza pesato sugli eventi e tetto in byte del batch. Il controllo periodico del lavoratore è spento: i
 * test svegliano i lavoratori da sé.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "loyaltyhub.ingestion.imports.max-bytes=20000",
        "loyaltyhub.ingestion.imports.max-rows=50",
        "loyaltyhub.ingestion.imports.poll-interval-ms=3600000",
        "loyaltyhub.ingestion.imports.poll-initial-delay-ms=3600000",
        "loyaltyhub.ingestion.imports.retry-batch=5",
        "loyaltyhub.ingestion.rate-limit-per-minute=10",
        "loyaltyhub.ingestion.batch.max-bytes=200000"})
@EmbeddedKafka(partitions = 1, topics = {"lh.actions.v1", "lh.dlq.v1", "lh.audit.v1", "lh.facts.v1"})
@ActiveProfiles("demo")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ImportsRobustnessIT extends ImportsItSupport {

    private static final EmbeddedPostgres PG = startEmbeddedPg();
    private static final String NUL = "\\u0000"; // escape JSON: nel file c'è la sequenza, il parser la trasforma in NUL
    private static final String INTERNAL = "Errore interno";

    @MockitoSpyBean
    private IngestionService ingestion;
    @Autowired
    private ImportRepository imports;
    @Autowired
    private ImportFieldTypes fieldTypes;
    @Autowired
    private AuditPublisher audit;
    @Autowired
    private PlatformTransactionManager transactions;
    @Autowired
    private Clock clock;
    @Autowired
    private ImportWorker worker;
    @Autowired
    private MemberErasureRepository erasure;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        String base = PG.getJdbcUrl("postgres", "postgres");
        registry.add("spring.datasource.url", () -> base + "&currentSchema=ingestion");
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "");
        registry.add("spring.kafka.bootstrap-servers", () -> System.getProperty("spring.embedded.kafka.brokers"));
    }

    @AfterAll
    void tearDown() throws IOException {
        PG.close();
    }

    /** Un errore imprevisto e deterministico della pipeline per gli id {@code boom-*} (lo spy si azzera a ogni test). */
    @BeforeEach
    void poisonPipeline() {
        doThrow(new IllegalStateException("guasto simulato")).when(ingestion).ingestTracked(
                argThat((InboundEventRequest r) -> r != null && r.id() != null && r.id().startsWith("boom-")), anyString());
    }

    // ================= B1: righe ed elementi velenosi =================

    @Test
    void poisonRowsBecomeInvalidAndTheJobMovesOn() {
        String now = Instant.now().toString();
        String longId = "x".repeat(3000);
        String ndjson = String.join("\n",
                login("nul-1", "external:X" + NUL + "Y", now, "IOS"),
                login(longId, "member:MBR-000002", now, "IOS"),
                "",
                login("nul-2", "member:MBR-000002", now, "IO" + NUL + "S"),
                login("boom-1", "member:MBR-000002", now, "IOS"),
                login("poison-ok-1", "member:MBR-000002", now, "IOS")) + "\n";
        String id = upload("velenose.ndjson", "application/x-ndjson", ndjson, "app", ADMIN, null, 202)
                .path("id").asString();

        JsonNode job = awaitFinished(id).path("job");
        assertThat(job.path("status").asString()).isEqualTo("DONE");
        assertThat(job.path("attempts").asInt()).as("nessuna ripresa: la riga velenosa non ferma il lavoro").isEqualTo(1);
        assertThat(job.path("counts").path("invalid").asInt()).isEqualTo(4);
        assertThat(job.path("counts").path("accepted").asInt()).isEqualTo(1);

        JsonNode rows = get("/v1/imports/" + id + "/rows").path("items");
        assertThat(rows).hasSize(4);
        assertThat(rows.get(0).path("detail").asString()).isEqualTo("subject contiene il carattere NUL, non ammesso");
        assertThat(rows.get(1).path("detail").asString()).isEqualTo("id troppo lungo (al massimo 256 caratteri)");
        assertThat(rows.get(1).path("eventId").asString()).hasSize(256);
        assertThat(rows.get(2).path("detail").asString()).isEqualTo("data contiene il carattere NUL, non ammesso");
        assertThat(rows.get(2).path("rowNumber").asInt()).isEqualTo(3);
        assertThat(rows.get(2).path("lineNumber").asInt()).as("linea fisica, dopo la riga vuota").isEqualTo(4);
        assertThat(rows.get(3).path("eventId").asString()).isEqualTo("boom-1");
        assertThat(rows.get(3).path("detail").asString()).startsWith(INTERNAL);
        assertThat(inboundCount("boom-1")).as("la transazione della riga velenosa è annullata").isZero();
        assertThat(inboundCount("poison-ok-1")).isEqualTo(1);
    }

    @Test
    void nulInTheEventIdIsRecordedInvalidAndTheNextRowIsProcessed() {
        String now = Instant.now().toString();
        String ndjson = login("id" + NUL + "x", "member:MBR-000002", now, "IOS") + "\n"
                + login("nul-id-ok", "member:MBR-000002", now, "IOS") + "\n";
        String id = upload("id-nul.ndjson", "application/x-ndjson", ndjson, "app", ADMIN, null, 202)
                .path("id").asString();

        JsonNode job = awaitFinished(id).path("job");
        assertThat(job.path("status").asString()).isEqualTo("DONE");
        assertThat(job.path("attempts").asInt()).as("la riga si registra al primo tentativo").isEqualTo(1);
        assertThat(job.path("rowsDone").asInt()).isEqualTo(2);
        assertThat(job.path("counts").path("invalid").asInt()).isEqualTo(1);
        assertThat(job.path("counts").path("accepted").asInt()).isEqualTo(1);

        JsonNode row = get("/v1/imports/" + id + "/rows").path("items").get(0);
        assertThat(row.path("outcome").asString()).isEqualTo("INVALID");
        assertThat(row.path("detail").asString()).isEqualTo("id contiene il carattere NUL, non ammesso");
        assertThat(row.path("eventId").asString()).as("NUL sostituito, mai scritto").isEqualTo("id\uFFFDx");
        assertThat(inboundCount("nul-id-ok")).isEqualTo(1);
    }

    /**
     * Il rifiuto generico del NUL nel corpo (Q-532 causa (3)) non tocca il parsing del file: un file {@code .json}
     * (array di eventi) con un NUL in un elemento dà una riga {@code INVALID} e l'elemento successivo è elaborato, come
     * prescrive «Formati» di {@code docs/servizi/ingestion-service.md} (Q-371); non un {@code 422} sull'intero file.
     */
    @Test
    void nulInAJsonFileElementIsRecordedInvalidAndTheNextElementIsProcessed() {
        String now = Instant.now().toString();
        String json = "[" + login("json-nul" + NUL + "x", "member:MBR-000002", now, "IOS") + ","
                + login("json-nul-ok", "member:MBR-000002", now, "IOS") + "]";
        String id = upload("id-nul.json", "application/json", json, "app", ADMIN, null, 202).path("id").asString();

        JsonNode job = awaitFinished(id).path("job");
        assertThat(job.path("status").asString()).isEqualTo("DONE");
        assertThat(job.path("rowsDone").asInt()).isEqualTo(2);
        assertThat(job.path("counts").path("invalid").asInt()).isEqualTo(1);
        assertThat(job.path("counts").path("accepted").asInt()).isEqualTo(1);

        JsonNode row = get("/v1/imports/" + id + "/rows").path("items").get(0);
        assertThat(row.path("outcome").asString()).isEqualTo("INVALID");
        assertThat(row.path("detail").asString()).isEqualTo("id contiene il carattere NUL, non ammesso");
        assertThat(inboundCount("json-nul-ok")).isEqualTo(1);
    }

    @Test
    void batchNeverReturns500AfterPartialCommits() {
        List<Object> batch = new ArrayList<>();
        batch.add(purchase("batch-ok-a", "member:MBR-000002"));
        Map<String, Object> nulData = purchase("batch-nul", "member:MBR-000002");
        nulData.put("data", Map.of("orderId", "ORD\u0000X", "amount", 1, "currency", "EUR"));
        batch.add(nulData);
        batch.add(purchase("y".repeat(3000), "member:MBR-000002"));
        batch.add(purchase("boom-batch", "member:MBR-000002"));
        batch.add(purchase("batch-ok-b", "member:MBR-000002"));

        JsonNode body = postJson("/v1/events/batch", batch, null, 202);
        List<String> statuses = new ArrayList<>();
        body.path("items").forEach(i -> statuses.add(i.path("status").asString()));
        assertThat(statuses).containsExactly("ACCEPTED", "INVALID", "INVALID", "INVALID", "ACCEPTED");
        assertThat(body.path("items").get(1).path("detail").asString()).contains("NUL");
        assertThat(body.path("items").get(2).path("detail").asString()).contains("id troppo lungo");
        assertThat(body.path("items").get(3).path("detail").asString()).startsWith(INTERNAL);
        assertThat(inboundCount("boom-batch")).isZero();
        assertThat(outboxCount("batch-ok-%")).isEqualTo(2);

        // Anche POST /v1/events rifiuta NUL e id oltre il limite come errore di forma (400), mai 500.
        Map<String, Object> single = purchase("z".repeat(300), "member:MBR-000002");
        assertThat(postJson("/v1/events", single, null, 400).path("detail").asString()).contains("id troppo lungo");
    }

    @Test
    void transactionOrderIdOverTheLimitNamesTheField() {
        Map<String, Object> txn = Map.of("source", "urn:loyaltyhub:source:pos", "orderId", "o".repeat(246),
                "memberRef", "member:MBR-000002", "amount", 10, "currency", "EUR");
        assertThat(postJson("/v1/transactions", txn, null, 400).path("detail").asString())
                .isEqualTo("orderId troppo lungo (al massimo 245 caratteri).");
    }

    // ================= M1: soggetto dei non abbinati e anonimizzazione =================

    @Test
    void unmatchedSubjectLivesOnlyInTheMonitorAndErasureRemovesIt() {
        String ndjson = login("erase-1", "email:cancella.me@example.org", Instant.now().toString(), "IOS") + "\n";
        String id = upload("anonimizza.ndjson", "application/x-ndjson", ndjson, "app", ADMIN, null, 202)
                .path("id").asString();
        assertThat(awaitFinished(id).path("job").path("counts").path("unmatched").asInt()).isEqualTo(1);
        assertThat(get("/v1/imports/" + id + "/rows").path("items").get(0).path("detail").asString())
                .contains("cancella.me@example.org");
        assertThat(jdbc.sql("SELECT count(*) FROM import_row WHERE import_id = ? AND detail IS NOT NULL").param(id)
                .query(Long.class).single()).as("il rapporto non copia il soggetto").isZero();

        jdbc.sql("INSERT INTO member_index (member_id, external_id, email_lower, status) VALUES (?, NULL, ?, 'ACTIVE')")
                .params("MBR-990077", "cancella.me@example.org").update();
        erasure.erase("MBR-990077");

        JsonNode row = get("/v1/imports/" + id + "/rows").path("items").get(0);
        assertThat(row.toString()).doesNotContain("cancella.me");
        String report = client().get().uri("/v1/imports/" + id + "/report.csv").retrieve().body(String.class);
        assertThat(report).doesNotContain("cancella.me");
    }

    // ================= M2: Idempotency-Key =================

    @Test
    void idempotencyKeyIsPerAuthorAndBoundToTheSameFile() {
        String csv = "id,type,subject,time,data\nidk-1,app.login.daily,member:MBR-000002," + Instant.now()
                + ",\"{\"\"platform\"\":\"\"IOS\"\"}\"\n";
        String first = upload("idk.csv", "text/csv", csv, "app", ADMIN, "chiave-riusata", 202).path("id").asString();
        JsonNode reused = upload("altro.csv", "text/csv", csv.replace("idk-1", "idk-2"), "app", ADMIN, "chiave-riusata", 422);
        assertThat(reused.path("code").asString()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
        assertThat(upload("idk.csv", "text/csv", csv, "crm", ADMIN, "chiave-riusata", 422).path("code").asString())
                .as("stessa chiave, fonte diversa").isEqualTo("IDEMPOTENCY_KEY_REUSED");
        String otherAuthor = upload("idk.csv", "text/csv", csv, "app", "CARE:paolo.care", "chiave-riusata", 202)
                .path("id").asString();
        assertThat(otherAuthor).as("la chiave vale per autore").isNotEqualTo(first);
        awaitFinished(first);
        awaitFinished(otherAuthor);
    }

    @Test
    void concurrentUploadsWithTheSameKeyCreateOneJob() throws Exception {
        String csv = "id,type,subject,time,data\nidk-par-1,app.login.daily,member:MBR-000002," + Instant.now()
                + ",\"{\"\"platform\"\":\"\"IOS\"\"}\"\n";
        CountDownLatch start = new CountDownLatch(1);
        Callable<String> call = () -> {
            start.await();
            return upload("par.csv", "text/csv", csv, "app", ADMIN, "chiave-parallela", 202).path("id").asString();
        };
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<String>> results = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                results.add(pool.submit(call));
            }
            start.countDown();
            List<String> ids = new ArrayList<>();
            for (Future<String> f : results) {
                ids.add(f.get());
            }
            assertThat(ids).containsOnly(ids.getFirst());
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.sql("SELECT count(*) FROM import_job WHERE idempotency_key = 'chiave-parallela'")
                .query(Long.class).single()).isEqualTo(1L);
    }

    // ================= M5: lavoratori in gara =================

    @Test
    void twoWorkersRacingOnTheSameJobPublishEachRowOnce() {
        raceAndVerify("race-a", newWorker(0), newWorker(0));
    }

    @Test
    void staleReclaimRacingALiveWorkerPublishesEachRowOnce() {
        // Il lavoratore del servizio considera «fermo» un lavoro solo dopo 2 minuti; l'altro (0 ms) lo riprende di
        // continuo mentre il primo lavora: vince chi conferma per primo la riga, l'altro annulla tutto.
        raceAndVerify("race-b", worker, newWorker(0));
    }

    private void raceAndVerify(String prefix, ImportWorker first, ImportWorker second) {
        int rows = 30;
        StringBuilder ndjson = new StringBuilder();
        String now = Instant.now().toString();
        for (int i = 1; i <= rows; i++) {
            ndjson.append(login(prefix + "-" + i, "member:MBR-000002", now, "IOS"))
                    .append('\n');
        }
        String id = "01JRACE" + prefix.toUpperCase().replace("-", "") + "00000000000000"; // ULID ben formato (26)
        jdbc.sql("""
                        INSERT INTO import_job (id, kind, format, file_name, size_bytes, sha256, default_source, status,
                          rows_total, content, created_by, created_at)
                        VALUES (?, 'EVENTS', 'NDJSON', 'gara.ndjson', ?, 'x', 'app', 'QUEUED', ?, ?, 'ADMIN:test', now())
                        """)
                .params(id, ndjson.length(), rows, ndjson.toString()).update();
        try {
            long deadline = System.currentTimeMillis() + 60_000;
            while (!status(id).equals("DONE") && System.currentTimeMillis() < deadline) {
                first.kick();
                second.kick();
                sleep(5);
            }
        } finally {
            if (first != worker) {
                first.shutdown();
            }
            second.shutdown();
        }
        assertThat(status(id)).isEqualTo("DONE");
        JsonNode job = get("/v1/imports/" + id).path("job");
        assertThat(job.path("rowsDone").asInt()).isEqualTo(rows);
        assertThat(job.path("counts").path("accepted").asInt()).isEqualTo(rows);
        assertThat(job.path("counts").path("duplicate").asInt()).as("il perdente non lascia duplicati").isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM inbound_event WHERE event_id LIKE ?").param(prefix + "-%")
                .query(Long.class).single()).isEqualTo(rows);
        assertThat(outboxCount(prefix + "-%")).as("una sola pubblicazione per riga").isEqualTo(rows);
        assertThat(jdbc.sql("SELECT count(DISTINCT payload->>'id') FROM outbox WHERE payload->>'id' LIKE ?")
                .param(prefix + "-%").query(Long.class).single()).isEqualTo(rows);
    }

    private ImportWorker newWorker(long staleAfterMs) {
        return new ImportWorker(imports, ingestion, fieldTypes, audit, mapper, transactions, clock, staleAfterMs, 100_000, 7);
    }

    @Test
    void kickAfterShutdownNeverThrows() {
        ImportWorker stopped = newWorker(120_000);
        stopped.shutdown();
        assertThatCode(stopped::kick).doesNotThrowAnyException();
    }

    // ================= M5: «Riprova non abbinati» a blocchi, pulizia =================

    @Test
    void retryUnmatchedPaginatesInBlocks() {
        StringBuilder ndjson = new StringBuilder();
        String now = Instant.now().toString();
        for (int i = 1; i <= 12; i++) {
            ndjson.append(login("pag-" + i, "external:CRM-PAG-" + i, now, "IOS")).append('\n');
        }
        String id = upload("pagine.ndjson", "application/x-ndjson", ndjson.toString(), "app", ADMIN, null, 202)
                .path("id").asString();
        assertThat(awaitFinished(id).path("job").path("counts").path("unmatched").asInt()).isEqualTo(12);
        jdbc.sql("INSERT INTO member_index (member_id, external_id, email_lower, status) VALUES (?, ?, NULL, 'ACTIVE')")
                .params("MBR-990088", "CRM-PAG-12").update();

        List<Integer> retried = new ArrayList<>();
        int accepted = 0;
        int after = 0;
        for (int call = 0; call < 10; call++) {
            JsonNode r = post("/v1/imports/" + id + "/retry-unmatched?afterRow=" + after, "CARE:paolo.care", 200);
            retried.add(r.path("retried").asInt());
            accepted += r.path("accepted").asInt();
            assertThat(r.path("failed").asInt()).isZero();
            if (!r.hasNonNull("nextAfterRow")) {
                break;
            }
            after = r.path("nextAfterRow").asInt();
        }
        assertThat(retried).containsExactly(5, 5, 2);
        assertThat(accepted).isEqualTo(1);
        assertThat(get("/v1/imports/" + id).path("openUnmatched").asInt()).isEqualTo(11);
    }

    @Test
    void cleanupDeletesOnlyJobsFinishedBeyondRetention() {
        Instant now = Instant.now();
        insertFinished("01JCQ000000000000000000A01", now.minus(Duration.ofDays(8)));
        insertFinished("01JCQ000000000000000000B01", now.minus(Duration.ofDays(6)));
        jdbc.sql("INSERT INTO import_row (import_id, row_number, outcome) VALUES ('01JCQ000000000000000000A01', 1, 'INVALID')")
                .update();
        worker.cleanup();
        assertThat(get("/v1/imports/01JCQ000000000000000000A01", 404).path("code").asString()).isEqualTo("NOT_FOUND");
        assertThat(jdbc.sql("SELECT count(*) FROM import_row WHERE import_id = '01JCQ000000000000000000A01'")
                .query(Long.class).single()).isZero();
        assertThat(get("/v1/imports/01JCQ000000000000000000B01").path("job").path("status").asString()).isEqualTo("DONE");
    }

    // ================= id di percorso =================

    @Test
    void pathIdsThatAreNotJobIdsAre404WithoutReflection() {
        List<String> hostile = List.of("<script>alert(1)</script>", "a\"b'c", "x\r\nSet-Cookie: lh=1",
                "../../etc/passwd", "..", "01jcq000000000000000000a01", "01JCQ000000000000000000A0", "8ZZZZZZZZZZZZZZZZZZZZZZZZZ");
        for (String raw : hostile) {
            for (String suffix : List.of("", "/rows", "/report.csv", "/retry-unmatched")) {
                String method = suffix.equals("/retry-unmatched") ? "POST" : "GET";
                URI uri = URI.create("http://localhost:" + port + "/v1/imports/"
                        + URLEncoder.encode(raw, StandardCharsets.UTF_8).replace("+", "%20") + suffix);
                client().method(HttpMethod.valueOf(method)).uri(uri).header("X-LH-Actor", ADMIN)
                        .accept(MediaType.TEXT_HTML, MediaType.ALL)
                        .exchange((req, res) -> {
                            String body = new String(res.getBody().readAllBytes(), StandardCharsets.UTF_8);
                            int status = res.getStatusCode().value();
                            MediaType type = res.getHeaders().getContentType();
                            String where = method + " " + uri + " → " + status + " " + type;
                            // 404 dal controller; 400 dal contenitore, che rifiuta prima dell'applicazione una barra
                            // codificata (%2F) nel percorso.
                            assertThat(status).as(where).isIn(400, 404);
                            assertThat(res.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION)).as(where).isNull();
                            assertThat(res.getHeaders().getFirst("Set-Cookie")).as(where).isNull();
                            // Il valore decodificato non torna mai: al più l'instance RFC 9457 riporta il percorso
                            // come ricevuto, ancora codificato.
                            assertThat(body).as(where).doesNotContain("<script>", "\r", "\n", "Set-Cookie:", "etc/passwd",
                                    "a\"b");
                            if (status == 404) {
                                assertThat(type).as(where).isNotNull();
                                assertThat(type.isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)).as(where).isTrue();
                                JsonNode problem = mapper.readTree(body);
                                assertThat(problem.path("code").asString()).as(where).isEqualTo("NOT_FOUND");
                                assertThat(problem.path("detail").asString()).as(where).isEqualTo("Import non trovato");
                            }
                            return null;
                        });
            }
        }
        // Un id ben formato ma inesistente: 404 anche per il rapporto, senza intestazioni di file.
        client().get().uri("/v1/imports/01JCQ000000000000000000Z99/report.csv")
                .exchange((req, res) -> {
                    assertThat(res.getStatusCode().value()).isEqualTo(404);
                    assertThat(res.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION)).isNull();
                    assertThat(res.getHeaders().getContentType().toString()).startsWith("application/problem+json");
                    return null;
                });
    }

    // ================= limiti del batch =================

    @Test
    void batchRateLimitCountsEventsPerAddress() {
        JsonNode never = postBatchFrom("203.0.113.7", 11, 422);
        assertThat(never.path("code").asString()).as("mai ammissibile: niente 429 da ritentare all'infinito")
                .isEqualTo("BATCH_OVER_RATE_LIMIT");
        assertThat(never.path("detail").asString()).contains("dividilo");
        postBatchFrom("203.0.113.7", 6, 202);
        JsonNode limited = postBatchFrom("203.0.113.7", 6, 429);
        assertThat(limited.path("code").asString()).isEqualTo("RATE_LIMITED");
        assertThat(limited.path("type").asString()).isEqualTo("urn:loyaltyhub:problem:rate-limited");
        postBatchFrom("203.0.113.8", 6, 202);
    }

    @Test
    void batchBodyOverTheCapIs413WithoutReadingIt() {
        String big = "[{\"id\":\"big\",\"data\":{\"x\":\"" + "a".repeat(210_000) + "\"}}]";
        JsonNode body = client().post().uri("/v1/events/batch").contentType(MediaType.APPLICATION_JSON).body(big)
                .exchange((req, res) -> {
                    assertThat(res.getStatusCode().value()).isEqualTo(413);
                    return mapper.readTree(res.getBody());
                });
        assertThat(body.path("code").asString()).isEqualTo("BATCH_BODY_TOO_LARGE");
    }

    // ================= helper =================

    private JsonNode postBatchFrom(String ip, int events, int expected) {
        List<Object> batch = new ArrayList<>();
        for (int i = 0; i < events; i++) {
            batch.add(Map.of("id", "rl-" + ip + "-" + i + "-" + System.nanoTime()));
        }
        return client().post().uri("/v1/events/batch").contentType(MediaType.APPLICATION_JSON)
                .header("X-Forwarded-For", ip).header("X-LH-Actor", SourceActors.FALLBACK).body(batch)
                .exchange((req, res) -> {
                    String text = new String(res.getBody().readAllBytes(), StandardCharsets.UTF_8);
                    assertThat(res.getStatusCode().value()).as(text).isEqualTo(expected);
                    if (expected == 429) {
                        assertThat(res.getHeaders().getFirst("Retry-After")).isNotBlank();
                    } else {
                        assertThat(res.getHeaders().getFirst("Retry-After")).isNull();
                    }
                    return mapper.readTree(text);
                });
    }

    private void insertFinished(String id, Instant finishedAt) {
        jdbc.sql("""
                        INSERT INTO import_job (id, kind, format, file_name, size_bytes, sha256, status, rows_total,
                          rows_done, created_by, created_at, started_at, finished_at)
                        VALUES (?, 'EVENTS', 'CSV', 'vecchio.csv', 1, 'x', 'DONE', 1, 1, 'ADMIN:test', ?, ?, ?)
                        """)
                .params(id, Timestamp.from(finishedAt), Timestamp.from(finishedAt), Timestamp.from(finishedAt))
                .update();
    }

    private String status(String id) {
        return jdbc.sql("SELECT status FROM import_job WHERE id = ?").param(id).query(String.class).single();
    }

    private long outboxCount(String idPattern) {
        return jdbc.sql("SELECT count(*) FROM outbox WHERE payload->>'id' LIKE ?").param(idPattern)
                .query(Long.class).single();
    }

    private static String login(String id, String subject, String time, String platform) {
        return "{\"id\":\"" + id + "\",\"type\":\"app.login.daily\",\"subject\":\"" + subject + "\",\"time\":\"" + time
                + "\",\"data\":{\"platform\":\"" + platform + "\"}}";
    }
}
