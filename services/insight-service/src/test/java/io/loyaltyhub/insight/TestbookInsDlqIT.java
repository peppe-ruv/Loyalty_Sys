package io.loyaltyhub.insight;

import io.loyaltyhub.insight.domain.DlqEntry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testbook TB-INS — DLQ (docs/testbook/TB-INS-insight.md §3): stato × azione × famiglia ({@code DST}), ruoli
 * ({@code DRL}), nota ({@code DNT}), esito di ingestion nel riprocessa ({@code DIN}), audit delle chiusure ({@code DAU}),
 * concorrenza ({@code DCC}), elenco e paginazione ({@code DLS}), ingest dei record di {@code lh.dlq.v1} ({@code DIG}).
 * Oracolo: insight §2, §3, §5, §7; docs/04 §5; docs/06 §2–§3; docs/08 §2 ({@code dlq.handle}) e BO-27; Q-104…Q-111, Q-261.
 */
class TestbookInsDlqIT extends TestbookInsBase {

    private static final String NOTE = "Scartata dal testbook";

    // ---------- DST: stato × azione × famiglia ----------

    @ParameterizedTest(name = "[{0}] {1}", quoteTextArguments = false)
    @CsvFileSource(resources = "/testbook/ins/dlq-stato-azione.csv", numLinesToSkip = 1)
    void statoAzione(String id, String desc, String status, String action, String family, String note,
                     int expHttp, String expCode, String expStatus, int calls) {
        String dlqId;
        if ("NONE".equals(status)) {
            dlqId = "01NOTEXIST" + id.substring(id.length() - 3);
        } else {
            dlqId = openEntry(family, "lh-tb-dst", "E_" + family, uid("COR-DST"), null).id();
            if (!"OPEN".equals(status)) {
                assertThat(dlqRepo.resolve(dlqId, status, "ADMIN:setup", "stato di partenza")).isTrue();
            }
            assertThat(get("/v1/dlq/" + dlqId).path("reprocessable").asBoolean())
                    .as("reprocessable solo per un'azione aperta (insight §5)")
                    .isEqualTo("OPEN".equals(status) && "ACTION".equals(family));
        }
        Object body = "-".equals(note) ? null : Map.of("note", NOTE);
        Resp r = call("POST", "/v1/dlq/" + dlqId + "/" + action, actor("ADMIN"), body);
        assertThat(r.status()).as(r.text()).isEqualTo(expHttp);
        if (!"-".equals(expCode)) {
            // SPEC-GAP ammesso: su una voce chiusa di effetto/fatto entrambi i 409 rispettano la specifica (Q-107, §5).
            assertThat(List.of(expCode.split("\\|"))).as(r.text()).contains(r.code());
        }
        assertThat(statusOf(dlqId)).isEqualTo(expStatus);
        assertThat(INGESTION_CALLS).as("chiamate a ingestion").hasSize(calls);
        if (expHttp == 200) {
            assertThat(r.body().path("resolvedBy").asString()).isEqualTo(actor("ADMIN"));
            assertThat(r.body().path("resolvedAt").isMissingNode()).isFalse();
            assertThat(r.body().path("reprocessable").asBoolean()).isFalse();
        }
    }

    // ---------- DRL: ruolo × azione ----------

    @ParameterizedTest(name = "[{0}] {1}", quoteTextArguments = false)
    @CsvFileSource(resources = "/testbook/ins/dlq-ruoli.csv", numLinesToSkip = 1)
    void ruoli(String id, String desc, String action, String role, int expHttp, String expCode, String expStatus,
               int calls) {
        String dlqId = openEntry("ACTION", "lh-tb-drl", "E_ROLE", uid("COR-DRL"), null).id();
        Resp r = call("POST", "/v1/dlq/" + dlqId + "/" + action, actor(role), Map.of("note", NOTE));
        assertThat(r.status()).as(r.text()).isEqualTo(expHttp);
        if (!"-".equals(expCode)) {
            assertThat(r.code()).isEqualTo(expCode);
        }
        assertThat(statusOf(dlqId)).isEqualTo(expStatus);
        assertThat(INGESTION_CALLS).hasSize(calls);
    }

    // ---------- DNT: nota ----------

    @ParameterizedTest(name = "[{0}] {1}", quoteTextArguments = false)
    @CsvFileSource(resources = "/testbook/ins/dlq-nota.csv", numLinesToSkip = 1)
    void nota(String id, String desc, String action, String family, String bodyKind, int expHttp, String expCode,
              String expStatus, String expNote) {
        String dlqId = openEntry(family, "lh-tb-dnt", "E_NOTE", uid("COR-DNT"), null).id();
        String longNote = "n".repeat(2000);
        String unicode = "Città di prova – ✓ 😀";
        Object body = switch (bodyKind) {
            case "NONE" -> null;
            case "EMPTY_OBJ" -> "{}";
            case "NULL" -> "{\"note\":null}";
            case "EMPTY" -> Map.of("note", "");
            case "BLANK" -> Map.of("note", "   ");
            case "ONE_CHAR" -> Map.of("note", "x");
            case "PADDED" -> Map.of("note", "  motivo verificato  ");
            case "LONG" -> Map.of("note", longNote);
            case "UNICODE" -> Map.of("note", unicode);
            case "MALFORMED" -> "{\"note\": ";
            default -> throw new IllegalArgumentException(bodyKind);
        };
        Resp r = call("POST", "/v1/dlq/" + dlqId + "/" + action, actor("ADMIN"), body);
        assertThat(r.status()).as(r.text()).isEqualTo(expHttp);
        if (!"-".equals(expCode)) {
            assertThat(r.code()).isEqualTo(expCode);
        }
        DlqEntry after = dlqRepo.findById(dlqId).orElseThrow();
        assertThat(after.status()).isEqualTo(expStatus);
        String expected = switch (expNote) {
            case "-" -> null;
            case "LONG" -> longNote;
            case "UNICODE" -> unicode;
            default -> expNote;
        };
        assertThat(after.resolutionNote()).isEqualTo(expected);
    }

