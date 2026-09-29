// Fixture di semgrep --test per .semgrep/rules/statement.yml. `ruleid:` = deve segnalare, `ok:` = non deve segnalare.
package io.loyaltyhub.fixture;

import java.sql.Connection;
import java.sql.PreparedStatement;
// ruleid: lh-jdbc-statement-vietato
import java.sql.Statement;
import org.springframework.jdbc.core.simple.JdbcClient;

class StatementFixture {

    private final JdbcClient jdbc;

    StatementFixture(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    long prepared(Connection c, String memberId) throws Exception {
        // ok: lh-jdbc-statement-vietato
        try (PreparedStatement ps = c.prepareStatement("SELECT count(*) FROM wallet WHERE member_id = ?")) {
            ps.setString(1, memberId);
            // ok: lh-jdbc-statement-vietato
            try (var rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        }
    }

    long jdbcClient(String memberId) {
        // ok: lh-jdbc-statement-vietato
        return jdbc.sql("SELECT count(*) FROM wallet WHERE member_id = ?").param(memberId).query(Long.class).single();
    }

    void createStatement(Connection c, String memberId) throws Exception {
        // ruleid: lh-jdbc-statement-vietato
        try (var st = c.createStatement()) {
            st.execute("DELETE FROM wallet WHERE member_id = '" + memberId + "'");
        }
    }

    void typedStatement(Statement st, String sql) throws Exception {
        // ruleid: lh-jdbc-statement-vietato
        st.execute(sql);
        // ruleid: lh-jdbc-statement-vietato
        st.executeUpdate("DELETE FROM outbox");
    }

    void declaredStatement(Connection c) throws Exception {
        // ruleid: lh-jdbc-statement-vietato
        Statement st = c.createStatement();
        st.close();
    }
}
