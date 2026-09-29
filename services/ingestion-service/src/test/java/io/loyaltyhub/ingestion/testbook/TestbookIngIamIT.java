package io.loyaltyhub.ingestion.testbook;

import io.loyaltyhub.ingestion.testbook.TestbookIngRows.Row;
import io.loyaltyhub.testsupport.SourceActors;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testbook TB-ING, area IAM: le fonti si autenticano come utenze di integrazione con ruolo {@code SOURCE}, un client
 * per fonte ({@code src-<codice>}), e l'ingresso ({@code POST /v1/events}, {@code /v1/events/batch},
 * {@code /v1/transactions}) vale solo per la fonte del proprio client (Q-492, F2-SEC-07, F2-IAM-02, docs/18 §3.2 e
 * §3.10). Profilo {@code demo}: l'identità è {@code X-LH-Actor: SOURCE:<client-id>}. Il caso con un token vero è in
 * {@code SourceAuthOidcIT}.
 */
class TestbookIngIamIT extends TestbookIngHarness {

    @TestFactory
    Stream<DynamicTest> iam() {
        return rows("iam.csv", this::iamRow);
    }

    void iamRow(Row a) {
        String kase = a.getString(2);
        if (kase.startsWith("reach:")) {
            reach(kase);
            return;
        }
        if (kase.startsWith("batch")) {
            batch(kase);
            return;
        }
        if (kase.startsWith("txn")) {
            txn(kase);
            return;
        }
        event(kase);
    }

    // ---------- POST /v1/events ----------

    private void event(String kase) {
        String own = freshSource(true, List.of());
        String other = freshSource(true, List.of());
        Fresh m = freshMember("ACTIVE");
        String id = freshEventId();
        ObjectNode e = event(id, own, "purchase.completed", "member:" + m.memberId(), Instant.now(), purchaseData("ORD-" + id, 42));
        String actor = SourceActors.of(own);
        Response r;
        switch (kase) {
            case "own" -> {
                r = callAs("POST", "/v1/events", actor, e);
                assertOutcome(r, id, "ACCEPTED", "-");
                assertThat(publications(own, id)).isEqualTo(1);
                return;
            }
            case "other" -> e.put("source", URN + other);
            case "noPrefix" -> actor = "SOURCE:" + own;
            case "onlyPrefix" -> actor = "SOURCE:src-";
            case "suffix" -> e.put("source", URN + own + ":extra");
            case "upper" -> e.put("source", URN + own.toUpperCase());
            case "adminAny" -> {
                r = callAs("POST", "/v1/events", "ADMIN:marta.admin", e.put("source", URN + other));
                assertOutcome(r, id, "ACCEPTED", "-");
                assertThat(publications(other, id)).isEqualTo(1);
                return;
            }
            case "role:MARKETING", "role:LEGAL", "role:CARE", "role:ANALYST" ->
                    actor = kase.substring(5) + ":tb.user";
            case "role:NONE" -> actor = null;
            case "short" -> {
                e.put("source", own);
                r = callAs("POST", "/v1/events", actor, e);
                assertRejected(r, 400, "BAD_REQUEST", id);
                return;
            }
            case "noSource" -> {
                e.remove("source");
                r = callAs("POST", "/v1/events", actor, e);
                assertRejected(r, 400, "BAD_REQUEST", id);
                return;
            }
            case "reprocess" -> {
                r = callAs("POST", "/v1/events", actor, e, Map.of("X-LH-Reprocess", "DLQ-TB"));
                assertRejected(r, 403, "FORBIDDEN_ROLE", id);
                return;
            }
            default -> throw new IllegalArgumentException(kase);
        }
        r = callAs("POST", "/v1/events", actor, e);
        boolean mismatch = List.of("other", "noPrefix", "onlyPrefix", "suffix", "upper").contains(kase);
        assertRejected(r, 403, mismatch ? "SOURCE_MISMATCH" : "FORBIDDEN_ROLE", id);
        assertThat(rows(own, id) + rows(other, id)).as("nulla salvato").isZero();
    }

    // ---------- POST /v1/events/batch ----------

