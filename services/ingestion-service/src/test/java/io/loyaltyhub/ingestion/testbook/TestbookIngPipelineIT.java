package io.loyaltyhub.ingestion.testbook;

import io.loyaltyhub.common.event.JsonSchemaValidator;
import io.loyaltyhub.ingestion.domain.EventType;
import io.loyaltyhub.ingestion.infra.EventTypeRepository;
import io.loyaltyhub.ingestion.infra.MemberErasureRepository;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import io.loyaltyhub.ingestion.testbook.TestbookIngRows.Row;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
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
 * Testbook TB-ING — pipeline di accettazione di {@code POST /v1/events} (docs/testbook/TB-ING-ingresso.md, aree FRM, PIP,
 * SRC, TYP, SCH, TIM, DUP, MBR, ACC, MON). Oracolo: docs/servizi/ingestion-service.md §3, §5; docs/05 §2-§3;
 * contracts/events; docs/15. Un caso = una riga dei CSV in {@code testbook/ing/}.
 */
class TestbookIngPipelineIT extends TestbookIngHarness {

    @Autowired EventTypeRepository eventTypes;
    @Autowired MemberErasureRepository erasure;

    // ---------- FRM: forma dell'envelope ----------

    @TestFactory
    Stream<DynamicTest> frm() {
        return rows("frm.csv", this::frmRow);
    }

    void frmRow(Row a) {
        String op = a.getString(2), field = a.getString(3), value = decode(a.getString(4));
        int http = a.getInteger(5);
        String status = a.getString(6), code = a.getString(7), detail = a.getString(8);

        String src = freshSource(true, List.of());
        Fresh m = freshMember("ACTIVE");
        String id = freshEventId();
        ObjectNode e = event(id, src, "purchase.completed", "member:" + m.memberId(), Instant.now(), purchaseData("ORD-" + id, 42));
        Response r;
        switch (op) {
            case "del" -> {
                e.remove(field);
                r = postEvent(e);
            }
            case "str" -> {
                e.put(field, value);
                r = postEvent(e);
            }
            case "json" -> {
                e.set(field, json(value));
                r = postEvent(e);
            }
            case "raw" -> r = call("POST", "/v1/events", null, value);
            case "ctype" -> r = postWithContentType(e, value);
            default -> throw new IllegalArgumentException(op);
        }
        assertThat(r.status()).as("HTTP (corpo: %s)", r.body()).isEqualTo(http);
        if (http == 400) {
            assertThat(r.body().path("status").asInt()).as("problem RFC 9457").isEqualTo(400);
            if (!"-".equals(detail)) {
                assertThat(r.text("detail")).as("il dettaglio indica il campo").contains(detail);
            }
            assertThat(rowsOfSource(src)).as("nulla salvato").isZero();
            assertThat(publicationsById(id)).as("nulla pubblicato").isZero();
        } else {
            assertOutcome(r, id, status, code);
        }
    }

    private Response postWithContentType(ObjectNode e, String contentType) {
        return RestClient.create("http://localhost:" + port).method(HttpMethod.POST).uri("/v1/events")
                .header("Content-Type", contentType).body(mapper.writeValueAsBytes(e))
                .exchange((req, res) -> {
                    byte[] bytes = res.getBody().readAllBytes();
                    return new Response(res.getStatusCode().value(), mapper.readTree(bytes));
                });
    }

    private long rowsOfSource(String src) {
        return jdbc.sql("SELECT count(*) FROM inbound_event WHERE source_code = ?").param(src).query(Long.class).single();
    }

    private static String decode(String v) {
        if (v == null) {
            return null;
        }
        return switch (v) {
            case "<vuoto>" -> "";
            case "<spazi>" -> "   ";
            default -> v;
        };
    }

    // ---------- PIP: ordine della pipeline (primo fallimento vince) ----------

    @TestFactory
    Stream<DynamicTest> pip() {
        return rows("pip.csv", this::pipRow);
    }

