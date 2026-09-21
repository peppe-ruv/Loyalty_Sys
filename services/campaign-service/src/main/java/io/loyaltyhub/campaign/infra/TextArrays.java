package io.loyaltyhub.campaign.infra;

import java.util.List;

/** Conversione tra {@code text[]} di Postgres e {@code List<String>} per JdbcClient. */
final class TextArrays {

    private TextArrays() {
    }

    static String literal(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "{}";
        }
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(values.get(i).replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
        }
        return sb.append('}').toString();
    }

    static List<String> toList(java.sql.Array array) {
        try {
            if (array == null) {
                return List.of();
            }
            return List.of((String[]) array.getArray());
        } catch (Exception e) {
            return List.of();
        }
    }
}
