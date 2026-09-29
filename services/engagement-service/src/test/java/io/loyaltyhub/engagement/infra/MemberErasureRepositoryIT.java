package io.loyaltyhub.engagement.infra;

import io.loyaltyhub.engagement.domain.WebhookSignature;
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
 * Anonimizzazione in engagement senza corrompere i testi conservati (F-MBR-05, Q-404): il nome del membro si sostituisce
 * nei messaggi e nelle consegne webhook solo come parola intera; parole che lo contengono («Adamo», {@code ADA7}), i
 * codici nei campi di codice ({@code RWD-ADA}) e gli stati ({@code ANONYMIZED} con un nome «Anon») restano. Solo
 * database (Postgres incorporato e migrazioni del servizio), senza Kafka né contesto Spring.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MemberErasureRepositoryIT {

    private static final String SECRET = "whsec-test-erasure";

    private EmbeddedPostgres pg;
    private JdbcClient jdbc;
    private MemberErasureRepository repository;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeAll
    void migrate() throws Exception {
        pg = EmbeddedPostgres.builder().start();
        SimpleDriverDataSource ds = new SimpleDriverDataSource(new org.postgresql.Driver(),
                pg.getJdbcUrl("postgres", "postgres") + "&currentSchema=engagement", "postgres", "");
        Flyway.configure().dataSource(ds).schemas("engagement").defaultSchema("engagement").createSchemas(true)
                .locations("classpath:db/migration/common", "classpath:db/migration/engagement").load().migrate();
        jdbc = JdbcClient.create(ds);
        repository = new MemberErasureRepository(jdbc, mapper);
        jdbc.sql("INSERT INTO webhook (id, code, name, url, secret) VALUES ('WH1', 'WH-ERASE', 'Prova', ?, ?)")
                .params("https://hooks.example.test/in", SECRET).update();
    }

    @AfterAll
    void stop() throws Exception {
        pg.close();
    }

    private void member(String memberId, String firstName) {
        jdbc.sql("INSERT INTO engagement_member_snapshot (member_id, first_name) VALUES (?, ?)")
                .params(memberId, firstName).update();
    }

    private void message(String id, String memberId, String title, String body) {
        jdbc.sql("""
                        INSERT INTO inbox_message (id, member_id, template_code, channel, title, body, category,
                                                   source_event_id)
                        VALUES (?, ?, 'TPL-WELCOME', 'INBOX', ?, ?, 'INFO', ?)
                        """)
                .params(id, memberId, title, body, "EVT-" + id).update();
    }

    private void delivery(String id, String memberId, String payload) {
        jdbc.sql("""
                        INSERT INTO webhook_delivery (id, webhook_id, event_id, fact_type, member_id, payload, signature)
                        VALUES (?, 'WH1', ?, 'member.status.changed', ?, ?, ?)
                        """)
                .params(id, "EVT-" + id, memberId, payload, WebhookSignature.sign(SECRET, payload)).update();
    }

    private String[] messageText(String id) {
        return jdbc.sql("SELECT title, body FROM inbox_message WHERE id = ?").param(id)
                .query((rs, n) -> new String[]{rs.getString("title"), rs.getString("body")}).single();
    }

    private JsonNode deliveryPayload(String id) {
        String payload = jdbc.sql("SELECT payload FROM webhook_delivery WHERE id = ?").param(id).query(String.class).single();
        String signature = jdbc.sql("SELECT signature FROM webhook_delivery WHERE id = ?").param(id)
                .query(String.class).single();
        assertThat(signature).as("consegna rifirmata sul corpo ripulito").isEqualTo(WebhookSignature.sign(SECRET, payload));
        return mapper.readTree(payload);
    }

    @Test
    @DisplayName("Q-404: nome «Ada» per intero in un campo di codice («level»): sostituito; ACTIVE e GOLD restano")
    void wholeNameInACodedFieldIsReplaced() {
        member("MBR-000903", "Ada");
        delivery("DLV-903", "MBR-000903", """
                {"specversion":"1.0","id":"EVT-LEVEL","type":"io.loyaltyhub.fact.member.updated",
                 "source":"urn:loyaltyhub:service:member","subject":"member:MBR-000903",
                 "data":{"memberId":"MBR-000903","level":"Ada","tier":"GOLD","status":"ACTIVE","channel":"ada"}}""");

        repository.erase("MBR-000903");

        JsonNode d = deliveryPayload("DLV-903").path("data");
        assertThat(d.path("level").asString()).isEqualTo("Membro anonimo");
        assertThat(d.path("tier").asString()).isEqualTo("GOLD");
        assertThat(d.path("status").asString()).isEqualTo("ACTIVE");
        assertThat(d.path("channel").asString()).as("nome con altre maiuscole: un codice").isEqualTo("ada");
    }

    @Test
    @DisplayName("nome «Ada»: sostituito come parola intera, «Adamo», ADA7 e il codice RWD-ADA restano")
    void nameInsideLongerWordsAndCodesIsKept() {
        member("MBR-000901", "Ada");
        message("MSG-901", "MBR-000901", "Ciao Ada!", "Ada, il premio «Adamo» ti aspetta: codice ADA7, premio RWD-ADAMO.");
        message("MSG-902", "MBR-000999", "Ciao Ada!", "Messaggio di un altro membro");
        delivery("DLV-901", "MBR-000901", """
                {"specversion":"1.0","id":"EVT-ADA","type":"io.loyaltyhub.fact.member.status.changed",
                 "source":"urn:loyaltyhub:service:member","subject":"member:MBR-000901",
                 "data":{"memberId":"MBR-000901","firstName":"Ada","previousStatus":"ACTIVE","newStatus":"ANONYMIZED",
                         "rewardCode":"RWD-ADA","note":"Ada, cliente del negozio Adamo"}}""");

        repository.erase("MBR-000901");

        assertThat(messageText("MSG-901")).containsExactly("Ciao Membro anonimo!",
                "Membro anonimo, il premio «Adamo» ti aspetta: codice ADA7, premio RWD-ADAMO.");
        assertThat(messageText("MSG-902")).as("messaggi di altri membri intatti")
                .containsExactly("Ciao Ada!", "Messaggio di un altro membro");
        JsonNode p = deliveryPayload("DLV-901");
        assertThat(p.path("id").asString()).isEqualTo("EVT-ADA");
        assertThat(p.path("data").has("firstName")).isFalse();
        assertThat(p.path("data").path("rewardCode").asString()).isEqualTo("RWD-ADA");
        assertThat(p.path("data").path("previousStatus").asString()).isEqualTo("ACTIVE");
        assertThat(p.path("data").path("note").asString()).isEqualTo("Membro anonimo, cliente del negozio Adamo");
        assertThat(jdbc.sql("SELECT coalesce(first_name, '-') || ':' || status FROM engagement_member_snapshot "
                + "WHERE member_id = 'MBR-000901'").query(String.class).single()).isEqualTo("-:ANONYMIZED");

        repository.erase("MBR-000901");
        assertThat(messageText("MSG-901")[0]).as("idempotente").isEqualTo("Ciao Membro anonimo!");
    }

    @Test
    @DisplayName("nome «Anon»: lo stato ANONYMIZED nel testo e nel webhook non diventa «Membro anonimoYMIZED»")
    void nameEqualToAPrefixOfTheStatusKeepsTheStatus() {
        member("MBR-000902", "Anon");
        message("MSG-903", "MBR-000902", "Ciao anon", "Il profilo di Anon è ora ANONYMIZED, in forma anonima.");
        delivery("DLV-902", "MBR-000902", """
                {"specversion":"1.0","id":"EVT-ANON","type":"io.loyaltyhub.fact.member.status.changed",
                 "source":"urn:loyaltyhub:service:member","subject":"member:MBR-000902",
                 "data":{"memberId":"MBR-000902","previousStatus":"ACTIVE","newStatus":"ANONYMIZED",
                         "note":"Anon ha chiesto la cancellazione: stato ANONYMIZED"}}""");

        repository.erase("MBR-000902");

        assertThat(messageText("MSG-903")).containsExactly("Ciao Membro anonimo",
                "Il profilo di Membro anonimo è ora ANONYMIZED, in forma anonima.");
        JsonNode d = deliveryPayload("DLV-902").path("data");
        assertThat(d.path("newStatus").asString()).isEqualTo("ANONYMIZED");
        assertThat(d.path("note").asString()).isEqualTo("Membro anonimo ha chiesto la cancellazione: stato ANONYMIZED");
    }
}
