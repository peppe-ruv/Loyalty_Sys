package io.loyaltyhub.wallet.infra;

import tools.jackson.databind.ObjectMapper;
import io.loyaltyhub.wallet.domain.Tier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/** Scala dei livelli (docs/servizi/wallet-service.md §2). Soglie crescenti col rank; {@code BASE} = 0. */
@Repository
public class TierRepository {

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public TierRepository(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public Optional<Tier> findByCode(String code) {
        return jdbc.sql("SELECT code, name, rank, threshold_sts, multiplier, benefits::text AS benefits, color, icon "
                        + "FROM tier WHERE code = ?").param(code).query(this::map).optional();
    }

    public List<Tier> findAllByRank() {
        return jdbc.sql("SELECT code, name, rank, threshold_sts, multiplier, benefits::text AS benefits, color, icon "
                + "FROM tier ORDER BY rank").query(this::map).list();
    }

    public void upsert(Tier t, String benefitsJson) {
        jdbc.sql("""
                        INSERT INTO tier (code, name, rank, threshold_sts, multiplier, benefits, color, icon)
                        VALUES (?, ?, ?, ?, ?, cast(? AS jsonb), ?, ?)
                        ON CONFLICT (code) DO UPDATE SET
                          name = excluded.name, rank = excluded.rank, threshold_sts = excluded.threshold_sts,
                          multiplier = excluded.multiplier, benefits = excluded.benefits,
                          color = excluded.color, icon = excluded.icon
                        """)
                .params(t.code(), t.name(), t.rank(), t.thresholdSts(), t.multiplier(), benefitsJson,
                        t.color(), t.icon())
                .update();
    }

    public void deleteAll() {
        jdbc.sql("DELETE FROM tier").update();
    }

    @SuppressWarnings("unchecked")
    private Tier map(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        List<String> benefits;
        try {
            benefits = mapper.readValue(rs.getString("benefits"), List.class);
        } catch (Exception e) {
            benefits = List.of();
        }
        return new Tier(rs.getString("code"), rs.getString("name"), rs.getInt("rank"),
                rs.getLong("threshold_sts"), rs.getBigDecimal("multiplier"), benefits,
                rs.getString("color"), rs.getString("icon"));
    }
}
