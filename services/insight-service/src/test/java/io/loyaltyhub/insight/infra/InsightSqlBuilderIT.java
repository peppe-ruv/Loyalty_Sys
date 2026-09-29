package io.loyaltyhub.insight.infra;

import io.loyaltyhub.insight.domain.AuditRecord;
import io.loyaltyhub.insight.domain.DlqEntry;
import io.loyaltyhub.insight.domain.StoredEvent;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ricerche di event store, tracciati, DLQ e audit col builder SQL comune sul database vero (regola 19, ADR-042,
 * docs/18 §3.10 punto 4): stessi risultati, stesso ordine e stessa paginazione di prima, filtri combinati in
 * {@code AND}, {@code q} letterale ({@code %}, {@code _} e {@code \} non sono caratteri jolly) e tentativi di iniezione
 * senza effetto. Solo database (Flyway su Postgres incorporato), senza Kafka né contesto Spring; dati propri.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class InsightSqlBuilderIT {

    private static final String INJECTION = "x%' OR 1=1 --";
    private static final Instant T0 = Instant.parse("2026-09-10T08:00:00Z");

    private final ObjectMapper mapper = new ObjectMapper();

    private EmbeddedPostgres pg;
    private JdbcClient jdbc;
    private EventStoreRepository events;
    private DlqRepository dlq;
    private AuditRepository audit;

    @BeforeAll
    void setUp() throws Exception {
        pg = EmbeddedPostgres.builder().start();
        SimpleDriverDataSource ds = new SimpleDriverDataSource(new org.postgresql.Driver(),
                pg.getJdbcUrl("postgres", "postgres") + "&currentSchema=insight", "postgres", "");
        Flyway.configure().dataSource(ds).schemas("insight").defaultSchema("insight").createSchemas(true)
                .locations("classpath:db/migration/common", "classpath:db/migration/insight").load().migrate();
        jdbc = JdbcClient.create(ds);
        events = new EventStoreRepository(jdbc);
        dlq = new DlqRepository(jdbc, mapper, Clock.systemUTC());
        audit = new AuditRepository(jdbc, mapper);

        // Event store: E1..E5 ricevuti a un minuto l'uno dall'altro (E5 il più recente), E6 senza tracciato.
        event("E1", "lh.actions.v1", "ACTION", "purchase.completed", "MBR-1", "COR-A", "{\"note\":\"sconto 100%\"}", 0);
        event("E2", "lh.effects.v1", "EFFECT", "points.grant", "MBR-1", "COR-A", "{\"note\":\"sconto 100 euro\"}", 1);
        event("E3", "lh.facts.v1", "FACT", "wallet.points.earned", "MBR-2", "COR-B", "{\"note\":\"a_b\"}", 2);
        event("E4", "lh.facts.v1", "FACT", "member.registered", "MBR-2", "COR-C", "{\"note\":\"axb\"}", 3);
        event("E5", "lh.facts.v1", "FACT", "tier.changed", "MBR-1", "COR-D", "{\"note\":\"c:\\\\tmp\"}", 4);
        event("E6", "lh.facts.v1", "FACT", "tier.changed", "MBR-3", null, "{\"note\":\"senza tracciato\"}", 5);

        // DLQ: D1 e D2 visti nello stesso istante (spareggio per id decrescente), D3 chiusa.
        dlqEntry("D1", "lh-campaign", "RULE_ERROR", T0);
        dlqEntry("D2", "lh-campaign", "RULE_ERROR", T0);
        dlqEntry("D3", "lh-wallet", "TIMEOUT", T0.minusSeconds(60));
        dlq.resolve("D3", DlqEntry.DISCARDED, "ADMIN:test", "chiusa per il test");

        // Audit: tre voci di due servizi, in ordine di tempo A1 < A2 < A3.
        auditEntry("A1", "wallet", "RULE", "R-1", "UPDATE", "anna", T0);
        auditEntry("A2", "wallet", "RULE", "R-2", "CREATE", "bruno", T0.plusSeconds(60));
        auditEntry("A3", "campaign", "CAMPAIGN", "C-1", "UPDATE", "anna", T0.plusSeconds(120));
    }

    @AfterAll
    void stop() throws Exception {
        pg.close();
    }

    // ---------- event store ----------

    @Test
    void eventsCombineFiltersAndKeepNewestFirst() {
        assertThat(ids(events.search(null, null, null, null, null, null, null, null, null, 10, 0)))
                .containsExactly("E6", "E5", "E4", "E3", "E2", "E1");
        assertThat(ids(events.search(" lh.facts.v1 ", " fact ", null, " MBR-2 ", null, null, null, null, null, 10, 0)))
                .containsExactly("E4", "E3");
        assertThat(ids(events.search(null, null, "tier.changed", "MBR-1", "COR-D", "test", null, null, null, 10, 0)))
                .containsExactly("E5");
        assertThat(events.count(" lh.facts.v1 ", "fact", null, "MBR-2", null, null, null, null, null)).isEqualTo(2);
    }

    @Test
    void eventsTimeWindowIsInclusiveOnReceivedAt() {
        Instant from = T0.plusSeconds(60);
        Instant to = T0.plusSeconds(180);
        assertThat(ids(events.search(null, null, null, null, null, null, from, to, null, 10, 0)))
                .containsExactly("E4", "E3", "E2");
        assertThat(events.count(null, null, null, null, null, null, from, to, null)).isEqualTo(3);
    }

    @Test
    void eventsPageWithLimitAndOffset() {
        assertThat(ids(events.search(null, null, null, null, null, null, null, null, null, 2, 1)))
                .containsExactly("E5", "E4");
        assertThat(ids(events.search(null, null, null, null, null, null, null, null, null, 2, 6))).isEmpty();
    }

    @Test
    void eventTextSearchIsLiteral() {
        assertThat(ids(events.search(null, null, null, null, null, null, null, null, " SCONTO ", 10, 0)))
                .containsExactly("E2", "E1");
        assertThat(ids(events.search(null, null, null, null, null, null, null, null, "100%", 10, 0)))
                .containsExactly("E1");
        assertThat(ids(events.search(null, null, null, null, null, null, null, null, "a_b", 10, 0)))
                .containsExactly("E3");
        assertThat(ids(events.search(null, null, null, null, null, null, null, null, "c:\\\\tmp", 10, 0)))
                .containsExactly("E5");
        // "%" da solo non vale "qualsiasi testo": trova solo il payload che contiene davvero il carattere.
        assertThat(events.count(null, null, null, null, null, null, null, null, "%")).isEqualTo(1);
    }

    @Test
    void eventInjectionHasNoEffect() {
        long before = events.count();
        assertThat(events.search(INJECTION, null, null, null, null, null, null, null, null, 10, 0)).isEmpty();
        assertThat(events.search(null, null, null, INJECTION, null, null, null, null, INJECTION, 10, 0)).isEmpty();
        assertThat(events.count(null, null, null, null, null, null, null, null, INJECTION)).isZero();
        assertThat(events.recentCorrelationIds(INJECTION, null, null, 10, 0)).isEmpty();
        assertThat(events.count()).isEqualTo(before);
    }

    // ---------- tracciati ----------

    @Test
    void correlationsAreDistinctNewestFirstAndFiltered() {
        assertThat(events.recentCorrelationIds(null, null, null, 10, 0))
                .containsExactly("COR-D", "COR-C", "COR-B", "COR-A");
        assertThat(events.countCorrelationIds(null, null, null)).isEqualTo(4);
        assertThat(events.recentCorrelationIds(" MBR-1 ", null, null, 10, 0)).containsExactly("COR-D", "COR-A");
        assertThat(events.recentCorrelationIds(null, T0.plusSeconds(60), T0.plusSeconds(120), 10, 0))
                .containsExactly("COR-B", "COR-A");
        assertThat(events.recentCorrelationIds("MBR-1", null, null, 1, 1)).containsExactly("COR-A");
        assertThat(events.countCorrelationIds("MBR-1", null, T0)).isEqualTo(1);
    }

    // ---------- DLQ ----------

    @Test
    void dlqFiltersAndTieBreakOnId() {
        assertThat(dlqIds(dlq.search(null, null, null, 10, 0))).containsExactly("D2", "D1", "D3");
        assertThat(dlqIds(dlq.search(" open ", " lh-campaign ", " RULE_ERROR ", 10, 0))).containsExactly("D2", "D1");
        assertThat(dlqIds(dlq.search("discarded", null, null, 10, 0))).containsExactly("D3");
        assertThat(dlqIds(dlq.search(null, null, null, 1, 1))).containsExactly("D1");
        assertThat(dlq.count("OPEN", null, "TIMEOUT")).isZero();
        assertThat(dlq.count(null, "lh-wallet", "TIMEOUT")).isEqualTo(1);
        assertThat(dlq.search(INJECTION, INJECTION, INJECTION, 10, 0)).isEmpty();
        assertThat(dlq.count(null, null, null)).isEqualTo(3);
    }

    // ---------- audit ----------

    @Test
    void auditFiltersNewestFirstAndValuesAsReceived() {
        assertThat(auditIds(audit.search(null, null, null, null, null, null, null, null, 10, 0)))
                .containsExactly("A3", "A2", "A1");
        assertThat(auditIds(audit.search("anna", null, null, null, null, "UPDATE", null, null, 10, 0)))
                .containsExactly("A3", "A1");
        assertThat(auditIds(audit.search(null, "ADMIN", "wallet", "RULE", "R-2", null, null, null, 10, 0)))
                .containsExactly("A2");
        assertThat(auditIds(audit.search(null, null, null, null, null, null, T0.plusSeconds(60), T0.plusSeconds(120),
                10, 0))).containsExactly("A3", "A2");
        assertThat(auditIds(audit.search(null, null, null, null, null, null, null, null, 1, 1))).containsExactly("A2");
        // Come prima del builder, l'audit non toglie gli spazi: " wallet" non è "wallet".
        assertThat(audit.count(null, null, " wallet", null, null, null, null, null)).isZero();
        assertThat(audit.count(" ", "", null, null, null, null, null, null)).isEqualTo(3);
    }

    @Test
    void auditInjectionHasNoEffect() {
        assertThat(audit.search(INJECTION, INJECTION, INJECTION, INJECTION, INJECTION, INJECTION, null, null, 10, 0))
                .isEmpty();
        assertThat(audit.count(null, null, INJECTION, null, null, null, null, null)).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM audit_entry").query(Long.class).single()).isEqualTo(3);
    }

    // ---------- dati ----------

    private void event(String id, String topic, String family, String shortType, String memberId,
                       String correlationId, String payload, int minute) {
        events.insert(new StoredEvent(id, topic, family, "io.loyaltyhub." + family.toLowerCase() + "." + shortType,
                shortType, "test", memberId, correlationId, null, 0, "SYSTEM:test", null, T0, null, 0, minute,
                payload));
        jdbc.sql("UPDATE event_store SET received_at = ? WHERE event_id = ?")
                .params(Timestamp.from(T0.plusSeconds(60L * minute)), id).update();
    }

    private void dlqEntry(String id, String consumer, String errorCode, Instant seen) {
        dlq.insert(new DlqEntry(id, "EVT-" + id, "lh.actions.v1", "io.loyaltyhub.action.purchase.completed", "ACTION",
                consumer, errorCode, null, "errore di prova", null, true, 1, "MBR-1", "COR-" + id,
                mapper.readTree("{}"), seen, DlqEntry.OPEN, null, null, null), 0, 0L);
    }

    private void auditEntry(String id, String service, String entityType, String entityId, String action,
                            String actor, Instant at) {
        audit.insert(new AuditRecord(id, "EVT-" + id, at, "ADMIN", actor, service, entityType, entityId, action,
                "voce di prova " + id, null, mapper.readTree("{\"x\":1}"), "COR-" + id));
    }

    private static List<String> ids(List<StoredEvent> list) {
        return list.stream().map(StoredEvent::eventId).toList();
    }

    private static List<String> dlqIds(List<DlqEntry> list) {
        return list.stream().map(DlqEntry::id).toList();
    }

    private static List<String> auditIds(List<AuditRecord> list) {
        return list.stream().map(AuditRecord::id).toList();
    }
}
