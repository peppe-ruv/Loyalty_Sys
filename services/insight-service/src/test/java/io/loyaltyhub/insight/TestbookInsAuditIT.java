package io.loyaltyhub.insight;

import io.loyaltyhub.insight.domain.AuditRecord;
import io.loyaltyhub.insight.infra.AuditRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testbook TB-INS — audit (F-AUD-01, BO-22; docs/testbook/TB-INS-insight.md §7): voce dall'evento
 * {@code io.loyaltyhub.audit.entry} su {@code lh.audit.v1} ({@code AIN}: chi, cosa, quando, prima/dopo), filtri
 * ({@code AFL}), paginazione ({@code APG}), dettaglio ({@code ADT}). Oracolo: docs/05 §6 e
 * contracts/events/audit/entry, insight §2–§3, docs/06 §2–§3, docs/08 BO-22.
 */
class TestbookInsAuditIT extends TestbookInsBase {

    @Autowired
    private AuditRepository audits;

    private ObjectNode auditEvent(String eventId, String actor, Map<String, Object> data, Instant time, String cor) {
        ObjectNode env = envelope(eventId, AUDIT_TYPE, "urn:loyaltyhub:service:campaign",
                data.get("entityType") + ":" + data.get("entityId"), cor, null, time, data);
        if (actor == null) {
            env.remove("lhactor");
        } else {
            env.put("lhactor", actor);
        }
        return env;
    }

