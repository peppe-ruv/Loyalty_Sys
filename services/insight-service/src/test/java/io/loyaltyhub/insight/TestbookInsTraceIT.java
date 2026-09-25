package io.loyaltyhub.insight;

import io.loyaltyhub.insight.domain.StoredEvent;
import io.loyaltyhub.insight.infra.EventStoreRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testbook TB-INS — tracciati (docs/testbook/TB-INS-insight.md §4): stato ({@code TST}: voci DLQ × quiete di 5 s,
 * tabella completa), albero ({@code TTR}), esito ({@code TOU}), elenco ({@code TLS}). Gli eventi sono scritti
 * direttamente nell'event store con l'istante di osservazione del caso ({@code received_at}); l'orologio
 * dell'applicazione è fermo, così i 5 s di quiete non dipendono dal tempo reale. Oracolo: insight §3, §5, §7;
 * F-INS-02; BO-25; docs/04 §5 (correlazione, causazione); docs/05 §5 (fatti); Q-106, Q-111.
 */
class TestbookInsTraceIT extends TestbookInsBase {

    @Autowired
    private EventStoreRepository events;

    private static final String SERVICE = "urn:loyaltyhub:service:";

    /** Scrive un evento osservato all'istante {@code receivedAt}. */
    private void stored(String eventId, String family, String shortType, String source, String memberId,
                        String correlationId, String causationId, Instant eventTime, Instant receivedAt,
                        Map<String, ?> data) {
        String prefix = switch (family) {
            case "ACTION" -> ACTION;
            case "EFFECT" -> EFFECT;
            case "AUDIT" -> "io.loyaltyhub.audit.";
            default -> FACT;
        };
        String topic = switch (family) {
            case "ACTION" -> "lh.actions.v1";
            case "EFFECT" -> "lh.effects.v1";
            case "AUDIT" -> "lh.audit.v1";
            case "DLQ" -> "lh.dlq.v1";
            default -> "lh.facts.v1";
        };
        String payload = mapper.writeValueAsString(envelope(eventId, prefix + shortType, source,
                memberId == null ? null : "member:" + memberId, correlationId, causationId, eventTime, data));
        assertThat(events.insert(new StoredEvent(eventId, topic, family, prefix + shortType, shortType, source, memberId,
                correlationId, causationId, 0, "system", null, eventTime, null, 0, 0L, payload))).isTrue();
        setReceivedAt(eventId, receivedAt);
    }

    private JsonNode trace(String correlationId) {
        return get("/v1/traces/" + correlationId);
    }

    private Map<String, JsonNode> nodesById(JsonNode trace) {
        Map<String, JsonNode> out = new LinkedHashMap<>();
        trace.path("nodes").forEach(n -> out.put(n.path("eventId").asString(), n));
        return out;
    }

    private static boolean isNull(JsonNode n) {
        return n.isMissingNode() || n.isNull();
    }

    // ---------- TST: stato ----------

    @ParameterizedTest(name = "[{0}] {1}", quoteTextArguments = false)
    @CsvFileSource(resources = "/testbook/ins/tracciato-stato.csv", numLinesToSkip = 1)
    void stato(String id, String desc, String dlq, long ageMs, String expStatus) {
        String cor = uid("COR-TST");
        Instant t = Instant.parse("2035-06-01T10:00:00Z");
        String a = uid("EVT-A");
        stored(a, "ACTION", "purchase.completed", "urn:loyaltyhub:source:ecommerce", "MBR-000003", cor, null, t, t,
                Map.of("amount", 130));
        stored(uid("EVT-E"), "EFFECT", "points.grant", SERVICE + "campaign", "MBR-000003", cor, a, t, t.plusMillis(1000),
                Map.of("amount", 162, "currency", "PTS"));
        int i = 0;
        for (String s : "NONE".equals(dlq) ? new String[0] : dlq.split("\\+")) {
            String entry = openEntry("ACTION", "lh-campaign", "E_TST", cor, t.plusMillis(500 + i++)).id();
            if (!"OPEN".equals(s)) {
                dlqRepo.resolve(entry, s, "ADMIN:setup", "x");
            }
        }
        CLOCK.set(t.plusMillis(1000 + ageMs));
        JsonNode tr = trace(cor);
        assertThat(tr.path("status").asString()).isEqualTo(expStatus);
    }