    void pipRow(Row a) {
        boolean form = yes(a, 2), source = yes(a, 3), unknown = yes(a, 4), notAllowed = yes(a, 5), data = yes(a, 6),
                time = yes(a, 7), dup = yes(a, 8), unmatched = yes(a, 9), notActive = yes(a, 10);
        int http = a.getInteger(11);
        String status = a.getString(12), code = a.getString(13);

        String src = freshSource(true, List.of());
        Fresh active = freshMember("ACTIVE");
        String id = freshEventId();
        if (dup) {
            Response pre = postEvent(event(id, src, "purchase.completed", "member:" + freshMember("ACTIVE").memberId(),
                    Instant.now(), purchaseData("ORD-PRE", 10)));
            assertThat(pre.text("status")).as("invio precedente accettato").isEqualTo("ACCEPTED");
        }
        setSource(src, !source, notAllowed ? List.of("survey.completed") : List.of());
        String subject = unmatched ? "member:" + freshMember(null).memberId()
                : notActive ? "member:" + freshMember("BLOCKED").memberId()
                : "member:" + active.memberId();
        JsonNode payload = data ? json("{\"orderId\":\"ORD-X\",\"amount\":-1,\"currency\":\"EUR\"}") : purchaseData("ORD-X", 42);
        Instant when = time ? Instant.now().plus(Duration.ofMinutes(10)) : Instant.now();
        ObjectNode e = event(id, src, unknown ? "tb.unknown.type" : "purchase.completed", subject, when, payload);
        if (form) {
            e.put("specversion", "0.3");
        }
        Response r = postEvent(e);

        long before = dup ? 1 : 0;
        if (http == 400) {
            assertThat(r.status()).as("HTTP (corpo: %s)", r.body()).isEqualTo(400);
            assertThat(rows(src, id)).as("nulla salvato").isEqualTo(before);
            assertThat(publicationsById(id)).isEqualTo(before);
            return;
        }
        assertThat(r.status()).isEqualTo(202);
        assertThat(r.text("status")).as("status (corpo: %s)", r.body()).isEqualTo(status);
        assertThat(r.text("rejectCode")).as("rejectCode (corpo: %s)", r.body()).isEqualTo(nullIfDash(code));
        assertThat(rows(src, id)).as("una riga per invio").isEqualTo(before + 1);
        assertThat(publicationsById(id)).as("pubblicazioni").isEqualTo(before + ("ACCEPTED".equals(status) ? 1 : 0));
    }

    private static boolean yes(Row a, int i) {
        return "S".equals(a.getString(i));
    }

    // ---------- SRC: fonte e tipi ammessi ----------

    @TestFactory
    Stream<DynamicTest> src() {
        return rows("src.csv", this::srcRow);
    }

    // TESTBOOK: ambiguo, vedi TB-ING-SRC-070 (atteso = comportamento attuale, marcato AMBIGUO nel CSV)
    // Q-258 DECISA: TB-ING-SRC-067 — forma breve da una fonte esterna ⇒ 400, nulla salvato (stato atteso «400»)
    void srcRow(Row a) {
        String source = a.getString(2), type = a.getString(3), status = a.getString(4), code = a.getString(5);
        String sourceValue;
        if (source.startsWith("@disabled-allowing")) {
            sourceValue = URN + freshSource(false, List.of(type));
        } else if (source.startsWith("@disabled")) {
            sourceValue = URN + freshSource(false, List.of());
        } else if (source.startsWith("@allowing")) {
            sourceValue = URN + freshSource(true, List.of(type));
        } else if (source.startsWith("@short:")) {
            sourceValue = source.substring("@short:".length());
        } else if (source.startsWith("urn:")) {
            sourceValue = source;
        } else {
            sourceValue = URN + source;
        }
        Fresh m = freshMember("ACTIVE");
        String id = freshEventId();
        ObjectNode e = event(id, "x", type, "member:" + m.memberId(), Instant.now(), sample(type));
        e.put("source", sourceValue);
        Response r = postEvent(e);
        if ("400".equals(status)) {
            assertThat(r.status()).as("HTTP (corpo: %s)", r.body()).isEqualTo(400);
            assertThat(r.body().path("status").asInt()).as("problem RFC 9457").isEqualTo(400);
            assertThat(r.text("detail")).as("il dettaglio indica il campo").contains("source");
            assertThat(jdbc.sql("SELECT count(*) FROM inbound_event WHERE event_id = ?").param(id).query(Long.class).single())
                    .as("nulla salvato").isZero();
            assertThat(publicationsById(id)).as("nulla pubblicato").isZero();
            return;
        }
        assertOutcome(r, id, status, code);
    }

    private JsonNode sample(String type) {
        return json(eventTypes.sampleData(type).orElseThrow());
    }

    // ---------- TYP: tipo noto, abilitato, forma ----------

    @TestFactory
    Stream<DynamicTest> typ() {
        return rows("typ.csv", this::typRow);
    }

