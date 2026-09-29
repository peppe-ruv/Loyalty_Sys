package io.loyaltyhub.ingestion.infra;

import io.loyaltyhub.ingestion.infra.InboundEventRepository.Filter;
import io.loyaltyhub.ingestion.infra.InboundEventRepository.InboundRow;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Elenco e conteggi del monitor ingressi (BO-26) col builder SQL comune (regola 19, ADR-042, docs/18 §3.10 punto 4)
 * sul database vero: filtri in AND, {@code from}/{@code to} inclusi, {@code q} letterale e senza maiuscole su sette
 * colonne (anche nulle), ordine per ricezione decrescente con limite, conteggi con gli stessi filtri e tentativi di
 * iniezione senza effetto. Solo database (Postgres incorporato e migrazioni del servizio), senza Kafka né Spring.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class InboundEventRepositorySqlIT {

    private static final String INJECTION = "x%' OR 1=1 --";
    private static final Instant T0 = Instant.parse("2026-09-10T10:00:00Z");

    private EmbeddedPostgres pg;
    private JdbcClient jdbc;
    private InboundEventRepository repository;

    @BeforeAll
    void migrateAndLoad() throws Exception {
        pg = EmbeddedPostgres.builder().start();
        SimpleDriverDataSource ds = new SimpleDriverDataSource(new org.postgresql.Driver(),
                pg.getJdbcUrl("postgres", "postgres") + "&currentSchema=ingestion", "postgres", "");
        Flyway.configure().dataSource(ds).schemas("ingestion").defaultSchema("ingestion").createSchemas(true)
                .locations("classpath:db/migration/common", "classpath:db/migration/ingestion").load().migrate();
        jdbc = JdbcClient.create(ds);
        repository = new InboundEventRepository(jdbc);

        //       id      event_id        source  type                 subject                  member        min  status       correlation   reject_detail
        inbound("IN-01", "EVT-Alpha-01", "pos", "purchase.completed", "member:MBR-000001", "MBR-000001", 0, "ACCEPTED", "COR-01", null);
        inbound("IN-02", "EVT-Beta-02", "pos", "purchase.completed", "external:CRM-7", null, 1, "UNMATCHED", "COR-02",
                "Membro non trovato");
        inbound("IN-03", "EVT-Gamma-03", "ecommerce", "review.posted", "member:MBR-000002", "MBR-000002", 2, "REJECTED",
                "COR-03", "Campo data.rating 100% fuori scala");
        inbound("IN-04", "EVT-Delta-04", "ecommerce", "purchase.completed", "member:MBR-000001", "MBR-000001", 3,
                "DUPLICATE", "COR-04", "Stessa fonte e stesso id");
        inbound("IN-05", "EVT_Eps\\05", "app", "app.login", "email:ada@example.test", null, 4, "UNMATCHED",
                "COR-PERCENT", null);
    }

    @AfterAll
    void stop() throws Exception {
        pg.close();
    }

    private void inbound(String id, String eventId, String source, String type, String subject, String memberId,
                         int minutes, String status, String correlationId, String rejectDetail) {
        jdbc.sql("""
                        INSERT INTO inbound_event (id, event_id, source_code, type_code, subject, member_id, event_time,
                                                   received_at, status, payload, correlation_id, reject_detail)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, '{}'::jsonb, ?, ?)
                        """)
                .params(id, eventId, source, type, subject, memberId, Timestamp.from(T0),
                        Timestamp.from(T0.plusSeconds(60L * minutes)), status, correlationId, rejectDetail)
                .update();
    }

    private List<String> ids(String status, Filter filter) {
        return repository.search(status, filter, 100).stream().map(InboundRow::id).toList();
    }

    private static Filter q(String text) {
        return new Filter(null, null, null, null, null, text);
    }

    private long rows() {
        return jdbc.sql("SELECT count(*) FROM inbound_event").query(Long.class).single();
    }

    @Test
    @DisplayName("senza filtri: tutte le righe, più recenti prima, limite rispettato")
    void orderAndLimitUnchanged() {
        assertThat(ids(null, q(null))).containsExactly("IN-05", "IN-04", "IN-03", "IN-02", "IN-01");
        assertThat(repository.search(null, q(null), 2)).extracting(InboundRow::id).containsExactly("IN-05", "IN-04");
    }

    @Test
    @DisplayName("filtri in AND: esito (ripulito, maiuscolo), fonte, tipo, membro; from/to inclusi")
    void filtersCombineInAnd() {
        assertThat(ids(" unmatched ", q(null))).containsExactly("IN-05", "IN-02");
        assertThat(ids(null, new Filter(" pos ", null, null, null, null, null))).containsExactly("IN-02", "IN-01");
        assertThat(ids(null, new Filter("ecommerce", "purchase.completed", null, null, null, null)))
                .containsExactly("IN-04");
        assertThat(ids(null, new Filter(null, null, "MBR-000001", null, null, null))).containsExactly("IN-04", "IN-01");
        assertThat(ids("ACCEPTED", new Filter(null, null, "MBR-000001", null, null, null))).containsExactly("IN-01");
        assertThat(ids(null, new Filter(null, null, null, T0.plusSeconds(60), T0.plusSeconds(180), null)))
                .as("estremi inclusi").containsExactly("IN-04", "IN-03", "IN-02");
        assertThat(ids("REJECTED", new Filter("pos", null, null, null, null, null))).isEmpty();
    }

    @Test
    @DisplayName("q: senza maiuscole su id evento, soggetto, membro, tipo, fonte, correlazione e dettaglio del rifiuto")
    void textSearchCoversSevenColumns() {
        assertThat(ids(null, q("alpha"))).as("id evento").containsExactly("IN-01");
        assertThat(ids(null, q("CRM-7"))).as("soggetto").containsExactly("IN-02");
        assertThat(ids(null, q("mbr-000002"))).as("membro (e soggetto)").containsExactly("IN-03");
        assertThat(ids(null, q("REVIEW"))).as("tipo").containsExactly("IN-03");
        assertThat(ids(null, q("commerce"))).as("fonte").containsExactly("IN-04", "IN-03");
        assertThat(ids(null, q("cor-percent"))).as("correlazione").containsExactly("IN-05");
        assertThat(ids(null, q("non trovato"))).as("dettaglio del rifiuto").containsExactly("IN-02");
        assertThat(ids("UNMATCHED", new Filter("pos", null, null, null, null, " membro "))).containsExactly("IN-02");
    }

    @Test
    @DisplayName("q letterale: %, _ e \\ non sono caratteri jolly")
    void textSearchIsLiteral() {
        assertThat(ids(null, q("%"))).as("% letterale solo nel dettaglio di IN-03").containsExactly("IN-03");
        assertThat(ids(null, q("100%"))).containsExactly("IN-03");
        assertThat(ids(null, q("_"))).as("_ letterale solo nell'id evento di IN-05").containsExactly("IN-05");
        assertThat(ids(null, q("EVT_B"))).as("_ non vale un carattere qualsiasi").isEmpty();
        assertThat(ids(null, q("\\"))).as("\\ letterale").containsExactly("IN-05");
    }

    @Test
    @DisplayName("conteggi per esito: stessi filtri dell'elenco, esito escluso, quattro chiavi sempre presenti")
    void countsFollowTheSameFilters() {
        Map<String, Long> all = repository.countByStatus(q(null));
        assertThat(all).containsExactly(Map.entry("ACCEPTED", 1L), Map.entry("DUPLICATE", 1L),
                Map.entry("REJECTED", 1L), Map.entry("UNMATCHED", 2L));
        assertThat(repository.countByStatus(new Filter(null, "purchase.completed", null, null, null, "evt")))
                .containsExactly(Map.entry("ACCEPTED", 1L), Map.entry("DUPLICATE", 1L),
                        Map.entry("REJECTED", 0L), Map.entry("UNMATCHED", 1L));
    }

    @Test
    @DisplayName("iniezione in ogni filtro: elenco vuoto, conteggi a zero, tabella intatta")
    void injectionHasNoEffect() {
        long before = rows();
        assertThat(ids(INJECTION, q(null))).isEmpty();
        List<Filter> filters = List.of(new Filter(INJECTION, null, null, null, null, null),
                new Filter(null, INJECTION, null, null, null, null), new Filter(null, null, INJECTION, null, null, null),
                q(INJECTION), q("' OR ''='"), q("x'); DELETE FROM inbound_event; --"));
        for (Filter f : filters) {
            assertThat(ids(null, f)).as(f.toString()).isEmpty();
            assertThat(repository.countByStatus(f).values()).as(f.toString()).containsOnly(0L);
        }
        assertThat(rows()).as("nessun effetto sulla tabella").isEqualTo(before);
    }
}
