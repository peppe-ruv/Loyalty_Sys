package io.loyaltyhub.member.infra;

import io.loyaltyhub.member.domain.AttributeDefinition;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Definizioni degli attributi personalizzati (V3), nell'ordine di {@code position}. */
@Repository
public class AttributeDefinitionRepository {

    private final JdbcClient jdbc;

    public AttributeDefinitionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<AttributeDefinition> findAll() {
        return jdbc.sql("SELECT key, label, type, options FROM attribute_definition ORDER BY position, key")
                .query((rs, n) -> new AttributeDefinition(rs.getString("key"), rs.getString("label"),
                        rs.getString("type"), TextArrays.toList(rs.getArray("options"))))
                .list();
    }

    public Map<String, AttributeDefinition> byKey() {
        Map<String, AttributeDefinition> out = new LinkedHashMap<>();
        findAll().forEach(d -> out.put(d.key(), d));
        return out;
    }

    public void replaceAll(List<AttributeDefinition> defs) {
        deleteAll();
        for (int i = 0; i < defs.size(); i++) {
            AttributeDefinition d = defs.get(i);
            jdbc.sql("INSERT INTO attribute_definition (key, label, type, options, position) VALUES (?, ?, ?, ?::text[], ?)")
                    .params(d.key(), d.label(), d.type(), TextArrays.literal(d.options()), i)
                    .update();
        }
    }

    /** Membri (non anonimizzati) con un valore per la chiave: una definizione in uso non si toglie né cambia tipo. */
    public long membersUsing(String key) {
        return jdbc.sql("SELECT count(*) FROM member WHERE status <> 'ANONYMIZED' AND jsonb_exists(attributes, ?)")
                .param(key).query(Long.class).single();
    }

    /**
     * Membri (non anonimizzati) con un valore per la chiave che non è tra le {@code options} date: restringere le opzioni
     * lascerebbe loro un valore non più valido (Q-306).
     */
    public long membersOutsideOptions(String key, List<String> options) {
        return jdbc.sql("""
                        SELECT count(*) FROM member WHERE status <> 'ANONYMIZED' AND jsonb_exists(attributes, ?)
                        AND jsonb_typeof(attributes -> ?) <> 'null' AND NOT ((attributes ->> ?) = ANY (?::text[]))
                        """)
                .params(key, key, key, TextArrays.literal(options)).query(Long.class).single();
    }

    public void deleteAll() {
        jdbc.sql("DELETE FROM attribute_definition").update();
    }
}
