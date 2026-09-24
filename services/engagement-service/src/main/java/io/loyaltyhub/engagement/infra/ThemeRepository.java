package io.loyaltyhub.engagement.infra;

import io.loyaltyhub.engagement.domain.Theme;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.Optional;

/** Tema del portale (tabella {@code theme}, una sola riga {@code default}). */
@Repository
public class ThemeRepository {

    private static final String ID = "default";
    private static final TypeReference<Map<String, String>> MAP = new TypeReference<>() {
    };

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public ThemeRepository(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public Optional<Theme> find() {
        return jdbc.sql("""
                        SELECT program_name, tagline, logo_url, colors::text AS colors, hero_title, hero_subtitle, font_display,
                               currency_names::text AS currency_names, version, updated_at
                        FROM theme WHERE id = ?
                        """)
                .param(ID).query(this::map).optional();
    }

    /** Crea o sostituisce il tema se la versione è ancora {@code expectedVersion} (null = qualunque); false = conflitto. */
    public boolean save(Theme t, Long expectedVersion, String actor) {
        int n = jdbc.sql("""
                        INSERT INTO theme (id, program_name, tagline, logo_url, colors, hero_title, hero_subtitle, font_display,
                                           currency_names, version, updated_at, updated_by)
                        VALUES (?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?::jsonb, 0, now(), ?)
                        ON CONFLICT (id) DO UPDATE SET program_name = excluded.program_name, tagline = excluded.tagline,
                          logo_url = excluded.logo_url, colors = excluded.colors, hero_title = excluded.hero_title,
                          hero_subtitle = excluded.hero_subtitle, font_display = excluded.font_display,
                          currency_names = excluded.currency_names, version = theme.version + 1, updated_at = now(),
                          updated_by = excluded.updated_by
                        WHERE ?::bigint IS NULL OR theme.version = ?::bigint
                        """)
                .params(ID, t.programName(), t.tagline(), t.logoUrl(), mapper.writeValueAsString(t.colors()), t.heroTitle(),
                        t.heroSubtitle(), t.fontDisplay(), mapper.writeValueAsString(t.currencyNames()), actor,
                        expectedVersion, expectedVersion)
                .update();
        return n == 1;
    }

    public void deleteAll() {
        jdbc.sql("DELETE FROM theme").update();
    }

    private Theme map(ResultSet rs, int n) throws SQLException {
        return new Theme(rs.getString("program_name"), rs.getString("tagline"), rs.getString("logo_url"),
                mapper.readValue(rs.getString("colors"), MAP), rs.getString("hero_title"), rs.getString("hero_subtitle"),
                rs.getString("font_display"), mapper.readValue(rs.getString("currency_names"), MAP), rs.getLong("version"),
                rs.getTimestamp("updated_at").toInstant());
    }
}
