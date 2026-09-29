// Fixture di semgrep --test per .semgrep/rules/sql.yml. `ruleid:` = deve segnalare, `ok:` = non deve segnalare.
// Non è codice del prodotto: non viene compilato né analizzato dalla scansione (.semgrepignore).
package io.loyaltyhub.fixture;

import java.sql.Connection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;

class SqlFixture {

    private static final String COLUMNS = "id, code, name";
    private static final String ORDER = " ORDER BY code";
    private final JdbcClient jdbc;
    private final JdbcTemplate template;
    private final NamedParameterJdbcTemplate named;
    private String instanceField = "x";

    SqlFixture(JdbcClient jdbc, JdbcTemplate template, NamedParameterJdbcTemplate named) {
        this.jdbc = jdbc;
        this.template = template;
        this.named = named;
    }

    // ---- ammessi -------------------------------------------------------------------------------------------------

    void literal() {
        // ok: lh-sql-testo-da-input, lh-sql-operando-dinamico
        jdbc.sql("DELETE FROM wallet WHERE member_id = ?").param("m").update();
    }

    void textBlock() {
        // ok: lh-sql-testo-da-input, lh-sql-operando-dinamico
        jdbc.sql("""
                INSERT INTO wallet (member_id, currency) VALUES (?, ?)
                ON CONFLICT (member_id, currency) DO NOTHING
                """).params("m", "PTS").update();
    }

    void constants() {
        // ok: lh-sql-testo-da-input, lh-sql-operando-dinamico
        jdbc.sql("SELECT " + COLUMNS + " FROM reward WHERE id = ?" + ORDER).param("r").query(String.class).list();
    }

    void qualifiedConstant() {
        // ok: lh-sql-testo-da-input, lh-sql-operando-dinamico
        jdbc.sql("SELECT " + Other.COLUMNS + " FROM reward").query(String.class).list();
    }

    void builder(SqlWhere where, SqlOrder order, int size, int page) {
        String sql = "SELECT " + COLUMNS + " FROM reward" + where.sql() + order.sql() + " LIMIT :limit OFFSET :offset";
        // ok: lh-sql-testo-da-input, lh-sql-operando-dinamico, lh-sql-variabile-concatenata
        where.bind(jdbc.sql(sql)).param("limit", size).param("offset", page * size).query(String.class).list();
    }

    void builderInline(SqlWhere where) {
        // ok: lh-sql-testo-da-input, lh-sql-operando-dinamico
        where.bind(jdbc.sql("SELECT count(*) FROM coupon" + where.sql())).query(Long.class).single();
    }

    void builderAndSql(SqlWhere where) {
        // ok: lh-sql-testo-da-input, lh-sql-operando-dinamico
        where.bind(jdbc.sql(COLUMNS + " FROM redemption WHERE fulfilment = :fulfilment" + where.andSql())).query(Long.class).single();
    }

    void builderChain(SqlWhere where) {
        // ok: lh-sql-testo-da-input, lh-sql-operando-dinamico
        where.bind(jdbc.sql(COLUMNS + where.andSql() + searchOrder().sql() + " LIMIT :limit")).query(Long.class).single();
    }

    void builderInlineOrder() {
        // ok: lh-sql-testo-da-input, lh-sql-operando-dinamico
        jdbc.sql("SELECT 1 FROM reward" + SqlOrder.desc(Column.CODE).by(Column.NAME, 1).sql()).query(Long.class).list();
    }

    void ternaryWithConstantBranches(int limit) {
        String sql = "SELECT 1 ORDER BY rank" + (limit > 0 ? " LIMIT :limit" : "");
        // ok: lh-sql-testo-da-input, lh-sql-operando-dinamico, lh-sql-variabile-concatenata
        jdbc.sql(sql).query(Long.class).list();
    }

    void templateConstant() {
        // ok: lh-sql-testo-da-input
        template.update("UPDATE outbox SET published_at = now() WHERE id = ?", "1");
        // ok: lh-sql-testo-da-input, lh-sql-operando-dinamico
        named.query("SELECT " + COLUMNS + " FROM reward", (rs, n) -> rs.getString(1));
    }

    void templateParamsAreNotSqlText(int a, int b) {
        // ok: lh-sql-testo-da-input, lh-sql-operando-dinamico
        template.update("UPDATE wallet SET points = ? WHERE id = ?", a + b, "1");
    }

    void localConstantConcatenation() {
        String sql = "SELECT " + COLUMNS + " FROM reward" + ORDER;
        // ok: lh-sql-testo-da-input, lh-sql-operando-dinamico, lh-sql-variabile-concatenata
        jdbc.sql(sql).query(String.class).list();
    }

    void stringBuilderOnlyForNonSql() {
        StringBuilder label = new StringBuilder("code-").append(1);
        // ok: lh-sql-testo-da-input
        jdbc.sql("SELECT 1 FROM reward WHERE code = ?").param(label.toString()).query(Long.class).list();
    }

    // ---- vietati -------------------------------------------------------------------------------------------------

    void concatenatedParameter(String memberId) {
        // ruleid: lh-sql-testo-da-input, lh-sql-operando-dinamico
        jdbc.sql("SELECT * FROM wallet WHERE member_id = '" + memberId + "'").query(String.class).list();
    }

    void concatenatedParameterOperand(String table) {
        // ruleid: lh-sql-operando-dinamico, lh-sql-testo-da-input
        jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }

