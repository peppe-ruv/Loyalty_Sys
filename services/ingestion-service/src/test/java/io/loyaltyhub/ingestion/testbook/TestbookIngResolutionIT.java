package io.loyaltyhub.ingestion.testbook;

import io.loyaltyhub.common.event.LhEvent;
import io.loyaltyhub.common.event.LhEventTypes;
import io.loyaltyhub.common.ids.Ulid;
import io.loyaltyhub.ingestion.domain.EventType;
import io.loyaltyhub.ingestion.infra.EventTypeRepository;
import io.loyaltyhub.ingestion.infra.MemberErasureRepository;
import io.loyaltyhub.ingestion.messaging.FactsHandler;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import io.loyaltyhub.ingestion.testbook.TestbookIngRows.Row;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testbook TB-ING — eventi respinti e non abbinati: <em>Riprova</em>, <em>Abbina</em>, abbinamento automatico
 * (docs/testbook/TB-ING-ingresso.md, aree RES e AUT; ingestion §3, F-ING-04, F-ING-09, Q-114…Q-119).
 */
class TestbookIngResolutionIT extends TestbookIngHarness {

    private static final String CARE = "CARE:paolo.care";
    private static final String ADMIN = "ADMIN:marta.admin";

    @Autowired EventTypeRepository eventTypes;
    @Autowired MemberErasureRepository erasure;
    @Autowired FactsHandler facts;

    /** Una riga parcheggiata o respinta: fonte, id dell'evento, id della riga. */
    record Parked(String source, String eventId, String rowId, ObjectNode event) {
    }

    private Parked park(String src, String subject, String type, JsonNode data, Instant time, String expectedStatus) {
        String id = freshEventId();
        ObjectNode e = event(id, src, type, subject, time, data);
        Response r = postEvent(e);
        assertThat(r.text("status")).as("stato iniziale (corpo: %s)", r.body()).isEqualTo(expectedStatus);
        return new Parked(src, id, rowId(src, id), e);
    }

    private Parked parkPurchase(String src, String subject, String expectedStatus) {
        return park(src, subject, "purchase.completed", purchaseData("ORD-R", 25), Instant.now(), expectedStatus);
    }

    private Response retry(String rowId, String actor) {
        return call("POST", "/v1/inbound-events/" + rowId + "/retry", actor, null);
    }

    private Response match(String rowId, String actor, Object body) {
        return call("POST", "/v1/inbound-events/" + rowId + "/match", actor, body);
    }

    private static String actorOf(String csv) {
        return "-".equals(csv) ? null : csv;
    }

    // ---------- RES: guardie di ruolo ----------

    @TestFactory
    Stream<DynamicTest> guard() {
        return rows("res-guard.csv", this::guardRow);
    }

    // TESTBOOK: ambiguo, vedi TB-ING-RES-007, TB-ING-RES-014 (atteso = comportamento attuale, marcato AMBIGUO nel CSV)
    void guardRow(Row a) {
        String action = a.getString(2), actor = actorOf(a.getString(3));
        int http = a.getInteger(4);
        String code = a.getString(5), after = a.getString(6);
        String src = freshSource(true, List.of());
        Fresh later = freshMember(null);
        Parked p = parkPurchase(src, "external:" + later.externalId(), "UNMATCHED");
        Response r;
        if (action.equals("retry")) {
            members.upsert(later.memberId(), later.externalId(), later.email(), "ACTIVE");
            r = retry(p.rowId(), actor);
        } else {
            r = match(p.rowId(), actor, Map.of("memberId", freshMember("ACTIVE").memberId()));
        }
        assertThat(r.status()).as("HTTP (corpo: %s)", r.body()).isEqualTo(http);
        if (http != 200) {
            assertThat(r.text("code")).isEqualTo(code);
        } else {
            assertThat(r.text("resolvedBy")).isEqualTo(actor);
            List<JsonNode> audit = audits("inbound_event:" + p.rowId());
            assertThat(audit).hasSize(1);
            assertThat(audit.getFirst().path("lhactor").asString()).isEqualTo(actor);
        }
        assertThat(column(p.rowId(), "status")).isEqualTo(after);
        assertThat(publicationsById(p.eventId())).isEqualTo("ACCEPTED".equals(after) ? 1 : 0);
    }

    // ---------- RES: stato della riga × azione ----------

