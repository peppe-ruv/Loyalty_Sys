package io.loyaltyhub.ingestion.infra;

import io.loyaltyhub.ingestion.domain.Source;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class SourceRepository {

    private final JdbcClient jdbc;

    public SourceRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Source> findByCode(String code) {
        return jdbc.sql("SELECT code, name, kind, enabled, allowed_types, description FROM source WHERE code = ?")
                .param(code)
                .query((rs, n) -> new Source(
                        rs.getString("code"), rs.getString("name"), rs.getString("kind"),
                        rs.getBoolean("enabled"), toList(rs.getArray("allowed_types")), rs.getString("description")))
                .optional();
    }

    public void upsert(Source s) {
        jdbc.sql("""
                        INSERT INTO source (code, name, kind, enabled, allowed_types, description)
                        VALUES (?, ?, ?, ?, ?::text[], ?)
                        ON CONFLICT (code) DO UPDATE SET
                          name = excluded.name, kind = excluded.kind, enabled = excluded.enabled,
                          allowed_types = excluded.allowed_types, description = excluded.description
                        """)
                .params(s.code(), s.name(), s.kind(), s.enabled(), arrayLiteral(s.allowedTypes()), s.description())
                .update();
    }

    public void deleteAll() {
        jdbc.sql("DELETE FROM source").update();
    }

    private static List<String> toList(java.sql.Array array) {
        try {
            if (array == null) {
                return List.of();
            }
            String[] values = (String[]) array.getArray();
            return List.of(values);
        } catch (Exception e) {
            return List.of();
        }
    }

    static String arrayLiteral(List<String> values) {
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(values.get(i).replace("\"", "\\\"")).append('"');
        }
        return sb.append('}').toString();
    }
}
