package io.loyaltyhub.insight.infra;

import io.loyaltyhub.common.privacy.PersonalTextScrubber;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * La prova di {@code audit_redact} resta valida con la pulizia comune (Q-404): ogni valore che
 * {@link PersonalTextScrubber#found} restituisce per una voce è riconosciuto da {@code audit_mentions} (V6), anche
 * quando il valore del membro è attaccato ad altre lettere (scritture senza spazi), porta il prefisso di un telefono o
 * compare con altre maiuscole. Solo database, senza Kafka né contesto Spring.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuditMentionsCoherenceIT {

    private EmbeddedPostgres pg;
    private JdbcClient jdbc;

    @BeforeAll
    void migrate() throws Exception {
        pg = EmbeddedPostgres.builder().start();
        SimpleDriverDataSource ds = new SimpleDriverDataSource(new org.postgresql.Driver(),
                pg.getJdbcUrl("postgres", "postgres") + "&currentSchema=insight", "postgres", "");
        Flyway.configure().dataSource(ds).schemas("insight").defaultSchema("insight").createSchemas(true)
                .locations("classpath:db/migration/common", "classpath:db/migration/insight").load().migrate();
        jdbc = JdbcClient.create(ds);
    }

    @AfterAll
    void stop() throws Exception {
        pg.close();
    }

    @ParameterizedTest(name = "{0} / {1}")
    @CsvSource(delimiter = '|', value = {
            "Reclamo di OTTAVIO Quintilio|Ottavio",
            "Scritto a ottavio.q@example.test.|ottavio.q@example.test",
            "Chiamato il +393331234567 ieri|3331234567",
            "Tel. 00393331234567|3331234567",
            "Tel:393331234567|3331234567",
            "王伟先生您好！|王伟先",
            "Adaさん、こんにちは|Ada",
            "สวัสดีสมชายครับ|สมชาย",
            "CRM101|crm101"})
    @DisplayName("ogni valore trovato in Java è una menzione anche per audit_mentions")
    void foundValuesAreMentions(String text, String token) {
        Set<String> found = PersonalTextScrubber.found(text, List.of(token));
        assertThat(found).as("trovato in Java").isNotEmpty();
        for (String value : found) {
            assertThat(jdbc.sql("SELECT audit_mentions(?, ?)").params(text, value).query(Boolean.class).single())
                    .as("audit_mentions(%s, %s)", text, value).isTrue();
        }
    }
}
