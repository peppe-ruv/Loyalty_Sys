package io.loyaltyhub.ingestion.infra;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Anonimizzazione in ingestion senza corrompere i movimenti in ingresso (F-MBR-05, Q-404): e-mail e id esterno del membro
 * si sostituiscono nel payload conservato e nel dettaglio di rifiuto solo come parole intere; un id esterno che ne
 * contiene un altro ({@code CRM-101} per il membro {@code CRM-10}), un'e-mail che lo contiene
 * ({@code leada@example.test}), stati e codici restano. Solo database (Postgres incorporato e migrazioni del servizio),
 * senza Kafka né contesto Spring.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MemberErasureRepositoryIT {

    private EmbeddedPostgres pg;
    private JdbcClient jdbc;
    private MemberErasureRepository repository;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeAll
    void migrate() throws Exception {
        pg = EmbeddedPostgres.builder().start();
        SimpleDriverDataSource ds = new SimpleDriverDataSource(new org.postgresql.Driver(),
                pg.getJdbcUrl("postgres", "postgres") + "&currentSchema=ingestion", "postgres", "");
        Flyway.configure().dataSource(ds).schemas("ingestion").defaultSchema("ingestion").createSchemas(true)
                .locations("classpath:db/migration/common", "classpath:db/migration/ingestion").load().migrate();
        jdbc = JdbcClient.create(ds);
        repository = new MemberErasureRepository(jdbc, mapper);
    }

    @AfterAll
    void stop() throws Exception {
        pg.close();
    }

    private void inbound(String id, String subject, String memberId, String status, String payload, String rejectDetail) {
        jdbc.sql("""
                        INSERT INTO inbound_event (id, event_id, source_code, type_code, subject, member_id, event_time,
                                                   status, payload, correlation_id, reject_detail)
                        VALUES (?, ?, 'SRC-POS', 'purchase', ?, ?, now(), ?, cast(? AS jsonb), ?, ?)
                        """)
                .params(id, "EVT-" + id, subject, memberId, status, payload, "COR-" + id, rejectDetail).update();
    }

    private JsonNode payload(String id) {
        return mapper.readTree(jdbc.sql("SELECT payload::text FROM inbound_event WHERE id = ?").param(id)
                .query(String.class).single());
    }

    private String subject(String id) {
        return jdbc.sql("SELECT subject FROM inbound_event WHERE id = ?").param(id).query(String.class).single();
    }

    private String rejectDetail(String id) {
        return jdbc.sql("SELECT reject_detail FROM inbound_event WHERE id = ?").param(id).query(String.class).single();
    }

    @Test
    @DisplayName("id esterno CRM-10 ed e-mail ada@…: sostituiti come parole intere, CRM-101 e leada@… restano")
    void externalIdAndEmailInsideLongerValuesAreKept() {
        jdbc.sql("INSERT INTO member_index (member_id, external_id, email_lower, status) VALUES (?, ?, ?, 'ACTIVE')")
                .params("MBR-000902", "CRM-10", "ada@example.test").update();
        inbound("IN-1", "external:CRM-10", null, "UNMATCHED", """
                {"specversion":"1.0","id":"EVT-IN-1","type":"purchase","source":"urn:loyaltyhub:source:pos",
                 "subject":"external:CRM-10",
                 "data":{"externalId":"CRM-10","email":"ada@example.test","orderRef":"CRM-101","status":"PAID",
                         "note":"Cliente CRM-10, ordine CRM-101, copia a leada@example.test e ada@example.test"}}""",
                "Membro non trovato per CRM-10 (esiste CRM-101)");
        inbound("IN-2", "member:MBR-000902", "MBR-000902", "ACCEPTED", """
                {"specversion":"1.0","id":"EVT-IN-2","type":"purchase","source":"urn:loyaltyhub:source:pos",
                 "subject":"member:MBR-000902","data":{"store":"CRM-100 Centro","amount":12.5}}""", null);
        inbound("IN-3", "external:CRM-101", null, "UNMATCHED", """
                {"specversion":"1.0","id":"EVT-IN-3","type":"purchase","source":"urn:loyaltyhub:source:pos",
                 "subject":"external:CRM-101","data":{"externalId":"CRM-101"}}""", "Membro non trovato per CRM-101");

        repository.erase("MBR-000902");

        JsonNode p1 = payload("IN-1");
        assertThat(subject("IN-1")).isEqualTo("member:MBR-000902");
        assertThat(p1.path("subject").asString()).isEqualTo("member:MBR-000902");
        assertThat(p1.path("data").has("externalId")).isFalse();
        assertThat(p1.path("data").has("email")).isFalse();
        assertThat(p1.path("data").path("orderRef").asString()).isEqualTo("CRM-101");
        assertThat(p1.path("data").path("status").asString()).isEqualTo("PAID");
        assertThat(p1.path("data").path("note").asString())
                .isEqualTo("Cliente Membro anonimo, ordine CRM-101, copia a leada@example.test e Membro anonimo");
        assertThat(rejectDetail("IN-1")).isEqualTo("Membro non trovato per Membro anonimo (esiste CRM-101)");

        assertThat(payload("IN-2").path("data").path("store").asString()).isEqualTo("CRM-100 Centro");
        assertThat(subject("IN-3")).as("evento di un altro id esterno: non toccato").isEqualTo("external:CRM-101");
        assertThat(rejectDetail("IN-3")).isEqualTo("Membro non trovato per CRM-101");
        assertThat(jdbc.sql("SELECT coalesce(external_id, '-') || ':' || coalesce(email_lower, '-') || ':' || status "
                + "FROM member_index WHERE member_id = 'MBR-000902'").query(String.class).single()).isEqualTo("-:-:ANONYMIZED");
    }

    @Test
    @DisplayName("Q-404: id esterno ed e-mail per intero in campi di identificativo e codice: sostituiti, stati e codici restano")
    void wholeMemberValuesInIdentifierAndCodeFieldsAreReplaced() {
        jdbc.sql("INSERT INTO member_index (member_id, external_id, email_lower, status) VALUES (?, ?, ?, 'ACTIVE')")
                .params("MBR-000904", "CRM101", "mario.rossi@example.test").update();
        inbound("IN-4", "external:CRM101", null, "UNMATCHED", """
                {"specversion":"1.0","id":"EVT-IN-4","type":"purchase","source":"urn:loyaltyhub:source:pos",
                 "subject":"external:CRM101","lhactor":"mario.rossi@example.test",
                 "data":{"customerId":"CRM101","loginId":"Mario.Rossi@example.test","customerCode":"crm101",
                         "customer":"CRM101","channel":"CRM101","status":"PAID","reason":"TEST",
                         "promoCode":"CRM1010","customerRef":{"id":"CRM101","source":"mario.rossi@example.test"}}}""",
                "Membro non trovato per CRM101");

        repository.erase("MBR-000904");

        JsonNode p = payload("IN-4");
        JsonNode d = p.path("data");
        for (String key : new String[]{"customerId", "loginId", "customerCode", "customer", "channel"}) {
            assertThat(d.path(key).asString()).as(key).isEqualTo("Membro anonimo");
        }
        assertThat(d.path("customerRef").path("id").asString()).isEqualTo("Membro anonimo");
        assertThat(d.path("customerRef").path("source").asString()).isEqualTo("Membro anonimo");
        assertThat(p.path("lhactor").asString()).isEqualTo("Membro anonimo");
        assertThat(d.path("status").asString()).isEqualTo("PAID");
        assertThat(d.path("reason").asString()).isEqualTo("TEST");
        assertThat(d.path("promoCode").asString()).as("un altro codice").isEqualTo("CRM1010");
        assertThat(p.path("id").asString()).isEqualTo("EVT-IN-4");
        assertThat(p.toString()).doesNotContainIgnoringCase("mario.rossi").doesNotContain("\"CRM101\"");
        assertThat(rejectDetail("IN-4")).isEqualTo("Membro non trovato per Membro anonimo");

        repository.erase("MBR-000904");
        assertThat(payload("IN-4")).as("idempotente").isEqualTo(p);
    }
}