    // TESTBOOK: ambiguo, vedi TB-ING-TYP-010, TB-ING-TYP-011 (atteso = comportamento attuale, marcato AMBIGUO nel CSV)
    void typRow(Row a) {
        String kase = a.getString(2), status = a.getString(3), code = a.getString(4);
        String src = "simulator";
        JsonNode data = purchaseData("ORD-T", 12);
        String type = switch (kase) {
            case "short" -> "purchase.completed";
            case "full" -> "io.loyaltyhub.action.purchase.completed";
            case "unknown" -> "tb.never.seen";
            case "fact" -> "io.loyaltyhub.fact.tier.upgraded";
            case "effect" -> "io.loyaltyhub.effect.points.grant";
            case "case" -> "Purchase.Completed";
            case "doublePrefix" -> "action.purchase.completed";
            case "customDisabled" -> customType(false, "{\"type\":\"object\"}");
            case "customEnabled" -> customType(true, "{\"type\":\"object\",\"required\":[\"meterId\"]}");
            case "noSchema" -> customType(true, null);
            case "customAllowlist" -> {
                src = "ecommerce";
                yield customType(true, "{\"type\":\"object\"}");
            }
            default -> throw new IllegalArgumentException(kase);
        };
        if (kase.startsWith("custom") || kase.equals("noSchema")) {
            data = json(kase.equals("noSchema") ? "{\"anything\":[1,\"due\",{\"x\":null}]}" : "{\"meterId\":\"MTR-1\"}");
        }
        Fresh m = freshMember("ACTIVE");
        String id = freshEventId();
        Response r = postEvent(event(id, src, type, "member:" + m.memberId(), Instant.now(), data));
        assertOutcome(r, id, status, code);
        if ("ACCEPTED".equals(status)) {
            String full = type.startsWith("io.loyaltyhub.") ? type : "io.loyaltyhub.action." + type;
            assertThat(published(src, id).path("type").asString()).as("type pubblicato completo").isEqualTo(full);
        }
    }

    private String customType(boolean enabled, String schema) {
        String code = "tbtyp.c" + seq() + ".sent";
        eventTypes.save(new EventType(code, "Tipo testbook", null, EventType.CUSTOM, "ENGAGEMENT", schema,
                schema == null ? null : "{}", enabled, null));
        return code;
    }

    // ---------- SCH: data contro lo schema ----------

    @TestFactory
    Stream<DynamicTest> sch() {
        return rows("sch.csv", this::schRow);
    }

    // Q-265 DECISA: TB-ING-SCH-083 — correctAnswers > totalQuestions ⇒ REJECTED/INVALID_DATA (vincolo tra campi)
    void schRow(Row a) {
        String type = a.getString(2), data = a.getString(3), status = a.getString(4), code = a.getString(5),
                field = a.getString(6);
        Fresh m = freshMember("ACTIVE");
        String id = freshEventId();
        Response r = postEvent(event(id, "simulator", type, "member:" + m.memberId(), Instant.now(), json(data)));
        assertOutcome(r, id, status, code);
        if ("INVALID_DATA".equals(code)) {
            assertThat(r.text("detail")).as("errore dello schema sul campo").contains(field);
            assertThat((String) lastRow(id).get("reject_detail")).as("dettaglio salvato per BO-26").contains(field);
        }
    }

    // ---------- TIM: finestra temporale ----------

    @TestFactory
    Stream<DynamicTest> tim() {
        return rows("tim.csv", this::timRow);
    }

    // TESTBOOK: ambiguo, vedi TB-ING-TIM-022, TB-ING-TIM-024 (atteso = comportamento attuale, marcato AMBIGUO nel CSV)
    void timRow(Row a) {
        Instant now = Instant.parse(a.getString(2));
        String timeSpec = a.getString(3), status = a.getString(4), code = a.getString(5);
        CLOCK.set(now);
        String time;
        if (timeSpec.startsWith("Δ")) {
            time = now.plus(Duration.parse(timeSpec.substring(1))).toString();
        } else if (timeSpec.equals("@offset")) {
            time = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(now.atOffset(ZoneOffset.ofHours(2)));
        } else if (timeSpec.equals("@lower")) {
            time = now.toString().toLowerCase();
        } else if (timeSpec.equals("@nanos")) {
            time = now.plusNanos(123_456_789).toString();
        } else {
            time = timeSpec;
        }
        Fresh m = freshMember("ACTIVE");
        String id = freshEventId();
        ObjectNode e = event(id, "simulator", "purchase.completed", "member:" + m.memberId(), now, purchaseData("ORD-T", 9));
        e.put("time", time);
        assertOutcome(postEvent(e), id, status, code);
    }

