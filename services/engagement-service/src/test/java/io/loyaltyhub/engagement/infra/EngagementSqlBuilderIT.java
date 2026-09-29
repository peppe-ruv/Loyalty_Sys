package io.loyaltyhub.engagement.infra;

import io.loyaltyhub.engagement.domain.ContentItem;
import io.loyaltyhub.engagement.domain.InboxMessage;
import io.loyaltyhub.engagement.domain.MessageTemplate;
import io.loyaltyhub.engagement.domain.NotificationRule;
import io.loyaltyhub.engagement.domain.WebhookDelivery;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Elenchi filtrati di engagement col builder SQL comune (regola 19, ADR-042, docs/18 §3.10 punto 4): ogni filtro da
 * solo e combinato, ordinamento e paginazione invariati, {@code q} letterale ({@code %}, {@code _}, {@code \} non sono
 * caratteri jolly) e tentativi di iniezione senza effetto. Solo database (Postgres incorporato e migrazioni del
 * servizio), senza Kafka né contesto Spring; dati propri, non i seed.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EngagementSqlBuilderIT {

    private static final String INJECTION = "x%' OR 1=1 --";

    private EmbeddedPostgres pg;
    private JdbcClient jdbc;
    private ContentRepository contents;
    private TemplateRepository templates;
    private RuleRepository rules;
    private InboxRepository inbox;
    private WebhookDeliveryRepository deliveries;

    @BeforeAll
    void migrateAndInsert() throws Exception {
        pg = EmbeddedPostgres.builder().start();
        SimpleDriverDataSource ds = new SimpleDriverDataSource(new org.postgresql.Driver(),
                pg.getJdbcUrl("postgres", "postgres") + "&currentSchema=engagement", "postgres", "");
        Flyway.configure().dataSource(ds).schemas("engagement").defaultSchema("engagement").createSchemas(true)
                .locations("classpath:db/migration/common", "classpath:db/migration/engagement").load().migrate();
        jdbc = JdbcClient.create(ds);
        ObjectMapper mapper = new ObjectMapper();
        contents = new ContentRepository(jdbc, mapper);
        templates = new TemplateRepository(jdbc);
        rules = new RuleRepository(jdbc, mapper);
        inbox = new InboxRepository(jdbc);
        deliveries = new WebhookDeliveryRepository(jdbc);

        jdbc.sql("""
                INSERT INTO content_item (id, code, kind, placement, title, status, priority) VALUES
                  ('C1', 'CNT-ALPHA', 'CARD', 'HOME_GRID', 'Alfa 50% di sconto', 'LIVE', 10),
                  ('C2', 'CNT-BETA', 'BANNER', 'HOME_GRID', 'Beta_promo', 'DRAFT', 90),
                  ('C3', 'CNT-GAMMA', 'POPUP', NULL, 'Gamma \\ barra', 'PAUSED', 50),
                  ('C4', 'CNT-DELTA', 'CARD', 'CATALOG_TOP', 'Delta', 'ARCHIVED', 99),
                  ('C5', 'CNT-EPS', 'CARD', 'HOME_GRID', 'Epsilon', 'LIVE', 80)
                """).update();
        jdbc.sql("""
                INSERT INTO message_template (code, name, channel, title_tpl, body_tpl, category) VALUES
                  ('TPL-C', 'C', 'INAPP', 't', 'b', 'TIER'),
                  ('TPL-A', 'A', 'INAPP', 't', 'b', 'POINTS'),
                  ('TPL-B', 'B', 'EMAIL_FAKE', 't', 'b', 'POINTS')
                """).update();
        jdbc.sql("""
                INSERT INTO notification_rule (id, code, fact_type, template_code) VALUES
                  ('R2', 'NR-2', 'wallet.points.earned', 'TPL-A'),
                  ('R1', 'NR-1', 'wallet.points.earned', 'TPL-B'),
                  ('R3', 'NR-3', 'member.tier.changed', 'TPL-C')
                """).update();
        // M4 e M5 hanno lo stesso istante: lo spareggio su id DESC mette M5 prima di M4.
        jdbc.sql("""
                INSERT INTO inbox_message (id, member_id, template_code, channel, title, body, category,
                                           source_event_id, created_at) VALUES
                  ('M1', 'MBR-1', 'TPL-A', 'INAPP', 't', 'b', 'POINTS', 'E1', '2026-01-01T10:00:00Z'),
                  ('M2', 'MBR-1', 'TPL-C', 'INAPP', 't', 'b', 'TIER', 'E2', '2026-01-02T10:00:00Z'),
                  ('M3', 'MBR-2', 'TPL-B', 'EMAIL_FAKE', 't', 'b', 'POINTS', 'E3', '2026-01-03T10:00:00Z'),
                  ('M4', 'MBR-1', 'TPL-B', 'EMAIL_FAKE', 't', 'b', 'POINTS', 'E4', '2026-01-04T10:00:00Z'),
                  ('M5', 'MBR-2', 'TPL-A', 'INAPP', 't', 'b', 'POINTS', 'E5', '2026-01-04T10:00:00Z')
                """).update();
        jdbc.sql("""
                INSERT INTO webhook (id, code, name, url, secret) VALUES
                  ('WH1', 'WH-ONE', 'Uno', 'https://hooks.example.test/1', 's1'),
                  ('WH2', 'WH-TWO', 'Due', 'https://hooks.example.test/2', 's2')
                """).update();
        // D3 e D4 hanno lo stesso istante: lo spareggio su id DESC mette D4 prima di D3.
        jdbc.sql("""
                INSERT INTO webhook_delivery (id, webhook_id, event_id, fact_type, payload, signature, status,
                                              created_at) VALUES
                  ('D1', 'WH1', 'E1', 'wallet.points.earned', '{}', 'sig', 'OK', '2026-01-01T10:00:00Z'),
                  ('D2', 'WH1', 'E2', 'wallet.points.earned', '{}', 'sig', 'FAILED', '2026-01-02T10:00:00Z'),
                  ('D3', 'WH2', 'E3', 'wallet.points.earned', '{}', 'sig', 'OK', '2026-01-03T10:00:00Z'),
                  ('D4', 'WH1', 'E4', 'wallet.points.earned', '{}', 'sig', 'OK', '2026-01-03T10:00:00Z')
                """).update();
    }

    @AfterAll
    void stop() throws Exception {
        pg.close();
    }

    // ---------- contenuti (BO-20): ordine per stato, priorità DESC, codice ----------

    @Test
    void contentFiltersAloneAndCombinedKeepTheStatusPriorityOrder() {
        assertThat(contentCodes(null, null, null, null))
                .containsExactly("CNT-EPS", "CNT-ALPHA", "CNT-GAMMA", "CNT-BETA", "CNT-DELTA");
        assertThat(contentCodes(" ", "", " ", "")).as("testi vuoti = nessun filtro")
                .containsExactly("CNT-EPS", "CNT-ALPHA", "CNT-GAMMA", "CNT-BETA", "CNT-DELTA");
        assertThat(contentCodes(" card ", null, null, null)).containsExactly("CNT-EPS", "CNT-ALPHA", "CNT-DELTA");
        assertThat(contentCodes(null, "home_grid", null, null)).containsExactly("CNT-EPS", "CNT-ALPHA", "CNT-BETA");
        assertThat(contentCodes(null, null, "live", null)).containsExactly("CNT-EPS", "CNT-ALPHA");
        assertThat(contentCodes(null, null, null, " ALFA ")).as("q nel titolo").containsExactly("CNT-ALPHA");
        assertThat(contentCodes(null, null, null, "cnt-b")).as("q nel codice").containsExactly("CNT-BETA");
        assertThat(contentCodes("CARD", "HOME_GRID", "LIVE", "eps")).containsExactly("CNT-EPS");
        assertThat(contentCodes("CARD", "HOME_GRID", "DRAFT", null)).isEmpty();
    }

    @Test
    void contentSearchTreatsLikeWildcardsAsLiterals() {
        assertThat(contentCodes(null, null, null, "%")).containsExactly("CNT-ALPHA");
        assertThat(contentCodes(null, null, null, "_")).containsExactly("CNT-BETA");
        assertThat(contentCodes(null, null, null, "\\")).containsExactly("CNT-GAMMA");
        assertThat(contentCodes(null, null, null, "CNT_ALPHA")).as("_ non vale un carattere qualsiasi").isEmpty();
        assertThat(contentCodes(null, null, null, "Beta%promo")).as("% non vale una sequenza qualsiasi").isEmpty();
    }

    // ---------- template e regole (BO-19): ordine per codice / tipo di fatto e codice ----------

    @Test
    void templateFiltersAloneAndCombined() {
        assertThat(templateCodes(null, null)).containsExactly("TPL-A", "TPL-B", "TPL-C");
        assertThat(templateCodes(" points ", null)).containsExactly("TPL-A", "TPL-B");
        assertThat(templateCodes(null, "inapp")).containsExactly("TPL-A", "TPL-C");
        assertThat(templateCodes("POINTS", "INAPP")).containsExactly("TPL-A");
        assertThat(templateCodes("TIER", "EMAIL_FAKE")).isEmpty();
    }

    @Test
    void ruleFiltersAloneAndCombined() {
        assertThat(ruleCodes(null, null)).containsExactly("NR-3", "NR-1", "NR-2");
        assertThat(ruleCodes(" wallet.points.earned ", null)).containsExactly("NR-1", "NR-2");
        assertThat(ruleCodes("WALLET.POINTS.EARNED", null)).as("il tipo di fatto non si porta in maiuscolo").isEmpty();
        assertThat(ruleCodes(null, "tpl-a")).containsExactly("NR-2");
        assertThat(ruleCodes("wallet.points.earned", "TPL-B")).containsExactly("NR-1");
        assertThat(ruleCodes("member.tier.changed", "TPL-A")).isEmpty();
    }

    // ---------- registro messaggi (BO-19, Scheda 360°): created_at DESC, id DESC, pagine {items,page} ----------

    @Test
    void inboxFiltersAloneAndCombinedWithMatchingCount() {
        assertThat(inboxIds(filter(null, null, null, null))).containsExactly("M5", "M4", "M3", "M2", "M1");
        assertThat(inboxIds(filter(" MBR-1 ", null, null, null))).containsExactly("M4", "M2", "M1");
        assertThat(inboxIds(filter(null, "points", null, null))).containsExactly("M5", "M4", "M3", "M1");
        assertThat(inboxIds(filter(null, null, "inapp", null))).containsExactly("M5", "M2", "M1");
        assertThat(inboxIds(filter(null, null, null, "tpl-b"))).containsExactly("M4", "M3");
        assertThat(inboxIds(filter("MBR-1", "POINTS", "EMAIL_FAKE", "TPL-B"))).containsExactly("M4");
        assertThat(inboxIds(filter("MBR-2", "TIER", null, null))).isEmpty();
    }

    @Test
    void inboxPagesAreDisjointAndFollowTheStableOrder() {
        InboxRepository.Filter all = filter(null, null, null, null);
        assertThat(ids(inbox.search(all, 0, 2))).containsExactly("M5", "M4");
        assertThat(ids(inbox.search(all, 1, 2))).containsExactly("M3", "M2");
        assertThat(ids(inbox.search(all, 2, 2))).containsExactly("M1");
        assertThat(inbox.search(all, 3, 2)).isEmpty();
        assertThat(inbox.count(all)).isEqualTo(5);
        assertThat(ids(inbox.search(filter("MBR-1", null, null, null), 1, 2))).containsExactly("M1");
    }

    // ---------- storico consegne webhook (BO-23): created_at DESC, id DESC ----------

    @Test
    void deliveryFiltersAloneAndCombinedWithPages() {
        assertThat(deliveryIds(null, null)).containsExactly("D4", "D3", "D2", "D1");
        assertThat(deliveryIds("WH1", null)).containsExactly("D4", "D2", "D1");
        assertThat(deliveryIds(null, " ok ")).containsExactly("D4", "D3", "D1");
        assertThat(deliveryIds("WH1", "OK")).containsExactly("D4", "D1");
        assertThat(deliveryIds("WH2", "FAILED")).isEmpty();

        assertThat(deliveries.page(null, null, 0, 3).stream().map(WebhookDelivery::id).toList())
                .containsExactly("D4", "D3", "D2");
        assertThat(deliveries.page(null, null, 1, 3).stream().map(WebhookDelivery::id).toList())
                .containsExactly("D1");
    }

    // ---------- iniezione ----------

    @Test
    void injectionAttemptsFindNothingAndChangeNothing() {
        long[] before = rowCounts();

        assertThat(contents.findAll(INJECTION, null, null, null)).isEmpty();
        assertThat(contents.findAll(null, INJECTION, null, null)).isEmpty();
        assertThat(contents.findAll(null, null, INJECTION, null)).isEmpty();
        assertThat(contents.findAll(null, null, null, INJECTION)).isEmpty();
        assertThat(contents.findAll(null, null, null, "' OR '1'='1")).isEmpty();
        assertThat(templates.findAll(INJECTION, null)).isEmpty();
        assertThat(templates.findAll(null, "INAPP'; DELETE FROM message_template; --")).isEmpty();
        assertThat(rules.findAll(INJECTION, null)).isEmpty();
        assertThat(rules.findAll(null, "TPL-A' OR '1'='1")).isEmpty();
        for (InboxRepository.Filter f : List.of(filter(INJECTION, null, null, null), filter(null, INJECTION, null, null),
                filter(null, null, "INAPP'; DROP TABLE inbox_message; --", null), filter(null, null, null, INJECTION))) {
            assertThat(inbox.search(f, 0, 100)).as(f.toString()).isEmpty();
            assertThat(inbox.count(f)).as(f.toString()).isZero();
        }
        assertThat(deliveries.page("WH1' OR '1'='1", null, 0, 100)).isEmpty();
        assertThat(deliveries.count(null, INJECTION)).isZero();

        assertThat(rowCounts()).containsExactly(before);
    }

    // ---------- helper ----------

    private List<String> contentCodes(String kind, String placement, String status, String q) {
        return contents.findAll(kind, placement, status, q).stream().map(ContentItem::code).toList();
    }

    private List<String> templateCodes(String category, String channel) {
        return templates.findAll(category, channel).stream().map(MessageTemplate::code).toList();
    }

    private List<String> ruleCodes(String factType, String templateCode) {
        return rules.findAll(factType, templateCode).stream().map(NotificationRule::code).toList();
    }

    private static InboxRepository.Filter filter(String memberId, String category, String channel, String template) {
        return new InboxRepository.Filter(memberId, category, channel, template);
    }

    /** Id della prima pagina (al più 100), verificando che il totale coincida. */
    private List<String> inboxIds(InboxRepository.Filter f) {
        List<String> ids = ids(inbox.search(f, 0, 100));
        assertThat(inbox.count(f)).as("count " + f).isEqualTo(ids.size());
        return ids;
    }

    /** Id della prima pagina (al più 100), verificando che il totale coincida. */
    private List<String> deliveryIds(String webhookId, String status) {
        List<String> ids = deliveries.page(webhookId, status, 0, 100).stream().map(WebhookDelivery::id).toList();
        assertThat(deliveries.count(webhookId, status)).as("count " + webhookId + "/" + status).isEqualTo(ids.size());
        return ids;
    }

    private static List<String> ids(List<InboxMessage> messages) {
        return messages.stream().map(InboxMessage::id).toList();
    }

    private long[] rowCounts() {
        return jdbc.sql("""
                        SELECT (SELECT count(*) FROM content_item), (SELECT count(*) FROM message_template),
                               (SELECT count(*) FROM notification_rule), (SELECT count(*) FROM inbox_message),
                               (SELECT count(*) FROM webhook), (SELECT count(*) FROM webhook_delivery)
                        """)
                .query((rs, n) -> new long[]{rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4),
                        rs.getLong(5), rs.getLong(6)})
                .single();
    }
}
