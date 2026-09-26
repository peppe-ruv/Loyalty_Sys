package io.loyaltyhub.common.sql;

import java.util.Objects;

/**
 * Colonna ammessa in un filtro o in un ordinamento del builder SQL (ADR-042, docs/18 §3.10 punto 4, regola 19).
 * Si implementa <b>solo con un enum</b>: l'espressione {@link #sql()} è una costante del codice, mai un testo ricevuto
 * dall'input. {@link SqlWhere} e {@link SqlOrder} rifiutano implementazioni che non sono costanti enum.
 *
 * <pre>{@code
 * enum LedgerColumn implements SqlColumn {
 *     CURRENCY("e.currency"), OCCURRED_AT("e.occurred_at");
 *     private final String sql;
 *     LedgerColumn(String sql) { this.sql = sql; }
 *     public String sql() { return sql; }
 * }
 * }</pre>
 */
public interface SqlColumn {

    /** Espressione SQL costante della colonna (es. {@code e.occurred_at}). */
    String sql();

    /** Verifica che la colonna sia una costante enum con espressione non vuota e ne restituisce il testo. */
    static String checked(SqlColumn column) {
        Objects.requireNonNull(column, "colonna nulla");
        if (!(column instanceof Enum<?>)) {
            throw new IllegalArgumentException(
                    "Colonna SQL non ammessa: le colonne vengono solo da enum (" + column.getClass().getName() + ")");
        }
        String sql = column.sql();
        if (sql == null || sql.isBlank()) {
            throw new IllegalArgumentException("Colonna SQL senza espressione: " + column);
        }
        return sql;
    }
}