    // ---------- DUP: deduplica ----------

    @TestFactory
    Stream<DynamicTest> dup() {
        return rows("dup.csv", this::dupRow);
    }

    void dupRow(Row a) throws Exception {
        String kase = a.getString(2);
        String src = freshSource(true, List.of());
        Fresh m = freshMember("ACTIVE");
        String id = freshEventId();
        ObjectNode ev = event(id, src, "purchase.completed", "member:" + m.memberId(), Instant.now(), purchaseData("ORD-D", 30));
        switch (kase) {
            case "twice", "thrice" -> {
                assertOutcome(postEvent(ev), id, "ACCEPTED", "-");
                Response second = postEvent(ev);
                assertOutcome(second, id, "DUPLICATE", "-");
                assertThat(second.text("eventId")).isEqualTo(id);
                assertThat((String) lastRow(id).get("reject_detail")).as("spiegazione per BO-26").contains("stessa fonte");
                if (kase.equals("thrice")) {
                    assertOutcome(postEvent(ev), id, "DUPLICATE", "-");
                }
                assertThat(countStatus(src, id, "DUPLICATE")).isEqualTo(kase.equals("thrice") ? 2 : 1);
                assertThat(publicationsById(id)).as("una sola pubblicazione").isEqualTo(1);
            }
            case "twoSources" -> {
                String other = freshSource(true, List.of());
                assertOutcome(postEvent(ev), id, "ACCEPTED", "-");
                Response r2 = postEvent(event(id, other, "purchase.completed", "member:" + m.memberId(), Instant.now(),
                        purchaseData("ORD-D", 30)));
                assertThat(r2.text("status")).isEqualTo("ACCEPTED");
                assertThat(publications(src, id)).isEqualTo(1);
                assertThat(publications(other, id)).isEqualTo(1);
            }
            case "diffPayload" -> {
                assertOutcome(postEvent(ev), id, "ACCEPTED", "-");
                Response r2 = postEvent(event(id, src, "app.login.daily", "member:" + m.memberId(), Instant.now(),
                        json("{\"platform\":\"WEB\"}")));
                assertOutcome(r2, id, "DUPLICATE", "-");
                assertThat(publicationsById(id)).isEqualTo(1);
            }
            case "diffMember" -> {
                assertOutcome(postEvent(ev), id, "ACCEPTED", "-");
                Response r2 = postEvent(event(id, src, "purchase.completed", "member:" + freshMember("ACTIVE").memberId(),
                        Instant.now(), purchaseData("ORD-D", 30)));
                assertOutcome(r2, id, "DUPLICATE", "-");
                assertThat(publicationsById(id)).isEqualTo(1);
            }
            case "afterRejected" -> {
                ObjectNode bad = ev.deepCopy();
                bad.set("data", json("{\"orderId\":\"ORD-D\",\"currency\":\"EUR\"}"));
                assertOutcome(postEvent(bad), id, "REJECTED", "INVALID_DATA");
                // TESTBOOK: ambiguo, vedi TB-ING-DUP-006 (F-ING-02 «già visto» vs dedup sui soli ACCEPTED)
                assertOutcome(postEvent(ev), id, "ACCEPTED", "-");
            }
            case "afterUnmatched" -> {
                Fresh later = freshMember(null);
                ObjectNode parked = ev.deepCopy();
                parked.put("subject", "external:" + later.externalId());
                assertOutcome(postEvent(parked), id, "UNMATCHED", "-");
                members.upsert(later.memberId(), later.externalId(), later.email(), "ACTIVE");
                // TESTBOOK: ambiguo, vedi TB-ING-DUP-007
                assertOutcome(postEvent(parked), id, "ACCEPTED", "-");
            }
            case "rejectedTwice" -> {
                ev.put("subject", "member:" + freshMember("BLOCKED").memberId());
                assertOutcome(postEvent(ev), id, "REJECTED", "MEMBER_NOT_ACTIVE");
                // TESTBOOK: ambiguo, vedi TB-ING-DUP-008
                assertOutcome(postEvent(ev), id, "REJECTED", "MEMBER_NOT_ACTIVE");
                assertThat(countStatus(src, id, "REJECTED")).isEqualTo(2);
            }
            case "shortVsUrn" -> {
                assertOutcome(postEvent(ev), id, "ACCEPTED", "-");
                ObjectNode shortSrc = ev.deepCopy();
                shortSrc.put("source", src);
                // Q-258 DECISA: la forma breve da una fonte esterna è un errore di forma (400), mai un secondo invio.
                assertThat(postEvent(shortSrc).status()).as("forma breve ⇒ 400").isEqualTo(400);
                assertThat(countStatus(src, id, "DUPLICATE")).as("nessuna riga DUPLICATE").isZero();
                assertThat(publicationsById(id)).isEqualTo(1);
            }
            case "shortVsFullType" -> {
                assertOutcome(postEvent(ev), id, "ACCEPTED", "-");
                ObjectNode full = ev.deepCopy();
                full.put("type", "io.loyaltyhub.action.purchase.completed");
                assertOutcome(postEvent(full), id, "DUPLICATE", "-");
                assertThat(publicationsById(id)).isEqualTo(1);
            }
            case "idCase" -> {
                String lower = "tb-case-" + seq() + "-abc";
                String upper = lower.toUpperCase();
                ev.put("id", lower);
                assertOutcome(postEvent(ev), lower, "ACCEPTED", "-");
                ev.put("id", upper);
                assertOutcome(postEvent(ev), upper, "ACCEPTED", "-");
            }
            case "concurrent" -> {
                ExecutorService pool = Executors.newFixedThreadPool(8);
                try {
                    List<Callable<Response>> tasks = new ArrayList<>();
                    for (int i = 0; i < 8; i++) {
                        tasks.add(() -> postEvent(ev));
                    }
                    Map<String, Integer> outcomes = new HashMap<>();
                    for (Future<Response> f : pool.invokeAll(tasks)) {
                        Response r = f.get();
                        assertThat(r.status()).isEqualTo(202);
                        outcomes.merge(r.text("status"), 1, Integer::sum);
                    }
                    assertThat(outcomes).containsEntry("ACCEPTED", 1).containsEntry("DUPLICATE", 7);
                    assertThat(publicationsById(id)).isEqualTo(1);
                } finally {
                    pool.shutdownNow();
                }
            }
            default -> throw new IllegalArgumentException(kase);
        }
    }