    @TestFactory
    Stream<DynamicTest> state() {
        return rows("res-state.csv", this::stateRow);
    }

    void stateRow(Row a) {
        String initial = a.getString(2), action = a.getString(3);
        int http = a.getInteger(4);
        String code = a.getString(5), after = a.getString(6);
        String src = freshSource(true, List.of());
        Fresh m = freshMember("ACTIVE");
        String rowId;
        String eventId = null;
        switch (initial) {
            case "ACCEPTED" -> {
                Parked p = parkPurchase(src, "member:" + m.memberId(), "ACCEPTED");
                rowId = p.rowId();
                eventId = p.eventId();
            }
            case "DUPLICATE" -> {
                Parked p = parkPurchase(src, "member:" + m.memberId(), "ACCEPTED");
                assertThat(postEvent(p.event()).text("status")).isEqualTo("DUPLICATE");
                rowId = jdbc.sql("SELECT id FROM inbound_event WHERE source_code = ? AND event_id = ? AND status = 'DUPLICATE'")
                        .params(src, p.eventId()).query(String.class).single();
                eventId = p.eventId();
            }
            case "REJECTED" -> {
                Fresh blocked = freshMember("BLOCKED");
                Parked p = parkPurchase(src, "member:" + blocked.memberId(), "REJECTED");
                members.upsert(blocked.memberId(), blocked.externalId(), blocked.email(), "ACTIVE");
                rowId = p.rowId();
                eventId = p.eventId();
            }
            case "UNMATCHED" -> {
                Fresh later = freshMember(null);
                Parked p = parkPurchase(src, "external:" + later.externalId(), "UNMATCHED");
                if (action.equals("retry")) {
                    members.upsert(later.memberId(), later.externalId(), later.email(), "ACTIVE");
                }
                rowId = p.rowId();
                eventId = p.eventId();
            }
            default -> rowId = "NOPE-" + seq();
        }
        Response r = action.equals("retry") ? retry(rowId, ADMIN) : match(rowId, ADMIN, Map.of("memberId", m.memberId()));
        assertThat(r.status()).as("HTTP (corpo: %s)", r.body()).isEqualTo(http);
        if (http != 200) {
            assertThat(r.text("code")).isEqualTo(code);
        }
        if (eventId != null) {
            assertThat(column(rowId, "status")).isEqualTo(after);
            long expected = "ACCEPTED".equals(after) || "DUPLICATE".equals(after) ? 1 : 0;
            assertThat(publicationsById(eventId)).as("nessuna doppia pubblicazione").isEqualTo(expected);
        }
    }

    // ---------- RES: esiti della riprova ----------

    @TestFactory
    Stream<DynamicTest> retryOutcome() {
        return rows("res-retry.csv", this::retryOutcomeRow);
    }