    // ---------- DIN: esito di ingestion nel riprocessa ----------

    @ParameterizedTest(name = "[{0}] {1}", quoteTextArguments = false)
    @CsvFileSource(resources = "/testbook/ins/dlq-ingestion.csv", numLinesToSkip = 1)
    void ingestion(String id, String desc, int stubHttp, String stubBody, int expHttp, String expCode,
                   String expStatus) {
        String dlqId = openEntry("ACTION", "lh-tb-din", "E_RESEND", uid("COR-DIN"), null).id();
        INGESTION_REPLY.set(stub(stubHttp, stubBody));
        Resp r = call("POST", "/v1/dlq/" + dlqId + "/reprocess", actor("ADMIN"), null);
        assertThat(r.status()).as(r.text()).isEqualTo(expHttp);
        if (!"-".equals(expCode)) {
            assertThat(r.code()).isEqualTo(expCode);
        }
        assertThat(statusOf(dlqId)).isEqualTo(expStatus);
        assertThat(INGESTION_CALLS).as("un solo tentativo verso ingestion").hasSize(1);
    }

    private static StubReply stub(int http, String kind) {
        return switch (kind) {
            case "ACCEPTED" -> new StubReply(http, "{\"status\":\"ACCEPTED\",\"eventId\":\"x\"}", false);
            case "DUPLICATE" -> new StubReply(http, "{\"status\":\"DUPLICATE\"}", false);
            case "REJECTED" -> new StubReply(http,
                    "{\"status\":\"REJECTED\",\"rejectCode\":\"TYPE_UNKNOWN\",\"detail\":\"tipo sconosciuto\"}", false);
            case "UNMATCHED" -> new StubReply(http, "{\"status\":\"UNMATCHED\"}", false);
            case "NOSTATUS" -> new StubReply(http, "{}", false);
            case "EMPTY" -> new StubReply(http, "", false);
            case "PROBLEM" -> new StubReply(http, "{\"status\":" + http + ",\"code\":\"X\",\"detail\":\"no\"}", false);
            case "DROP" -> new StubReply(0, "", true);
            default -> throw new IllegalArgumentException(kind);
        };
    }

    @Test
    @DisplayName("[TB-INS-DIN-013] il re-invio porta lo stesso id e i soli attributi d'ingresso (niente lh*, dataschema)")
    void resendBody() throws Exception {
        DlqEntry e = openEntry("ACTION", "lh-tb-din", "E_RESEND", uid("COR-DIN"), null);
        assertThat(call("POST", "/v1/dlq/" + e.id() + "/reprocess", actor("ADMIN"), null).status()).isEqualTo(200);
        StubCall c = INGESTION_CALLS.poll(5, TimeUnit.SECONDS);
        assertThat(c).isNotNull();
        List<String> fields = new ArrayList<>();
        c.body().propertyNames().forEach(fields::add);
        assertThat(fields).containsExactlyInAnyOrder("specversion", "id", "source", "type", "subject", "time", "data");
        assertThat(c.body().path("id").asString()).isEqualTo(e.eventId());
        assertThat(c.body().path("type").asString()).isEqualTo(e.originalType());
        assertThat(c.body().path("data").path("platform").asString()).isEqualTo("ANDROID");
    }

    @Test
    @DisplayName("[TB-INS-DIN-014] il re-invio è POST /v1/events con X-LH-Actor dell'ADMIN e X-LH-Reprocess = id della voce (Q-104)")
    void resendHeaders() throws Exception {
        DlqEntry e = openEntry("ACTION", "lh-tb-din", "E_RESEND", uid("COR-DIN"), null);
        assertThat(call("POST", "/v1/dlq/" + e.id() + "/reprocess", actor("ADMIN"), null).status()).isEqualTo(200);
        StubCall c = INGESTION_CALLS.poll(5, TimeUnit.SECONDS);
        assertThat(c).isNotNull();
        assertThat(c.method()).isEqualTo("POST");
        assertThat(c.path()).isEqualTo("/v1/events");
        assertThat(c.actor()).isEqualTo(actor("ADMIN"));
        assertThat(c.reprocess()).isEqualTo(e.id());
    }

    @Test
    @DisplayName("[TB-INS-DIN-015] dopo un rifiuto di ingestion la voce resta aperta e un nuovo riprocessa riuscito la chiude")
    void retryAfterRefusal() {
        DlqEntry e = openEntry("ACTION", "lh-tb-din", "E_RESEND", uid("COR-DIN"), null);
        INGESTION_REPLY.set(stub(202, "DUPLICATE"));
        assertThat(call("POST", "/v1/dlq/" + e.id() + "/reprocess", actor("ADMIN"), null).status()).isEqualTo(409);
        INGESTION_REPLY.set(accepted());
        Resp ok = call("POST", "/v1/dlq/" + e.id() + "/reprocess", actor("ADMIN"), null);
        assertThat(ok.status()).isEqualTo(200);
        assertThat(statusOf(e.id())).isEqualTo("REPROCESSED");
        assertThat(INGESTION_CALLS).hasSize(2);
    }

