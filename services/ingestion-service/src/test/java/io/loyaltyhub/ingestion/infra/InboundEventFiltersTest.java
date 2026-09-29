package io.loyaltyhub.ingestion.infra;

import io.loyaltyhub.common.sql.SqlColumn;
import io.loyaltyhub.common.sql.SqlWhere;
import io.loyaltyhub.ingestion.infra.InboundEventRepository.Filter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Filtri del monitor ingressi (BO-26) col builder SQL comune (regola 19, ADR-042, docs/18 §3.10 punto 4): il testo SQL
 * contiene solo colonne dell'enum {@link InboundEventRepository.InboundColumn} e segnaposto {@code :wN}; ogni valore
 * dell'utente è un parametro legato. Test puro, senza database.
 */
class InboundEventFiltersTest {

    private static final String INJECTION = "x%' OR 1=1 --";
    private static final String ESC = " ESCAPE '\\'";

    @Test
    @DisplayName("nessun filtro: nessuna condizione, nessun parametro")
    void noFilterMeansNoCondition() {
        SqlWhere where = InboundEventRepository.filters(null, new Filter(null, null, null, null, null, null));
        assertThat(where.sql()).isEmpty();
        assertThat(where.params()).isEmpty();

        SqlWhere blank = InboundEventRepository.filters("  ", new Filter(" ", "", "\t", null, null, "  "));
        assertThat(blank.sql()).as("testi vuoti ignorati come prima del builder").isEmpty();
        assertThat(blank.params()).isEmpty();
    }

    @Test
    @DisplayName("tutti i filtri: in AND nell'ordine di sempre, q in un gruppo OR, valori ripuliti come parametri")
    void allFiltersCombineInAnd() {
        Instant from = Instant.parse("2026-09-01T00:00:00Z");
        Instant to = Instant.parse("2026-09-30T23:59:59Z");
        SqlWhere where = InboundEventRepository.filters(" rejected ",
                new Filter(" pos ", " purchase.completed ", " MBR-000001 ", from, to, "  Ab_%\\ "));

        assertThat(where.sql()).isEqualTo(" WHERE status = :w0 AND source_code = :w1 AND type_code = :w2"
                + " AND member_id = :w3 AND received_at >= :w4 AND received_at <= :w5"
                + " AND (event_id ILIKE :w6" + ESC + " OR subject ILIKE :w7" + ESC + " OR member_id ILIKE :w8" + ESC
                + " OR type_code ILIKE :w9" + ESC + " OR source_code ILIKE :w10" + ESC
                + " OR correlation_id ILIKE :w11" + ESC + " OR reject_detail ILIKE :w12" + ESC + ")");
        assertThat(where.params()).containsEntry("w0", "REJECTED").containsEntry("w1", "pos")
                .containsEntry("w2", "purchase.completed").containsEntry("w3", "MBR-000001")
                .containsEntry("w4", Timestamp.from(from)).containsEntry("w5", Timestamp.from(to));
        for (int i = 6; i <= 12; i++) {
            assertThat(where.params()).as("q ripulito una volta e letterale: %%, _ e \\ neutralizzati")
                    .containsEntry("w" + i, "%Ab\\_\\%\\\\%");
        }
    }

    @Test
    @DisplayName("filtri parziali: solo quelli presenti, nessun AND o WHERE scelto a mano")
    void partialFilters() {
        SqlWhere onlyTo = InboundEventRepository.filters(null,
                new Filter(null, null, null, null, Instant.parse("2026-09-30T00:00:00Z"), null));
        assertThat(onlyTo.sql()).isEqualTo(" WHERE received_at <= :w0");

        SqlWhere statusAndQ = InboundEventRepository.filters("unmatched",
                new Filter(null, null, null, null, null, "ada"));
        assertThat(statusAndQ.sql()).startsWith(" WHERE status = :w0 AND (event_id ILIKE :w1");
        assertThat(statusAndQ.params()).containsEntry("w0", "UNMATCHED").containsEntry("w1", "%ada%");
    }

    @Test
    @DisplayName("iniezione in ogni filtro: il testo SQL non contiene mai l'input, che resta un parametro")
    void injectionNeverReachesSqlText() {
        SqlWhere where = InboundEventRepository.filters(INJECTION,
                new Filter(INJECTION, INJECTION, INJECTION, null, null, INJECTION));
        String sql = where.sql();
        assertThat(sql).doesNotContain("1=1").doesNotContain("--").doesNotContain("x%");
        assertThat(sql.replace(ESC, "")).doesNotContain("'");
        assertThat(where.params().values()).contains(INJECTION.toUpperCase(), INJECTION)
                .contains("%" + SqlWhere.escapeLike(INJECTION) + "%");
    }

    @Test
    @DisplayName("allowlist: le colonne vengono solo dall'enum; una colonna non enum è rifiutata dal builder")
    void columnsComeOnlyFromTheEnum() {
        assertThat(InboundEventRepository.InboundColumn.class.isEnum()).isTrue();
        assertThat(List.of(InboundEventRepository.InboundColumn.values()))
                .extracting(SqlColumn::sql)
                .allMatch(sql -> sql.matches("[a-z_]+"), "solo nomi di colonna costanti");
        assertThat(ImportRepository.ImportColumn.class.isEnum()).isTrue();

        SqlColumn fromInput = () -> "status; DROP TABLE inbound_event";
        assertThatThrownBy(() -> new SqlWhere().eq(fromInput, "x"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("enum");
    }
}