    void retryOutcomeRow(Row a) throws Exception {
        String kase = a.getString(2);
        String src = freshSource(true, List.of());
        Fresh m = freshMember("ACTIVE");
        switch (kase) {
            case "sourceStillOff", "sourceOn" -> {
                setSource(src, false, List.of());
                Parked p = parkPurchase(src, "member:" + m.memberId(), "REJECTED");
                if (kase.equals("sourceOn")) {
                    setSource(src, true, List.of());
                    expectRetry(p, "ACCEPTED", null, 1);
                    assertThat(column(p.rowId(), "resolution")).isEqualTo("RETRY");
                    assertThat(published(src, p.eventId()).path("id").asString()).isEqualTo(p.eventId());
                } else {
                    expectRetry(p, "REJECTED", "SOURCE_DISABLED", 0);
                }
            }
            case "typeStillOff", "typeOn" -> {
                String type = customType(false, "{\"type\":\"object\"}");
                Parked p = park(src, "member:" + m.memberId(), type, json("{}"), Instant.now(), "REJECTED");
                if (kase.equals("typeOn")) {
                    saveType(type, true, "{\"type\":\"object\"}");
                    expectRetry(p, "ACCEPTED", null, 1);
                } else {
                    expectRetry(p, "REJECTED", "UNKNOWN_TYPE", 0);
                }
            }
            case "allowFixed" -> {
                setSource(src, true, List.of("survey.completed"));
                Parked p = parkPurchase(src, "member:" + m.memberId(), "REJECTED");
                setSource(src, true, List.of("survey.completed", "purchase.completed"));
                expectRetry(p, "ACCEPTED", null, 1);
            }
            case "dataStill" -> {
                Parked p = park(src, "member:" + m.memberId(), "purchase.completed",
                        json("{\"orderId\":\"O\",\"currency\":\"EUR\"}"), Instant.now(), "REJECTED");
                expectRetry(p, "REJECTED", "INVALID_DATA", 0);
            }
            case "schemaRelaxed" -> {
                String type = customType(true, "{\"type\":\"object\",\"required\":[\"meterId\"]}");
                Parked p = park(src, "member:" + m.memberId(), type, json("{}"), Instant.now(), "REJECTED");
                saveType(type, true, "{\"type\":\"object\"}");
                expectRetry(p, "ACCEPTED", null, 1);
            }
            case "timeNowOk" -> {
                Instant t = Instant.now().truncatedTo(ChronoUnit.SECONDS);
                CLOCK.set(t);
                Parked p = park(src, "member:" + m.memberId(), "purchase.completed", purchaseData("O", 3),
                        t.plus(Duration.ofMinutes(10)), "REJECTED");
                CLOCK.set(t.plus(Duration.ofMinutes(10)));
                expectRetry(p, "ACCEPTED", null, 1);
            }
            case "timeTooOld" -> {
                Parked p = park(src, "member:" + m.memberId(), "purchase.completed", purchaseData("O", 3),
                        Instant.now().minus(Duration.ofDays(31)), "REJECTED");
                expectRetry(p, "REJECTED", "INVALID_TIME", 0);
            }
            case "memberStillBlocked", "memberReactivated" -> {
                Fresh b = freshMember("BLOCKED");
                Parked p = parkPurchase(src, "member:" + b.memberId(), "REJECTED");
                if (kase.equals("memberReactivated")) {
                    members.upsert(b.memberId(), b.externalId(), b.email(), "ACTIVE");
                    expectRetry(p, "ACCEPTED", null, 1);
                } else {
                    expectRetry(p, "REJECTED", "MEMBER_NOT_ACTIVE", 0);
                }
            }
            case "unmatchedNoMember" -> {
                Parked p = parkPurchase(src, "external:" + freshMember(null).externalId(), "UNMATCHED");
                expectRetry(p, "UNMATCHED", null, 0);
                assertThat(column(p.rowId(), "resolution")).isNull();
            }
            case "unmatchedMemberArrived", "unmatchedMemberBlocked" -> {
                Fresh later = freshMember(null);
                Parked p = parkPurchase(src, "external:" + later.externalId(), "UNMATCHED");
                boolean blocked = kase.endsWith("Blocked");
                members.upsert(later.memberId(), later.externalId(), later.email(), blocked ? "BLOCKED" : "ACTIVE");
                Response r = expectRetry(p, blocked ? "REJECTED" : "ACCEPTED", blocked ? "MEMBER_NOT_ACTIVE" : null, blocked ? 0 : 1);
                assertThat(r.text("memberId")).isEqualTo(later.memberId());
            }
            case "acceptedElsewhere" -> {
                Fresh b = freshMember("BLOCKED");
                Parked p = parkPurchase(src, "member:" + b.memberId(), "REJECTED");
                members.upsert(b.memberId(), b.externalId(), b.email(), "ACTIVE");
                assertThat(postEvent(p.event()).text("status")).isEqualTo("ACCEPTED");
                Response r = retry(p.rowId(), CARE);
                assertThat(r.status()).isEqualTo(200);
                assertThat(r.text("status")).isEqualTo("DUPLICATE");
                assertThat(column(p.rowId(), "status")).isEqualTo("DUPLICATE");
                assertThat(publicationsById(p.eventId())).isEqualTo(1);
            }
            case "dedupAfterRetry" -> {
                Fresh later = freshMember(null);
                Parked p = parkPurchase(src, "external:" + later.externalId(), "UNMATCHED");
                members.upsert(later.memberId(), later.externalId(), later.email(), "ACTIVE");
                expectRetry(p, "ACCEPTED", null, 1);
                assertThat(postEvent(p.event()).text("status")).isEqualTo("DUPLICATE");
                assertThat(publicationsById(p.eventId())).isEqualTo(1);
            }
            case "identity" -> {
                Fresh later = freshMember(null);
                String subject = "external:" + later.externalId();
                Parked p = parkPurchase(src, subject, "UNMATCHED");
                members.upsert(later.memberId(), later.externalId(), later.email(), "ACTIVE");
                Response r = expectRetry(p, "ACCEPTED", null, 1);
                JsonNode pub = published(src, p.eventId());
                assertThat(pub.path("id").asString()).isEqualTo(p.eventId());
                assertThat(pub.path("lhcorrelationid").asString()).isEqualTo(p.eventId());
                assertThat(pub.path("lhhop").asInt(-1)).isZero();
                assertThat(pub.path("subject").asString()).isEqualTo("member:" + later.memberId());
                assertThat(column(p.rowId(), "subject")).as("subject originale conservato").isEqualTo(subject);
                assertThat(r.body().path("payload").path("subject").asString()).isEqualTo("member:" + later.memberId());
                assertThat(r.text("correlationId")).isEqualTo(p.eventId());
            }
            case "concurrent" -> {
                Fresh later = freshMember(null);
                Parked p = parkPurchase(src, "external:" + later.externalId(), "UNMATCHED");
                members.upsert(later.memberId(), later.externalId(), later.email(), "ACTIVE");
                ExecutorService pool = Executors.newFixedThreadPool(2);
                try {
                    List<Callable<Response>> tasks = List.of(() -> retry(p.rowId(), CARE), () -> retry(p.rowId(), ADMIN));
                    Map<Integer, Integer> codes = new HashMap<>();
                    for (Future<Response> f : pool.invokeAll(tasks)) {
                        codes.merge(f.get().status(), 1, Integer::sum);
                    }
                    assertThat(codes).containsEntry(200, 1).containsEntry(409, 1);
                } finally {
                    pool.shutdownNow();
                }
                assertThat(publicationsById(p.eventId())).isEqualTo(1);
            }
            case "auditOk", "auditKo" -> {
                Fresh later = freshMember(null);
                Parked p = parkPurchase(src, "external:" + later.externalId(), "UNMATCHED");
                if (kase.equals("auditOk")) {
                    members.upsert(later.memberId(), later.externalId(), later.email(), "ACTIVE");
                    expectRetry(p, "ACCEPTED", null, 1);
                } else {
                    expectRetry(p, "UNMATCHED", null, 0);
                }
                List<JsonNode> audit = audits("inbound_event:" + p.rowId());
                assertThat(audit).as("voce di audit della riprova").hasSize(1);
                JsonNode e = audit.getFirst();
                assertThat(e.path("type").asString()).isEqualTo("io.loyaltyhub.audit.entry");
                assertThat(e.path("lhactor").asString()).isEqualTo(CARE);
                assertThat(e.path("data").path("action").asString()).isEqualTo("TRANSITION");
                if (kase.equals("auditOk")) {
                    assertThat(e.path("data").path("before").path("status").asString()).isEqualTo("UNMATCHED");
                    assertThat(e.path("data").path("after").path("status").asString()).isEqualTo("ACCEPTED");
                }
            }
            default -> throw new IllegalArgumentException(kase);
        }
    }