    private long countStatus(String src, String id, String status) {
        return jdbc.sql("SELECT count(*) FROM inbound_event WHERE source_code = ? AND event_id = ? AND status = ?")
                .params(src, id, status).query(Long.class).single();
    }

    // ---------- MBR: risoluzione del membro ----------

    @TestFactory
    Stream<DynamicTest> mbr() {
        return rows("mbr.csv", this::mbrRow);
    }

    // Q-255 DECISA: TB-ING-MBR-025…TB-ING-MBR-033 — ogni forma non prevista del subject (senza prefisso, prefisso
    // sconosciuto, member: vuoto) ⇒ UNMATCHED qualunque sia il membro; external: a confronto esatto
    void mbrRow(Row a) {
        String form = a.getString(2), state = a.getString(3), status = a.getString(4), code = a.getString(5),
                member = a.getString(6);
        String subject;
        String expectedMember;
        if (form.startsWith("seed:")) {
            subject = form.substring("seed:".length());
            expectedMember = "N".equals(member) ? null : member;
        } else {
            Fresh m = freshMember(switch (state) {
                case "ABSENT" -> null;
                case "ANONYMIZED" -> "ACTIVE";
                default -> state;
            });
            if (state.equals("ANONYMIZED")) {
                erasure.erase(m.memberId());
            }
            subject = switch (form) {
                case "member" -> "member:" + m.memberId();
                case "external" -> "external:" + m.externalId();
                case "email" -> "email:" + m.email();
                case "emailMixed" -> "email:" + mixed(m.email());
                case "noprefix" -> m.memberId();
                case "externalCase" -> "external:" + m.externalId().toLowerCase();
                case "phone" -> "phone:+390000" + seq();
                case "memberEmpty" -> "member:";
                default -> throw new IllegalArgumentException(form);
            };
            expectedMember = "S".equals(member) ? m.memberId() : null;
        }
        String id = freshEventId();
        Response r = postEvent(event(id, "simulator", "purchase.completed", subject, Instant.now(), purchaseData("ORD-M", 20)));
        assertOutcome(r, id, status, code);
        assertThat(r.text("memberId")).as("memberId risolto").isEqualTo(expectedMember);
        if ("ACCEPTED".equals(status)) {
            assertThat(published("simulator", id).path("subject").asString()).isEqualTo("member:" + expectedMember);
        }
    }

