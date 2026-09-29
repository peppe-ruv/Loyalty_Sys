package io.loyaltyhub.engagement.infra;

import io.loyaltyhub.common.sql.SqlColumn;
import io.loyaltyhub.common.sql.SqlWhere;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Filtri dinamici di engagement col builder SQL comune (regola 19, ADR-042, docs/18 §3.10 punto 4): il testo SQL è
 * fatto solo di colonne da enum e segnaposto {@code :wN}; l'input finisce solo nei parametri legati. Senza database.
 */
class EngagementSqlFiltersTest {

    private static final String INJECTION = "x%' OR 1=1 --";

    @Test
    void contentFiltersCombineInAndWithLiteralText() {
        SqlWhere where = ContentRepository.filters(" card ", "home_hero", "live", "50%_x");

        assertThat(where.sql()).isEqualTo(" WHERE kind = :w0 AND placement = :w1 AND status = :w2"
                + " AND (title ILIKE :w3 ESCAPE '\\' OR code ILIKE :w4 ESCAPE '\\')");
        assertThat(where.params()).containsExactly(
                Map.entry("w0", "CARD"), Map.entry("w1", "HOME_HERO"), Map.entry("w2", "LIVE"),
                Map.entry("w3", "%50\\%\\_x%"), Map.entry("w4", "%50\\%\\_x%"));
    }

    @Test
    void absentOrBlankFiltersProduceNoSql() {
        assertThat(ContentRepository.filters(null, " ", "", null).sql()).isEmpty();
        assertThat(TemplateRepository.filters(null, " ").sql()).isEmpty();
        assertThat(RuleRepository.filters(" ", null).sql()).isEmpty();
        assertThat(InboxRepository.filters(new InboxRepository.Filter(null, " ", "", null)).sql()).isEmpty();
        assertThat(WebhookDeliveryRepository.filters("", null).sql()).isEmpty();
    }

    @Test
    void eachFilterAloneBindsOneParameter() {
        assertThat(ContentRepository.filters(null, null, null, " Alfa ").sql())
                .isEqualTo(" WHERE (title ILIKE :w0 ESCAPE '\\' OR code ILIKE :w1 ESCAPE '\\')");
        assertThat(TemplateRepository.filters("points", null).sql()).isEqualTo(" WHERE category = :w0");
        assertThat(TemplateRepository.filters(null, "inapp").sql()).isEqualTo(" WHERE channel = :w0");
        assertThat(RuleRepository.filters(null, "tpl-a").params()).containsExactly(Map.entry("w0", "TPL-A"));
        assertThat(RuleRepository.filters(" wallet.points.earned ", null).params()).as("tipo di fatto non in maiuscolo")
                .containsExactly(Map.entry("w0", "wallet.points.earned"));
        assertThat(WebhookDeliveryRepository.filters(null, " failed ").sql()).isEqualTo(" WHERE status = :w0");
        assertThat(WebhookDeliveryRepository.filters(null, " failed ").params())
                .containsExactly(Map.entry("w0", "FAILED"));
    }

    @Test
    void otherFiltersCombineInAnd() {
        assertThat(TemplateRepository.filters("points", "inapp").sql())
                .isEqualTo(" WHERE category = :w0 AND channel = :w1");
        assertThat(RuleRepository.filters("member.tier.changed", "tpl-c").sql())
                .isEqualTo(" WHERE fact_type = :w0 AND template_code = :w1");
        SqlWhere inbox = InboxRepository.filters(
                new InboxRepository.Filter(" MBR-000001 ", "points", "inapp", "tpl-a"));
        assertThat(inbox.sql())
                .isEqualTo(" WHERE member_id = :w0 AND category = :w1 AND channel = :w2 AND template_code = :w3");
        assertThat(inbox.params()).containsExactly(Map.entry("w0", "MBR-000001"), Map.entry("w1", "POINTS"),
                Map.entry("w2", "INAPP"), Map.entry("w3", "TPL-A"));
        assertThat(WebhookDeliveryRepository.filters("WH1", "ok").sql())
                .isEqualTo(" WHERE webhook_id = :w0 AND status = :w1");
    }

    @Test
    void injectionShapedInputNeverReachesSqlText() {
        List<SqlWhere> all = List.of(
                ContentRepository.filters(INJECTION, INJECTION, INJECTION, INJECTION),
                TemplateRepository.filters(INJECTION, INJECTION),
                RuleRepository.filters(INJECTION, INJECTION),
                InboxRepository.filters(new InboxRepository.Filter(INJECTION, INJECTION, INJECTION, INJECTION)),
                WebhookDeliveryRepository.filters(INJECTION, INJECTION));
        for (SqlWhere where : all) {
            assertThat(where.sql()).doesNotContain("OR 1=1").doesNotContain("--").doesNotContain("x%")
                    .doesNotContain("X%");
            assertThat(where.params()).isNotEmpty();
            assertThat(where.params().values())
                    .allSatisfy(v -> assertThat(v.toString()).containsIgnoringCase("OR 1=1"));
        }
    }

    @Test
    void columnsComeOnlyFromEnums() {
        // L'allowlist è l'enum: una colonna costruita a mano (per esempio da un parametro) è rifiutata dal builder.
        SqlColumn forged = () -> "status; DROP TABLE content_item";
        assertThatThrownBy(() -> new SqlWhere().eq(forged, "LIVE")).isInstanceOf(IllegalArgumentException.class);
        assertThat(List.of(ContentRepository.ContentColumn.class, TemplateRepository.TemplateColumn.class,
                RuleRepository.RuleColumn.class, InboxRepository.InboxColumn.class,
                WebhookDeliveryRepository.DeliveryColumn.class)).allSatisfy(c -> assertThat(c.isEnum()).isTrue());
    }

    // Filo d'inciampo contro le regressioni, non prova di sicurezza: non vede una concatenazione con "+"
    // né classi fuori da *Repository.java. La garanzia viene dal builder e dai test sul testo SQL qui sopra.
    @Test
    void repositoriesDoNotAssembleSqlByHand() throws IOException {
        // Guardia di regressione: niente WHERE/AND scelti a mano né testo SQL composto con append/format.
        Path infra = Path.of("src/main/java/io/loyaltyhub/engagement/infra");
        try (Stream<Path> files = Files.list(infra)) {
            for (Path file : files.filter(f -> f.toString().endsWith("Repository.java")).toList()) {
                String source = Files.readString(file);
                assertThat(source).as(file.toString())
                        .doesNotContain("StringBuilder")
                        .doesNotContain(".append(")
                        .doesNotContain("WHERE 1 = 1")
                        .doesNotContain("WHERE 1=1")
                        .doesNotContain(".formatted(")
                        .doesNotContain("String.format(");
            }
        }
    }
}