    @Test
    @DisplayName("[TB-INS-TST-031] correlationId sconosciuto (AMBIGUO: 404?): 200 IN_PROGRESS senza nodi")
    void unknownCorrelation() {
        // TESTBOOK: ambiguo, vedi TB-INS-TST-031
        Resp r = call("GET", "/v1/traces/" + uid("COR-NONE"), null, null);
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.body().path("status").asString()).isEqualTo("IN_PROGRESS");
        assertThat(r.body().path("nodes").size()).isZero();
        assertThat(r.body().path("outcome").path("points").size()).isZero();
    }

    @Test
    @DisplayName("[TB-INS-TST-032] solo una voce DLQ aperta (eventi già potati): FAILED, un nodo DLQ, membro dalla voce")
    void onlyDlq() {
        String cor = uid("COR-TST");
        openEntry("ACTION", "lh-campaign", "E_TST", cor, Instant.parse("2035-06-02T10:00:00Z"));
        CLOCK.set(Instant.parse("2035-06-02T11:00:00Z"));
        JsonNode tr = trace(cor);
        assertThat(tr.path("status").asString()).isEqualTo("FAILED");
        assertThat(tr.path("nodes").size()).isEqualTo(1);
        assertThat(tr.path("nodes").get(0).path("family").asString()).isEqualTo("DLQ");
        assertThat(tr.path("memberId").asString()).isEqualTo("MBR-000002");
    }

    @Test
    @DisplayName("[TB-INS-TST-033] riga di famiglia DLQ nell'event store (ramo senza specifica, Q-111): FAILED")
    void dlqFamilyRow() {
        String cor = uid("COR-TST");
        Instant t = Instant.parse("2035-06-03T10:00:00Z");
        stored(uid("EVT-D"), "DLQ", "app.login.daily", "urn:loyaltyhub:source:app", "MBR-000002", cor, null, t, t, Map.of());
        CLOCK.set(t.plusSeconds(60));
        assertThat(trace(cor).path("status").asString()).isEqualTo("FAILED");
    }

    @Test
    @DisplayName("[TB-INS-TST-034] quiete misurata sull'arrivo: evento con time vecchio di 1 h ma arrivato 1 s fa ⇒ IN_PROGRESS")
    void quietOnArrival() {
        String cor = uid("COR-TST");
        Instant t = Instant.parse("2035-06-04T10:00:00Z");
        stored(uid("EVT-A"), "ACTION", "purchase.completed", "urn:loyaltyhub:source:ecommerce", "MBR-000003", cor, null,
                t.minusSeconds(3600), t, Map.of());
        CLOCK.set(t.plusSeconds(1));
        assertThat(trace(cor).path("status").asString()).isEqualTo("IN_PROGRESS");
    }

    @Test
    @DisplayName("[TB-INS-TST-035] orologio indietro rispetto all'ultimo arrivo (età negativa): IN_PROGRESS")
    void negativeAge() {
        String cor = uid("COR-TST");
        Instant t = Instant.parse("2035-06-05T10:00:00Z");
        stored(uid("EVT-A"), "ACTION", "purchase.completed", "urn:loyaltyhub:source:ecommerce", "MBR-000003", cor, null, t, t,
                Map.of());
        CLOCK.set(t.minusSeconds(10));
        assertThat(trace(cor).path("status").asString()).isEqualTo("IN_PROGRESS");
    }

    @Test
    @DisplayName("[TB-INS-TST-036] voce DLQ REPROCESSED più recente dell'ultimo evento (AMBIGUO): la quiete parte dalla voce")
    void quietIncludesDlq() {
        // TESTBOOK: ambiguo, vedi TB-INS-TST-036
        String cor = uid("COR-TST");
        Instant t = Instant.parse("2035-06-06T10:00:00Z");
        stored(uid("EVT-A"), "ACTION", "purchase.completed", "urn:loyaltyhub:source:ecommerce", "MBR-000003", cor, null, t, t,
                Map.of());
        String entry = openEntry("ACTION", "lh-campaign", "E_TST", cor, t.plusSeconds(10)).id();
        dlqRepo.resolve(entry, "REPROCESSED", "ADMIN:setup", null);
        CLOCK.set(t.plusSeconds(12));
        assertThat(trace(cor).path("status").asString()).isEqualTo("IN_PROGRESS");
        CLOCK.set(t.plusSeconds(15));
        assertThat(trace(cor).path("status").asString()).isEqualTo("COMPLETE");
    }

    // ---------- TTR: albero ----------

    @Test
    @DisplayName("[TB-INS-TTR-001] catena azione → effetto → fatto: radice senza genitore, genitore = causationId, offsetMs e durationMs dagli arrivi")
    void chain() {
        String cor = uid("COR-TTR");
        Instant t = Instant.parse("2035-07-01T10:00:00Z");
        String a = uid("EVT-A");
        String e = uid("EVT-E");
        String f = uid("EVT-F");
        stored(a, "ACTION", "purchase.completed", "urn:loyaltyhub:source:ecommerce", "MBR-000003", cor, null, t, t,
                Map.of("amount", 130));
        stored(e, "EFFECT", "points.grant", SERVICE + "campaign", "MBR-000003", cor, a, t, t.plusMillis(250),
                Map.of("amount", 162, "currency", "PTS"));
        stored(f, "FACT", "wallet.points.earned", SERVICE + "wallet", "MBR-000003", cor, e, t, t.plusMillis(900),
                Map.of("amount", 162, "currency", "PTS"));
        CLOCK.set(t.plusSeconds(30));
        JsonNode tr = trace(cor);
        Map<String, JsonNode> n = nodesById(tr);
        assertThat(n.keySet()).containsExactly(a, e, f);
        assertThat(isNull(n.get(a).path("parentEventId"))).isTrue();
        assertThat(n.get(e).path("parentEventId").asString()).isEqualTo(a);
        assertThat(n.get(f).path("parentEventId").asString()).isEqualTo(e);
        assertThat(n.get(a).path("offsetMs").asLong()).isZero();
        assertThat(n.get(e).path("offsetMs").asLong()).isEqualTo(250);
        assertThat(n.get(f).path("offsetMs").asLong()).isEqualTo(900);
        assertThat(tr.path("durationMs").asLong()).isEqualTo(900);
        assertThat(tr.path("startedAt").asString()).isEqualTo(t.toString());
        assertThat(tr.path("status").asString()).isEqualTo("COMPLETE");
    }

    @Test
    @DisplayName("[TB-INS-TTR-002] ramificazione: azione → valutazione + 2 effetti → 2 fatti; una sola radice, 6 nodi")
    void fanOut() {
        String cor = uid("COR-TTR");
        Instant t = Instant.parse("2035-07-02T10:00:00Z");
        String a = uid("EVT-A");
        stored(a, "ACTION", "purchase.completed", "urn:loyaltyhub:source:ecommerce", "MBR-000003", cor, null, t, t, Map.of());
        stored(uid("EVT-V"), "FACT", "campaign.evaluated", SERVICE + "campaign", "MBR-000003", cor, a, t, t.plusMillis(10), Map.of());
        String e1 = uid("EVT-E1");
        String e2 = uid("EVT-E2");
        stored(e1, "EFFECT", "points.grant", SERVICE + "campaign", "MBR-000003", cor, a, t, t.plusMillis(20), Map.of("currency", "PTS"));
        stored(e2, "EFFECT", "points.grant", SERVICE + "campaign", "MBR-000003", cor, a, t, t.plusMillis(21), Map.of("currency", "STS"));
        String f1 = uid("EVT-F1");
        String f2 = uid("EVT-F2");
        stored(f1, "FACT", "wallet.points.earned", SERVICE + "wallet", "MBR-000003", cor, e1, t, t.plusMillis(40), Map.of("amount", 162, "currency", "PTS"));
        stored(f2, "FACT", "wallet.points.earned", SERVICE + "wallet", "MBR-000003", cor, e2, t, t.plusMillis(41), Map.of("amount", 130, "currency", "STS"));
        Map<String, JsonNode> n = nodesById(trace(cor));
        assertThat(n).hasSize(6);
        assertThat(n.values().stream().filter(x -> isNull(x.path("parentEventId"))).count()).isEqualTo(1);
        assertThat(n.get(f1).path("parentEventId").asString()).isEqualTo(e1);
        assertThat(n.get(f2).path("parentEventId").asString()).isEqualTo(e2);
        assertThat(n.get(e2).path("parentEventId").asString()).isEqualTo(a);
    }

    @Test
    @DisplayName("[TB-INS-TTR-003] un solo evento: durationMs 0, offsetMs 0")
    void singleEvent() {
        String cor = uid("COR-TTR");
        Instant t = Instant.parse("2035-07-03T10:00:00Z");
        stored(uid("EVT-A"), "ACTION", "app.login.daily", "urn:loyaltyhub:source:app", "MBR-000002", cor, null, t, t, Map.of());
        JsonNode tr = trace(cor);
        assertThat(tr.path("durationMs").asLong()).isZero();
        assertThat(tr.path("nodes").get(0).path("offsetMs").asLong()).isZero();
    }

    @Test
    @DisplayName("[TB-INS-TTR-004] genitore assente dall'event store (AMBIGUO): il nodo conserva parentEventId del genitore mancante")
    void orphan() {
        // TESTBOOK: ambiguo, vedi TB-INS-TTR-004
        String cor = uid("COR-TTR");
        Instant t = Instant.parse("2035-07-04T10:00:00Z");
        String f = uid("EVT-F");
        stored(f, "FACT", "wallet.points.earned", SERVICE + "wallet", "MBR-000003", cor, "EVT-POTATO", t, t,
                Map.of("amount", 10, "currency", "PTS"));
        assertThat(nodesById(trace(cor)).get(f).path("parentEventId").asString()).isEqualTo("EVT-POTATO");
    }

    @Test
    @DisplayName("[TB-INS-TTR-005] corsia del nodo: servizio dal source urn:loyaltyhub:service:*, azioni in ingestion, altro source = famiglia (ramo senza specifica)")
    void lanes() {
        String cor = uid("COR-TTR");
        Instant t = Instant.parse("2035-07-05T10:00:00Z");
        String a = uid("EVT-A");
        String f = uid("EVT-F");
        String o = uid("EVT-O");
        stored(a, "ACTION", "purchase.completed", "urn:loyaltyhub:source:ecommerce", "MBR-000003", cor, null, t, t, Map.of());
        stored(f, "FACT", "wallet.points.earned", SERVICE + "wallet", "MBR-000003", cor, a, t, t.plusMillis(5), Map.of());
        stored(o, "FACT", "coupon.issued", "urn:altro:sistema", "MBR-000003", cor, a, t, t.plusMillis(6), Map.of());
        Map<String, JsonNode> n = nodesById(trace(cor));
        assertThat(n.get(a).path("service").asString()).isEqualTo("ingestion");
        assertThat(n.get(f).path("service").asString()).isEqualTo("wallet");
        assertThat(n.get(o).path("service").asString()).isEqualTo("fact");
    }

    @Test
    @DisplayName("[TB-INS-TTR-006] nodo DLQ: famiglia DLQ, genitore = evento fallito, corsia = consumer senza lh-, sintesi con codice e stato")
    void dlqNode() {
        String cor = uid("COR-TTR");
        Instant t = Instant.parse("2035-07-06T10:00:00Z");
        String entryA = openEntry("ACTION", "lh-campaign", "DEMO_POISON", cor, t.plusMillis(100)).id();
        String entryB = openEntry("ACTION", "consumer-esterno", "X", cor, t.plusMillis(200)).id();
        String eventA = dlqRepo.findById(entryA).orElseThrow().eventId();
        Map<String, JsonNode> n = nodesById(trace(cor));
        JsonNode na = n.get(entryA);
        assertThat(na.path("family").asString()).isEqualTo("DLQ");
        assertThat(na.path("parentEventId").asString()).isEqualTo(eventA);
        assertThat(na.path("service").asString()).isEqualTo("campaign");
        assertThat(na.path("summary").asString()).isEqualTo("DLQ · DEMO_POISON · OPEN");
        assertThat(n.get(entryB).path("service").asString()).isEqualTo("consumer-esterno");
    }

    @Test
    @DisplayName("[TB-INS-TTR-007] memberId del tracciato: il primo evento che ne ha uno (il primo può non averlo)")
    void memberFromFirstWithMember() {
        String cor = uid("COR-TTR");
        Instant t = Instant.parse("2035-07-07T10:00:00Z");
        stored(uid("EVT-C"), "FACT", "campaign.status.changed", SERVICE + "campaign", null, cor, null, t, t, Map.of());
        stored(uid("EVT-A"), "ACTION", "app.login.daily", "urn:loyaltyhub:source:app", "MBR-000008", cor, null, t, t.plusMillis(3), Map.of());
        assertThat(trace(cor).path("memberId").asString()).isEqualTo("MBR-000008");
    }

    @Test
    @DisplayName("[TB-INS-TTR-008] nessun evento con membro: memberId dalla voce DLQ")
    void memberFromDlq() {
        String cor = uid("COR-TTR");
        Instant t = Instant.parse("2035-07-08T10:00:00Z");
        stored(uid("EVT-C"), "FACT", "campaign.status.changed", SERVICE + "campaign", null, cor, null, t, t, Map.of());
        openEntry("ACTION", "lh-campaign", "X", cor, t.plusMillis(1));
        assertThat(trace(cor).path("memberId").asString()).isEqualTo("MBR-000002");
    }

    @Test
    @DisplayName("[TB-INS-TTR-009] eventi di altri tracciati esclusi (stesso membro, altro correlationId)")
    void isolation() {
        String cor = uid("COR-TTR");
        Instant t = Instant.parse("2035-07-09T10:00:00Z");
        String a = uid("EVT-A");
        stored(a, "ACTION", "app.login.daily", "urn:loyaltyhub:source:app", "MBR-000009", cor, null, t, t, Map.of());
        stored(uid("EVT-X"), "ACTION", "app.login.daily", "urn:loyaltyhub:source:app", "MBR-000009", uid("COR-ALTRO"), null, t, t, Map.of());
        assertThat(nodesById(trace(cor)).keySet()).containsExactly(a);
    }

    @Test
    @DisplayName("[TB-INS-TTR-010] campi del nodo (insight §3): eventId, family, shortType, service, time = time di business, offsetMs, parentEventId, summary")
    void nodeFields() {
        String cor = uid("COR-TTR");
        Instant t = Instant.parse("2035-07-10T10:00:00Z");
        Instant business = Instant.parse("2035-07-10T09:59:00Z");
        String a = uid("EVT-A");
        stored(a, "ACTION", "purchase.completed", "urn:loyaltyhub:source:ecommerce", "MBR-000003", cor, null, business, t,
                Map.of("amount", 130));
        JsonNode node = trace(cor).path("nodes").get(0);
        List<String> fields = new ArrayList<>();
        node.propertyNames().forEach(fields::add);
        assertThat(fields).contains("eventId", "family", "shortType", "service", "time", "offsetMs", "summary");
        assertThat(node.path("time").asString()).isEqualTo(business.toString());
        assertThat(node.path("family").asString()).isEqualTo("ACTION");
        assertThat(node.path("shortType").asString()).isEqualTo("purchase.completed");
    }

    @Test
    @DisplayName("[TB-INS-TTR-011] sintesi per tipo (insight §5, AMBIGUO sulla forma): wallet.points.earned contiene «+162 PTS»")
    void nodeSummary() {
        // TESTBOOK: ambiguo, vedi TB-INS-TTR-011 (l'esempio di §5 è «+162 PTS · Acquisto»)
        String cor = uid("COR-TTR");
        Instant t = Instant.parse("2035-07-11T10:00:00Z");
        stored(uid("EVT-F"), "FACT", "wallet.points.earned", SERVICE + "wallet", "MBR-000003", cor, null, t, t,
                Map.of("amount", 162, "currency", "PTS"));
        assertThat(trace(cor).path("nodes").get(0).path("summary").asString()).contains("+162 PTS");
    }

    // ---------- TOU: esito ----------

    private JsonNode outcomeOf(String cor) {
        return trace(cor).path("outcome");
    }

    private String facts(Instant t, Object... typeAndData) {
        String cor = uid("COR-TOU");
        for (int i = 0; i < typeAndData.length; i += 2) {
            @SuppressWarnings("unchecked")
            Map<String, ?> data = (Map<String, ?>) typeAndData[i + 1];
            stored(uid("EVT-F"), "FACT", (String) typeAndData[i], SERVICE + "wallet", "MBR-000003", cor, null, t,
                    t.plusMillis(i), data);
        }
        return cor;
    }

    private Map<String, Long> points(JsonNode outcome) {
        Map<String, Long> m = new LinkedHashMap<>();
        outcome.path("points").forEach(p -> m.put(p.path("currency").asString(), p.path("amount").asLong()));
        return m;
    }

    @Test
    @DisplayName("[TB-INS-TOU-001] scenario SILVER 130 € (insight §7): outcome.points = [{PTS,162},{STS,130}]")
    void outcomeSilver() {
        String cor = facts(Instant.parse("2035-08-01T10:00:00Z"),
                "wallet.points.earned", Map.of("amount", 162, "currency", "PTS"),
                "wallet.points.earned", Map.of("amount", 130, "currency", "STS"));
        assertThat(points(outcomeOf(cor))).containsExactly(Map.entry("PTS", 162L), Map.entry("STS", 130L));
    }

    @Test
    @DisplayName("[TB-INS-TOU-002] due accrediti PTS nello stesso tracciato (acquisto + bonus): sommati per valuta")
    void outcomeSum() {
        String cor = facts(Instant.parse("2035-08-02T10:00:00Z"),
                "wallet.points.earned", Map.of("amount", 162, "currency", "PTS"),
                "wallet.points.earned", Map.of("amount", 500, "currency", "PTS"));
        assertThat(points(outcomeOf(cor))).containsExactly(Map.entry("PTS", 662L));
    }

    @Test
    @DisplayName("[TB-INS-TOU-003] nessun accredito: points vuoto, tierChange assente, contatori 0")
    void outcomeEmpty() {
        String cor = facts(Instant.parse("2035-08-03T10:00:00Z"), "campaign.evaluated", Map.of());
        JsonNode o = outcomeOf(cor);
        assertThat(o.path("points").size()).isZero();
        assertThat(isNull(o.path("tierChange"))).isTrue();
        assertThat(o.path("dlq").asInt()).isZero();
    }

    @Test
    @DisplayName("[TB-INS-TOU-004] accredito senza valuta (AMBIGUO): contato come PTS")
    void outcomeNoCurrency() {
        // TESTBOOK: ambiguo, vedi TB-INS-TOU-004
        String cor = facts(Instant.parse("2035-08-04T10:00:00Z"), "wallet.points.earned", Map.of("amount", 40));
        assertThat(points(outcomeOf(cor))).containsExactly(Map.entry("PTS", 40L));
    }

    @Test
    @DisplayName("[TB-INS-TOU-005] tier.upgraded SILVER → GOLD (EVT-FACT-28): tierChange {from SILVER, to GOLD}")
    void outcomeUpgrade() {
        String cor = facts(Instant.parse("2035-08-05T10:00:00Z"), "tier.upgraded",
                Map.of("previousTier", "SILVER", "newTier", "GOLD", "periodSts", 3010));
        JsonNode tc = outcomeOf(cor).path("tierChange");
        assertThat(tc.path("from").asString()).isEqualTo("SILVER");
        assertThat(tc.path("to").asString()).isEqualTo("GOLD");
    }

    @Test
    @DisplayName("[TB-INS-TOU-006] tier.downgraded GOLD → SILVER (EVT-FACT-29): tierChange {from GOLD, to SILVER}")
    void outcomeDowngrade() {
        String cor = facts(Instant.parse("2035-08-06T10:00:00Z"), "tier.downgraded",
                Map.of("previousTier", "GOLD", "newTier", "SILVER", "editionCode", "ED-2035"));
        JsonNode tc = outcomeOf(cor).path("tierChange");
        assertThat(tc.path("from").asString()).isEqualTo("GOLD");
        assertThat(tc.path("to").asString()).isEqualTo("SILVER");
    }

    @Test
    @DisplayName("[TB-INS-TOU-007] l'effetto points.grant da solo non è un accredito: points vuoto (niente doppio conteggio con il fatto)")
    void outcomeEffectNotCounted() {
        String cor = uid("COR-TOU");
        Instant t = Instant.parse("2035-08-07T10:00:00Z");
        stored(uid("EVT-E"), "EFFECT", "points.grant", SERVICE + "campaign", "MBR-000003", cor, null, t, t,
                Map.of("amount", 162, "currency", "PTS"));
        assertThat(outcomeOf(cor).path("points").size()).isZero();
    }

    @Test
    @DisplayName("[TB-INS-TOU-008] due message.delivered (EVT-FACT-60): outcome.messages = 2")
    void outcomeMessages() {
        String cor = facts(Instant.parse("2035-08-08T10:00:00Z"),
                "message.delivered", Map.of("templateCode", "MSG-POINTS-EARNED", "channel", "INBOX", "inboxMessageId", "1"),
                "message.delivered", Map.of("templateCode", "MSG-TIER-UP", "channel", "INBOX", "inboxMessageId", "2"));
        assertThat(outcomeOf(cor).path("messages").asInt()).isEqualTo(2);
    }

    @Test
    @DisplayName("[TB-INS-TOU-009] un coupon.issued (EVT-FACT-45): outcome.coupons = 1")
    void outcomeCoupons() {
        String cor = facts(Instant.parse("2035-08-09T10:00:00Z"),
                "coupon.issued", Map.of("couponCode", "SHP10-AAAA-BBBB", "rewardCode", "RWD-SHOP-10", "origin", "REDEMPTION"));
        assertThat(outcomeOf(cor).path("coupons").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("[TB-INS-TOU-010] tre contest.played (EVT-FACT-51): outcome.plays = 3")
    void outcomePlays() {
        String cor = facts(Instant.parse("2035-08-10T10:00:00Z"),
                "contest.played", Map.of("contestCode", "IW-X", "playId", "1", "outcome", "LOSE"),
                "contest.played", Map.of("contestCode", "IW-X", "playId", "2", "outcome", "LOSE"),
                "contest.played", Map.of("contestCode", "IW-X", "playId", "3", "outcome", "WIN"));
        assertThat(outcomeOf(cor).path("plays").asInt()).isEqualTo(3);
    }

    @Test
    @DisplayName("[TB-INS-TOU-011] due voci DLQ (una OPEN, una REPROCESSED) (AMBIGUO): outcome.dlq = 2")
    void outcomeDlq() {
        // TESTBOOK: ambiguo, vedi TB-INS-TOU-011
        String cor = uid("COR-TOU");
        Instant t = Instant.parse("2035-08-11T10:00:00Z");
        openEntry("ACTION", "lh-campaign", "X", cor, t);
        String second = openEntry("ACTION", "lh-gamification", "X", cor, t.plusMillis(5)).id();
        dlqRepo.resolve(second, "REPROCESSED", "ADMIN:setup", null);
        assertThat(outcomeOf(cor).path("dlq").asInt()).isEqualTo(2);
    }

    // ---------- TLS: elenco ----------

    private JsonNode list(String query) {
        return get("/v1/traces" + query);
    }

    private void simpleTrace(String member, String cor, Instant t) {
        stored(uid("EVT-A"), "ACTION", "purchase.completed", "urn:loyaltyhub:source:ecommerce", member, cor, null, t, t,
                Map.of("amount", 130));
    }

    @Test
    @DisplayName("[TB-INS-TLS-001] filtro memberId: solo i tracciati del membro")
    void listByMember() {
        String m = uid("MBR-TLS");
        simpleTrace(m, uid("COR-TLS"), Instant.parse("2035-09-01T10:00:00Z"));
        simpleTrace(m, uid("COR-TLS"), Instant.parse("2035-09-01T10:01:00Z"));
        simpleTrace(uid("MBR-ALTRO"), uid("COR-TLS"), Instant.parse("2035-09-01T10:02:00Z"));
        JsonNode items = list("?memberId=" + m).path("items");
        assertThat(items.size()).isEqualTo(2);
        items.forEach(i -> assertThat(i.path("memberId").asString()).isEqualTo(m));
    }

    @Test
    @DisplayName("[TB-INS-TLS-002] ordine: prima il tracciato con l'attività più recente")
    void listOrder() {
        String m = uid("MBR-TLS");
        String a = uid("COR-A");
        String b = uid("COR-B");
        Instant t = Instant.parse("2035-09-02T10:00:00Z");
        simpleTrace(m, a, t);
        simpleTrace(m, b, t.plusSeconds(5));
        stored(uid("EVT-F"), "FACT", "wallet.points.earned", SERVICE + "wallet", m, a, null, t, t.plusSeconds(10),
                Map.of("amount", 1, "currency", "PTS"));
        List<String> order = new ArrayList<>();
        list("?memberId=" + m).path("items").forEach(i -> order.add(i.path("correlationId").asString()));
        assertThat(order).containsExactly(a, b);
    }

    @Test
    @DisplayName("[TB-INS-TLS-003] uno per correlationId: tre eventi dello stesso tracciato ⇒ una riga")
    void listOnePerTrace() {
        String m = uid("MBR-TLS");
        String c = uid("COR-TLS");
        Instant t = Instant.parse("2035-09-03T10:00:00Z");
        simpleTrace(m, c, t);
        stored(uid("EVT-E"), "EFFECT", "points.grant", SERVICE + "campaign", m, c, null, t, t.plusMillis(5), Map.of());
        stored(uid("EVT-F"), "FACT", "wallet.points.earned", SERVICE + "wallet", m, c, null, t, t.plusMillis(9), Map.of());
        assertThat(list("?memberId=" + m).path("items").size()).isEqualTo(1);
    }

    @Test
    @DisplayName("[TB-INS-TLS-004] limit=1: solo il più recente")
    void listLimit() {
        String m = uid("MBR-TLS");
        String newest = uid("COR-N");
        simpleTrace(m, uid("COR-O"), Instant.parse("2035-09-04T10:00:00Z"));
        simpleTrace(m, newest, Instant.parse("2035-09-04T11:00:00Z"));
        JsonNode items = list("?limit=1&memberId=" + m).path("items");
        assertThat(items.size()).isEqualTo(1);
        assertThat(items.get(0).path("correlationId").asString()).isEqualTo(newest);
    }

    @Test
    @DisplayName("[TB-INS-TLS-005] limit=0 (AMBIGUO): portato a 1")
    void listLimitZero() {
        // TESTBOOK: ambiguo, vedi TB-INS-TLS-005
        String m = uid("MBR-TLS");
        simpleTrace(m, uid("COR-O"), Instant.parse("2035-09-05T10:00:00Z"));
        simpleTrace(m, uid("COR-N"), Instant.parse("2035-09-05T11:00:00Z"));
        assertThat(list("?limit=0&memberId=" + m).path("items").size()).isEqualTo(1);
    }

    @Test
    @DisplayName("[TB-INS-TLS-006] from/to sull'istante d'arrivo, estremi inclusi: dentro sì, un'ora dopo no")
    void listWindow() {
        String m = uid("MBR-TLS");
        String in = uid("COR-IN");
        Instant t = Instant.parse("2035-09-06T10:00:00Z");
        simpleTrace(m, in, t);
        simpleTrace(m, uid("COR-OUT"), t.plusSeconds(3600));
        List<String> ids = new ArrayList<>();
        list("?memberId=" + m + "&from=" + t + "&to=" + t).path("items")
                .forEach(i -> ids.add(i.path("correlationId").asString()));
        assertThat(ids).containsExactly(in);
    }

    @Test
    @DisplayName("[TB-INS-TLS-007] from non ISO-8601: 400")
    void listBadFrom() {
        Resp r = call("GET", "/v1/traces?from=ieri", null, null);
        assertThat(r.status()).isEqualTo(400);
        assertThat(r.code()).isEqualTo("BAD_REQUEST");
    }

    @Test
    @DisplayName("[TB-INS-TLS-008] to non ISO-8601: 400")
    void listBadTo() {
        assertThat(call("GET", "/v1/traces?to=2035-13-45", null, null).status()).isEqualTo(400);
    }

    @Test
    @DisplayName("[TB-INS-TLS-009] riga di elenco (BO-25): azione radice, inizio, durata, stato, esito «+162 PTS · +130 STS · tier GOLD»")
    void listRow() {
        String m = uid("MBR-TLS");
        String c = uid("COR-TLS");
        Instant t = Instant.parse("2035-09-09T10:00:00Z");
        String a = uid("EVT-A");
        stored(a, "ACTION", "purchase.completed", "urn:loyaltyhub:source:ecommerce", m, c, null, t, t, Map.of("amount", 130));
        stored(uid("EVT-F1"), "FACT", "wallet.points.earned", SERVICE + "wallet", m, c, a, t, t.plusMillis(400),
                Map.of("amount", 162, "currency", "PTS"));
        stored(uid("EVT-F2"), "FACT", "wallet.points.earned", SERVICE + "wallet", m, c, a, t, t.plusMillis(500),
                Map.of("amount", 130, "currency", "STS"));
        stored(uid("EVT-F3"), "FACT", "tier.upgraded", SERVICE + "wallet", m, c, a, t, t.plusMillis(600),
                Map.of("previousTier", "SILVER", "newTier", "GOLD"));
        CLOCK.set(t.plusSeconds(60));
        JsonNode row = list("?memberId=" + m).path("items").get(0);
        assertThat(row.path("rootShortType").asString()).isEqualTo("purchase.completed");
        assertThat(row.path("startedAt").asString()).isEqualTo(t.toString());
        assertThat(row.path("durationMs").asLong()).isEqualTo(600);
        assertThat(row.path("status").asString()).isEqualTo("COMPLETE");
        assertThat(row.path("outcomeSummary").asString()).isEqualTo("+162 PTS · +130 STS · tier GOLD");
    }

    @Test
    @DisplayName("[TB-INS-TLS-010] risposta paginata {items, page} (docs/06 §2)")
    void listShape() {
        String m = uid("MBR-TLS");
        simpleTrace(m, uid("COR-TLS"), Instant.parse("2035-09-10T10:00:00Z"));
        JsonNode body = list("?memberId=" + m);
        assertThat(body.path("items").size()).isEqualTo(1);
        assertThat(body.path("page").path("totalItems").asLong()).as("oggetto page di docs/06 §2").isEqualTo(1);
    }

    @Test
    @DisplayName("[TB-INS-TLS-011] nessun parametro: tracciati recenti, al più 100 righe (docs/06 §2), uno per correlationId")
    void listDefault() {
        simpleTrace(uid("MBR-TLS"), uid("COR-TLS"), Instant.parse("2035-09-11T10:00:00Z"));
        JsonNode items = list("").path("items");
        assertThat(items.size()).isBetween(1, 100);
        Map<String, Integer> seen = new HashMap<>();
        items.forEach(i -> seen.merge(i.path("correlationId").asString(), 1, Integer::sum));
        assertThat(seen.values()).allMatch(v -> v == 1);
    }
}