    // ---------- DAU: audit delle chiusure (Q-109, F-AUD-01) ----------

    private JsonNode auditOf(String entityId) {
        return get("/v1/audit?entityType=DlqEntry&entityId=" + entityId).path("items");
    }

    private JsonNode awaitAudit(String entityId, int n) {
        return awaitValue("audit di " + entityId, () -> {
            JsonNode items = auditOf(entityId);
            return items.size() >= n ? items : null;
        }, 20_000);
    }

    /** Barriera: una chiusura riuscita dopo il caso; quando il suo audit è arrivato, anche i precedenti lo sono. */
    private void auditBarrier() {
        String sentinel = openEntry("FACT", "lh-tb-dau", "E_SENTINEL", uid("COR-DAU"), null).id();
        assertThat(call("POST", "/v1/dlq/" + sentinel + "/discard", actor("ADMIN"), Map.of("note", NOTE)).status())
                .isEqualTo(200);
        awaitAudit(sentinel, 1);
    }

    @Test
    @DisplayName("[TB-INS-DAU-001] riprocessa riuscito: audit TRANSITION su DlqEntry con attore ADMIN e stato OPEN → REPROCESSED")
    void auditReprocess() {
        String id = openEntry("ACTION", "lh-tb-dau", "E_AUD", uid("COR-DAU"), null).id();
        assertThat(call("POST", "/v1/dlq/" + id + "/reprocess", actor("ADMIN"), null).status()).isEqualTo(200);
        JsonNode a = awaitAudit(id, 1).get(0);
        assertThat(a.path("service").asString()).isEqualTo("insight");
        assertThat(a.path("action").asString()).isEqualTo("TRANSITION");
        assertThat(a.path("actorRole").asString()).isEqualTo("ADMIN");
        assertThat(a.path("actorName").asString()).isEqualTo("ada.admin");
        assertThat(a.path("before").path("status").asString()).isEqualTo("OPEN");
        assertThat(a.path("after").path("status").asString()).isEqualTo("REPROCESSED");
    }

    @Test
    @DisplayName("[TB-INS-DAU-002] scarta riuscito: audit TRANSITION con la nota nel dopo")
    void auditDiscard() {
        String id = openEntry("EFFECT", "lh-tb-dau", "E_AUD", uid("COR-DAU"), null).id();
        assertThat(call("POST", "/v1/dlq/" + id + "/discard", actor("ADMIN"), Map.of("note", "pool vuoto")).status())
                .isEqualTo(200);
        JsonNode a = awaitAudit(id, 1).get(0);
        assertThat(a.path("action").asString()).isEqualTo("TRANSITION");
        assertThat(a.path("after").path("status").asString()).isEqualTo("DISCARDED");
        assertThat(a.path("after").path("note").asString()).isEqualTo("pool vuoto");
        assertThat(a.path("before").path("status").asString()).isEqualTo("OPEN");
    }

    @Test
    @DisplayName("[TB-INS-DAU-003] scarta rifiutato per ruolo (403): nessuna voce di audit")
    void noAuditOnForbidden() {
        String id = openEntry("FACT", "lh-tb-dau", "E_AUD", uid("COR-DAU"), null).id();
        assertThat(call("POST", "/v1/dlq/" + id + "/discard", actor("CARE"), Map.of("note", NOTE)).status()).isEqualTo(403);
        auditBarrier();
        assertThat(auditOf(id).size()).isZero();
    }

    @Test
    @DisplayName("[TB-INS-DAU-004] seconda chiusura (409 DLQ_NOT_OPEN): nessuna seconda voce di audit")
    void noAuditOnSecondClose() {
        String id = openEntry("FACT", "lh-tb-dau", "E_AUD", uid("COR-DAU"), null).id();
        assertThat(call("POST", "/v1/dlq/" + id + "/discard", actor("ADMIN"), Map.of("note", NOTE)).status()).isEqualTo(200);
        assertThat(call("POST", "/v1/dlq/" + id + "/discard", actor("ADMIN"), Map.of("note", NOTE)).status()).isEqualTo(409);
        auditBarrier();
        assertThat(auditOf(id).size()).isEqualTo(1);
    }

    @Test
    @DisplayName("[TB-INS-DAU-005] riprocessa rifiutato da ingestion (409 REPROCESS_REJECTED): nessuna voce di audit")
    void noAuditOnRefusedResend() {
        String id = openEntry("ACTION", "lh-tb-dau", "E_AUD", uid("COR-DAU"), null).id();
        INGESTION_REPLY.set(stub(202, "DUPLICATE"));
        assertThat(call("POST", "/v1/dlq/" + id + "/reprocess", actor("ADMIN"), null).status()).isEqualTo(409);
        auditBarrier();
        assertThat(auditOf(id).size()).isZero();
    }

    @Test
    @DisplayName("[TB-INS-DAU-006] scarta senza nota (422 NOTE_REQUIRED): nessuna voce di audit")
    void noAuditOnMissingNote() {
        String id = openEntry("FACT", "lh-tb-dau", "E_AUD", uid("COR-DAU"), null).id();
        assertThat(call("POST", "/v1/dlq/" + id + "/discard", actor("ADMIN"), Map.of()).status()).isEqualTo(422);
        auditBarrier();
        assertThat(auditOf(id).size()).isZero();
    }

    // ---------- DCC: concorrenza ----------

