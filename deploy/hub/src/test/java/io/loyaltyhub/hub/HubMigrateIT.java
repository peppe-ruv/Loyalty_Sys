package io.loyaltyhub.hub;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.sql.Connection;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Job di migrazione del chart Helm (F2-DIST-02, ADR-038): {@link HubMigrate} applica le migrazioni di tutti gli
 * schemi dell'hub e termina; una seconda esecuzione non trova nulla da fare; senza configurazione si rifiuta.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HubMigrateIT {

    private final EmbeddedPostgres pg = start();

    @AfterAll
    void stop() throws Exception {
        pg.close();
    }

    @Test
    void migratesEverySchemaAndIsIdempotent() throws Exception {
        Map<String, String> env = Map.of(
                "DB_URL", pg.getJdbcUrl("postgres", "postgres"),
                "DB_USERNAME", "postgres",
                "DB_PASSWORD", "postgres");

        HubMigrate.run(env);
        List<Integer> first = applied();
        HubMigrate.run(env);

        assertThat(schemas()).contains("ingestion", "member", "campaign", "wallet", "insight", "reward",
                "gamification", "engagement");
        assertThat(first).allSatisfy(n -> assertThat(n).isPositive());
        assertThat(applied()).as("seconda esecuzione: nessuna nuova migrazione").isEqualTo(first);
    }

    /**
     * Il comando esatto del Job del chart e del servizio {@code migrate} del compose di riferimento, sul jar
     * eseguibile costruito dal modulo (fase package, prima dei test d'integrazione).
     */
    @Test
    void launcherCommandOfTheJobMigratesAndFailsWithoutConfiguration() throws Exception {
        java.nio.file.Path jar;
        try (var files = java.nio.file.Files.list(java.nio.file.Paths.get("target"))) {
            jar = files.filter(p -> p.getFileName().toString().matches("hub-.*-boot\\.jar")).findFirst()
                    .orElseThrow(() -> new IllegalStateException("jar eseguibile dell'hub assente in target/"));
        }
        String javaBin = java.nio.file.Paths.get(System.getProperty("java.home"), "bin", "java").toString();
        java.util.List<String> command = java.util.List.of(javaBin, "-Dloader.main=io.loyaltyhub.hub.HubMigrate",
                "-cp", jar.toAbsolutePath().toString(), "org.springframework.boot.loader.launch.PropertiesLauncher");

        // Database dedicato: il comando parte da zero.
        try (Connection c = pg.getPostgresDatabase().getConnection()) {
            c.createStatement().execute("CREATE DATABASE launcher");
        }
        ProcessBuilder ok = new ProcessBuilder(command).redirectErrorStream(true);
        ok.environment().put("DB_URL", pg.getJdbcUrl("postgres", "launcher"));
        ok.environment().put("DB_USERNAME", "postgres");
        ok.environment().put("DB_PASSWORD", "postgres");
        Process p = ok.start();
        String out = new String(p.getInputStream().readAllBytes());
        assertThat(p.waitFor()).as(out).isZero();
        assertThat(out).contains("Successfully applied").contains("schema \"engagement\"");

        ProcessBuilder missing = new ProcessBuilder(command).redirectErrorStream(true);
        missing.environment().remove("DB_URL");
        missing.environment().remove("SPRING_DATASOURCE_URL");
        Process q = missing.start();
        String err = new String(q.getInputStream().readAllBytes());
        assertThat(q.waitFor()).as(err).isEqualTo(2);
        assertThat(err).contains("variabile mancante: SPRING_DATASOURCE_URL o DB_URL");
    }

    @Test
    void refusesWithoutConfiguration() {
        assertThatThrownBy(() -> HubMigrate.run(Map.of("DB_USERNAME", "x", "DB_PASSWORD", "y")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DB_URL");
    }

    private List<String> schemas() throws Exception {
        List<String> out = new ArrayList<>();
        try (Connection c = pg.getPostgresDatabase().getConnection();
             ResultSet rs = c.createStatement().executeQuery("SELECT schema_name FROM information_schema.schemata")) {
            while (rs.next()) {
                out.add(rs.getString(1));
            }
        }
        return out;
    }

    /** Righe della storia Flyway per schema, nell'ordine di {@code HubDatabase}. */
    private List<Integer> applied() throws Exception {
        List<Integer> out = new ArrayList<>();
        try (Connection c = pg.getPostgresDatabase().getConnection()) {
            for (String schema : List.of("ingestion", "member", "campaign", "wallet", "insight", "reward",
                    "gamification", "engagement")) {
                try (ResultSet rs = c.createStatement().executeQuery(
                        "SELECT count(*) FROM " + schema + ".flyway_schema_history")) {
                    rs.next();
                    out.add(rs.getInt(1));
                }
            }
        }
        return out;
    }

    private static EmbeddedPostgres start() {
        try {
            return EmbeddedPostgres.builder().start();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
