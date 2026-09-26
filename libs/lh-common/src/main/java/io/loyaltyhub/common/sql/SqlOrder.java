package io.loyaltyhub.common.sql;

import io.loyaltyhub.common.web.LhException;
import org.springframework.http.HttpStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

/**
 * Builder di {@code ORDER BY} con colonne solo da enum (ADR-042, docs/18 §3.10 punto 4, regola 19). Il parametro API
 * {@code sort=campo,desc} (docs/06 §2) si traduce con {@link #parse(String, Map)} contro un'allowlist
 * {@code nome API → colonna}; un campo o una direzione non ammessi sono un errore 400 {@code INVALID_SORT}.
 *
 * <pre>{@code
 * SqlOrder order = SqlOrder.parse(sort, Map.of("occurredAt", LedgerColumn.OCCURRED_AT), SqlOrder.desc(LedgerColumn.OCCURRED_AT))
 *         .by(LedgerColumn.ID, SqlOrder.Direction.ASC); // spareggio stabile
 * }</pre>
 */
public final class SqlOrder {

    /** Codice del problem RFC 9457 per un ordinamento non ammesso. */
    public static final String INVALID_SORT = "INVALID_SORT";

    private static final int MAX_ECHO = 40;

    public enum Direction {
        ASC, DESC
    }

    private final List<String> terms = new ArrayList<>();

    /** Aggiunge un criterio in coda (i primi hanno la precedenza). */
    public SqlOrder by(SqlColumn column, Direction direction) {
        String col = SqlColumn.checked(column);
        Objects.requireNonNull(direction, "direzione nulla");
        terms.add(col + " " + direction.name());
        return this;
    }

    public static SqlOrder asc(SqlColumn column) {
        return new SqlOrder().by(column, Direction.ASC);
    }

    public static SqlOrder desc(SqlColumn column) {
        return new SqlOrder().by(column, Direction.DESC);
    }

    public boolean isEmpty() {
        return terms.isEmpty();
    }

    /** {@code ""} se vuoto, altrimenti {@code " ORDER BY a ASC, b DESC"}. */
    public String sql() {
        return terms.isEmpty() ? "" : " ORDER BY " + String.join(", ", terms);
    }

    /**
     * Traduce {@code sort} ({@code campo} oppure {@code campo,asc|desc}; direzione predefinita {@code ASC}) contro
     * l'allowlist {@code allowed}. {@code sort} assente o vuoto → ordinamento vuoto.
     *
     * @throws LhException 400 {@code INVALID_SORT} se il campo non è nell'allowlist o la forma non è valida
     */
    public static SqlOrder parse(String sort, Map<String, ? extends SqlColumn> allowed) {
        Objects.requireNonNull(allowed, "allowlist nulla");
        SqlOrder order = new SqlOrder();
        if (sort == null || sort.isBlank()) {
            return order;
        }
        String[] parts = sort.split(",", -1);
        if (parts.length > 2) {
            throw invalid("forma attesa campo oppure campo,asc|desc: «" + echo(sort) + "»", allowed);
        }
        String field = parts[0].trim();
        SqlColumn column = allowed.get(field);
        if (column == null) {
            throw invalid("campo «" + echo(field) + "» non ammesso", allowed);
        }
        Direction direction = Direction.ASC;
        if (parts.length == 2) {
            String dir = parts[1].trim().toUpperCase(Locale.ROOT);
            if (!dir.equals("ASC") && !dir.equals("DESC")) {
                throw invalid("direzione «" + echo(parts[1].trim()) + "» non ammessa (asc o desc)", allowed);
            }
            direction = Direction.valueOf(dir);
        }
        return order.by(column, direction);
    }

    /** Come {@link #parse(String, Map)}, ma con {@code fallback} se {@code sort} è assente o vuoto. */
    public static SqlOrder parse(String sort, Map<String, ? extends SqlColumn> allowed, SqlOrder fallback) {
        SqlOrder order = parse(sort, allowed);
        return order.isEmpty() ? Objects.requireNonNull(fallback, "ordinamento predefinito nullo") : order;
    }

    private static LhException invalid(String reason, Map<String, ? extends SqlColumn> allowed) {
        String detail = "Parametro sort non valido: " + reason + ". Campi ammessi: "
                + String.join(", ", new TreeSet<>(allowed.keySet())) + ".";
        return new LhException(HttpStatus.BAD_REQUEST, "bad-request", INVALID_SORT, detail,
                List.of(new LhException.FieldError("sort", reason)));
    }

    private static String echo(String input) {
        return input.length() <= MAX_ECHO ? input : input.substring(0, MAX_ECHO) + "…";
    }
}
