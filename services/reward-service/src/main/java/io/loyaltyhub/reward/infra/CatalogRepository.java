package io.loyaltyhub.reward.infra;

import io.loyaltyhub.reward.domain.Band;
import io.loyaltyhub.reward.domain.Category;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** Categorie e fasce del catalogo (docs/servizi/reward-service.md §2). */
@Repository
public class CatalogRepository {

    private final JdbcClient jdbc;

    public CatalogRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<Category> categories() {
        return jdbc.sql("SELECT code, name, icon, sort_order FROM reward_category ORDER BY sort_order, code")
                .query((rs, n) -> new Category(rs.getString("code"), rs.getString("name"), rs.getString("icon"),
                        rs.getInt("sort_order")))
                .list();
    }

    public Optional<Category> category(String code) {
        return categories().stream().filter(c -> c.code().equals(code)).findFirst();
    }

    public void upsertCategory(Category c) {
        jdbc.sql("""
                        INSERT INTO reward_category (code, name, icon, sort_order) VALUES (?, ?, ?, ?)
                        ON CONFLICT (code) DO UPDATE SET name = excluded.name, icon = excluded.icon,
                          sort_order = excluded.sort_order
                        """)
                .params(c.code(), c.name(), c.icon(), c.sortOrder()).update();
    }

    public List<Band> bands() {
        return jdbc.sql("SELECT code, name, points_threshold, color, sort_order FROM reward_band ORDER BY points_threshold")
                .query((rs, n) -> new Band(rs.getString("code"), rs.getString("name"), rs.getLong("points_threshold"),
                        rs.getString("color"), rs.getInt("sort_order")))
                .list();
    }

    public Optional<Band> band(String code) {
        return bands().stream().filter(b -> b.code().equals(code)).findFirst();
    }

    public void upsertBand(Band b) {
        jdbc.sql("""
                        INSERT INTO reward_band (code, name, points_threshold, color, sort_order) VALUES (?, ?, ?, ?, ?)
                        ON CONFLICT (code) DO UPDATE SET name = excluded.name, points_threshold = excluded.points_threshold,
                          color = excluded.color, sort_order = excluded.sort_order
                        """)
                .params(b.code(), b.name(), b.pointsThreshold(), b.color(), b.sortOrder()).update();
    }

    public void deleteBand(String code) {
        jdbc.sql("DELETE FROM reward_band WHERE code = ?").param(code).update();
    }

    public long rewardsInBand(String code) {
        return jdbc.sql("SELECT count(*) FROM reward WHERE band_code = ?").param(code).query(Long.class).single();
    }

    public void deleteAll() {
        jdbc.sql("DELETE FROM reward_band").update();
        jdbc.sql("DELETE FROM reward_category").update();
    }
}
