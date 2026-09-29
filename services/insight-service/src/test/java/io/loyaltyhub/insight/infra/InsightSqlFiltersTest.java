package io.loyaltyhub.insight.infra;

import io.loyaltyhub.common.sql.SqlColumn;
import io.loyaltyhub.common.sql.SqlWhere;
import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Filtri di event store, tracciati, DLQ e audit col builder SQL comune (regola 19, ADR-042, docs/18 §3.10 punto 4),
 * senza database: il testo SQL è sempre composto da colonne dell'allowlist e segnaposto {@code :wN}, i valori
 * dell'utente (anche un tentativo di iniezione) stanno solo tra i parametri, i filtri si combinano in {@code AND}
 * nell'ordine dichiarato e quelli assenti o vuoti spariscono.
 */
class InsightSqlFiltersTest {

    private static final String INJECTION = "x%' OR 1=1 --";
    private static final Instant FROM = Instant.parse("2026-09-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-09-30T23:59:59Z");

    @Test
    void eventFiltersCombineInOrderWithOnlyPlaceholders() {
        SqlWhere where = EventStoreRepository.filters(" lh.facts.v1 ", " fact ", " member.registered ",
                " MBR-000001 ", " COR-1 ", " member-service ", FROM, TO, " 100%_x ");

        assertThat(where.sql()).isEqualTo(" WHERE topic = :w0 AND family = :w1 AND short_type = :w2"
                + " AND member_id = :w3 AND correlation_id = :w4 AND source = :w5"
                + " AND received_at >= :w6 AND received_at <= :w7 AND payload::text ILIKE :w8 ESCAPE '\\'");
        assertThat(where.params().values()).containsExactly("lh.facts.v1", "FACT", "member.registered",
                "MBR-000001", "COR-1", "member-service", Timestamp.from(FROM), Timestamp.from(TO),
                "%100\\%\\_x%");
    }

    @Test
    void eventFiltersSkipAbsentAndBlankValues() {
        SqlWhere none = EventStoreRepository.filters(null, " ", "", null, "\t", null, null, null, "  ");
        assertThat(none.sql()).isEmpty();
        assertThat(none.params()).isEmpty();

        SqlWhere some = EventStoreRepository.filters(null, "effect", null, null, null, null, null, TO, null);
        assertThat(some.sql()).isEqualTo(" WHERE family = :w0 AND received_at <= :w1");
        assertThat(some.params().values()).containsExactly("EFFECT", Timestamp.from(TO));
    }

    @Test
    void eventInputNeverReachesSqlText() {
        SqlWhere where = EventStoreRepository.filters(INJECTION, INJECTION, INJECTION, INJECTION, INJECTION,
                INJECTION, null, null, INJECTION);
        assertNoInput(where.sql());
        assertThat(where.params().values()).hasSize(7).allSatisfy(v -> assertThat(v.toString()).contains("OR 1=1"));
        // q è letterale: il % e l'apice dell'input sono neutralizzati nel modello, non nel testo SQL.
        assertThat(where.params().get("w6")).isEqualTo("%x\\%' OR 1=1 --%");
    }

    @Test
    void correlationFiltersAppendToConstantWhere() {
        SqlWhere where = EventStoreRepository.correlationFilters(" " + INJECTION + " ", FROM, TO);
        assertThat(where.andSql()).isEqualTo(" AND member_id = :w0 AND received_at >= :w1 AND received_at <= :w2");
        assertThat(where.params().values()).containsExactly(INJECTION, Timestamp.from(FROM), Timestamp.from(TO));
        assertNoInput(where.andSql());

        assertThat(EventStoreRepository.correlationFilters(" ", null, null).andSql()).isEmpty();
        assertThat(EventStoreRepository.CORRELATIONS_SELECT + EventStoreRepository.correlationFilters(null, null, null)
                .andSql() + EventStoreRepository.CORRELATIONS_PAGE)
                .isEqualTo("SELECT correlation_id FROM event_store WHERE correlation_id IS NOT NULL"
                        + " GROUP BY correlation_id ORDER BY max(received_at) DESC LIMIT :limit OFFSET :offset");
    }

    @Test
    void dlqFiltersCleanOnceAndBindEverything() {
        SqlWhere where = DlqRepository.filters(" open ", " lh-campaign ", " RULE_ERROR ");
        assertThat(where.sql()).isEqualTo(" WHERE status = :w0 AND consumer = :w1 AND error_code = :w2");
        assertThat(where.params().values()).containsExactly("OPEN", "lh-campaign", "RULE_ERROR");

        SqlWhere hostile = DlqRepository.filters(INJECTION, INJECTION, INJECTION);
        assertNoInput(hostile.sql());
        assertThat(hostile.params().values()).containsExactly(INJECTION.toUpperCase(), INJECTION, INJECTION);

        assertThat(DlqRepository.filters(" ", null, "").sql()).isEmpty();
        assertThat(DlqRepository.filters(null, null, "E1").sql()).isEqualTo(" WHERE error_code = :w0");
    }

    @Test
    void auditFiltersKeepValuesAsReceived() {
        SqlWhere where = AuditRepository.filters("anna", "ADMIN", " wallet", "RULE", "R-1", "UPDATE", FROM, TO);
        assertThat(where.sql()).isEqualTo(" WHERE actor_name = :w0 AND actor_role = :w1 AND service = :w2"
                + " AND entity_type = :w3 AND entity_id = :w4 AND action = :w5 AND at >= :w6 AND at <= :w7");
        // Come prima del builder: l'audit non toglie spazi ai valori, salta solo quelli assenti o vuoti.
        assertThat(where.params().values()).containsExactly("anna", "ADMIN", " wallet", "RULE", "R-1", "UPDATE",
                Timestamp.from(FROM), Timestamp.from(TO));

        SqlWhere hostile = AuditRepository.filters(INJECTION, INJECTION, INJECTION, INJECTION, INJECTION,
                INJECTION, null, null);
        assertNoInput(hostile.sql());
        assertThat(hostile.params().values()).hasSize(6).containsOnly(INJECTION);

        assertThat(AuditRepository.filters(null, " ", "", null, null, null, null, null).sql()).isEmpty();
    }

    @Test
    void orderAndPagingAreConstantAndUnchanged() {
        assertThat(EventStoreRepository.SEARCH_ORDER).isEqualTo(" ORDER BY received_at DESC");
        assertThat(DlqRepository.SEARCH_ORDER).isEqualTo(" ORDER BY first_seen_at DESC, id DESC");
        assertThat(AuditRepository.SEARCH_ORDER).isEqualTo(" ORDER BY at DESC");
        assertThat(List.of(EventStoreRepository.PAGE, DlqRepository.SEARCH_PAGE, AuditRepository.SEARCH_PAGE))
                .containsOnly(" LIMIT :limit OFFSET :offset");
    }

    @Test
    void allowlistsAreEnumsOfPlainColumnExpressions() {
        Stream.of(EventStoreRepository.EventColumn.values(), DlqRepository.DlqColumn.values(),
                        AuditRepository.AuditColumn.values())
                .flatMap(Stream::of)
                .forEach(c -> assertThat(SqlColumn.checked(c)).as(c.toString()).matches("[a-z_]+(::text)?"));
    }

    /** Il testo SQL non contiene nessun frammento dell'input ostile. */
    private static void assertNoInput(String sql) {
        assertThat(sql).doesNotContain("x%").doesNotContain("OR 1=1").doesNotContain("--").doesNotContain("x\\%");
        assertThat(sql.replace("ESCAPE '\\'", "")).doesNotContain("'");
    }
}
