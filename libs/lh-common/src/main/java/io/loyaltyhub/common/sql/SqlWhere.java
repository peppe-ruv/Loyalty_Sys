package io.loyaltyhub.common.sql;

import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Builder delle condizioni {@code WHERE} dinamiche (ADR-042, docs/18 §3.10 punto 4, regola 19). Le colonne vengono solo
 * da enum ({@link SqlColumn}); i valori non entrano mai nel testo SQL ma diventano parametri con nome
 * {@code :w0, :w1, …} (nomi riservati: la query non deve usarne altri con lo stesso schema).
 *
 * <p>Le condizioni si compongono in {@code AND}; {@link #anyOf} apre un gruppo in {@code OR}, {@link #allOf} un gruppo
 * in {@code AND} dentro un {@code OR}. Un gruppo {@code AND} vuoto non restringe nulla; un gruppo {@code OR} vuoto e un
 * {@code IN} su insieme vuoto non corrispondono a nessuna riga ({@code 1 = 0}). Non è thread-safe: un'istanza per query.
 *
 * <pre>{@code
 * SqlWhere where = new SqlWhere()
 *         .eq(LedgerColumn.MEMBER_ID, memberId)
 *         .eqIfPresent(LedgerColumn.CURRENCY, currency)
 *         .when(from != null, w -> w.gte(LedgerColumn.OCCURRED_AT, Timestamp.from(from)));
 * List<Row> rows = where.bind(jdbc.sql(SELECT + where.sql() + ORDER_BY)).query(MAPPER).list();
 * }</pre>
 */
public final class SqlWhere {

    /** Condizione che non corrisponde a nessuna riga. */
    static final String MATCH_NONE = "1 = 0";

    /** Modalità di confronto di {@link #like(SqlColumn, String, Match)}: il testo è sempre letterale (escape). */
    public enum Match {
        EXACT, PREFIX, SUFFIX, CONTAINS
    }

    private static final String PARAM_PREFIX = "w";

    private final Map<String, Object> params;
    private final List<String> clauses = new ArrayList<>();
    private final String joiner;

    public SqlWhere() {
        this(new LinkedHashMap<>(), " AND ");
    }

    private SqlWhere(Map<String, Object> params, String joiner) {
        this.params = params;
        this.joiner = joiner;
    }

    // --- Confronti ---

    public SqlWhere eq(SqlColumn column, Object value) {
        return compare(column, "=", value);
    }

    public SqlWhere ne(SqlColumn column, Object value) {
        return compare(column, "<>", value);
    }

    public SqlWhere gt(SqlColumn column, Object value) {
        return compare(column, ">", value);
    }

    public SqlWhere gte(SqlColumn column, Object value) {
        return compare(column, ">=", value);
    }

    public SqlWhere lt(SqlColumn column, Object value) {
        return compare(column, "<", value);
    }

    public SqlWhere lte(SqlColumn column, Object value) {
        return compare(column, "<=", value);
    }

    /** Come {@link #eq} ma salta il filtro se il valore è {@code null} o un testo vuoto. */
    public SqlWhere eqIfPresent(SqlColumn column, Object value) {
        if (value == null || (value instanceof CharSequence cs && cs.toString().isBlank())) {
            return this;
        }
        return eq(column, value);
    }

    /** {@code colonna IN (…)} con un parametro per elemento; insieme vuoto → nessuna riga. */
    public SqlWhere in(SqlColumn column, Collection<?> values) {
        String col = SqlColumn.checked(column);
        Objects.requireNonNull(values, "valori di IN nulli: usare when(...) per un filtro facoltativo");
        if (values.isEmpty()) {
            clauses.add(MATCH_NONE);
            return this;
        }
        List<String> names = new ArrayList<>(values.size());
        for (Object v : values) {
            names.add(":" + bindValue(v));
        }
        clauses.add(col + " IN (" + String.join(", ", names) + ")");
        return this;
    }

    public SqlWhere isNull(SqlColumn column) {
        clauses.add(SqlColumn.checked(column) + " IS NULL");
        return this;
    }

    public SqlWhere isNotNull(SqlColumn column) {
        clauses.add(SqlColumn.checked(column) + " IS NOT NULL");
        return this;
    }

    // --- Testo (LIKE con escape di % _ \) ---

    /** {@code LIKE} su testo letterale contenuto nella colonna (caratteri jolly dell'input neutralizzati). */
    public SqlWhere like(SqlColumn column, String text) {
        return like(column, text, Match.CONTAINS);
    }

    public SqlWhere like(SqlColumn column, String text, Match match) {
        return pattern(column, "LIKE", text, match);
    }

    /** {@code ILIKE} (senza distinzione tra maiuscole e minuscole) su testo letterale contenuto nella colonna. */
    public SqlWhere ilike(SqlColumn column, String text) {
        return ilike(column, text, Match.CONTAINS);
    }

    public SqlWhere ilike(SqlColumn column, String text, Match match) {
        return pattern(column, "ILIKE", text, match);
    }

    /** Neutralizza i caratteri speciali di {@code LIKE} ({@code \}, {@code %}, {@code _}) con escape {@code \}. */
    public static String escapeLike(String text) {
        Objects.requireNonNull(text, "testo nullo");
        StringBuilder sb = new StringBuilder(text.length() + 8);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\' || c == '%' || c == '_') {
                sb.append('\\');
            }
            sb.append(c);
        }
        return sb.toString();
    }

    // --- Composizione ---

    /** Applica {@code filters} solo se {@code condition} è vera (filtri facoltativi). */
    public SqlWhere when(boolean condition, Consumer<SqlWhere> filters) {
        if (condition) {
            filters.accept(this);
        }
        return this;
    }

    /** Gruppo in {@code OR}: {@code (a OR b …)}; vuoto → nessuna riga. */
    public SqlWhere anyOf(Consumer<SqlWhere> alternatives) {
        SqlWhere group = new SqlWhere(params, " OR ");
        alternatives.accept(group);
        clauses.add(group.clauses.isEmpty() ? MATCH_NONE : group.grouped());
        return this;
    }

    /** Gruppo in {@code AND}: {@code (a AND b …)}, utile dentro {@link #anyOf}; vuoto → nessuna restrizione. */
    public SqlWhere allOf(Consumer<SqlWhere> conditions) {
        SqlWhere group = new SqlWhere(params, " AND ");
        conditions.accept(group);
        if (!group.clauses.isEmpty()) {
            clauses.add(group.grouped());
        }
        return this;
    }

    // --- Uscita ---

    public boolean isEmpty() {
        return clauses.isEmpty();
    }

    /** {@code ""} se non ci sono condizioni, altrimenti {@code " WHERE …"}. */
    public String sql() {
        return clauses.isEmpty() ? "" : " WHERE " + String.join(joiner, clauses);
    }

    /** {@code ""} se non ci sono condizioni, altrimenti {@code " AND …"}: per accodarsi a un {@code WHERE} costante. */
    public String andSql() {
        return clauses.isEmpty() ? "" : " AND " + String.join(joiner, clauses);
    }

    /** Parametri con nome, nell'ordine di inserimento. */
    public Map<String, Object> params() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(params));
    }

    /** Lega i parametri alla query di {@link JdbcClient}; il testo SQL va composto con {@link #sql()}. */
    public JdbcClient.StatementSpec bind(JdbcClient.StatementSpec spec) {
        return spec.params(params());
    }

    // --- Interni ---

    private SqlWhere compare(SqlColumn column, String operator, Object value) {
        String col = SqlColumn.checked(column);
        clauses.add(col + " " + operator + " :" + bindValue(value));
        return this;
    }

    private SqlWhere pattern(SqlColumn column, String operator, String text, Match match) {
        String col = SqlColumn.checked(column);
        Objects.requireNonNull(match, "modalità nulla");
        String literal = escapeLike(text);
        String pattern = switch (match) {
            case EXACT -> literal;
            case PREFIX -> literal + "%";
            case SUFFIX -> "%" + literal;
            case CONTAINS -> "%" + literal + "%";
        };
        clauses.add(col + " " + operator + " :" + bindValue(pattern) + " ESCAPE '\\'");
        return this;
    }

    private String bindValue(Object value) {
        Objects.requireNonNull(value, "valore nullo: usare isNull(...) o eqIfPresent(...)");
        String name = PARAM_PREFIX + params.size();
        params.put(name, value);
        return name;
    }

    private String grouped() {
        return clauses.size() == 1 ? clauses.get(0) : "(" + String.join(joiner, clauses) + ")";
    }
}