    /** {@code socio.tb12@example.org} → {@code Socio.Tb12@Example.ORG}. */
    private static String mixed(String email) {
        String[] parts = email.split("@");
        String local = Character.toUpperCase(parts[0].charAt(0)) + parts[0].substring(1).replace(".tb", ".Tb");
        return local + "@Example.ORG";
    }

    // ---------- ACC: arricchimento e pubblicazione ----------

    @TestFactory
    Stream<DynamicTest> acc() {
        return rows("acc.csv", this::accRow);
    }

    void accRow(Row a) throws Exception {
        String kase = a.getString(2);
        String src = freshSource(true, List.of());
        Fresh m = freshMember("ACTIVE");
        String id = freshEventId();
        Instant when = Instant.parse("2026-09-18T10:15:00Z").plusMillis(seq());
        CLOCK.set(when.plusSeconds(60));
        JsonNode data = json("{\"orderId\":\"ORD-88213\",\"amount\":130.00,\"currency\":\"EUR\",\"channel\":\"ONLINE\","
                + "\"items\":[{\"sku\":\"SKU-1001\",\"category\":\"casa\",\"quantity\":1,\"unitPrice\":130.00}]}");
        String subject = kase.equals("subjectNormalized") || kase.equals("rowSubject")
                ? "email:" + m.email() : "member:" + m.memberId();
        ObjectNode ev = event(id, src, "purchase.completed", subject, when, data);
        switch (kase) {
            case "response" -> {
                Response r = postEvent(ev);
                assertOutcome(r, id, "ACCEPTED", "-");
                assertThat(r.text("eventId")).isEqualTo(id);
                assertThat(r.text("memberId")).isEqualTo(m.memberId());
            }
            case "envelope", "lhOverride" -> {
                if (kase.equals("lhOverride")) {
                    ev.put("lhhop", 7);
                    ev.put("lhcorrelationid", "COR-ESTERNA");
                    ev.put("lhtenant", "altro");
                    ev.put("lhactor", "ADMIN:intruso");
                }
                assertOutcome(postEvent(ev), id, "ACCEPTED", "-");
                JsonNode p = published(src, id);
                assertThat(p.path("specversion").asString()).isEqualTo("1.0");
                assertThat(p.path("id").asString()).isEqualTo(id);
                assertThat(p.path("source").asString()).isEqualTo(URN + src);
                assertThat(p.path("type").asString()).isEqualTo("io.loyaltyhub.action.purchase.completed");
                assertThat(p.path("subject").asString()).isEqualTo("member:" + m.memberId());
                assertThat(Instant.parse(p.path("time").asString())).isEqualTo(when);
                assertThat(p.path("datacontenttype").asString()).isEqualTo("application/json");
                assertThat(p.path("dataschema").asString()).isEqualTo("urn:loyaltyhub:schema:action.purchase.completed:1");
                assertThat(p.path("lhtenant").asString()).isEqualTo("aurora");
                assertThat(p.path("lhcorrelationid").asString()).isEqualTo(id);
                assertThat(p.path("lhhop").asInt(-1)).isZero();
                assertThat(p.path("data")).isEqualTo(data);
                if (kase.equals("lhOverride")) {
                    assertThat(p.path("lhactor").isMissingNode() || p.path("lhactor").isNull()).as("lhactor non accettato dalla fonte").isTrue();
                }
            }
            case "key" -> {
                assertOutcome(postEvent(ev), id, "ACCEPTED", "-");
                assertThat(publishedKey(src, id)).isEqualTo(m.memberId());
            }
            case "subjectNormalized" -> {
                assertOutcome(postEvent(ev), id, "ACCEPTED", "-");
                assertThat(published(src, id).path("subject").asString()).isEqualTo("member:" + m.memberId());
            }
            case "row" -> {
                assertOutcome(postEvent(ev), id, "ACCEPTED", "-");
                Map<String, Object> row = lastRow(id);
                assertThat(row.get("origin")).isEqualTo("EXTERNAL");
                assertThat(row.get("member_id")).isEqualTo(m.memberId());
                assertThat(row.get("correlation_id")).isEqualTo(id);
                assertThat(row.get("source_code")).isEqualTo(src);
            }
            case "rowSubject" -> {
                assertOutcome(postEvent(ev), id, "ACCEPTED", "-");
                // TESTBOOK: ambiguo, vedi TB-ING-ACC-006
                assertThat(lastRow(id).get("subject")).isEqualTo("member:" + m.memberId());
            }
            case "contract" -> {
                assertOutcome(postEvent(ev), id, "ACCEPTED", "-");
                JsonNode p = published(src, id);
                JsonSchemaValidator v = new JsonSchemaValidator();
                assertThat(v.validate("tb-envelope", contract("envelope.schema.json"), p.toString())).isEmpty();
                assertThat(v.validate("tb-purchase", contract("action/purchase.completed.schema.json"), p.path("data").toString()))
                        .isEmpty();
            }
            case "noGuard" -> {
                Response r = call("POST", "/v1/events", "ANALYST:sara.analyst", ev);
                assertOutcome(r, id, "ACCEPTED", "-");
            }
            case "kafka" -> {
                CLOCK.set(null);
                ev.put("time", Instant.now().toString());
                try (KafkaConsumer<String, String> consumer = consumer()) {
                    assertOutcome(postEvent(ev), id, "ACCEPTED", "-");
                    ConsumerRecord<String, String> rec = poll(consumer, id, Duration.ofSeconds(15));
                    assertThat(rec).as("record su lh.actions.v1 entro 15 s").isNotNull();
                    assertThat(rec.key()).isEqualTo(m.memberId());
                    assertThat(json(rec.value()).path("lhhop").asInt(-1)).isZero();
                }
            }
            default -> throw new IllegalArgumentException(kase);
        }
    }

