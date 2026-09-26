package io.loyaltyhub.ingestion;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ingresso batch e import file asincrono con rapporto (M8.7, F2-ING-01, F2-ING-02, BO-32; docs/18 §3.6,
 * docs/servizi/ingestion-service.md §3). Da capo a capo: esito per elemento, pubblicazione su {@code lh.actions.v1} dei
 * soli accettati (una volta), rapporto per riga, CSV scaricabile senza formule, «Riprova non abbinati», controlli sul
 * file, audit con i soli conteggi, ripresa di un lavoro interrotto. EmbeddedKafka + Postgres in-process (Zonky).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "loyaltyhub.ingestion.imports.max-bytes=4096",
        "loyaltyhub.ingestion.imports.max-rows=20",
        "loyaltyhub.ingestion.imports.poll-interval-ms=300",
        "loyaltyhub.ingestion.imports.stale-after-ms=1000"})
@EmbeddedKafka(partitions = 1, topics = {"lh.actions.v1", "lh.dlq.v1", "lh.audit.v1", "lh.facts.v1"})
@ActiveProfiles("demo")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ImportsIT {

    private static final String ACTIONS = "lh.actions.v1";
    private static final String ECOM = "urn:loyaltyhub:source:ecommerce";
    private static final String ADMIN = "ADMIN:marta.admin";
    private static final EmbeddedPostgres PG = startPg();

    @Value("${local.server.port}")
    private int port;
    @Autowired
    private ObjectMapper mapper;
    @Autowired
    private JdbcClient jdbc;

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

    // ================= POST /v1/events/batch (F2-ING-01) =================

    @Test
    void batchReportsEachItemInOrderAndPublishesOnlyTheAcceptedOnce() {
        List<Object> batch = new ArrayList<>();
        batch.add(purchase("batch-ok-1", "member:MBR-000002"));
        batch.add(purchase("batch-ok-1", "member:MBR-000002"));            // stesso (fonte, id) nello stesso batch
        batch.add(purchase("batch-unm-1", "external:CRM-NON-ISCRITTO"));
        Map<String, Object> invalidData = purchase("batch-rej-1", "member:MBR-000002");
        invalidData.put("data", Map.of("orderId", "ORD-1", "currency", "EUR"));
        batch.add(invalidData);
        Map<String, Object> noData = purchase("batch-inv-1", "member:MBR-000002");
        noData.remove("data");
        batch.add(noData);
        Map<String, Object> shortSource = purchase("batch-inv-2", "member:MBR-000002");
        shortSource.put("source", "ecommerce");
        batch.add(shortSource);
        batch.add("non un evento");

        JsonNode body = postJson("/v1/events/batch", batch, null, 202);
        assertThat(body.path("total").asInt()).isEqualTo(7);
        assertThat(body.path("counts").path("accepted").asInt()).isEqualTo(1);
        assertThat(body.path("counts").path("duplicate").asInt()).isEqualTo(1);
        assertThat(body.path("counts").path("unmatched").asInt()).isEqualTo(1);
        assertThat(body.path("counts").path("rejected").asInt()).isEqualTo(1);
        assertThat(body.path("counts").path("invalid").asInt()).isEqualTo(3);
        List<String> statuses = new ArrayList<>();
        body.path("items").forEach(i -> statuses.add(i.path("index").asInt() + ":" + i.path("status").asString()));
        assertThat(statuses).containsExactly("0:ACCEPTED", "1:DUPLICATE", "2:UNMATCHED", "3:REJECTED", "4:INVALID",
                "5:INVALID", "6:INVALID");
        JsonNode items = body.path("items");
        assertThat(items.get(0).path("memberId").asString()).isEqualTo("MBR-000002");
        assertThat(items.get(3).path("rejectCode").asString()).isEqualTo("INVALID_DATA");
        assertThat(items.get(3).path("detail").asString()).contains("amount");
        assertThat(items.get(4).path("detail").asString()).contains("data");
        assertThat(items.get(5).path("detail").asString()).contains("URN");

        // Nel monitor: gli esiti della pipeline sì (origine EXTERNAL), gli INVALID no (come un 400).
        assertThat(inboundCount("batch-unm-1")).isEqualTo(1);
        assertThat(inboundCount("batch-inv-1")).isZero();

        List<ConsumerRecord<String, String>> published = drain("batch-check", r -> r.value().contains("\"batch-"));
        assertThat(published).extracting(ConsumerRecord::key).containsExactly("MBR-000002");
        assertThat(readJson(published.getFirst().value()).path("id").asString()).isEqualTo("batch-ok-1");
    }

    @Test
    void batchLimitsAreProblemDetails() {
        List<Object> tooMany = new ArrayList<>();
        for (int i = 0; i < 1001; i++) {
            tooMany.add(Map.of("id", "x" + i));
        }
        JsonNode tooLarge = postJson("/v1/events/batch", tooMany, null, 422);
        assertThat(tooLarge.path("code").asString()).isEqualTo("BATCH_TOO_LARGE");
        assertThat(tooLarge.path("type").asString()).isEqualTo("urn:loyaltyhub:problem:validation");
        assertThat(postJson("/v1/events/batch", List.of(), null, 422).path("code").asString()).isEqualTo("BATCH_EMPTY");
        assertThat(postJson("/v1/events/batch", Map.of("id", "x"), null, 400).path("code").asString())
                .isEqualTo("BAD_REQUEST");
    }

    // ================= POST /v1/imports (F2-ING-02, BO-32) =================

    @Test
    void csvImportRunsInBackgroundWithReportPublicationAndAudit() {
        String now = Instant.now().toString();
        String csv = "id,type,subject,time,data.orderId,data.amount,data.currency\n"
                + "imp-ok-1,purchase.completed,member:MBR-000003," + now + ",ORD-I1,130,EUR\n"
                + "imp-ok-1,purchase.completed,member:MBR-000003," + now + ",ORD-I1,130,EUR\n"
                + "imp-rej-1,purchase.completed,member:MBR-000003," + now + ",ORD-I2,,EUR\n"
                + "imp-unm-1,purchase.completed,external:CRM-IMP-NUOVO," + now + ",ORD-I3,10,EUR\n"
                + "=1+2,purchase.completed,external:CRM-IMP-NUOVO," + now + ",ORD-I4,dieci,EUR\n"
                + "imp-inv-2,purchase.completed,member:MBR-000003,ieri,ORD-I5,1,EUR\n";
        JsonNode created = upload("ordini.csv", "text/csv", csv, "ecommerce", ADMIN, null, 202);
        String id = created.path("id").asString();
        assertThat(created.path("status").asString()).isIn("QUEUED", "RUNNING", "DONE");
        assertThat(created.path("format").asString()).isEqualTo("CSV");
        assertThat(created.path("rowsTotal").asInt()).isEqualTo(6);
        assertThat(created.path("createdBy").asString()).isEqualTo(ADMIN);

        JsonNode done = awaitFinished(id);
        JsonNode job = done.path("job");
        assertThat(job.path("status").asString()).isEqualTo("DONE");
        assertThat(job.path("rowsDone").asInt()).isEqualTo(6);
        assertThat(job.path("counts").path("accepted").asInt()).isEqualTo(1);
        assertThat(job.path("counts").path("duplicate").asInt()).isEqualTo(1);
        assertThat(job.path("counts").path("rejected").asInt()).isEqualTo(1);
        assertThat(job.path("counts").path("unmatched").asInt()).isEqualTo(1);
        assertThat(job.path("counts").path("invalid").asInt()).isEqualTo(2);
        assertThat(done.path("openUnmatched").asInt()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT content FROM import_job WHERE id = ?").param(id).query(String.class).optional())
                .as("il file non resta dopo l'elaborazione").isEmpty();

        JsonNode rows = get("/v1/imports/" + id + "/rows");
        assertThat(rows.path("page").path("totalItems").asInt()).isEqualTo(5);
        List<String> outcomes = new ArrayList<>();
        rows.path("items").forEach(r -> outcomes.add(r.path("rowNumber").asInt() + ":" + r.path("outcome").asString()));
        assertThat(outcomes).containsExactly("2:DUPLICATE", "3:REJECTED", "4:UNMATCHED", "5:INVALID", "6:INVALID");
        JsonNode unmatched = rows.path("items").get(2);
        assertThat(unmatched.path("inboundEventId").asString()).isNotBlank();
        assertThat(unmatched.path("currentStatus").asString()).isEqualTo("UNMATCHED");
        assertThat(rows.path("items").get(3).path("detail").asString()).isEqualTo("data.amount: atteso un numero");
        assertThat(rows.path("items").get(4).path("detail").asString()).contains("RFC 3339");
        assertThat(get("/v1/imports/" + id + "/rows?outcome=INVALID").path("page").path("totalItems").asInt()).isEqualTo(2);

        // La riga del monitor ingressi porta l'origine IMPORT (BO-26).
        JsonNode inbound = get("/v1/inbound-events/" + unmatched.path("inboundEventId").asString());
        assertThat(inbound.path("origin").asString()).isEqualTo("IMPORT");

        // Pubblicato una sola volta, con la fonte predefinita del lavoro.
        List<ConsumerRecord<String, String>> published = drain("import-check", r -> r.value().contains("\"imp-"));
        assertThat(published).hasSize(1);
        JsonNode action = readJson(published.getFirst().value());
        assertThat(action.path("id").asString()).isEqualTo("imp-ok-1");
        assertThat(action.path("source").asString()).isEqualTo(ECOM);
        assertThat(action.path("data").path("amount").asInt()).isEqualTo(130);

        // Audit: caricamento con l'attore reale e fine lavoro come job, solo conteggi (mai il contenuto).
        List<ConsumerRecord<String, String>> audit = drain("import-audit", "lh.audit.v1", r -> r.key().equals("import:" + id));
        assertThat(audit).hasSize(2);
        JsonNode createdEntry = readJson(audit.getFirst().value());
        assertThat(createdEntry.path("lhactor").asString()).isEqualTo(ADMIN);
        assertThat(createdEntry.path("data").path("action").asString()).isEqualTo("CREATE");
        assertThat(createdEntry.path("data").path("after").path("rows").asInt()).isEqualTo(6);
        JsonNode finishedEntry = readJson(audit.get(1).value());
        assertThat(finishedEntry.path("data").path("action").asString()).isEqualTo("JOB");
        assertThat(finishedEntry.path("data").path("after").path("accepted").asInt()).isEqualTo(1);
        assertThat(audit.get(0).value() + audit.get(1).value()).doesNotContain("ORD-I1", "CRM-IMP-NUOVO");

        // Rapporto scaricabile: formule neutralizzate, niente soggetto dei non abbinati.
        String report = RestClient.create("http://localhost:" + port).get().uri("/v1/imports/" + id + "/report.csv")
                .header("Accept", "application/json")
                .exchange((req, res) -> {
                    assertThat(res.getStatusCode().value()).isEqualTo(200);
                    assertThat(res.getHeaders().getContentType().toString()).startsWith("text/csv");
                    assertThat(res.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION)).startsWith("attachment;");
                    return new String(res.getBody().readAllBytes(), StandardCharsets.UTF_8);
                });
        assertThat(report).startsWith("riga,id_evento,esito,codice,dettaglio,esito_attuale\n");
        assertThat(report).contains("\n5,'=1+2,INVALID,,data.amount: atteso un numero,\n");
        assertThat(report).doesNotContain("CRM-IMP-NUOVO");
        assertThat(report.lines()).hasSize(6);
    }

    @Test
    void retryUnmatchedAcceptsRowsWhoseMemberNowExists() {
        String now = Instant.now().toString();
        String ndjson = """
                {"id":"retry-unm-1","type":"app.login.daily","subject":"external:CRM-IMP-RETRY","time":"%s","data":{"platform":"IOS"}}
                {"id":"retry-unm-2","type":"app.login.daily","subject":"external:CRM-IMP-ALTRO","time":"%s","data":{"platform":"IOS"}}
                """.formatted(now, now);
        String id = upload("accessi.ndjson", "application/x-ndjson", ndjson, "app", ADMIN, null, 202).path("id").asString();
        JsonNode done = awaitFinished(id);
        assertThat(done.path("job").path("counts").path("unmatched").asInt()).isEqualTo(2);
        assertThat(done.path("openUnmatched").asInt()).isEqualTo(2);

        post("/v1/imports/" + id + "/retry-unmatched", "ANALYST:sara.analyst", 403);
        jdbc.sql("INSERT INTO member_index (member_id, external_id, email_lower, status) VALUES (?, ?, NULL, 'ACTIVE')")
                .params("MBR-990001", "CRM-IMP-RETRY").update();
        JsonNode retried = post("/v1/imports/" + id + "/retry-unmatched", "CARE:paolo.care", 200);
        assertThat(retried.path("retried").asInt()).isEqualTo(2);
        assertThat(retried.path("accepted").asInt()).isEqualTo(1);
        assertThat(retried.path("stillUnmatched").asInt()).isEqualTo(1);
        assertThat(retried.hasNonNull("nextAfterRow")).as("un solo blocco").isFalse();
        assertThat(get("/v1/imports/" + id).path("openUnmatched").asInt()).isEqualTo(1);
        JsonNode rows = get("/v1/imports/" + id + "/rows");
        assertThat(rows.path("items").get(0).path("outcome").asString()).as("esito all'import").isEqualTo("UNMATCHED");
        assertThat(rows.path("items").get(0).path("currentStatus").asString()).as("esito attuale").isEqualTo("ACCEPTED");

        List<ConsumerRecord<String, String>> published = drain("retry-check", r -> r.value().contains("\"retry-unm-"));
        assertThat(published).extracting(ConsumerRecord::key).containsExactly("MBR-990001");
    }

    @Test
    void fileControlsRejectTheWholeFileWithACode() {
        String header = "id,type,subject,time\n";
        assertThat(upload("x.csv", "text/csv", header + "a".repeat(4100), "app", ADMIN, null, 422)
                .path("code").asString()).isEqualTo("IMPORT_FILE_TOO_LARGE");
        assertThat(upload("x.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", header, "app",
                ADMIN, null, 422).path("code").asString()).isEqualTo("IMPORT_FORMAT_UNSUPPORTED");
        assertThat(upload("x.csv", "application/pdf", header, "app", ADMIN, null, 422).path("code").asString())
                .isEqualTo("IMPORT_FORMAT_UNSUPPORTED");
        assertThat(uploadBytes("x.csv", "text/csv", new byte[]{'P', 'K', 3, 4, 0, 0}, "app", ADMIN, null, 422)
                .path("code").asString()).isEqualTo("IMPORT_NOT_TEXT");
        assertThat(upload("x.csv", "text/csv", "[{\"id\":1}]", "app", ADMIN, null, 422).path("code").asString())
                .isEqualTo("IMPORT_FORMAT_MISMATCH");
        assertThat(upload("x.csv", "text/csv", "id,colore\n", "app", ADMIN, null, 422).path("code").asString())
                .isEqualTo("IMPORT_INVALID");
        assertThat(upload("x.csv", "text/csv", header, "app", ADMIN, null, 422).path("code").asString())
                .as("solo intestazione").isEqualTo("IMPORT_EMPTY");
        assertThat(upload("x.csv", "text/csv", "", "app", ADMIN, null, 422).path("code").asString())
                .as("zero byte").isEqualTo("IMPORT_EMPTY");
        assertThat(upload("x.ndjson", "application/x-ndjson", "\n  \n", "app", ADMIN, null, 422).path("code").asString())
                .as("solo righe vuote").isEqualTo("IMPORT_EMPTY");
        assertThat(uploadBytes("x.csv", "text/csv", new byte[]{'i', 'd', (byte) 0xC3, (byte) 0x28}, "app", ADMIN, null, 422)
                .path("code").asString()).as("UTF-8 non valido").isEqualTo("IMPORT_NOT_TEXT");
        StringBuilder many = new StringBuilder(header);
        for (int i = 0; i < 21; i++) {
            many.append("r").append(i).append(",app.login.daily,member:MBR-000002,2026-01-01T00:00:00Z\n");
        }
        assertThat(upload("x.csv", "text/csv", many.toString(), "app", ADMIN, null, 422).path("code").asString())
                .isEqualTo("IMPORT_TOO_MANY_ROWS");
        assertThat(upload("x.csv", "text/csv", header + "a,b,c,d\n", "fonte-fantasma", ADMIN, null, 422)
                .path("errors").get(0).path("field").asString()).isEqualTo("source");
        MultiValueMap<String, Object> attributes = parts("x.csv", "text/csv", (header + "a,b,c,d\n").getBytes(StandardCharsets.UTF_8), "app");
        attributes.add("kind", "ATTRIBUTES");
        assertThat(send(attributes, ADMIN, null, 422).path("code").asString()).isEqualTo("IMPORT_KIND_NOT_SUPPORTED");
        assertThat(upload("x.csv", "text/csv", header + "a,b,c,d\n", "app", "ANALYST:sara.analyst", null, 403)
                .path("code").asString()).isEqualTo("FORBIDDEN_ROLE");
        assertThat(upload("x.csv", "text/csv", header + "a,b,c,d\n", "app", "MARKETING:luca.marketing", null, 403)
                .path("code").asString()).isEqualTo("FORBIDDEN_ROLE");
    }

    @Test
    void reUploadNeverAppliesAnEventTwice() {
        String csv = "id,type,subject,time,data\nidem-1,app.login.daily,member:MBR-000002," + Instant.now()
                + ",\"{\"\"platform\"\":\"\"IOS\"\"}\"\n";
        // Stessa Idempotency-Key (doppio clic, ritrasmissione): stesso lavoro, nessun secondo import.
        String first = upload("idem.csv", "text/csv", csv, "app", ADMIN, "chiave-idem-1", 202).path("id").asString();
        String second = upload("idem.csv", "text/csv", csv, "app", ADMIN, "chiave-idem-1", 202).path("id").asString();
        assertThat(second).isEqualTo(first);
        assertThat(jdbc.sql("SELECT count(*) FROM import_job WHERE idempotency_key = 'chiave-idem-1'").query(Long.class)
                .single()).isEqualTo(1L);
        assertThat(awaitFinished(first).path("job").path("counts").path("accepted").asInt()).isEqualTo(1);

        // Stesso file senza chiave: nuovo lavoro, ma la dedup della pipeline lo rende un duplicato.
        String again = upload("idem.csv", "text/csv", csv, "app", ADMIN, null, 202).path("id").asString();
        assertThat(again).isNotEqualTo(first);
        JsonNode counts = awaitFinished(again).path("job").path("counts");
        assertThat(counts.path("accepted").asInt()).isZero();
        assertThat(counts.path("duplicate").asInt()).isEqualTo(1);
        assertThat(drain("idem-check", r -> r.value().contains("\"idem-1\""))).hasSize(1);
    }

    @Test
    void interruptedJobResumesAfterTheLastCommittedRow() {
        String now = Instant.now().toString();
        String content = """
                {"id":"resume-1","source":"urn:loyaltyhub:source:app","type":"app.login.daily","subject":"member:MBR-000002","time":"%s","data":{"platform":"IOS"}}
                {"id":"resume-2","source":"urn:loyaltyhub:source:app","type":"app.login.daily","subject":"member:MBR-000002","time":"%s","data":{"platform":"IOS"}}
                """.formatted(now, now);
        // Un lavoratore si è fermato dopo aver confermato la riga 1 (conteggi e punto di ripresa già scritti).
        insertRunning("01JRESUMEIMPORT00000000001", content, 1, 1, Instant.now().minus(Duration.ofHours(1)));
        JsonNode done = awaitFinished("01JRESUMEIMPORT00000000001");
        assertThat(done.path("job").path("status").asString()).isEqualTo("DONE");
        assertThat(done.path("job").path("attempts").asInt()).isEqualTo(2);
        assertThat(done.path("job").path("counts").path("accepted").asInt()).isEqualTo(2);
        assertThat(inboundCount("resume-1")).as("riga già elaborata: non si ripete").isZero();
        assertThat(inboundCount("resume-2")).isEqualTo(1);

        // Oltre i tentativi ammessi il lavoro fallisce invece di ripartire all'infinito.
        insertRunning("01JRESUMEIMPORT00000000002", content, 0, 3, Instant.now().minus(Duration.ofHours(1)));
        JsonNode failed = awaitFinished("01JRESUMEIMPORT00000000002");
        assertThat(failed.path("job").path("status").asString()).isEqualTo("FAILED");
        assertThat(failed.path("job").path("errorDetail").asString()).contains("abbandonato");
    }

    @Test
    void demoSeedFillsTheImportList() {
        JsonNode list = get("/v1/imports?size=100");
        JsonNode seeded = null;
        for (JsonNode j : list.path("items")) {
            if (j.path("fileName").asString().equals("ordini-ecommerce-ieri.csv")) {
                seeded = j;
            }
        }
        assertThat(seeded).as("import demo di docs/10 §8.2").isNotNull();
        assertThat(seeded.path("status").asString()).isEqualTo("DONE");
        assertThat(seeded.path("counts").path("accepted").asInt()).isEqualTo(1);
        assertThat(seeded.path("counts").path("duplicate").asInt()).isEqualTo(1);
        assertThat(seeded.path("counts").path("rejected").asInt()).isEqualTo(4);
        assertThat(seeded.path("counts").path("unmatched").asInt()).isEqualTo(2);
        assertThat(seeded.path("counts").path("invalid").asInt()).isEqualTo(1);
        JsonNode rows = get("/v1/imports/" + seeded.path("id").asString() + "/rows");
        assertThat(rows.path("page").path("totalItems").asInt()).isEqualTo(8);
        assertThat(get("/v1/imports?status=nope", 400).path("code").asString()).isEqualTo("BAD_REQUEST");
        assertThat(get("/v1/imports/NON-ESISTE", 404).path("code").asString()).isEqualTo("NOT_FOUND");
    }

    // ================= helper =================

    private void insertRunning(String id, String content, int rowsDone, int attempts, Instant heartbeat) {
        jdbc.sql("""
                        INSERT INTO import_job (id, kind, format, file_name, size_bytes, sha256, status, rows_total,
                          rows_done, accepted, attempts, content, created_by, created_at, started_at, heartbeat_at)
                        VALUES (?, 'EVENTS', 'NDJSON', 'ripresa.ndjson', ?, 'x', 'RUNNING', 2, ?, ?, ?, ?, 'ADMIN:test', ?, ?, ?)
                        """)
                .params(id, content.length(), rowsDone, rowsDone, attempts, content, Timestamp.from(heartbeat),
                        Timestamp.from(heartbeat), Timestamp.from(heartbeat))
                .update();
    }

    private long inboundCount(String eventId) {
        return jdbc.sql("SELECT count(*) FROM inbound_event WHERE event_id = ?").param(eventId).query(Long.class).single();
    }

    private JsonNode awaitFinished(String id) {
        long deadline = System.currentTimeMillis() + 30_000;
        JsonNode d = null;
        while (System.currentTimeMillis() < deadline) {
            d = get("/v1/imports/" + id);
            String s = d.path("job").path("status").asString();
            if (s.equals("DONE") || s.equals("FAILED")) {
                return d;
            }
            sleep(200);
        }
        throw new AssertionError("import " + id + " non concluso: " + d);
    }

    private JsonNode upload(String name, String contentType, String text, String source, String actor, String key, int expected) {
        return uploadBytes(name, contentType, text.getBytes(StandardCharsets.UTF_8), source, actor, key, expected);
    }

    private JsonNode uploadBytes(String name, String contentType, byte[] bytes, String source, String actor, String key,
                                 int expected) {
        return send(parts(name, contentType, bytes, source), actor, key, expected);
    }

    private static MultiValueMap<String, Object> parts(String name, String contentType, byte[] bytes, String source) {
        HttpHeaders fileHeaders = new HttpHeaders();
        fileHeaders.setContentType(MediaType.parseMediaType(contentType));
        fileHeaders.setContentDispositionFormData("file", name);
        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        parts.add("file", new HttpEntity<>(bytes, fileHeaders));
        if (source != null) {
            parts.add("source", source);
        }
        return parts;
    }

    private JsonNode send(MultiValueMap<String, Object> parts, String actor, String key, int expected) {
        RestClient.RequestBodySpec spec = client().post().uri("/v1/imports").contentType(MediaType.MULTIPART_FORM_DATA)
                .header("X-LH-Actor", actor);
        if (key != null) {
            spec = spec.header("Idempotency-Key", key);
        }
        return spec.body(parts).exchange((req, res) -> {
            String text = new String(res.getBody().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(res.getStatusCode().value()).as(text).isEqualTo(expected);
            return mapper.readTree(text);
        });
    }

    private JsonNode postJson(String path, Object body, String actor, int expected) {
        RestClient.RequestBodySpec spec = client().post().uri(path).contentType(MediaType.APPLICATION_JSON);
        if (actor != null) {
            spec = spec.header("X-LH-Actor", actor);
        }
        return spec.body(body).exchange((req, res) -> {
            String text = new String(res.getBody().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(res.getStatusCode().value()).as(text).isEqualTo(expected);
            return mapper.readTree(text);
        });
    }

    private JsonNode post(String path, String actor, int expected) {
        return client().post().uri(path).header("X-LH-Actor", actor).exchange((req, res) -> {
            String text = new String(res.getBody().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(res.getStatusCode().value()).as(text).isEqualTo(expected);
            return mapper.readTree(text);
        });
    }

    private JsonNode get(String path) {
        return get(path, 200);
    }

    private JsonNode get(String path, int expected) {
        return client().get().uri(path).exchange((req, res) -> {
            String text = new String(res.getBody().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(res.getStatusCode().value()).as(text).isEqualTo(expected);
            return mapper.readTree(text);
        });
    }

    private RestClient client() {
        return RestClient.create("http://localhost:" + port);
    }

    private Map<String, Object> purchase(String id, String subject) {
        Map<String, Object> event = new HashMap<>();
        event.put("specversion", "1.0");
        event.put("id", id);
        event.put("source", ECOM);
        event.put("type", "purchase.completed");
        event.put("subject", subject);
        event.put("time", Instant.now().toString());
        event.put("data", Map.of("orderId", "ORD-" + id, "amount", 130, "currency", "EUR", "channel", "ONLINE"));
        return event;
    }

    private List<ConsumerRecord<String, String>> drain(String group, Predicate<ConsumerRecord<String, String>> match) {
        return drain(group, ACTIONS, match);
    }

    /** Tutti i record del topic che soddisfano {@code match}, leggendo per qualche secondo dopo l'ultimo trovato. */
    private List<ConsumerRecord<String, String>> drain(String group, String topic,
                                                       Predicate<ConsumerRecord<String, String>> match) {
        List<ConsumerRecord<String, String>> out = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, System.getProperty("spring.embedded.kafka.brokers"),
                ConsumerConfig.GROUP_ID_CONFIG, group,
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class))) {
            consumer.subscribe(List.of(topic));
            long deadline = System.currentTimeMillis() + 8_000;
            while (System.currentTimeMillis() < deadline) {
                for (ConsumerRecord<String, String> r : consumer.poll(Duration.ofMillis(400))) {
                    if (match.test(r)) {
                        out.add(r);
                    }
                }
            }
        }
        return out;
    }

    private JsonNode readJson(String value) {
        return mapper.readTree(value);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static EmbeddedPostgres startPg() {
        try {
            return EmbeddedPostgres.builder().start();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
