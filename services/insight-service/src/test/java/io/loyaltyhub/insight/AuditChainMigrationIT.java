package io.loyaltyhub.insight;

import io.loyaltyhub.insight.application.AuditChainVerifier;
import io.loyaltyhub.insight.domain.AuditChainReport;
import io.loyaltyhub.insight.domain.AuditVerification;
import io.loyaltyhub.insight.infra.AuditChainRepository;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

import java.time.Clock;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Migrazione V6 su un database con voci di audit già presenti (F2-GRC-07, ADR-038 expand): le voci esistenti entrano
 * in catena in modo deterministico (per servizio, in ordine di {@code id}), la testa e un'ancora {@code BACKFILL}
 * fissano lo stato alla migrazione, e il verificatore Java, indipendente dalle funzioni SQL, trova le catene integre.
 * È anche la prova che le due implementazioni della forma canonica (SQL e Java) coincidono su testi Unicode, jsonb con
 * chiavi disordinate e numeri decimali, campi nulli e istanti con microsecondi.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AuditChainMigrationIT {

    private EmbeddedPostgres pg;
    private SimpleDriverDataSource ds;
    private JdbcClient jdbc;

    @BeforeAll
    void migrateWithLegacyRows() throws Exception {
        pg = EmbeddedPostgres.builder().start();
        ds = new SimpleDriverDataSource(new org.postgresql.Driver(),
                pg.getJdbcUrl("postgres", "postgres") + "&currentSchema=insight", "postgres", "");
        jdbc = JdbcClient.create(ds);
        flyway("5").migrate();

        // Voci scritte dalla versione precedente (V5): nessuna colonna di catena. Id non in ordine d'inserimento.
        legacy("01J8Z0000000000000000000C3", "campaign", "2026-09-01T10:00:00.123456Z", "Terza «voce» ✓",
                "{\"z\": 1, \"a\": {\"y\": [1, 2.50, null], \"b\": \"è\"}}", null, null);
        legacy("01J8Z0000000000000000000C1", "campaign", "2026-09-01T09:00:00Z", "Prima", null,
                "{\"priority\": 90}", "COR-1");
        legacy("01J8Z0000000000000000000C2", "campaign", "2026-09-01T09:30:00.5Z", "", "{}", "[]", "COR-2");
        legacy("01J8Z0000000000000000000W1", "wallet", "2026-09-02T12:00:00Z", null, null, null, null);
        legacy("01J8Z0000000000000000000W2", "wallet", "2026-09-02T12:00:01Z", "Rettifica 😀", "{\"b\": 1}", "{\"b\": 2}",
                "COR-W");

        flyway(null).migrate();
    }

    @AfterAll
    void stop() throws Exception {
        pg.close();
    }

    private Flyway flyway(String target) {
        var config = Flyway.configure().dataSource(ds).schemas("insight").defaultSchema("insight").createSchemas(true)
                .locations("classpath:db/migration/common", "classpath:db/migration/insight");
        if (target != null) {
            config.target(target);
        }
        return config.load();
    }

    private void legacy(String id, String service, String at, String summary, String before, String after,
                        String correlationId) {
        jdbc.sql("""
                        INSERT INTO audit_entry (id, event_id, at, actor_role, actor_name, service, entity_type,
                                                 entity_id, action, summary, before, after, correlation_id)
                        VALUES (?, ?, cast(? AS timestamptz), 'ADMIN', 'marta.admin', ?, 'CAMPAIGN', 'CMP-1', 'UPDATE',
                                ?, cast(? AS jsonb), cast(? AS jsonb), ?)
                        """)
                .params(id, "EVT-" + id, at, service, summary, before, after, correlationId).update();
    }

    private AuditVerification verify() {
        return new AuditChainVerifier(new AuditChainRepository(jdbc), Clock.systemUTC()).verify(null);
    }

    @Test
    @Order(1)
    @DisplayName("voci esistenti in catena per servizio e in ordine di id, testa e ancora BACKFILL")
    void backfillIsDeterministic() {
        assertThat(jdbc.sql("SELECT service || ':' || seq || ':' || id FROM audit_entry ORDER BY service, seq")
                .query(String.class).list()).containsExactly(
                "campaign:1:01J8Z0000000000000000000C1",
                "campaign:2:01J8Z0000000000000000000C2",
                "campaign:3:01J8Z0000000000000000000C3",
                "wallet:1:01J8Z0000000000000000000W1",
                "wallet:2:01J8Z0000000000000000000W2");
        assertThat(jdbc.sql("SELECT prev_hash FROM audit_entry WHERE seq = 1").query(String.class).list())
                .containsOnly("0".repeat(64));
        assertThat(jdbc.sql("SELECT service || ':' || seq FROM audit_chain_head ORDER BY service").query(String.class)
                .list()).containsExactly("campaign:3", "wallet:2");
        assertThat(jdbc.sql("SELECT kind || ':' || service || ':' || seq FROM audit_anchor ORDER BY service")
                .query(String.class).list()).containsExactly("BACKFILL:campaign:3", "BACKFILL:wallet:2");
    }

    @Test
    @Order(2)
    @DisplayName("il verificatore Java ricalcola gli stessi hash del database: catene integre")
    void javaAgreesWithSql() {
        AuditVerification v = verify();
        assertThat(v.services()).extracting(AuditChainReport::service).containsExactly("campaign", "wallet");
        assertThat(v.services()).allSatisfy(r -> {
            assertThat(r.ok()).as(r.detail()).isTrue();
            assertThat(r.anchorsChecked()).isEqualTo(1);
        });
        assertThat(v.status()).isEqualTo(AuditChainReport.Status.OK);
    }

    @Test
    @Order(3)
    @DisplayName("voce nuova dopo la migrazione: prosegue la catena riempita; le funzioni hanno lo schema fissato")
    void newEntriesContinueTheChain() {
        jdbc.sql("""
                        INSERT INTO audit_entry (id, event_id, at, actor_role, actor_name, service, entity_type,
                                                 entity_id, action, summary)
                        VALUES ('01J8Z0000000000000000000C4', 'EVT-C4', now(), 'ADMIN', 'marta.admin', 'campaign',
                                'CAMPAIGN', 'CMP-1', 'UPDATE', 'Quarta')
                        """).update();
        assertThat(jdbc.sql("SELECT seq FROM audit_entry WHERE id = '01J8Z0000000000000000000C4'").query(Long.class)
                .single()).isEqualTo(4);
        assertThat(jdbc.sql("""
                        SELECT n.prev_hash = p.entry_hash FROM audit_entry n JOIN audit_entry p
                          ON p.service = n.service AND p.seq = n.seq - 1 WHERE n.id = '01J8Z0000000000000000000C4'
                        """).query(Boolean.class).single()).isTrue();
        assertThat(verify().status()).isEqualTo(AuditChainReport.Status.OK);

        // SET search_path FROM CURRENT: le funzioni risolvono le tabelle nello schema del servizio anche quando la
        // sessione ne elenca altri (l'hub mette tutti gli schemi nel search_path).
        List<String> configs = jdbc.sql("""
                        SELECT array_to_string(p.proconfig, ',') FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
                        WHERE n.nspname = 'insight' AND p.proname IN ('audit_entry_chain', 'audit_purge_before',
                                                                      'audit_reset', 'audit_redact')
                        """).query(String.class).list();
        assertThat(configs).hasSize(4).allSatisfy(c -> assertThat(c).contains("search_path=").contains("insight"));
    }
}