    private Response expectRetry(Parked p, String status, String code, long publications) {
        Response r = retry(p.rowId(), CARE);
        assertThat(r.status()).as("HTTP (corpo: %s)", r.body()).isEqualTo(200);
        assertThat(r.text("status")).isEqualTo(status);
        assertThat(r.text("rejectCode")).isEqualTo(code);
        assertThat(column(p.rowId(), "status")).isEqualTo(status);
        assertThat(rows(p.source(), p.eventId())).as("la riga è aggiornata, non duplicata").isEqualTo(1);
        assertThat(publicationsById(p.eventId())).isEqualTo(publications);
        return r;
    }

    private String customType(boolean enabled, String schema) {
        String code = "tbres.c" + seq() + ".sent";
        saveType(code, enabled, schema);
        return code;
    }

    private void saveType(String code, boolean enabled, String schema) {
        eventTypes.save(new EventType(code, "Tipo testbook", null, EventType.CUSTOM, "ENGAGEMENT", schema, "{}", enabled, null));
    }

    // ---------- RES: abbina ----------

    @TestFactory
    Stream<DynamicTest> matchOutcome() {
        return rows("res-match.csv", this::matchOutcomeRow);
    }

    // TESTBOOK: ambiguo, vedi TB-ING-RES-050…TB-ING-RES-054 (atteso = comportamento attuale, marcato AMBIGUO nel CSV)
    void matchOutcomeRow(Row a) {
        String kase = a.getString(2);
        String src = freshSource(true, List.of());
        String subject = "external:" + freshMember(null).externalId();
        Parked p = parkPurchase(src, subject, "UNMATCHED");
        switch (kase) {
            case "active", "seedMarco", "trimmed" -> {
                String target = kase.equals("seedMarco") ? "MBR-000002" : freshMember("ACTIVE").memberId();
                Response r = match(p.rowId(), CARE, Map.of("memberId", kase.equals("trimmed") ? "  " + target + " " : target));
                assertThat(r.status()).as("HTTP (corpo: %s)", r.body()).isEqualTo(200);
                assertThat(r.text("status")).isEqualTo("ACCEPTED");
                assertThat(r.text("memberId")).isEqualTo(target);
                assertThat(publicationsById(p.eventId())).isEqualTo(1);
                if (kase.equals("active")) {
                    assertThat(r.text("resolution")).isEqualTo("MANUAL_MATCH");
                    assertThat(r.text("resolvedBy")).isEqualTo(CARE);
                    assertThat(r.text("subject")).as("subject originale conservato").isEqualTo(subject);
                    assertThat(published(src, p.eventId()).path("subject").asString()).isEqualTo("member:" + target);
                    List<JsonNode> audit = audits("inbound_event:" + p.rowId());
                    assertThat(audit).hasSize(1);
                    assertThat(audit.getFirst().path("data").path("action").asString()).isEqualTo("TRANSITION");
                    assertThat(audit.getFirst().path("lhactor").asString()).isEqualTo(CARE);
                }
            }
            case "inactive", "blocked", "anonymized", "notIndexed", "blank", "noBody", "emptyObject" -> {
                Object body;
                String code;
                switch (kase) {
                    case "inactive" -> {
                        body = Map.of("memberId", freshMember("INACTIVE").memberId());
                        code = "MEMBER_NOT_ACTIVE";
                    }
                    case "blocked" -> {
                        body = Map.of("memberId", "MBR-000008");
                        code = "MEMBER_NOT_ACTIVE";
                    }
                    case "anonymized" -> {
                        Fresh an = freshMember("ACTIVE");
                        erasure.erase(an.memberId());
                        body = Map.of("memberId", an.memberId());
                        code = "MEMBER_NOT_ACTIVE";
                    }
                    case "notIndexed" -> {
                        body = Map.of("memberId", freshMember(null).memberId());
                        code = "MEMBER_NOT_FOUND";
                    }
                    case "blank" -> {
                        body = Map.of("memberId", "   ");
                        code = "MEMBER_REQUIRED";
                    }
                    case "noBody" -> {
                        body = null;
                        code = "MEMBER_REQUIRED";
                    }
                    default -> {
                        body = Map.of();
                        code = "MEMBER_REQUIRED";
                    }
                }
                Response r = match(p.rowId(), CARE, body);
                assertThat(r.status()).as("HTTP (corpo: %s)", r.body()).isEqualTo(422);
                assertThat(r.text("code")).isEqualTo(code);
                assertThat(column(p.rowId(), "status")).isEqualTo("UNMATCHED");
                assertThat(publicationsById(p.eventId())).isZero();
            }
            case "otherStepFails" -> {
                setSource(src, false, List.of());
                Response r = match(p.rowId(), CARE, Map.of("memberId", freshMember("ACTIVE").memberId()));
                assertThat(r.status()).isEqualTo(200);
                assertThat(r.text("status")).isEqualTo("REJECTED");
                assertThat(r.text("rejectCode")).isEqualTo("SOURCE_DISABLED");
                assertThat(publicationsById(p.eventId())).isZero();
            }
            case "thenRetry" -> {
                assertThat(match(p.rowId(), CARE, Map.of("memberId", freshMember("ACTIVE").memberId())).status()).isEqualTo(200);
                Response r = retry(p.rowId(), CARE);
                assertThat(r.status()).isEqualTo(409);
                assertThat(r.text("code")).isEqualTo("INBOUND_NOT_RETRYABLE");
                assertThat(publicationsById(p.eventId())).isEqualTo(1);
            }
            default -> throw new IllegalArgumentException(kase);
        }
    }