    void bareParameter(String sql) {
        // ruleid: lh-sql-testo-da-input
        jdbc.sql(sql).query(Long.class).single();
    }

    void variableFromParameter(String column) {
        String sql = "SELECT " + column + " FROM reward";
        // ruleid: lh-sql-testo-da-input, lh-sql-variabile-concatenata
        jdbc.sql(sql).query(String.class).list();
    }

    void stringFormat() {
        // ruleid: lh-sql-testo-da-input
        jdbc.sql(String.format("SELECT %s FROM reward", COLUMNS)).query(String.class).list();
    }

    void formatted() {
        String sql = "SELECT %s FROM reward".formatted(COLUMNS);
        // ruleid: lh-sql-testo-da-input
        jdbc.sql(sql).query(String.class).list();
    }

    void stringBuilder() {
        StringBuilder sb = new StringBuilder("SELECT 1 FROM reward WHERE 1 = 1");
        sb.append(" AND x = 1");
        // ruleid: lh-sql-testo-da-input
        jdbc.sql(sb.toString()).query(Long.class).single();
    }

    void loopVariable(java.util.List<String> tables) {
        for (String t : tables) {
            // ruleid: lh-sql-operando-dinamico, lh-sql-testo-da-input
            jdbc.sql("SELECT count(*) FROM " + t).query(Long.class).single();
        }
    }

    void instanceFieldOperand() {
        // ruleid: lh-sql-operando-dinamico
        jdbc.sql("SELECT " + instanceField + " FROM reward").query(String.class).list();
    }

    void getterOperand(Dto dto) {
        // ruleid: lh-sql-operando-dinamico, lh-sql-testo-da-input
        jdbc.sql("SELECT 1 FROM reward WHERE " + dto.filter()).query(Long.class).single();
    }

    void unqualifiedCallOperand() {
        // ruleid: lh-sql-operando-dinamico
        jdbc.sql("SELECT " + columns() + " FROM reward").query(String.class).list();
    }

    void jdbcTemplateWithParameter(String sql) {
        // ruleid: lh-sql-testo-da-input
        template.queryForObject(sql, Long.class);
        // ruleid: lh-sql-testo-da-input
        named.update(sql, java.util.Map.of());
    }

    void prepareStatementWithParameter(Connection c, String sql) throws Exception {
        // ruleid: lh-sql-testo-da-input
        c.prepareStatement(sql);
    }

    void stringBuilderNew() {
        // ruleid: lh-sql-testo-da-input
        jdbc.sql(new StringBuilder("SELECT 1 FROM reward WHERE 1 = 1").append(" AND x = 1").toString()).query(Long.class).single();
    }

    void stringBufferNew() {
        // ruleid: lh-sql-testo-da-input
        jdbc.sql(new StringBuffer("SELECT 1 FROM reward").append(" WHERE x = 1").toString()).query(Long.class).single();
    }

    void appendChain() {
        StringBuilder sb = new StringBuilder();
        String sql = sb.append("SELECT 1 FROM reward").append(" WHERE 1 = 1").toString();
        // ruleid: lh-sql-testo-da-input
        jdbc.sql(sql).query(Long.class).single();
    }

    void templateConcatenatedParameter(String table) {
        // ruleid: lh-sql-operando-dinamico, lh-sql-testo-da-input
        template.queryForList("SELECT * FROM " + table, String.class);
    }

    void templateConcatenatedField() {
        // ruleid: lh-sql-operando-dinamico
        template.update("UPDATE reward SET " + instanceField + " = 1");
    }

    void templateConcatenatedCall(Dto dto) {
        // ruleid: lh-sql-operando-dinamico, lh-sql-testo-da-input
        template.batchUpdate("INSERT INTO t (a) VALUES (" + dto.filter() + ")", new Object[0][0], new int[0]);
    }

    void namedConcatenatedField() {
        // ruleid: lh-sql-operando-dinamico
        named.query("SELECT " + instanceField + " FROM reward", (rs, n) -> rs.getString(1));
    }

    void connectionConcatenatedField(Connection c) throws Exception {
        // ruleid: lh-sql-operando-dinamico
        c.prepareStatement("SELECT " + instanceField + " FROM reward");
    }

    void localConcatenatedField() {
        String sql = "SELECT " + instanceField + " FROM reward";
        // ruleid: lh-sql-variabile-concatenata
        jdbc.sql(sql).query(String.class).list();
    }

    void localConcatenatedCall() {
        var sql = columns() + " FROM reward";
        // ruleid: lh-sql-variabile-concatenata
        jdbc.sql(sql).query(String.class).list();
    }

    void localConcatenatedGetter(Dto dto) {
        String sql = "SELECT 1 FROM reward WHERE " + dto.filter();
        // ruleid: lh-sql-variabile-concatenata, lh-sql-testo-da-input
        jdbc.sql(sql).query(Long.class).single();
    }

    private String columns() {
        return COLUMNS;
    }

    // Segnaposto per i tipi usati sopra.
    interface SqlWhere { String sql(); String andSql(); <T> T bind(T spec); }
    interface SqlOrder {
        String sql();
        static SqlOrder desc(Object column) { return null; }
        SqlOrder by(Object column, int direction);
    }
    enum Column { CODE, NAME }
    private static SqlOrder searchOrder() { return SqlOrder.desc(Column.CODE); }
    interface Dto { String filter(); }
    static final class Other { static final String COLUMNS = "id"; }
}