    private void batch(String kase) {
        String own = freshSource(true, List.of());
        String other = freshSource(true, List.of());
        Fresh m = freshMember("ACTIVE");
        String id1 = freshEventId();
        String id2 = freshEventId();
        ObjectNode e1 = event(id1, own, "purchase.completed", "member:" + m.memberId(), Instant.now(), purchaseData("O-" + id1, 10));
        ObjectNode e2 = event(id2, own, "purchase.completed", "member:" + m.memberId(), Instant.now(), purchaseData("O-" + id2, 20));
        String actor = SourceActors.of(own);
        switch (kase) {
            case "batchOwn" -> {
                Response r = callAs("POST", "/v1/events/batch", actor, List.of(e1, e2));
                assertBatch(r, 202, "ACCEPTED", "ACCEPTED");
                assertThat(publicationsById(id1) + publicationsById(id2)).isEqualTo(2);
            }
            case "batchMixed" -> {
                e2.put("source", URN + other);
                Response r = callAs("POST", "/v1/events/batch", actor, List.of(e1, e2));
                assertThat(r.status()).as("HTTP (corpo: %s)", r.body()).isEqualTo(403);
                assertThat(r.text("code")).isEqualTo("SOURCE_MISMATCH");
                assertThat(rowsByEventId(id1) + rowsByEventId(id2)).as("nulla salvato").isZero();
                assertThat(publicationsById(id1) + publicationsById(id2)).as("nulla pubblicato").isZero();
            }
            case "batchNoSource" -> {
                e2.remove("source");
                Response r = callAs("POST", "/v1/events/batch", actor, List.of(e1, e2));
                assertBatch(r, 202, "ACCEPTED", "INVALID");
                assertThat(publicationsById(id1)).isEqualTo(1);
                assertThat(rowsByEventId(id2)).as("elemento non valido: nulla salvato").isZero();
            }
            case "batchAdmin" -> {
                e2.put("source", URN + other);
                Response r = callAs("POST", "/v1/events/batch", "ADMIN:marta.admin", List.of(e1, e2));
                assertBatch(r, 202, "ACCEPTED", "ACCEPTED");
                assertThat(publicationsById(id1) + publicationsById(id2)).isEqualTo(2);
            }
            case "batchAnonymous" -> {
                Response r = callAs("POST", "/v1/events/batch", null, List.of(e1, e2));
                assertThat(r.status()).as("HTTP (corpo: %s)", r.body()).isEqualTo(403);
                assertThat(r.text("code")).isEqualTo("FORBIDDEN_ROLE");
                assertThat(rowsByEventId(id1) + rowsByEventId(id2)).as("nulla salvato").isZero();
                assertThat(publicationsById(id1) + publicationsById(id2)).as("nulla pubblicato").isZero();
            }
            default -> throw new IllegalArgumentException(kase);
        }
    }

    private void assertBatch(Response r, int http, String first, String second) {
        assertThat(r.status()).as("HTTP (corpo: %s)", r.body()).isEqualTo(http);
        JsonNode items = r.body().path("items");
        assertThat(items.size()).isEqualTo(2);
        assertThat(items.get(0).path("status").asString()).as("elemento 0").isEqualTo(first);
        assertThat(items.get(1).path("status").asString()).as("elemento 1").isEqualTo(second);
    }

    // ---------- POST /v1/transactions ----------

    private void txn(String kase) {
        String own = freshSource(true, List.of());
        String other = freshSource(true, List.of());
        Fresh m = freshMember("ACTIVE");
        String orderId = "TB-AUT-" + seq() + "-" + System.nanoTime() % 100_000;
        String eventId = "txn-" + orderId;
        ObjectNode t = mapper.createObjectNode();
        t.put("source", own);
        t.put("orderId", orderId);
        t.put("memberRef", "member:" + m.memberId());
        t.put("amount", 64.9);
        t.put("currency", "EUR");
        t.put("occurredAt", Instant.now().toString());
        String actor = SourceActors.of(own);
        switch (kase) {
            case "txnOwn" -> {
                Response r = callAs("POST", "/v1/transactions", actor, t);
                assertOutcome(r, eventId, "ACCEPTED", "-");
                assertThat(publications(own, eventId)).isEqualTo(1);
                return;
            }
            case "txnAdmin" -> {
                t.put("source", other);
                Response r = callAs("POST", "/v1/transactions", "ADMIN:marta.admin", t);
                assertOutcome(r, eventId, "ACCEPTED", "-");
                assertThat(publications(other, eventId)).isEqualTo(1);
                return;
            }
            case "txnOther" -> t.put("source", other);
            case "txnAnonymous" -> actor = null;
            case "txnAnalyst" -> actor = "ANALYST:sara.analyst";
            default -> throw new IllegalArgumentException(kase);
        }
        Response r = callAs("POST", "/v1/transactions", actor, t);
        assertRejected(r, 403, kase.equals("txnOther") ? "SOURCE_MISMATCH" : "FORBIDDEN_ROLE", eventId);
    }

    // ---------- altri endpoint: mai raggiungibili da SOURCE ----------

    private void reach(String kase) {
        String[] parts = kase.split(":", 3);
        Object body = parts[1].equals("GET") ? null : Map.of();
        Response r = callAs(parts[1], parts[2], SourceActors.of("ecommerce"), body);
        assertThat(r.status()).as("HTTP (corpo: %s)", r.body()).isEqualTo(403);
        assertThat(r.text("code")).isEqualTo("FORBIDDEN_ROLE");
        // Lo stesso endpoint risponde a ADMIN (non è un 404 mascherato).
        Response admin = callAs(parts[1], parts[2], "ADMIN:marta.admin", body);
        assertThat(admin.status()).as("ADMIN, corpo: %s", admin.body()).isNotEqualTo(403).isNotEqualTo(404);
    }

    // ---------- verifiche comuni ----------

    /** Risposta di rifiuto (problem+json con {@code code}) e nulla salvato né pubblicato per l'id. */
    private void assertRejected(Response r, int http, String code, String eventId) {
        assertThat(r.status()).as("HTTP (corpo: %s)", r.body()).isEqualTo(http);
        assertThat(r.body().path("status").asInt()).as("problem RFC 9457").isEqualTo(http);
        assertThat(r.text("code")).as("code (corpo: %s)", r.body()).isEqualTo(code);
        assertThat(rowsByEventId(eventId)).as("nulla salvato").isZero();
        assertThat(publicationsById(eventId)).as("nulla pubblicato").isZero();
        if (http == 403) {
            // Il messaggio non riporta il valore dichiarato dal chiamante.
            assertThat(r.body().path("detail").asString()).doesNotContain(URN);
        }
    }
}