    // ---------- AUT: abbinamento automatico ----------

    @TestFactory
    Stream<DynamicTest> autoMatch() {
        return rows("aut.csv", this::autoMatchRow);
    }

    void autoMatchRow(Row a) {
        String form = a.getString(2), age = a.getString(3), status = a.getString(4), fact = a.getString(5),
                expected = a.getString(6);
        String src = freshSource(true, List.of());
        Fresh m = freshMember(null);
        String subject = switch (form) {
            case "external" -> "external:" + m.externalId();
            case "email" -> "email:" + m.email();
            case "emailMixed" -> "email:" + m.email().toUpperCase();
            case "member" -> "member:" + m.memberId();
            default -> throw new IllegalArgumentException(form);
        };
        Parked p = parkPurchase(src, subject, "UNMATCHED");
        Instant receivedAt;
        if (age.startsWith("@7D")) {
            Instant t = Instant.now().truncatedTo(ChronoUnit.MILLIS);
            CLOCK.set(t);
            // età = 7 giorni + scostamento ("-PT1S" ⇒ 7 g − 1 s)
            receivedAt = t.minus(Duration.ofDays(7)).minus(Duration.parse(age.substring(3)));
        } else {
            receivedAt = Instant.now().minus(Duration.parse(age));
        }
        age(p.rowId(), receivedAt);
        facts.handle(memberFact(fact.equals("registered") ? LhEventTypes.Fact.MEMBER_REGISTERED : LhEventTypes.Fact.MEMBER_UPDATED,
                m, status));
        assertThat(column(p.rowId(), "status")).isEqualTo(expected);
        assertThat(publicationsById(p.eventId())).isEqualTo("ACCEPTED".equals(expected) ? 1 : 0);
        if ("ACCEPTED".equals(expected)) {
            assertThat(column(p.rowId(), "resolution")).isEqualTo("AUTO_MATCH");
            assertThat(column(p.rowId(), "member_id")).isEqualTo(m.memberId());
        }
    }