    private static Map<String, Object> data(String entityId, String action) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("service", "campaign");
        d.put("entityType", "CAMPAIGN");
        d.put("entityId", entityId);
        d.put("action", action);
        d.put("summary", "Modificata campagna " + entityId);
        return d;
    }

    private JsonNode awaitByEntity(String entityId) {
        return awaitValue("voce di audit per " + entityId, () -> {
            JsonNode items = get("/v1/audit?entityId=" + entityId).path("items");
            return items.size() > 0 ? items : null;
        }, 20_000);
    }

    // ---------- AIN ----------

    @ParameterizedTest(name = "[{0}] {1}", quoteTextArguments = false)
    @CsvFileSource(resources = "/testbook/ins/audit-ingest.csv", numLinesToSkip = 1)
    void ingest(String id, String desc, String lhactor, String action, String expRole, String expName,
                String expAction) {
        String entity = uid("CMP-AIN");
        publish("lh.audit.v1", auditEvent(uid("EVT-AIN"), "-".equals(lhactor) ? null : lhactor, data(entity, action),
                Instant.parse("2026-09-20T10:00:00Z"), uid("COR-AIN")));
        JsonNode item = awaitByEntity(entity).get(0);
        assertThat(item.path("action").asString()).isEqualTo(expAction);
        assertThat(nullable(item.path("actorRole"))).isEqualTo("-".equals(expRole) ? null : expRole);
        assertThat(nullable(item.path("actorName"))).isEqualTo("-".equals(expName) ? null : expName);
        JsonNode byAction = get("/v1/audit?entityId=" + entity + "&action=" + expAction).path("items");
        assertThat(byAction.size()).as("filtrabile per azione").isEqualTo(1);
    }

    private static String nullable(JsonNode n) {
        return n.isMissingNode() || n.isNull() ? null : n.asString();
    }

    @Test
    @DisplayName("[TB-INS-AIN-015] before/after presenti: conservati come oggetti con i soli campi cambiati")
    void beforeAfter() {
        String entity = uid("CMP-AIN");
        Map<String, Object> d = data(entity, "UPDATE");
        d.put("before", Map.of("name", "Vecchio", "priority", 100));
        d.put("after", Map.of("name", "Nuovo", "priority", 90));
        publish("lh.audit.v1", auditEvent(uid("EVT-AIN"), "MARKETING:luca.marketing", d, Instant.now(), uid("COR")));
        JsonNode item = awaitByEntity(entity).get(0);
        assertThat(item.path("before").path("name").asString()).isEqualTo("Vecchio");
        assertThat(item.path("after").path("name").asString()).isEqualTo("Nuovo");
        assertThat(item.path("after").path("priority").asInt()).isEqualTo(90);
    }

    @Test
    @DisplayName("[TB-INS-AIN-016] before/after assenti (creazione, job): campi nulli")
    void noBeforeAfter() {
        String entity = uid("CMP-AIN");
        publish("lh.audit.v1", auditEvent(uid("EVT-AIN"), "ADMIN:marta.admin", data(entity, "CREATE"), Instant.now(), uid("COR")));
        JsonNode item = awaitByEntity(entity).get(0);
        assertThat(nullable(item.path("before"))).isNull();
        assertThat(nullable(item.path("after"))).isNull();
    }

    @Test
    @DisplayName("[TB-INS-AIN-017] evento di audit senza data (AMBIGUO): nessuna voce, l'evento resta nell'event store")
    void noData() {
        // Q-329 DECISA (TB-INS-AIN-017)
        String ev = uid("EVT-AIN");
        ObjectNode env = envelope(ev, AUDIT_TYPE, "urn:loyaltyhub:service:campaign", "CAMPAIGN:CMP-X", uid("COR"), null,
                Instant.now(), null);
        env.put("lhactor", "ADMIN:marta.admin");
        publish("lh.audit.v1", env);
        awaitStored(ev);
        assertThat(audits.search(null, null, null, null, null, null, null, null, 500, 0))
                .noneMatch(a -> ev.equals(a.eventId()));
    }

    @Test
    @DisplayName("[TB-INS-AIN-018] stesso evento di audit consegnato due volte: una sola voce")
    void duplicate() {
        String entity = uid("CMP-AIN");
        ObjectNode env = auditEvent(uid("EVT-AIN"), "ADMIN:marta.admin", data(entity, "UPDATE"), Instant.now(), uid("COR"));
        publish("lh.audit.v1", env);
        publish("lh.audit.v1", env);
        String sentinel = uid("CMP-AIN");
        publish("lh.audit.v1", auditEvent(uid("EVT-AIN"), "ADMIN:marta.admin", data(sentinel, "UPDATE"), Instant.now(), uid("COR")));
        awaitByEntity(sentinel);
        assertThat(get("/v1/audit?entityId=" + entity).path("items").size()).isEqualTo(1);
    }

    @Test
    @DisplayName("[TB-INS-AIN-019] chi/cosa/quando: at = time dell'evento, servizio, tipo e id dell'oggetto, sintesi, correlationId")
    void whoWhatWhen() {
        String entity = uid("CMP-AIN");
        String cor = uid("COR-AIN");
        Instant at = Instant.parse("2026-09-21T08:15:30Z");
        publish("lh.audit.v1", auditEvent(uid("EVT-AIN"), "ADMIN:marta.admin", data(entity, "TRANSITION"), at, cor));
        JsonNode item = awaitByEntity(entity).get(0);
        assertThat(item.path("at").asString()).isEqualTo(at.toString());
        assertThat(item.path("service").asString()).isEqualTo("campaign");
        assertThat(item.path("entityType").asString()).isEqualTo("CAMPAIGN");
        assertThat(item.path("entityId").asString()).isEqualTo(entity);
        assertThat(item.path("summary").asString()).isEqualTo("Modificata campagna " + entity);
        assertThat(item.path("correlationId").asString()).isEqualTo(cor);
        assertThat(item.path("eventId").asString()).startsWith("EVT-AIN");
    }

    @Test
    @DisplayName("[TB-INS-AIN-020] voce senza entityId (AMBIGUO: il contratto lo richiede): registrata con id vuoto")
    void noEntityId() {
        // Q-329 DECISA (TB-INS-AIN-020)
        String summary = uid("senza-oggetto");
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("service", "tb-ain-020");
        d.put("entityType", "CAMPAIGN");
        d.put("action", "UPDATE");
        d.put("summary", summary);
        ObjectNode env = envelope(uid("EVT-AIN"), AUDIT_TYPE, "urn:loyaltyhub:service:campaign", "CAMPAIGN:?", uid("COR"),
                null, Instant.now(), d);
        env.put("lhactor", "ADMIN:marta.admin");
        publish("lh.audit.v1", env);
        JsonNode item = awaitValue("voce senza oggetto", () -> {
            JsonNode items = get("/v1/audit?service=tb-ain-020").path("items");
            return items.size() > 0 ? items.get(0) : null;
        }, 20_000);
        assertThat(item.path("entityId").asString()).isEmpty();
    }

    // ---------- AFL: filtri ----------

    private static final Instant T0 = Instant.parse("2035-10-01T10:00:00Z");

    private List<String> seedFive(String service) {
        String[][] rows = {
                {"ADMIN", "marta.admin", "CAMPAIGN", "CMP-1", "UPDATE"},
                {"MARKETING", "luca.marketing", "CAMPAIGN", "CMP-2", "CREATE"},
                {"CARE", "carla.care", "WALLET", "MBR-000004", "ADJUST"},
                {"ADMIN", "marta.admin", "DlqEntry", "DLQ-1", "TRANSITION"},
                {"system", null, "job", "expire-points", "JOB"}};
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < rows.length; i++) {
            String id = uid("AUD");
            audits.insert(new AuditRecord(id, uid("EVT"), T0.plusSeconds(60L * i), rows[i][0], rows[i][1], service,
                    rows[i][2], rows[i][3], rows[i][4], "voce " + i, null, null, null));
            ids.add(id);
        }
        return ids;
    }

    private static String expand(String query) {
        StringBuilder out = new StringBuilder();
        for (String part : query.split("&")) {
            if (part.isEmpty()) {
                continue;
            }
            int at = part.indexOf("=@");
            if (at > 0) {
                part = part.substring(0, at + 1) + T0.plusMillis(Long.parseLong(part.substring(at + 2)));
            }
            out.append('&').append(part);
        }
        return out.toString();
    }

    @ParameterizedTest(name = "[{0}] {1}", quoteTextArguments = false)
    @CsvFileSource(resources = "/testbook/ins/audit-filtri.csv", numLinesToSkip = 1)
    void filtri(String id, String desc, String query, int expHttp, String expIdx) {
        String service = "tb-" + id.toLowerCase();
        List<String> ids = seedFive(service);
        Resp r = call("GET", "/v1/audit?service=" + service + expand(query == null ? "" : query), null, null);
        assertThat(r.status()).as(r.text()).isEqualTo(expHttp);
        if (expHttp != 200) {
            assertThat(r.code()).isEqualTo("BAD_REQUEST");
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
    }

    // ---------- APG: paginazione (docs/06 §2) ----------

    private JsonNode page(String service, String query) {
        return get("/v1/audit?service=" + service + query);
    }

    private List<String> itemIds(JsonNode body) {
        List<String> out = new ArrayList<>();
        body.path("items").forEach(i -> out.add(i.path("id").asString()));
        return out;
    }

    @Test
    @DisplayName("[TB-INS-APG-001] page=0&size=2: due voci e page {number 0, size 2, totalItems 5, totalPages 3}")
    void firstPage() {
        List<String> ids = seedFive("tb-apg-001");
        JsonNode b = page("tb-apg-001", "&page=0&size=2");
        assertThat(itemIds(b)).containsExactly(ids.get(4), ids.get(3));
        JsonNode p = b.path("page");
        assertThat(p.path("number").asInt()).isZero();
        assertThat(p.path("size").asInt()).isEqualTo(2);
        assertThat(p.path("totalItems").asLong()).isEqualTo(5);
        assertThat(p.path("totalPages").asInt()).isEqualTo(3);
    }

    @Test
    @DisplayName("[TB-INS-APG-002] page=2&size=2: l'ultima voce")
    void lastPage() {
        List<String> ids = seedFive("tb-apg-002");
        assertThat(itemIds(page("tb-apg-002", "&page=2&size=2"))).containsExactly(ids.get(0));
    }

    @Test
    @DisplayName("[TB-INS-APG-003] size=101: limitata a 100 (docs/06 §2)")
    void sizeCap() {
        seedFive("tb-apg-003");
        assertThat(page("tb-apg-003", "&size=101").path("page").path("size").asInt()).isEqualTo(100);
    }

    @Test
    @DisplayName("[TB-INS-APG-004] senza parametri: risposta {items, page} con totalItems")
    void defaultShape() {
        seedFive("tb-apg-004");
        JsonNode b = page("tb-apg-004", "");
        assertThat(b.path("items").size()).isEqualTo(5);
        assertThat(b.path("page").path("totalItems").asLong()).isEqualTo(5);
    }

    // ---------- ADT: dettaglio ----------

    @Test
    @DisplayName("[TB-INS-ADT-001] GET /v1/audit/{id}: voce completa con before/after per il DiffView")
    void detail() {
        String id = uid("AUD");
        tools.jackson.databind.node.ObjectNode before = mapper.createObjectNode().put("balance", 12300);
        tools.jackson.databind.node.ObjectNode after = mapper.createObjectNode().put("balance", 12500);
        audits.insert(new AuditRecord(id, uid("EVT"), T0, "CARE", "carla.care", "wallet", "WALLET", "MBR-000004",
                "ADJUST", "Accredito manuale di 200 PTS (GOODWILL)", before, after, "COR-ADT"));
        JsonNode d = get("/v1/audit/" + id);
        assertThat(d.path("before").path("balance").asLong()).isEqualTo(12300);
        assertThat(d.path("after").path("balance").asLong()).isEqualTo(12500);
        assertThat(d.path("actorRole").asString()).isEqualTo("CARE");
        assertThat(d.path("summary").asString()).isEqualTo("Accredito manuale di 200 PTS (GOODWILL)");
    }

    @Test
    @DisplayName("[TB-INS-ADT-002] id inesistente: 404 NOT_FOUND")
    void detailNotFound() {
        Resp r = call("GET", "/v1/audit/01NOTEXISTAUDIT", null, null);
        assertThat(r.status()).isEqualTo(404);
        assertThat(r.code()).isEqualTo("NOT_FOUND");
    }
}
