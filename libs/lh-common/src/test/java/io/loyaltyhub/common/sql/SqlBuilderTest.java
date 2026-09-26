package io.loyaltyhub.common.sql;

import io.loyaltyhub.common.web.LhException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Builder SQL con allowlist (F2-SEC-10, ADR-042, docs/18 §3.10 punto 4, regola 19). */
class SqlBuilderTest {

    enum Col implements SqlColumn {
        MEMBER_ID("e.member_id"), CURRENCY("e.currency"), TYPE("e.type"), OCCURRED_AT("e.occurred_at"),
        DESCRIPTION("e.description"), CAMPAIGN("e.campaign_code");

        private final String sql;

        Col(String sql) {
            this.sql = sql;
        }

        @Override
        public String sql() {
            return sql;
        }
    }

    private static final Map<String, Col> SORTABLE = Map.of("occurredAt", Col.OCCURRED_AT, "currency", Col.CURRENCY);

    @Test
    void emptyWhereProducesNoSqlAndNoParams() {
        SqlWhere w = new SqlWhere();
        assertThat(w.isEmpty()).isTrue();
        assertThat(w.sql()).isEmpty();
        assertThat(w.andSql()).isEmpty();
        assertThat(w.params()).isEmpty();
    }

    @Test
    void multipleFiltersAreJoinedWithAndAndNamedParameters() {
        Instant from = Instant.parse("2026-01-01T00:00:00Z");
        SqlWhere w = new SqlWhere()
                .eq(Col.MEMBER_ID, "MBR-000001")
                .ne(Col.TYPE, "EXPIRE")
                .gte(Col.OCCURRED_AT, from)
                .lt(Col.OCCURRED_AT, from.plusSeconds(60))
                .isNotNull(Col.CAMPAIGN);
        assertThat(w.sql()).isEqualTo(" WHERE e.member_id = :w0 AND e.type <> :w1 AND e.occurred_at >= :w2"
                + " AND e.occurred_at < :w3 AND e.campaign_code IS NOT NULL");
        assertThat(w.andSql()).startsWith(" AND e.member_id = :w0 AND ");
        assertThat(w.params()).containsExactly(Map.entry("w0", "MBR-000001"), Map.entry("w1", "EXPIRE"),
                Map.entry("w2", from), Map.entry("w3", from.plusSeconds(60)));
    }

    @Test
    void nullOrBlankOptionalFiltersAreSkipped() {
        String currency = null;
        Instant to = null;
        SqlWhere w = new SqlWhere()
                .eqIfPresent(Col.CURRENCY, currency)
                .eqIfPresent(Col.TYPE, "  ")
                .when(to != null, x -> x.lte(Col.OCCURRED_AT, to))
                .eqIfPresent(Col.MEMBER_ID, "MBR-000002")
                .isNull(Col.CAMPAIGN);
        assertThat(w.sql()).isEqualTo(" WHERE e.member_id = :w0 AND e.campaign_code IS NULL");
        assertThat(w.params()).containsExactly(Map.entry("w0", "MBR-000002"));
    }