    @TestFactory
    Stream<DynamicTest> autoMatchExtra() {
        return rows("aut-extra.csv", this::autoMatchExtraRow);
    }

    void autoMatchExtraRow(Row a) {
        String kase = a.getString(2);
        String src = freshSource(true, List.of());
        Fresh m = freshMember(null);
        String external = "external:" + m.externalId();
        switch (kase) {
            case "noKeys" -> {
                Parked p = parkPurchase(src, external, "UNMATCHED");
                facts.handle(fact(LhEventTypes.Fact.MEMBER_REGISTERED, m.memberId(), Map.of("status", "ACTIVE")));
                assertThat(column(p.rowId(), "status")).isEqualTo("UNMATCHED");
            }
            case "sourceOff" -> {
                Parked p = parkPurchase(src, external, "UNMATCHED");
                setSource(src, false, List.of());
                facts.handle(memberFact(LhEventTypes.Fact.MEMBER_REGISTERED, m, "ACTIVE"));
                assertThat(column(p.rowId(), "status")).isEqualTo("UNMATCHED");
                assertThat(publicationsById(p.eventId())).isZero();
            }
            case "typeOff" -> {
                String type = "tbaut.c" + seq() + ".sent";
                eventTypes.save(new EventType(type, "Tipo testbook", null, EventType.CUSTOM, "ENGAGEMENT",
                        "{\"type\":\"object\"}", "{}", true, null));
                Parked p = park(src, external, type, json("{}"), Instant.now(), "UNMATCHED");
                eventTypes.save(new EventType(type, "Tipo testbook", null, EventType.CUSTOM, "ENGAGEMENT",
                        "{\"type\":\"object\"}", "{}", false, null));
                facts.handle(memberFact(LhEventTypes.Fact.MEMBER_REGISTERED, m, "ACTIVE"));
                assertThat(column(p.rowId(), "status")).isEqualTo("UNMATCHED");
            }
            case "alreadyResolved" -> {
                Parked p = parkPurchase(src, external, "UNMATCHED");
                String first = freshMember("ACTIVE").memberId();
                assertThat(match(p.rowId(), ADMIN, Map.of("memberId", first)).status()).isEqualTo(200);
                facts.handle(memberFact(LhEventTypes.Fact.MEMBER_REGISTERED, m, "ACTIVE"));
                assertThat(column(p.rowId(), "member_id")).isEqualTo(first);
                assertThat(column(p.rowId(), "resolution")).isEqualTo("MANUAL_MATCH");
                assertThat(publicationsById(p.eventId())).isEqualTo(1);
            }
            case "redelivery" -> {
                Parked p = parkPurchase(src, external, "UNMATCHED");
                LhEvent<JsonNode> f = memberFact(LhEventTypes.Fact.MEMBER_REGISTERED, m, "ACTIVE");
                facts.handle(f);
                facts.handle(f);
                assertThat(column(p.rowId(), "status")).isEqualTo("ACCEPTED");
                assertThat(publicationsById(p.eventId())).isEqualTo(1);
            }
            case "limit" -> {
                List<Parked> parked = new ArrayList<>();
                Instant base = Instant.now().minus(Duration.ofHours(3));
                for (int i = 0; i < 101; i++) {
                    Parked p = parkPurchase(src, external, "UNMATCHED");
                    age(p.rowId(), base.plus(Duration.ofSeconds(i)));
                    parked.add(p);
                }
                facts.handle(memberFact(LhEventTypes.Fact.MEMBER_REGISTERED, m, "ACTIVE"));
                long accepted = parked.stream().filter(p -> "ACCEPTED".equals(column(p.rowId(), "status"))).count();
                assertThat(accepted).isEqualTo(100);
                assertThat(column(parked.getLast().rowId(), "status")).as("la più recente resta parcheggiata").isEqualTo("UNMATCHED");
            }
            case "provenance" -> {
                Parked p = parkPurchase(src, external, "UNMATCHED");
                facts.handle(memberFact(LhEventTypes.Fact.MEMBER_REGISTERED, m, "ACTIVE"));
                assertThat(column(p.rowId(), "resolution")).isEqualTo("AUTO_MATCH");
                assertThat(column(p.rowId(), "resolved_by")).isEqualTo("system");
                List<JsonNode> audit = audits("inbound_event:" + p.rowId());
                assertThat(audit).hasSize(1);
                assertThat(audit.getFirst().path("lhactor").asString()).isEqualTo("system");
            }
            case "both" -> {
                Parked byExternal = parkPurchase(src, external, "UNMATCHED");
                Parked byEmail = parkPurchase(src, "email:" + m.email(), "UNMATCHED");
                facts.handle(memberFact(LhEventTypes.Fact.MEMBER_REGISTERED, m, "ACTIVE"));
                assertThat(column(byExternal.rowId(), "status")).isEqualTo("ACCEPTED");
                assertThat(column(byEmail.rowId(), "status")).isEqualTo("ACCEPTED");
            }
            case "statusChanged" -> {
                Parked p = parkPurchase(src, external, "UNMATCHED");
                facts.handle(memberFact(LhEventTypes.Fact.MEMBER_REGISTERED, m, "BLOCKED"));
                facts.handle(fact(LhEventTypes.Fact.MEMBER_STATUS_CHANGED, m.memberId(),
                        Map.of("previousStatus", "BLOCKED", "newStatus", "ACTIVE")));
                assertThat(column(p.rowId(), "status")).isEqualTo("UNMATCHED");
                assertThat(publicationsById(p.eventId())).isZero();
            }
            default -> throw new IllegalArgumentException(kase);
        }
    }

    private void age(String rowId, Instant receivedAt) {
        jdbc.sql("UPDATE inbound_event SET received_at = ? WHERE id = ?").params(Timestamp.from(receivedAt), rowId).update();
    }

    private LhEvent<JsonNode> memberFact(String type, Fresh m, String status) {
        return fact(type, m.memberId(), Map.of("externalId", m.externalId(), "email", m.email(), "status", status));
    }

    private LhEvent<JsonNode> fact(String type, String memberId, Map<String, Object> data) {
        String id = Ulid.next(Clock.systemUTC());
        return new LhEvent<>(LhEvent.SPEC_VERSION, id, "urn:loyaltyhub:service:member", type, "member:" + memberId,
                Instant.now().truncatedTo(ChronoUnit.MILLIS), LhEvent.DATA_CONTENT_TYPE, null, LhEvent.TENANT,
                "COR-" + id, null, 0, null, mapper.valueToTree(data));
    }
}
