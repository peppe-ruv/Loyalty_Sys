package io.loyaltyhub.wallet;

import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Confronto tra le righe di riferimento del wallet nel database e {@code seed/currencies.json} e {@code seed/tiers.json}
 * (Q-629, opzione B). La migrazione {@code V3__reference_data.sql} e il {@code WalletSeeder} della demo inseriscono gli
 * stessi valori: se divergono, i test che usano questo confronto falliscono. Il seed si legge dal classpath, come fa
 * l'applicazione in demo, senza passare dal {@code SeedLoader} (che esiste solo col profilo {@code demo}).
 */
final class ReferenceDataSeedAssertions {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ReferenceDataSeedAssertions() {
    }

    static JsonNode seed(String fileName) {
        try (InputStream in = new ClassPathResource("seed/" + fileName).getInputStream()) {
            return MAPPER.readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException("Seed non leggibile: " + fileName, e);
        }
    }

    /** Valute e livelli del database sono esattamente quelli del seed (stessi codici, stessi attributi, nessuna riga in più). */
    static void assertMatchesSeed(JdbcClient jdbc) {
        assertCurrenciesMatchSeed(jdbc);
        assertTiersMatchSeed(jdbc);
    }

    static void assertCurrenciesMatchSeed(JdbcClient jdbc) {
        JsonNode seed = seed("currencies.json");
        List<String> codes = new ArrayList<>();
        for (JsonNode c : seed) {
            String code = c.path("code").asString();
            codes.add(code);
            var row = jdbc.sql("SELECT name, spendable, expiry_policy::text AS policy FROM currency WHERE code = ?")
                    .param(code)
                    .query((rs, n) -> new Object[]{rs.getString("name"), rs.getBoolean("spendable"), rs.getString("policy")})
                    .optional();
            assertThat(row).as("valuta %s assente dal database", code).isPresent();
            assertThat(row.get()[0]).as("%s.name", code).isEqualTo(c.path("name").asString());
            assertThat(row.get()[1]).as("%s.spendable", code).isEqualTo(c.path("spendable").asBoolean(true));
            assertThat(MAPPER.readTree((String) row.get()[2])).as("%s.expiry_policy", code)
                    .isEqualTo(c.path("expiryPolicy"));
        }
        assertThat(jdbc.sql("SELECT code FROM currency ORDER BY code").query(String.class).list())
                .as("valute nel database").containsExactlyInAnyOrderElementsOf(codes);
    }

    static void assertTiersMatchSeed(JdbcClient jdbc) {
        JsonNode seed = seed("tiers.json");
        List<String> codes = new ArrayList<>();
        for (JsonNode t : seed) {
            String code = t.path("code").asString();
            codes.add(code);
            var row = jdbc.sql("""
                            SELECT name, rank, threshold_sts, multiplier, benefits::text AS benefits, color, icon
                            FROM tier WHERE code = ?""")
                    .param(code)
                    .query((rs, n) -> new Object[]{rs.getString("name"), rs.getInt("rank"), rs.getLong("threshold_sts"),
                            rs.getBigDecimal("multiplier"), rs.getString("benefits"), rs.getString("color"),
                            rs.getString("icon")})
                    .optional();
            assertThat(row).as("livello %s assente dal database", code).isPresent();
            Object[] r = row.get();
            assertThat(r[0]).as("%s.name", code).isEqualTo(t.path("name").asString());
            assertThat(r[1]).as("%s.rank", code).isEqualTo(t.path("rank").asInt());
            assertThat(r[2]).as("%s.threshold_sts", code).isEqualTo(t.path("thresholdSts").asLong(0));
            assertThat((java.math.BigDecimal) r[3]).as("%s.multiplier", code)
                    .isEqualByComparingTo(t.path("multiplier").decimalValue());
            assertThat(MAPPER.readTree((String) r[4])).as("%s.benefits", code).isEqualTo(t.path("benefits"));
            assertThat(r[5]).as("%s.color", code).isEqualTo(t.path("color").asString());
            assertThat(r[6]).as("%s.icon", code).isEqualTo(t.path("icon").asString());
        }
        assertThat(jdbc.sql("SELECT code FROM tier ORDER BY rank").query(String.class).list())
                .as("livelli nel database").containsExactlyInAnyOrderElementsOf(codes);
    }
}