    @Test
    void nullValueInMandatoryFilterIsRejected() {
        assertThatThrownBy(() -> new SqlWhere().eq(Col.CURRENCY, null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void inUsesOneParameterPerElementAndEmptyMatchesNothing() {
        SqlWhere w = new SqlWhere().in(Col.TYPE, List.of("EARN", "SPEND"));
        assertThat(w.sql()).isEqualTo(" WHERE e.type IN (:w0, :w1)");
        assertThat(w.params()).containsExactly(Map.entry("w0", "EARN"), Map.entry("w1", "SPEND"));

        SqlWhere empty = new SqlWhere().eq(Col.MEMBER_ID, "MBR-000001").in(Col.TYPE, List.of());
        assertThat(empty.sql()).isEqualTo(" WHERE e.member_id = :w0 AND 1 = 0");
        assertThat(empty.params()).containsOnlyKeys("w0");
    }

    @Test
    void likeEscapesWildcardsAndDeclaresEscapeCharacter() {
        SqlWhere w = new SqlWhere()
                .like(Col.DESCRIPTION, "50%_off\\x")
                .ilike(Col.CAMPAIGN, "sum_", SqlWhere.Match.PREFIX)
                .like(Col.CURRENCY, "PTS", SqlWhere.Match.EXACT);
        assertThat(w.sql()).isEqualTo(" WHERE e.description LIKE :w0 ESCAPE '\\' AND e.campaign_code ILIKE :w1"
                + " ESCAPE '\\' AND e.currency LIKE :w2 ESCAPE '\\'");
        assertThat(w.params()).containsEntry("w0", "%50\\%\\_off\\\\x%")
                .containsEntry("w1", "sum\\_%")
                .containsEntry("w2", "PTS");
        assertThat(SqlWhere.escapeLike("a%b_c\\d")).isEqualTo("a\\%b\\_c\\\\d");
    }

    @Test
    void orGroupsAreParenthesisedAndShareParameterNumbering() {
        SqlWhere w = new SqlWhere()
                .eq(Col.MEMBER_ID, "MBR-000001")
                .anyOf(g -> g.ilike(Col.DESCRIPTION, "bonus")
                        .allOf(a -> a.eq(Col.TYPE, "ADJUST").gt(Col.OCCURRED_AT, Instant.EPOCH)));
        assertThat(w.sql()).isEqualTo(" WHERE e.member_id = :w0 AND (e.description ILIKE :w1 ESCAPE '\\'"
                + " OR (e.type = :w2 AND e.occurred_at > :w3))");
        assertThat(w.params()).containsOnlyKeys("w0", "w1", "w2", "w3");

        assertThat(new SqlWhere().anyOf(g -> { }).sql()).isEqualTo(" WHERE 1 = 0");
        assertThat(new SqlWhere().allOf(g -> { }).sql()).isEmpty();
    }

    @Test
    void valuesNeverAppearInSqlText() {
        String hostile = "x' OR '1'='1'; DROP TABLE ledger_entry; --";
        SqlWhere w = new SqlWhere()
                .eq(Col.MEMBER_ID, hostile)
                .eqIfPresent(Col.CURRENCY, hostile)
                .in(Col.TYPE, List.of(hostile, "EARN"))
                .like(Col.DESCRIPTION, hostile)
                .anyOf(g -> g.ilike(Col.CAMPAIGN, hostile).lte(Col.OCCURRED_AT, hostile));
        String sql = w.sql();
        assertThat(sql).doesNotContain("DROP", "OR '1'", "EARN", "--", ";");
        assertThat(sql.replace("ESCAPE '\\'", "")).doesNotContain("'");
        assertThat(w.params().values()).allSatisfy(v -> assertThat(String.valueOf(v)).isNotBlank());
        assertThat(w.params()).hasSize(7);
    }

    @Test
    void columnsMustComeFromAnEnum() {
        SqlColumn forged = () -> "e.member_id; DROP TABLE x";
        assertThatThrownBy(() -> new SqlWhere().eq(forged, "a")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SqlOrder().by(forged, SqlOrder.Direction.ASC))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void orderBuilderProducesOrderByClause() {
        assertThat(new SqlOrder().sql()).isEmpty();
        assertThat(SqlOrder.desc(Col.OCCURRED_AT).by(Col.MEMBER_ID, SqlOrder.Direction.ASC).sql())
                .isEqualTo(" ORDER BY e.occurred_at DESC, e.member_id ASC");
    }

    @Test
    void sortParameterIsParsedAgainstTheAllowlist() {
        assertThat(SqlOrder.parse("occurredAt,desc", SORTABLE).sql()).isEqualTo(" ORDER BY e.occurred_at DESC");
        assertThat(SqlOrder.parse(" currency , ASC ", SORTABLE).sql()).isEqualTo(" ORDER BY e.currency ASC");
        assertThat(SqlOrder.parse("currency", SORTABLE).sql()).isEqualTo(" ORDER BY e.currency ASC");
        assertThat(SqlOrder.parse(null, SORTABLE).isEmpty()).isTrue();
        assertThat(SqlOrder.parse("", SORTABLE, SqlOrder.desc(Col.OCCURRED_AT)).sql())
                .isEqualTo(" ORDER BY e.occurred_at DESC");
    }

    @Test
    void unknownSortFieldOrDirectionIsRejectedWith400() {
        for (String bad : List.of("e.member_id,desc", "occurred_at", "occurredAt,sideways",
                "occurredAt,desc,currency", "occurredAt desc; DROP TABLE x", "1")) {
            assertThatThrownBy(() -> SqlOrder.parse(bad, SORTABLE))
                    .isInstanceOfSatisfying(LhException.class, e -> {
                        assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                        assertThat(e.code()).isEqualTo(SqlOrder.INVALID_SORT);
                        assertThat(e.typeSuffix()).isEqualTo("bad-request");
                        assertThat(e.getMessage()).contains("Campi ammessi: currency, occurredAt");
                        assertThat(e.errors()).extracting(LhException.FieldError::field).containsExactly("sort");
                    });
        }
    }
}