    private static String contract(String file) throws Exception {
        return Files.readString(Path.of("../../contracts/events", file), StandardCharsets.UTF_8);
    }

    private KafkaConsumer<String, String> consumer() {
        KafkaConsumer<String, String> c = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, System.getProperty("spring.embedded.kafka.brokers"),
                ConsumerConfig.GROUP_ID_CONFIG, "tb-ing-" + System.nanoTime(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class));
        c.subscribe(List.of(ACTIONS));
        return c;
    }

    private static ConsumerRecord<String, String> poll(KafkaConsumer<String, String> c, String id, Duration timeout) {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            for (ConsumerRecord<String, String> r : c.poll(Duration.ofMillis(300))) {
                if (r.value().contains(id)) {
                    return r;
                }
            }
        }
        return null;
    }

    // ---------- MON: monitor ingressi ----------

    @TestFactory
    Stream<DynamicTest> mon() {
        return rows("mon.csv", this::monRow);
    }

    void monRow(Row a) {
        String kase = a.getString(2);
        String src = freshSource(true, List.of());
        switch (kase) {
            case "countsEmpty" -> {
                JsonNode c = call("GET", "/v1/inbound-events/counts?source=" + src, null, null).body();
                assertThat(c.propertyNames()).containsExactly("ACCEPTED", "DUPLICATE", "REJECTED", "UNMATCHED");
                assertThat(counts(c)).containsExactly(0L, 0L, 0L, 0L);
            }
            case "counts", "countsMember" -> {
                Fresh active = freshMember("ACTIVE");
                String acc = sendTo(src, "member:" + active.memberId(), null);
                sendTo(src, "member:" + active.memberId(), acc);
                sendTo(src, "member:" + active.memberId(), acc);
                sendTo(src, "member:" + freshMember("BLOCKED").memberId(), null);
                sendTo(src, "external:NOPE-" + seq(), null);
                if (kase.equals("counts")) {
                    assertThat(counts(call("GET", "/v1/inbound-events/counts?source=" + src, null, null).body()))
                            .containsExactly(1L, 2L, 1L, 1L);
                } else {
                    assertThat(counts(call("GET", "/v1/inbound-events/counts?source=" + src + "&memberId="
                            + active.memberId(), null, null).body())).containsExactly(1L, 2L, 0L, 0L);
                }
            }
            case "listStatus", "listType" -> {
                Fresh active = freshMember("ACTIVE");
                sendTo(src, "member:" + active.memberId(), null);
                sendTo(src, "member:" + freshMember("BLOCKED").memberId(), null);
                String id = freshEventId();
                postEvent(event(id, src, "app.login.daily", "member:" + active.memberId(), Instant.now(),
                        json("{\"platform\":\"WEB\"}")));
                String q = kase.equals("listStatus") ? "status=REJECTED" : "type=app.login.daily";
                JsonNode list = call("GET", "/v1/inbound-events?source=" + src + "&" + q, null, null).body();
                assertThat(list.size()).isEqualTo(1);
                if (kase.equals("listStatus")) {
                    assertThat(list.get(0).path("status").asString()).isEqualTo("REJECTED");
                } else {
                    assertThat(list.get(0).path("typeCode").asString()).isEqualTo("app.login.daily");
                }
            }
            case "detail" -> {
                String id = freshEventId();
                Fresh m = freshMember("ACTIVE");
                postEvent(event(id, src, "purchase.completed", "member:" + m.memberId(), Instant.now(),
                        json("{\"orderId\":\"\",\"amount\":-3,\"currency\":\"EUR\"}")));
                JsonNode d = call("GET", "/v1/inbound-events/" + rowId(src, id), null, null).body();
                assertThat(d.path("status").asString()).isEqualTo("REJECTED");
                assertThat(d.path("rejectCode").asString()).isEqualTo("INVALID_DATA");
                assertThat(d.path("rejectDetail").asString()).contains("amount").contains("orderId");
                assertThat(d.path("correlationId").asString()).isEqualTo(id);
                assertThat(d.path("payload").path("id").asString()).isEqualTo(id);
                assertThat(d.path("payload").path("data").path("amount").asInt()).isEqualTo(-3);
            }
            case "detail404" -> assertThat(call("GET", "/v1/inbound-events/NOPE-" + seq(), null, null).status()).isEqualTo(404);
            case "fromTo" -> {
                sendTo(src, "member:" + freshMember("ACTIVE").memberId(), null);
                Instant future = Instant.now().plus(Duration.ofDays(1));
                JsonNode list = call("GET", "/v1/inbound-events?source=" + src + "&from=" + future + "&to="
                        + future.plus(Duration.ofDays(1)), null, null).body();
                assertThat(list.size()).as("nessuna riga ricevuta domani").isZero();
            }
            case "q" -> {
                Fresh m = freshMember("ACTIVE");
                String wanted = sendTo(src, "member:" + m.memberId(), null);
                sendTo(src, "member:" + m.memberId(), null);
                JsonNode list = call("GET", "/v1/inbound-events?source=" + src + "&q=" + wanted, null, null).body();
                assertThat(list.size()).as("solo la riga con l'id cercato").isEqualTo(1);
            }
            case "seedHistory" -> {
                List<String> statuses = jdbc.sql("""
                                SELECT status FROM inbound_event
                                WHERE received_at < ? AND received_at >= ? AND event_id NOT LIKE 'tb-%'
                                """)
                        .params(java.sql.Timestamp.from(STARTED), java.sql.Timestamp.from(STARTED.minus(Duration.ofDays(3))))
                        .query(String.class).list();
                assertThat(statuses.size()).as("storico demo degli ultimi 3 giorni").isGreaterThanOrEqualTo(40);
                assertThat(statuses).contains("ACCEPTED", "DUPLICATE", "REJECTED", "UNMATCHED");
            }
            case "readOnly" -> {
                String id = sendTo(src, "member:" + freshMember("ACTIVE").memberId(), null);
                String analyst = "ANALYST:sara.analyst";
                assertThat(call("GET", "/v1/inbound-events?source=" + src, analyst, null).status()).isEqualTo(200);
                assertThat(call("GET", "/v1/inbound-events/counts?source=" + src, analyst, null).status()).isEqualTo(200);
                assertThat(call("GET", "/v1/inbound-events/" + rowId(src, id), analyst, null).status()).isEqualTo(200);
            }
            default -> throw new IllegalArgumentException(kase);
        }
    }

    /** Invia un acquisto valido alla fonte (id nuovo o {@code reuseId}); restituisce l'id. */
    private String sendTo(String src, String subject, String reuseId) {
        String id = reuseId != null ? reuseId : freshEventId();
        Response r = postEvent(event(id, src, "purchase.completed", subject, Instant.now(), purchaseData("ORD-" + id, 5)));
        assertThat(r.status()).isEqualTo(202);
        return id;
    }

    private static List<Long> counts(JsonNode c) {
        return List.of(c.path("ACCEPTED").asLong(-1), c.path("DUPLICATE").asLong(-1),
                c.path("REJECTED").asLong(-1), c.path("UNMATCHED").asLong(-1));
    }
}