    private List<Resp> concurrently(List<Callable<Resp>> calls) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(calls.size());
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Resp>> futures = new ArrayList<>();
            for (Callable<Resp> c : calls) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return c.call();
                }));
            }
            start.countDown();
            List<Resp> out = new ArrayList<>();
            for (Future<Resp> f : futures) {
                out.add(f.get(60, TimeUnit.SECONDS));
            }
            return out;
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("[TB-INS-DCC-001] due scarta simultanei della stessa voce: uno 200, l'altro 409 DLQ_NOT_OPEN")
    void concurrentDiscard() throws Exception {
        String id = openEntry("FACT", "lh-tb-dcc", "E_CC", uid("COR-DCC"), null).id();
        List<Resp> rs = concurrently(List.of(
                () -> call("POST", "/v1/dlq/" + id + "/discard", actor("ADMIN"), Map.of("note", "primo")),
                () -> call("POST", "/v1/dlq/" + id + "/discard", "ADMIN:altro.admin", Map.of("note", "secondo"))));
        assertThat(rs).extracting(Resp::status).containsExactlyInAnyOrder(200, 409);
        assertThat(rs.stream().filter(r -> r.status() == 409).findFirst().orElseThrow().code()).isEqualTo("DLQ_NOT_OPEN");
        assertThat(statusOf(id)).isEqualTo("DISCARDED");
    }

    @Test
    @DisplayName("[TB-INS-DCC-002] riprocessa e scarta simultanei della stessa azione: una sola chiusura riesce")
    void concurrentReprocessDiscard() throws Exception {
        String id = openEntry("ACTION", "lh-tb-dcc", "E_CC", uid("COR-DCC"), null).id();
        List<Resp> rs = concurrently(List.of(
                () -> call("POST", "/v1/dlq/" + id + "/reprocess", actor("ADMIN"), null),
                () -> call("POST", "/v1/dlq/" + id + "/discard", actor("ADMIN"), Map.of("note", "scarto"))));
        assertThat(rs.stream().filter(r -> r.status() == 200).count()).as(rs.toString()).isEqualTo(1);
        assertThat(rs.stream().filter(r -> r.status() == 409).count()).as(rs.toString()).isEqualTo(1);
        assertThat(statusOf(id)).isIn("REPROCESSED", "DISCARDED");
    }

    // ---------- DLS: elenco, filtri, paginazione ----------

    @ParameterizedTest(name = "[{0}] {1}", quoteTextArguments = false)
    @CsvFileSource(resources = "/testbook/ins/dlq-elenco.csv", numLinesToSkip = 1)
    void elenco(String id, String desc, String query, int expHttp, String expIdx, String expTotal, String expNumber,
                String expSize, String expPages) {
        String consumer = "lh-tb-" + id.toLowerCase();
        String[] statuses = {"OPEN", "OPEN", "REPROCESSED", "DISCARDED", "OPEN"};
        String[] codes = {"E_A", "E_B", "E_A", "E_A", "E_B"};
        List<String> ids = new ArrayList<>();
        Instant base = Instant.parse("2035-03-01T10:00:00Z");
        for (int i = 0; i < 5; i++) {
            DlqEntry e = openEntry("FACT", consumer, codes[i], uid("COR-DLS"), base.plusSeconds(60L * i));
            if (!"OPEN".equals(statuses[i])) {
                dlqRepo.resolve(e.id(), statuses[i], "ADMIN:setup", "x");
            }
            ids.add(e.id());
        }
        Resp r = call("GET", "/v1/dlq?consumer=" + consumer + (query == null ? "" : query), null, null);
        assertThat(r.status()).as(r.text()).isEqualTo(expHttp);
        if (expHttp != 200) {
            return;
        }
        List<String> expected = new ArrayList<>();
        if (expIdx != null && !expIdx.isBlank()) {
            for (String s : expIdx.trim().split(" ")) {
                expected.add(ids.get(Integer.parseInt(s)));
            }
        }
        List<String> actual = new ArrayList<>();
        r.body().path("items").forEach(i -> actual.add(i.path("id").asString()));
        assertThat(actual).as("voci e ordine (dalla più recente)").containsExactlyElementsOf(expected);
        JsonNode page = r.body().path("page");
        assertThat(page.path("totalItems").asLong()).isEqualTo(Long.parseLong(expTotal));
        assertThat(page.path("number").asInt()).isEqualTo(Integer.parseInt(expNumber));
        if (!"-".equals(expSize)) {
            assertThat(page.path("size").asInt()).as("size (docs/06 §2: massimo 100)").isEqualTo(Integer.parseInt(expSize));
        }
        if (!"-".equals(expPages)) {
            assertThat(page.path("totalPages").asInt()).isEqualTo(Integer.parseInt(expPages));
        }
    }

    @Test
    @DisplayName("[TB-INS-DLS-024] dettaglio GET /v1/dlq/{id}: messaggio d'errore, stack abbreviato e payload (BO-27)")
    void detail() {
        DlqEntry e = openEntry("ACTION", "lh-tb-dls-024", "E_DET", uid("COR-DLS"), null);
        JsonNode d = get("/v1/dlq/" + e.id());
        assertThat(d.path("errorMessage").asString()).isEqualTo("errore di prova");
        assertThat(d.path("errorStack").asString()).contains("\tat ");
        assertThat(d.path("payload").path("id").asString()).isEqualTo(e.eventId());
        assertThat(d.path("eventId").asString()).isEqualTo(e.eventId());
    }

    @Test
    @DisplayName("[TB-INS-DLS-025] dettaglio di un id inesistente: 404 NOT_FOUND")
    void detailNotFound() {
        Resp r = call("GET", "/v1/dlq/01NOTEXISTDLS025", null, null);
        assertThat(r.status()).isEqualTo(404);
        assertThat(r.code()).isEqualTo("NOT_FOUND");
    }

    @Test
    @DisplayName("[TB-INS-DLS-026] riga dell'elenco con i campi di BO-27: quando, consumer, topic d'origine, tipo, errorCode, tentativi, stato")
    void listFields() {
        DlqEntry e = openEntry("EFFECT", "lh-tb-dls-026", "E_FLD", uid("COR-DLS"), null);
        JsonNode i = get("/v1/dlq?consumer=lh-tb-dls-026").path("items").get(0);
        assertThat(i.path("id").asString()).isEqualTo(e.id());
        assertThat(i.path("firstSeenAt").isMissingNode()).isFalse();
        assertThat(i.path("consumer").asString()).isEqualTo("lh-tb-dls-026");
        assertThat(i.path("originalTopic").asString()).isEqualTo("lh.effects.v1");
        assertThat(i.path("originalType").asString()).isEqualTo(EFFECT + "points.grant");
        assertThat(i.path("shortType").asString()).isEqualTo("points.grant");
        assertThat(i.path("errorCode").asString()).isEqualTo("E_FLD");
        assertThat(i.path("attempts").asInt()).isEqualTo(1);
        assertThat(i.path("status").asString()).isEqualTo("OPEN");
    }

    // ---------- DIG: ingest dei record di lh.dlq.v1 ----------

    private Map<String, String> lhHeaders(String topic, String consumer, String code, String errorClass,
                                          String attempts, String retryable) {
        Map<String, String> h = new LinkedHashMap<>();
        if (topic != null) {
            h.put("lh-original-topic", topic);
        }
        if (consumer != null) {
            h.put("lh-consumer", consumer);
        }
        if (code != null) {
            h.put("lh-error-code", code);
        }
        if (errorClass != null) {
            h.put("lh-error-class", errorClass);
        }
        h.put("lh-error-message", "messaggio di errore");
        if (attempts != null) {
            h.put("lh-attempts", attempts);
        }
        if (retryable != null) {
            h.put("lh-error-retryable", retryable);
        }
        h.put("lh-error-stack", "x.Boom: messaggio\n\tat io.loyaltyhub.Test.run(Test.java:1)");
        return h;
    }

    private String actionEnvelope(String eventId, String correlationId, String subject) {
        return mapper.writeValueAsString(envelope(eventId, ACTION + "app.login.daily", "urn:loyaltyhub:source:app",
                subject, correlationId, null, Instant.parse("2026-09-15T10:00:00Z"), Map.of("platform", "IOS")));
    }

    private List<JsonNode> entriesOf(String consumer, String eventId) {
        List<JsonNode> out = new ArrayList<>();
        for (JsonNode i : get("/v1/dlq?size=100&consumer=" + consumer).path("items")) {
            if (eventId == null || eventId.equals(i.path("eventId").asString())) {
                out.add(i);
            }
        }
        return out;
    }

    private JsonNode awaitEntry(String consumer, String eventId) {
        return awaitValue("voce DLQ " + eventId, () -> {
            List<JsonNode> l = entriesOf(consumer, eventId);
            return l.isEmpty() ? null : l.get(0);
        }, 20_000);
    }

    @Test
    @DisplayName("[TB-INS-DIG-001] record con intestazioni lh-* complete: voce OPEN con topic, consumer, errore, tentativi, membro, correlazione")
    void ingestFullHeaders() {
        String ev = uid("EVT-DIG");
        String cor = uid("COR-DIG");
        publish("lh.dlq.v1", "MBR-000002", actionEnvelope(ev, cor, "member:MBR-000002"),
                lhHeaders("lh.actions.v1", "lh-tb-dig-001", "DEMO_POISON", "x.NonRetryableEventException", "3", "true"), null);
        JsonNode e = awaitEntry("lh-tb-dig-001", ev);
        assertThat(e.path("originalTopic").asString()).isEqualTo("lh.actions.v1");
        assertThat(e.path("originalType").asString()).isEqualTo(ACTION + "app.login.daily");
        assertThat(e.path("family").asString()).isEqualTo("ACTION");
        assertThat(e.path("errorCode").asString()).isEqualTo("DEMO_POISON");
        assertThat(e.path("errorClass").asString()).isEqualTo("x.NonRetryableEventException");
        assertThat(e.path("errorMessage").asString()).isEqualTo("messaggio di errore");
        assertThat(e.path("attempts").asInt()).isEqualTo(3);
        assertThat(e.path("retryable").asBoolean(false)).isTrue();
        assertThat(e.path("memberId").asString()).isEqualTo("MBR-000002");
        assertThat(e.path("correlationId").asString()).isEqualTo(cor);
        assertThat(e.path("status").asString()).isEqualTo("OPEN");
        assertThat(e.path("payload").path("id").asString()).isEqualTo(ev);
    }

    @Test
    @DisplayName("[TB-INS-DIG-002] solo intestazioni kafka_dlt-* del recoverer standard (AMBIGUO, ramo senza specifica): campi letti da queste")
    void ingestSpringHeaders() {
        // Q-316 DECISA (TB-INS-DIG-002) (docs/04 §5 nomina solo gli header lh-*)
        String ev = uid("EVT-DIG");
        Map<String, String> h = new LinkedHashMap<>();
        h.put("kafka_dlt-original-topic", "lh.facts.v1");
        h.put("kafka_dlt-original-consumer-group", "lh-tb-dig-002");
        h.put("kafka_dlt-exception-cause-fqcn", "java.lang.IllegalStateException");
        h.put("kafka_dlt-exception-message", "boom");
        publish("lh.dlq.v1", "k", mapper.writeValueAsString(envelope(ev, FACT + "tier.upgraded",
                "urn:loyaltyhub:service:wallet", "member:MBR-000003", uid("COR"), null, Instant.now(), Map.of())), h, null);
        JsonNode e = awaitEntry("lh-tb-dig-002", ev);
        assertThat(e.path("originalTopic").asString()).isEqualTo("lh.facts.v1");
        assertThat(e.path("errorClass").asString()).isEqualTo("java.lang.IllegalStateException");
        assertThat(e.path("errorCode").asString()).isEqualTo("IllegalStateException");
        assertThat(e.path("errorMessage").asString()).isEqualTo("boom");
        assertThat(e.path("family").asString()).isEqualTo("FACT");
    }

    @Test
    @DisplayName("[TB-INS-DIG-003] senza lh-error-code (AMBIGUO): errorCode = nome semplice della classe d'errore")
    void ingestCodeFromClass() {
        // Q-316 DECISA (TB-INS-DIG-003)
        String ev = uid("EVT-DIG");
        publish("lh.dlq.v1", "k", actionEnvelope(ev, uid("COR"), "member:MBR-000002"),
                lhHeaders("lh.actions.v1", "lh-tb-dig-003", null, "a.b.FooException", "1", "false"), null);
        assertThat(awaitEntry("lh-tb-dig-003", ev).path("errorCode").asString()).isEqualTo("FooException");
    }

    @Test
    @DisplayName("[TB-INS-DIG-004] senza consumer (AMBIGUO): consumer = unknown")
    void ingestNoConsumer() {
        // Q-316 DECISA (TB-INS-DIG-004)
        String ev = uid("EVT-DIG");
        publish("lh.dlq.v1", "k", actionEnvelope(ev, uid("COR"), "member:MBR-000002"),
                lhHeaders("lh.actions.v1", null, "X", "x.X", "1", "false"), null);
        assertThat(awaitEntry("unknown", ev).path("consumer").asString()).isEqualTo("unknown");
    }

    @Test
    @DisplayName("[TB-INS-DIG-005] tentativi non numerici e retryable assente (AMBIGUO): campi nulli")
    void ingestBadAttempts() {
        // Q-316 DECISA (TB-INS-DIG-005)
        String ev = uid("EVT-DIG");
        publish("lh.dlq.v1", "k", actionEnvelope(ev, uid("COR"), "member:MBR-000002"),
                lhHeaders("lh.actions.v1", "lh-tb-dig-005", "X", "x.X", "tre", null), null);
        JsonNode e = awaitEntry("lh-tb-dig-005", ev);
        assertThat(e.path("attempts").isMissingNode() || e.path("attempts").isNull()).isTrue();
        assertThat(e.path("retryable").isMissingNode() || e.path("retryable").isNull()).isTrue();
    }

    @Test
    @DisplayName("[TB-INS-DIG-006] valore non JSON: payload {raw}, famiglia dal topic d'origine, id sintetico dlq-<topic>-<partizione>-<offset>")
    void ingestRaw() {
        String raw = "questo non è json " + uid("R");
        publish("lh.dlq.v1", "k", raw, lhHeaders("lh.actions.v1", "lh-tb-dig-006", "JacksonException", "x.J", "3", "true"), null);
        JsonNode e = awaitValue("voce grezza", () -> {
            for (JsonNode i : entriesOf("lh-tb-dig-006", null)) {
                if (raw.equals(i.path("payload").path("raw").asString())) {
                    return i;
                }
            }
            return null;
        }, 20_000);
        assertThat(e.path("family").asString()).isEqualTo("ACTION");
        assertThat(e.path("eventId").asString()).startsWith("dlq-lh.dlq.v1-0-");
        assertThat(e.path("reprocessable").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("[TB-INS-DIG-007] valore vuoto: payload {raw: \"\"}, voce registrata")
    void ingestEmpty() {
        publish("lh.dlq.v1", "k", "", lhHeaders("lh.effects.v1", "lh-tb-dig-007", "EMPTY", "x.E", "1", "false"), null);
        JsonNode e = awaitValue("voce vuota", () -> {
            List<JsonNode> l = entriesOf("lh-tb-dig-007", null);
            return l.isEmpty() ? null : l.get(0);
        }, 20_000);
        assertThat(e.path("payload").path("raw").asString()).isEmpty();
        assertThat(e.path("family").asString()).isEqualTo("EFFECT");
    }

    @Test
    @DisplayName("[TB-INS-DIG-008] tipo non riconoscibile con topic d'origine lh.facts.v1: famiglia FACT")
    void ingestUnknownTypeFamilyFromTopic() {
        String ev = uid("EVT-DIG");
        publish("lh.dlq.v1", "k", mapper.writeValueAsString(envelope(ev, "com.example.strano", "urn:x", "member:MBR-000002",
                uid("COR"), null, Instant.now(), Map.of())), lhHeaders("lh.facts.v1", "lh-tb-dig-008", "X", "x.X", "1", "false"), null);
        JsonNode e = awaitEntry("lh-tb-dig-008", ev);
        assertThat(e.path("family").asString()).isEqualTo("FACT");
        assertThat(e.path("reprocessable").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("[TB-INS-DIG-009] né tipo né topic d'origine: famiglia UNKNOWN, non riprocessabile")
    void ingestUnknownFamily() {
        String ev = uid("EVT-DIG");
        ObjectNode env = envelope(ev, "x", "urn:x", "member:MBR-000002", uid("COR"), null, Instant.now(), Map.of());
        env.remove("type");
        publish("lh.dlq.v1", "k", mapper.writeValueAsString(env), lhHeaders(null, "lh-tb-dig-009", "X", "x.X", "1", "false"), null);
        JsonNode e = awaitEntry("lh-tb-dig-009", ev);
        assertThat(e.path("family").asString()).isEqualTo("UNKNOWN");
        assertThat(e.path("reprocessable").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("[TB-INS-DIG-010] tipo d'azione arrivato da un topic diverso: la famiglia viene dal tipo (ACTION, riprocessabile)")
    void ingestTypeWins() {
        String ev = uid("EVT-DIG");
        publish("lh.dlq.v1", "k", actionEnvelope(ev, uid("COR"), "member:MBR-000002"),
                lhHeaders("lh.facts.v1", "lh-tb-dig-010", "X", "x.X", "1", "false"), null);
        JsonNode e = awaitEntry("lh-tb-dig-010", ev);
        assertThat(e.path("family").asString()).isEqualTo("ACTION");
        assertThat(e.path("reprocessable").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("[TB-INS-DIG-011] subject member:<id> ⇒ memberId; subject di configurazione ⇒ nessun membro")
    void ingestSubject() {
        String ev1 = uid("EVT-DIG");
        String ev2 = uid("EVT-DIG");
        publish("lh.dlq.v1", "k", actionEnvelope(ev1, uid("COR"), "member:MBR-000005"),
                lhHeaders("lh.actions.v1", "lh-tb-dig-011", "X", "x.X", "1", "false"), null);
        publish("lh.dlq.v1", "k", mapper.writeValueAsString(envelope(ev2, FACT + "campaign.status.changed",
                "urn:loyaltyhub:service:campaign", "CAMPAIGN:CMP-X", uid("COR"), null, Instant.now(), Map.of())),
                lhHeaders("lh.facts.v1", "lh-tb-dig-011", "X", "x.X", "1", "false"), null);
        assertThat(awaitEntry("lh-tb-dig-011", ev1).path("memberId").asString()).isEqualTo("MBR-000005");
        JsonNode e2 = awaitEntry("lh-tb-dig-011", ev2);
        assertThat(e2.path("memberId").isMissingNode() || e2.path("memberId").isNull()).isTrue();
    }

    private long dlqMetric(String day) {
        long sum = 0;
        for (JsonNode p : get("/v1/kpi/timeseries?metric=dlq&from=" + day + "&to=" + day).path("points")) {
            sum += p.path("value").asLong();
        }
        return sum;
    }

    private long topicCount(String topic) {
        for (JsonNode t : get("/v1/pipeline/status").path("topics")) {
            if (topic.equals(t.path("topic").asString())) {
                return t.path("countTotal").asLong();
            }
        }
        return 0;
    }

    @Test
    @DisplayName("[TB-INS-DIG-012] record DLQ riletto (stesso evento e consumer): una sola voce aperta, metrica dlq e statistica del topic contate una volta")
    void ingestReread() {
        String ev = uid("EVT-DIG");
        String day = "2020-02-10";
        long ts = Instant.parse(day + "T12:00:00Z").toEpochMilli();
        long metricBefore = dlqMetric(day);
        long topicBefore = topicCount("lh.dlq.v1");
        String value = actionEnvelope(ev, uid("COR"), "member:MBR-000002");
        Map<String, String> h = lhHeaders("lh.actions.v1", "lh-tb-dig-012", "X", "x.X", "1", "false");
        publish("lh.dlq.v1", "k", value, h, ts);
        publish("lh.dlq.v1", "k", value, h, ts);
        String sentinel = uid("EVT-DIG");
        publish("lh.dlq.v1", "k", actionEnvelope(sentinel, uid("COR"), "member:MBR-000002"), h,
                Instant.parse("2020-02-11T12:00:00Z").toEpochMilli());
        awaitEntry("lh-tb-dig-012", sentinel);
        assertThat(entriesOf("lh-tb-dig-012", ev)).hasSize(1);
        assertThat(dlqMetric(day) - metricBefore).as("metrica dlq del giorno").isEqualTo(1);
        assertThat(topicCount("lh.dlq.v1") - topicBefore).as("statistica di lh.dlq.v1 (voce + sentinella)").isEqualTo(2);
    }

    @Test
    @DisplayName("[TB-INS-DIG-013] stesso evento fallito in due consumer: due voci distinte")
    void ingestTwoConsumers() {
        String ev = uid("EVT-DIG");
        String value = actionEnvelope(ev, uid("COR"), "member:MBR-000002");
        publish("lh.dlq.v1", "k", value, lhHeaders("lh.actions.v1", "lh-tb-dig-013a", "X", "x.X", "1", "false"), null);
        publish("lh.dlq.v1", "k", value, lhHeaders("lh.actions.v1", "lh-tb-dig-013b", "X", "x.X", "1", "false"), null);
        awaitEntry("lh-tb-dig-013a", ev);
        awaitEntry("lh-tb-dig-013b", ev);
    }

    @Test
    @DisplayName("[TB-INS-DIG-014] nuovo fallimento dopo la chiusura della voce: si apre una seconda voce OPEN")
    void ingestAfterClose() {
        String ev = uid("EVT-DIG");
        String value = actionEnvelope(ev, uid("COR"), "member:MBR-000002");
        Map<String, String> h = lhHeaders("lh.actions.v1", "lh-tb-dig-014", "X", "x.X", "1", "false");
        publish("lh.dlq.v1", "k", value, h, null);
        String first = awaitEntry("lh-tb-dig-014", ev).path("id").asString();
        assertThat(call("POST", "/v1/dlq/" + first + "/discard", actor("ADMIN"), Map.of("note", NOTE)).status()).isEqualTo(200);
        publish("lh.dlq.v1", "k", value, h, null);
        List<JsonNode> both = awaitValue("seconda voce", () -> {
            List<JsonNode> l = entriesOf("lh-tb-dig-014", ev);
            return l.size() >= 2 ? l : null;
        }, 20_000);
        assertThat(both).extracting(n -> n.path("status").asString()).containsExactlyInAnyOrder("OPEN", "DISCARDED");
    }

    @Test
    @DisplayName("[TB-INS-DIG-015] stack di 30 righe: conservate le prime 16 più il segno di troncamento")
    void ingestLongStack() {
        String ev = uid("EVT-DIG");
        StringBuilder stack = new StringBuilder("x.Boom: messaggio");
        for (int i = 1; i < 30; i++) {
            stack.append("\n\tat io.loyaltyhub.Frame").append(i).append(".run(Frame.java:").append(i).append(')');
        }
        Map<String, String> h = lhHeaders("lh.actions.v1", "lh-tb-dig-015", "X", "x.X", "1", "false");
        h.put("lh-error-stack", stack.toString());
        publish("lh.dlq.v1", "k", actionEnvelope(ev, uid("COR"), "member:MBR-000002"), h, null);
        String stored = awaitEntry("lh-tb-dig-015", ev).path("errorStack").asString();
        String[] lines = stored.split("\n");
        assertThat(lines).hasSize(17);
        assertThat(lines[15]).contains("Frame15");
        assertThat(lines[16]).isEqualTo("\t…");
    }

    @Test
    @DisplayName("[TB-INS-DIG-016] il record DLQ non entra nell'event store (Q-111): nessuna riga per il suo id, nessuna famiglia DLQ")
    void dlqNotInEventStore() {
        String ev = uid("EVT-DIG");
        String cor = uid("COR-DIG");
        publish("lh.dlq.v1", "k", actionEnvelope(ev, cor, "member:MBR-000002"),
                lhHeaders("lh.actions.v1", "lh-tb-dig-016", "X", "x.X", "1", "false"), null);
        awaitEntry("lh-tb-dig-016", ev);
        assertThat(call("GET", "/v1/events/" + ev, null, null).status()).isEqualTo(404);
        assertThat(get("/v1/events?family=DLQ&correlationId=" + cor).path("items").size()).isZero();
    }

    @Test
    @DisplayName("[TB-INS-DIG-017] il record DLQ va nel flusso live: lh-event famiglia DLQ con id della voce e sintesi «DLQ · consumer · codice»")
    void dlqOnLiveStream() throws Exception {
        String ev = uid("EVT-DIG");
        String cor = uid("COR-DIG");
        try (SseClient sse = new SseClient("?correlationId=" + cor, null, Map.of())) {
            publish("lh.dlq.v1", "k", actionEnvelope(ev, cor, "member:MBR-000002"),
                    lhHeaders("lh.actions.v1", "lh-tb-dig-017", "DEMO_POISON", "x.X", "1", "false"), null);
            String entryId = awaitEntry("lh-tb-dig-017", ev).path("id").asString();
            List<SseEvent> got = sse.take(1, 10_000);
            assertThat(got).hasSize(1);
            JsonNode data = mapper.readTree(got.get(0).data());
            assertThat(got.get(0).event()).isEqualTo("lh-event");
            assertThat(got.get(0).id()).isEqualTo(entryId);
            assertThat(data.path("family").asString()).isEqualTo("DLQ");
            assertThat(data.path("topic").asString()).isEqualTo("lh.dlq.v1");
            assertThat(data.path("summary").asString()).isEqualTo("DLQ · lh-tb-dig-017 · DEMO_POISON");
        }
    }

    @Test
    @DisplayName("[TB-INS-DIG-018] nuova voce: metrica dlq +1 nel giorno della voce e statistica di lh.dlq.v1 +1")
    void dlqMetricAndTopic() {
        String ev = uid("EVT-DIG");
        String day = "2020-03-10";
        long metricBefore = dlqMetric(day);
        long topicBefore = topicCount("lh.dlq.v1");
        publish("lh.dlq.v1", "k", actionEnvelope(ev, uid("COR"), "member:MBR-000002"),
                lhHeaders("lh.actions.v1", "lh-tb-dig-018", "X", "x.X", "1", "false"),
                Instant.parse(day + "T12:00:00Z").toEpochMilli());
        JsonNode e = awaitEntry("lh-tb-dig-018", ev);
        assertThat(e.path("firstSeenAt").asString()).isEqualTo(day + "T12:00:00Z");
        assertThat(dlqMetric(day) - metricBefore).isEqualTo(1);
        assertThat(topicCount("lh.dlq.v1") - topicBefore).isEqualTo(1);
    }
}
