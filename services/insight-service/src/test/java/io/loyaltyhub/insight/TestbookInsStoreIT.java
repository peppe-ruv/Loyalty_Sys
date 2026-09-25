package io.loyaltyhub.insight;

import io.loyaltyhub.insight.application.RetentionJob;
import io.loyaltyhub.insight.domain.AuditRecord;
import io.loyaltyhub.insight.domain.StoredEvent;
import io.loyaltyhub.insight.infra.AuditRepository;
import io.loyaltyhub.insight.infra.EventStoreRepository;
import io.loyaltyhub.insight.infra.MetricRepository;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testbook TB-INS — conservazione, reset e storico sintetico (docs/testbook/TB-INS-insight.md §11–§12):
 * {@code RET} (event store 14 giorni o 200 000 righe, il minore; audit 180 giorni; metriche illimitate; payload
 * 8 KB), {@code RST} (reset demo), {@code SYN} (90 giorni sintetici con seme fisso). Contesto e database propri:
 * i casi svuotano le tabelle. Il job orario è spento e invocato dal caso. Oracolo: insight §5, §6, §7; RNF-07;
 * docs/06 §10; docs/10 §9; F-INS-04.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "loyaltyhub.insight.retention.cron=-")
@EmbeddedKafka(partitions = 1, topics = {"lh.actions.v1", "lh.effects.v1", "lh.facts.v1", "lh.audit.v1", "lh.dlq.v1"})
@ActiveProfiles("demo")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TestbookInsStoreIT extends TestbookInsSupport {

    static final EmbeddedPostgres PG_STORE = startPg();

    @Autowired
    private EventStoreRepository events;

    @Autowired
    private AuditRepository audits;

    @Autowired
    private MetricRepository metrics;

    @Autowired
    private RetentionJob retention;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        String base = PG_STORE.getJdbcUrl("postgres", "postgres");
        registry.add("spring.datasource.url", () -> base + "&currentSchema=insight");
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "");
        registry.add("spring.kafka.bootstrap-servers", () -> System.getProperty("spring.embedded.kafka.brokers"));
    }

    private void clean() {
        events.deleteAll();
        audits.deleteAll();
    }

    /** Evento arrivato {@code ageSql} fa (espressione di intervallo SQL). */
    private String eventAged(String ageSql) {
        String id = uid("EVT-RET");
        events.insert(new StoredEvent(id, "lh.facts.v1", "FACT", FACT + "tier.retained", "tier.retained",
                "urn:loyaltyhub:service:wallet", "MBR-000001", uid("COR"), null, 0, "system", null, Instant.now(), null,
                0, 0L, "{}"));
        jdbc.sql("UPDATE event_store SET received_at = now() - cast(? AS interval) WHERE event_id = ?")
                .params(ageSql, id).update();
        return id;
    }

    private String auditAged(String ageSql) {
        String id = uid("AUD-RET");
        audits.insert(new AuditRecord(id, uid("EVT"), Instant.now(), "ADMIN", "ada.admin", "campaign", "CAMPAIGN",
                "CMP-X", "UPDATE", "x", null, null, null));
        jdbc.sql("UPDATE audit_entry SET at = now() - cast(? AS interval) WHERE id = ?").params(ageSql, id).update();
        return id;
    }

    private boolean eventExists(String id) {
        return events.findById(id).isPresent();
    }

    // ---------- RET: conservazione ----------

    @ParameterizedTest(name = "[{0}] {1}", quoteTextArguments = false)
    @Order(1)
    @CsvSource({
            "TB-INS-RET-001,evento arrivato 13 giorni fa: conservato,13 days,true",
            "TB-INS-RET-002,evento arrivato 14 giorni meno 1 minuto fa: conservato,14 days -1 minute,true",
            "TB-INS-RET-003,evento arrivato 14 giorni più 1 minuto fa: eliminato,14 days 1 minute,false",
            "TB-INS-RET-004,evento arrivato 15 giorni fa: eliminato,15 days,false",
            "TB-INS-RET-005,evento appena arrivato: conservato,0 seconds,true"})
    void eventAge(String id, String desc, String age, boolean kept) {
        clean();
        String ev = eventAged(age);
        retention.purge();
        assertThat(eventExists(ev)).isEqualTo(kept);
    }

    @ParameterizedTest(name = "[{0}] {1}", quoteTextArguments = false)
    @Order(2)
    @CsvSource({
            "TB-INS-RET-006,soglia 5 righe e 4 eventi recenti: tutti conservati,5,4,0,4",
            "TB-INS-RET-007,soglia 5 righe e 5 eventi recenti (limite): tutti conservati,5,5,0,5",
            "TB-INS-RET-008,soglia 5 righe e 6 eventi recenti: eliminato il più vecchio,5,6,0,5",
            "TB-INS-RET-009,8 eventi (3 oltre 14 giorni) e soglia 6: vince l'età e restano 5,6,5,3,5",
            "TB-INS-RET-010,8 eventi (3 oltre 14 giorni) e soglia 3: vince la soglia e restano i 3 più recenti,3,5,3,3"})
    void rowCap(String id, String desc, int maxRows, int recent, int old, int expectedLeft) {
        clean();
        List<String> recentIds = new ArrayList<>();
        for (int i = 0; i < old; i++) {
            eventAged((20 + i) + " days");
        }
        for (int i = 0; i < recent; i++) {
            recentIds.add(eventAged((recent - i) + " minutes"));
        }
        new RetentionJob(events, audits, 14, maxRows, 180).purge();
        assertThat(events.count()).isEqualTo(expectedLeft);
        List<String> newest = recentIds.subList(recentIds.size() - Math.min(expectedLeft, recentIds.size()), recentIds.size());
        newest.forEach(e -> assertThat(eventExists(e)).as("conservato il più recente " + e).isTrue());
    }

    @ParameterizedTest(name = "[{0}] {1}", quoteTextArguments = false)
    @Order(3)
    @CsvSource({
            "TB-INS-RET-011,voce di audit di 179 giorni: conservata,179 days,true",
            "TB-INS-RET-012,voce di audit di 180 giorni meno 1 minuto: conservata,180 days -1 minute,true",
            "TB-INS-RET-013,voce di audit di 180 giorni più 1 minuto: eliminata,180 days 1 minute,false",
            "TB-INS-RET-014,voce di audit di 181 giorni: eliminata,181 days,false",
            "TB-INS-RET-015,voce di audit di 20 giorni (oltre i 14 dell'event store): conservata,20 days,true"})
    void auditAge(String id, String desc, String age, boolean kept) {
        clean();
        String a = auditAged(age);
        retention.purge();
        assertThat(audits.findById(a).isPresent()).isEqualTo(kept);
    }

    @Test
    @Order(4)
    @DisplayName("[TB-INS-RET-016] metric_daily illimitata: una metrica di 10 anni fa resta dopo la pulizia")
    void metricsUnlimited() {
        LocalDate old = LocalDate.now(ZoneOffset.UTC).minusYears(10);
        metrics.increment(old, "actions", MetricRepository.TOTAL, MetricRepository.TOTAL, 3);
        retention.purge();
        assertThat(metrics.total("actions", old, old)).isEqualTo(3);
    }

    @Test
    @Order(5)
    @DisplayName("[TB-INS-RET-017] payload di 20 KB: conservato troncato a 8 KB (insight §5)")
    void payloadTruncated() {
        String ev = uid("EVT-BIG");
        publish("lh.facts.v1", envelope(ev, FACT + "member.updated", "urn:loyaltyhub:service:member",
                "member:MBR-000001", uid("COR"), null, Instant.now(), Map.of("note", "x".repeat(20_000))));
        await("evento grande registrato", () -> eventExists(ev), 20_000);
        retention.purge();
        int bytes = jdbc.sql("SELECT octet_length(payload::text) FROM event_store WHERE event_id = ?").param(ev)
                .query(Integer.class).single();
        assertThat(bytes).isLessThanOrEqualTo(8192);
    }

    // ---------- RST: reset demo ----------

    private long rows(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }

    private List<String> metricSnapshot() {
        return jdbc.sql("SELECT day::text || '|' || metric || '|' || dimension || '|' || dim_value || '|' || value || '|' "
                + "|| synthetic FROM metric_daily ORDER BY 1").query(String.class).list();
    }

    @Test
    @Order(10)
    @DisplayName("[TB-INS-RST-001] reset da ADMIN: event store, audit, DLQ e statistiche per topic svuotati")
    void resetClears() {
        String ev = uid("EVT-RST");
        publish("lh.facts.v1", envelope(ev, FACT + "tier.retained", "urn:loyaltyhub:service:wallet", "member:MBR-000001",
                uid("COR"), null, Instant.now(), Map.of()));
        await("evento registrato", () -> eventExists(ev), 20_000);
        auditAged("1 day");
        publish("lh.dlq.v1", "k", "{}", Map.of("lh-original-topic", "lh.facts.v1", "lh-consumer", "lh-tb-rst"), null);
        await("voce DLQ", () -> rows("dlq_entry") > 0, 20_000);
        Resp r = call("POST", "/v1/demo/reset", actor("ADMIN"), null);
        assertThat(r.status()).as(r.text()).isEqualTo(200);
        assertThat(rows("event_store")).isZero();
        assertThat(rows("dlq_entry")).isZero();
        assertThat(rows("topic_stat")).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM audit_entry WHERE action <> 'RESET'").query(Long.class).single()).isZero();
    }

    @Test
    @Order(11)
    @DisplayName("[TB-INS-RST-002] reset: storico sintetico rigenerato identico (seme fisso, docs/10 §1.3)")
    void resetDeterministic() {
        assertThat(call("POST", "/v1/demo/reset", actor("ADMIN"), null).status()).isEqualTo(200);
        List<String> first = metricSnapshot();
        metrics.increment(LocalDate.now(ZoneOffset.UTC).minusDays(3), "actions", MetricRepository.TOTAL,
                MetricRepository.TOTAL, 1000);
        assertThat(call("POST", "/v1/demo/reset", actor("ADMIN"), null).status()).isEqualTo(200);
        assertThat(first).isNotEmpty();
        assertThat(metricSnapshot()).containsExactlyElementsOf(first);
    }

    @Test
    @Order(12)
    @DisplayName("[TB-INS-RST-003] reset da MARKETING: 403, nulla svuotato")
    void resetForbidden() {
        String ev = uid("EVT-RST");
        publish("lh.facts.v1", envelope(ev, FACT + "tier.retained", "urn:loyaltyhub:service:wallet", "member:MBR-000001",
                uid("COR"), null, Instant.now(), Map.of()));
        await("evento registrato", () -> eventExists(ev), 20_000);
        assertThat(call("POST", "/v1/demo/reset", actor("MARKETING"), null).status()).isEqualTo(403);
        assertThat(eventExists(ev)).isTrue();
    }

    @Test
    @Order(13)
    @DisplayName("[TB-INS-RST-004] reset: voce di audit RESET con l'attore ADMIN (docs/06 §10)")
    void resetAudited() {
        assertThat(call("POST", "/v1/demo/reset", actor("ADMIN"), null).status()).isEqualTo(200);
        await("audit RESET", () -> get("/v1/audit?action=RESET").path("items").size() > 0, 10_000);
        JsonNode a = get("/v1/audit?action=RESET").path("items").get(0);
        assertThat(a.path("actorRole").asString()).isEqualTo("ADMIN");
    }

    // ---------- SYN: storico sintetico ----------

    private List<JsonNode> series(String metric) {
        LocalDate today = LocalDate.now(ROME); // giorno di business (docs/03), come il generatore
        List<JsonNode> out = new ArrayList<>();
        get("/v1/kpi/timeseries?metric=" + metric + "&from=" + today.minusDays(90) + "&to=" + today.minusDays(1))
                .path("points").forEach(out::add);
        return out;
    }

    @ParameterizedTest(name = "[{0}] {1}", quoteTextArguments = false)
    @Order(20)
    @CsvSource({
            "TB-INS-SYN-001,actions: 90 giorni sintetici con valore positivo,actions",
            "TB-INS-SYN-002,points_earned: 90 giorni sintetici con valore positivo,points_earned",
            "TB-INS-SYN-003,points_spent: 90 giorni sintetici con valore positivo,points_spent",
            "TB-INS-SYN-004,points_expired: 90 giorni sintetici con valore positivo,points_expired",
            "TB-INS-SYN-005,members_new: 90 giorni sintetici con valore positivo,members_new",
            "TB-INS-SYN-006,members_active: 90 giorni sintetici con valore positivo,members_active",
            "TB-INS-SYN-007,tier_changes: 90 giorni sintetici con valore positivo,tier_changes",
            "TB-INS-SYN-008,redemptions (docs/10 §9 richieste 14/giorno): 90 giorni sintetici,redemptions",
            "TB-INS-SYN-009,plays (docs/10 §9 giocate 95/giorno): 90 giorni sintetici,plays",
            "TB-INS-SYN-010,wins (docs/10 §9 vincite 11/giorno): 90 giorni sintetici,wins"})
    void ninetyDays(String id, String desc, String metric) {
        List<JsonNode> pts = series(metric);
        assertThat(pts).as("giorni sintetici di " + metric).hasSize(90);
        assertThat(pts).allMatch(p -> p.path("synthetic").asBoolean() && p.path("value").asLong() > 0);
    }

    private static double mean(List<Long> v) {
        return v.stream().mapToLong(Long::longValue).average().orElse(0);
    }

    @Test
    @Order(21)
    @DisplayName("[TB-INS-SYN-011] stagionalità: points_earned del sabato e della domenica ≈ +35 % sui feriali (tra +20 % e +50 %)")
    void weekendUplift() {
        List<Long> weekend = new ArrayList<>();
        List<Long> weekday = new ArrayList<>();
        for (JsonNode p : series("points_earned")) {
            DayOfWeek d = LocalDate.parse(p.path("day").asString()).getDayOfWeek();
            (d == DayOfWeek.SATURDAY || d == DayOfWeek.SUNDAY ? weekend : weekday).add(p.path("value").asLong());
        }
        double ratio = mean(weekend) / mean(weekday);
        assertThat(ratio).isBetween(1.20, 1.50);
    }

    @Test
    @Order(22)
    @DisplayName("[TB-INS-SYN-012] picco della campagna estiva al giorno −30: actions a −30 oltre 1,3 volte la media dei giorni −50…−40")
    void peak() {
        List<JsonNode> pts = series("actions");
        long atPeak = pts.get(60).path("value").asLong();
        List<Long> before = new ArrayList<>();
        for (int i = 40; i <= 50; i++) {
            before.add(pts.get(i).path("value").asLong());
        }
        assertThat(atPeak).isGreaterThan((long) (1.3 * mean(before)));
    }

    @Test
    @Order(23)
    @DisplayName("[TB-INS-SYN-013] trend +0,4 %/giorno: media degli ultimi 14 giorni oltre 1,15 volte quella dei primi 14 (points_spent)")
    void trend() {
        List<JsonNode> pts = series("points_spent");
        List<Long> first = new ArrayList<>();
        List<Long> last = new ArrayList<>();
        for (int i = 0; i < 14; i++) {
            first.add(pts.get(i).path("value").asLong());
            last.add(pts.get(pts.size() - 1 - i).path("value").asLong());
        }
        assertThat(mean(last)).isGreaterThan(1.15 * mean(first));
    }

    @Test
    @Order(24)
    @DisplayName("[TB-INS-SYN-014] ripartizioni coerenti: per ogni giorno la somma per valuta e per fonte è uguale al totale")
    void allocations() {
        List<String> bad = jdbc.sql("""
                SELECT t.metric || ' ' || t.day FROM metric_daily t
                JOIN (SELECT day, metric, dimension, sum(value) AS s FROM metric_daily WHERE dimension <> ''
                      GROUP BY day, metric, dimension) p ON p.day = t.day AND p.metric = t.metric
                WHERE t.dimension = '' AND t.synthetic AND p.s <> t.value
                """).query(String.class).list();
        assertThat(bad).isEmpty();
    }

    @Test
    @Order(25)
    @DisplayName("[TB-INS-SYN-015] dato reale in un giorno sintetico: sommato e il giorno resta marcato synthetic")
    void realOnSynthetic() {
        LocalDate day = LocalDate.now(ZoneOffset.UTC).minusDays(2);
        long before = metrics.total("points_expired", day, day);
        metrics.increment(day, "points_expired", MetricRepository.TOTAL, MetricRepository.TOTAL, 7);
        JsonNode p = get("/v1/kpi/timeseries?metric=points_expired&from=" + day + "&to=" + day).path("points").get(0);
        assertThat(p.path("value").asLong()).isEqualTo(before + 7);
        assertThat(p.path("synthetic").asBoolean()).isTrue();
    }

    @Test
    @Order(26)
    @DisplayName("[TB-INS-SYN-016] BO-01 a 90 giorni subito dopo il reset (insight §7): nessun giorno passato vuoto")
    void dashboard90() {
        JsonNode pts = get("/v1/kpi/timeseries?metric=points_earned&days=90").path("points");
        assertThat(pts.size()).isGreaterThanOrEqualTo(89);
        for (JsonNode p : pts) {
            if (!LocalDate.parse(p.path("day").asString()).equals(LocalDate.now(ROME))) {
                assertThat(p.path("synthetic").asBoolean()).isTrue();
                assertThat(p.path("value").asLong()).isPositive();
            }
        }
    }
}
