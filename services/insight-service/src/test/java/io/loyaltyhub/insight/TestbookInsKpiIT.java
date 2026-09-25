package io.loyaltyhub.insight;

import io.loyaltyhub.insight.infra.MetricRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testbook TB-INS — KPI (docs/testbook/TB-INS-insight.md §5): metriche dall'ingest senza doppi conteggi
 * ({@code MET}), giorno di business in Europe/Rome con mezzanotte e cambio d'ora ({@code DAY}), overview con delta
 * ({@code KOV}), serie ({@code KTS}), ripartizioni ({@code KBR}). Le metriche dei casi KOV/KTS/KBR sono scritte
 * direttamente in {@code metric_daily} in giorni lontani (nessuna interferenza con lo storico sintetico, che copre
 * i 90 giorni prima di oggi). Oracolo: insight §2, §3, §5, §7; docs/03 (fuso di business Europe/Rome); docs/05 §5;
 * docs/06 §2; docs/08 BO-01; docs/10 §9.
 */
class TestbookInsKpiIT extends TestbookInsBase {

    @Autowired
    private MetricRepository metrics;

    // ---------- lettura ----------

    private long total(String metric, String from, String to) {
        long sum = 0;
        for (JsonNode p : get("/v1/kpi/timeseries?metric=" + metric + "&from=" + from + "&to=" + to).path("points")) {
            sum += p.path("value").asLong();
        }
        return sum;
    }

    private long slice(String metric, String dimension, String value, String from, String to) {
        long sum = 0;
        JsonNode b = get("/v1/kpi/breakdown?metric=" + metric + "&dimension=" + dimension + "&from=" + from + "&to=" + to
                + "&limit=100");
        for (JsonNode s : b.path("slices")) {
            if ("ANY".equals(value) || value.equals(s.path("dimValue").asString())) {
                sum += s.path("value").asLong();
            }
        }
        return sum;
    }

    private long read(String metric, String dimension, String value, String day) {
        return "-".equals(dimension) ? total(metric, day, day) : slice(metric, dimension, value, day, day);
    }

    /** Pubblica una sentinella dopo il caso sullo stesso topic (partizione unica): quando è registrata, anche i precedenti. */
    private void sentinel(String topic, String type) {
        String id = uid("EVT-SENT");
        publish(topic, envelope(id, type, "urn:loyaltyhub:service:campaign", "member:MBR-000001", uid("COR"), null,
                Instant.parse("2099-12-31T12:00:00Z"), Map.of()));
        awaitStored(id);
    }

    private static String fullType(String spec) {
        String[] p = spec.split(":", 2);
        return switch (p[0]) {
            case "ACTION" -> ACTION + p[1];
            case "EFFECT" -> EFFECT + p[1];
            case "AUDIT" -> "io.loyaltyhub.audit." + p[1];
            default -> FACT + p[1];
        };
    }

    // ---------- MET: metriche dall'ingest ----------

    @ParameterizedTest(name = "[{0}] {1}", quoteTextArguments = false)
    @CsvFileSource(resources = "/testbook/ins/kpi-metriche.csv", numLinesToSkip = 1)
    void metriche(String id, String desc, String typeSpec, String source, String data, String metric, String dimension,
                  String value, long expDelta, String copies) {
        int n = Integer.parseInt(id.substring(id.length() - 3));
        String day = LocalDate.parse("2033-01-01").plusDays(n).toString();
        long before = read(metric, dimension, value, day);
        String type = fullType(typeSpec);
        String eventId = uid("EVT-MET");
        ObjectNode env = envelope(eventId, type, source, "member:MBR-000003", uid("COR-MET"), null,
                Instant.parse(day + "T12:00:00Z"), kv(data));
        if (type.startsWith("io.loyaltyhub.audit.")) {
            env.put("subject", "CAMPAIGN:CMP-X");
            env.put("lhactor", "ADMIN:ada.admin");
        }
        String topic = topicOf(type);
        publish(topic, env);
        if ("2".equals(copies)) {
            publish(topic, env);
        } else if ("R".equals(copies)) {
            ObjectNode again = env.deepCopy();
            ((ObjectNode) again.path("data")).put("amount", 999);
            publish(topic, again);
        }
        sentinel(topic, type.startsWith(ACTION) ? ACTION + "sentinel.test" : FACT + "sentinel.test");
        assertThat(eventStored(eventId)).isTrue();
        assertThat(read(metric, dimension, value, day) - before).as("variazione di " + metric).isEqualTo(expDelta);
    }

    @Test
    @DisplayName("[TB-INS-MET-032] voce DLQ nuova: metrica dlq +1; lo stesso record riletto non la raddoppia")
    void dlqMetric() {
        String day = "2020-04-10";
        long before = total("dlq", day, day);
        String ev = uid("EVT-MET");
        String value = mapper.writeValueAsString(envelope(ev, ACTION + "app.login.daily", "urn:loyaltyhub:source:app",
                "member:MBR-000002", uid("COR"), null, Instant.parse(day + "T12:00:00Z"), Map.of()));
        Map<String, String> h = Map.of("lh-original-topic", "lh.actions.v1", "lh-consumer", "lh-tb-met-032",
                "lh-error-code", "X");
        long ts = Instant.parse(day + "T12:00:00Z").toEpochMilli();
        publish("lh.dlq.v1", "k", value, h, ts);
        publish("lh.dlq.v1", "k", value, h, ts);
        String sentinel = uid("EVT-SENT");
        publish("lh.dlq.v1", "k", mapper.writeValueAsString(envelope(sentinel, ACTION + "app.login.daily",
                "urn:loyaltyhub:source:app", "member:MBR-000002", uid("COR"), null, Instant.now(), Map.of())), h, null);
        await("sentinella DLQ", () -> get("/v1/dlq?consumer=lh-tb-met-032&size=100").toString().contains(sentinel), 20_000);
        assertThat(total("dlq", day, day) - before).isEqualTo(1);
    }

    // ---------- DAY: giorno di business (Europe/Rome) ----------

    @ParameterizedTest(name = "[{0}] {1}", quoteTextArguments = false)
    @CsvFileSource(resources = "/testbook/ins/kpi-giorno.csv", numLinesToSkip = 1)
    void giorno(String id, String desc, String time, String clock, String expDay, String otherDay) {
        if (!"-".equals(clock)) {
            CLOCK.set(Instant.parse(clock));
        }
        long expBefore = total("actions", expDay, expDay);
        long otherBefore = total("actions", otherDay, otherDay);
        String eventId = uid("EVT-DAY");
        ObjectNode env = envelope(eventId, ACTION + "app.login.daily", "urn:loyaltyhub:source:app", "member:MBR-000002",
                uid("COR-DAY"), null, "-".equals(time) ? null : Instant.parse(time), Map.of("platform", "IOS"));
        publish("lh.actions.v1", env);
        awaitStored(eventId);
        assertThat(total("actions", expDay, expDay) - expBefore).as("azioni nel giorno di Roma " + expDay).isEqualTo(1);
        assertThat(total("actions", otherDay, otherDay) - otherBefore).as("azioni in " + otherDay).isZero();
    }

    @Test
    @DisplayName("[TB-INS-DAY-022] voce DLQ vista alle 23:30Z del 10/3/2020 (00:30 dell'11 a Roma): metrica dlq nel giorno 2020-03-11")
    void dlqDay() {
        long before = total("dlq", "2020-03-11", "2020-03-11");
        String ev = uid("EVT-DAY");
        publish("lh.dlq.v1", "k", mapper.writeValueAsString(envelope(ev, ACTION + "app.login.daily",
                        "urn:loyaltyhub:source:app", "member:MBR-000002", uid("COR"), null, Instant.now(), Map.of())),
                Map.of("lh-original-topic", "lh.actions.v1", "lh-consumer", "lh-tb-day-022", "lh-error-code", "X"),
                Instant.parse("2020-03-10T23:30:00Z").toEpochMilli());
        await("voce DLQ", () -> get("/v1/dlq?consumer=lh-tb-day-022").toString().contains(ev), 20_000);
        assertThat(total("dlq", "2020-03-11", "2020-03-11") - before).isEqualTo(1);
    }

    @Test
    @DisplayName("[TB-INS-DAY-023] finestra di default alle 22:30Z del 10/5/2031 (00:30 dell'11 a Roma): to = 2031-05-11")
    void defaultWindowRome() {
        CLOCK.set(Instant.parse("2031-05-10T22:30:00Z"));
        assertThat(get("/v1/kpi/overview?days=7").path("to").asString()).isEqualTo("2031-05-11");
    }

    @Test
    @DisplayName("[TB-INS-DAY-024] finestra di default alle 12:00Z del 10/5/2031: to = 2031-05-10, from = 2031-05-04 (7 giorni)")
    void defaultWindowMidday() {
        CLOCK.set(Instant.parse("2031-05-10T12:00:00Z"));
        JsonNode o = get("/v1/kpi/overview?days=7");
        assertThat(o.path("to").asString()).isEqualTo("2031-05-10");
        assertThat(o.path("from").asString()).isEqualTo("2031-05-04");
    }

    // ---------- KOV: overview ----------

    private void put(String metric, String day, long value) {
        metrics.increment(LocalDate.parse(day), metric, MetricRepository.TOTAL, MetricRepository.TOTAL, value);
    }

    private JsonNode overview(String from, String to) {
        return get("/v1/kpi/overview?from=" + from + "&to=" + to);
    }

    @Test
    @DisplayName("[TB-INS-KOV-001] totali nella finestra [from, to] con estremi inclusi, esclusi il giorno prima e il giorno dopo")
    void overviewWindow() {
        put("actions", "2034-01-09", 5);
        put("actions", "2034-01-10", 10);
        put("actions", "2034-01-12", 20);
        put("actions", "2034-01-13", 7);
        assertThat(overview("2034-01-10", "2034-01-12").path("actions").asLong()).isEqualTo(30);
    }

    @Test
    @DisplayName("[TB-INS-KOV-002] delta sul periodo precedente della stessa lunghezza: abs 10, pct 50")
    void overviewDelta() {
        put("actions", "2034-02-07", 20);
        put("actions", "2034-02-10", 30);
        JsonNode d = overview("2034-02-10", "2034-02-12").path("deltas").path("actions");
        assertThat(d.path("abs").asLong()).isEqualTo(10);
        assertThat(d.path("pct").asDouble()).isEqualTo(50.0);
    }

    @Test
    @DisplayName("[TB-INS-KOV-003] periodo precedente a 0: pct assente, abs = valore corrente")
    void overviewDeltaFromZero() {
        put("points_earned", "2034-03-10", 400);
        JsonNode d = overview("2034-03-10", "2034-03-12").path("deltas").path("pointsEarned");
        assertThat(d.path("abs").asLong()).isEqualTo(400);
        assertThat(d.path("pct").isMissingNode() || d.path("pct").isNull()).isTrue();
    }

    @Test
    @DisplayName("[TB-INS-KOV-004] periodo corrente a 0 e precedente 100: abs -100, pct -100")
    void overviewDeltaToZero() {
        put("points_spent", "2034-04-09", 100);
        JsonNode d = overview("2034-04-10", "2034-04-10").path("deltas").path("pointsSpent");
        assertThat(d.path("abs").asLong()).isEqualTo(-100);
        assertThat(d.path("pct").asDouble()).isEqualTo(-100.0);
    }

    @Test
    @DisplayName("[TB-INS-KOV-005] finestra di un giorno (from = to): il precedente è il giorno prima")
    void overviewOneDay() {
        put("redemptions", "2034-05-09", 4);
        put("redemptions", "2034-05-10", 6);
        JsonNode o = overview("2034-05-10", "2034-05-10");
        assertThat(o.path("redemptions").asLong()).isEqualTo(6);
        assertThat(o.path("deltas").path("redemptions").path("abs").asLong()).isEqualTo(2);
    }

    @Test
    @DisplayName("[TB-INS-KOV-006] days=7 senza from/to (BO-01 7/30/90): da oggi-6 a oggi")
    void overviewDays7() {
        CLOCK.set(Instant.parse("2034-06-10T10:00:00Z"));
        JsonNode o = get("/v1/kpi/overview?days=7");
        assertThat(o.path("from").asString()).isEqualTo("2034-06-04");
        assertThat(o.path("to").asString()).isEqualTo("2034-06-10");
    }

    @Test
    @DisplayName("[TB-INS-KOV-007] senza parametri: default 30 giorni (BO-01)")
    void overviewDefault30() {
        CLOCK.set(Instant.parse("2034-06-10T10:00:00Z"));
        JsonNode o = get("/v1/kpi/overview");
        assertThat(o.path("from").asString()).isEqualTo("2034-05-12");
        assertThat(o.path("to").asString()).isEqualTo("2034-06-10");
    }

    @Test
    @DisplayName("[TB-INS-KOV-008] days=0: 400 BAD_REQUEST (Q-N12 DECISA: niente finestra corretta in silenzio)")
    void overviewDays0() {
        // Q-N12 DECISA
        CLOCK.set(Instant.parse("2034-06-10T10:00:00Z"));
        Resp r = call("GET", "/v1/kpi/overview?days=0", null, null);
        assertThat(r.status()).as(r.text()).isEqualTo(400);
        assertThat(r.code()).isEqualTo("BAD_REQUEST");
    }

    @Test
    @DisplayName("[TB-INS-KOV-009] days negativo: 400 BAD_REQUEST (Q-N12 DECISA)")
    void overviewDaysNegative() {
        // Q-N12 DECISA
        CLOCK.set(Instant.parse("2034-06-10T10:00:00Z"));
        Resp r = call("GET", "/v1/kpi/overview?days=-5", null, null);
        assertThat(r.status()).as(r.text()).isEqualTo(400);
        assertThat(r.code()).isEqualTo("BAD_REQUEST");
    }

    @Test
    @DisplayName("[TB-INS-KOV-010] days non numerico: 400")
    void overviewDaysBad() {
        assertThat(call("GET", "/v1/kpi/overview?days=trenta", null, null).status()).isEqualTo(400);
    }

    @Test
    @DisplayName("[TB-INS-KOV-011] from non è una data ISO (2034-13-01): 400")
    void overviewFromBad() {
        assertThat(call("GET", "/v1/kpi/overview?from=2034-13-01&to=2034-12-01", null, null).status()).isEqualTo(400);
    }

    @Test
    @DisplayName("[TB-INS-KOV-012] from dopo to: 400 BAD_REQUEST (Q-N12 DECISA: non una finestra vuota a zero)")
    void overviewInverted() {
        // Q-N12 DECISA
        put("actions", "2034-07-11", 9);
        Resp r = call("GET", "/v1/kpi/overview?from=2034-07-12&to=2034-07-10", null, null);
        assertThat(r.status()).as(r.text()).isEqualTo(400);
        assertThat(r.code()).isEqualTo("BAD_REQUEST");
    }

    @Test
    @DisplayName("[TB-INS-KOV-013] campi di insight §3: membersTotal, membersActive30d, actions, pointsEarned/Spent/Expired, redemptions, plays, wins, deltas")
    void overviewFields() {
        JsonNode o = overview("2034-08-01", "2034-08-31");
        List<String> fields = new ArrayList<>();
        o.propertyNames().forEach(fields::add);
        assertThat(fields).contains("membersTotal", "membersActive30d", "actions", "pointsEarned", "pointsSpent",
                "pointsExpired", "redemptions", "plays", "wins", "deltas");
    }

    @Test
    @DisplayName("[TB-INS-KOV-014] membri totali = storico sintetico (3 100 → 3 480) + i 12 reali (docs/10 §9)")
    void overviewMembersTotal() {
        assertThat(get("/v1/kpi/overview?days=30").path("membersTotal").asLong()).isGreaterThanOrEqualTo(3112);
    }

    @Test
    @DisplayName("[TB-INS-KOV-015] dato reale e sintetico dello stesso giorno sommati (insight §5)")
    void overviewSyntheticPlusReal() {
        metrics.putSynthetic(LocalDate.parse("2034-09-10"), "actions", MetricRepository.TOTAL, MetricRepository.TOTAL, 100);
        put("actions", "2034-09-10", 5);
        assertThat(overview("2034-09-10", "2034-09-10").path("actions").asLong()).isEqualTo(105);
    }

    @Test
    @DisplayName("[TB-INS-KOV-016] membri attivi (gauge, Q-N14 DECISA): valore dell'ultimo giorno della finestra, non la somma")
    void overviewMembersActiveGauge() {
        // Q-N14 DECISA
        put("members_active", "2034-10-09", 9);
        put("members_active", "2034-10-10", 11);
        assertThat(overview("2034-10-09", "2034-10-10").path("membersActive30d").asLong()).isEqualTo(11);
    }

    // ---------- KTS: serie ----------

    private JsonNode series(String query) {
        return get("/v1/kpi/timeseries?" + query);
    }

    @Test
    @DisplayName("[TB-INS-KTS-001] granularità day (AMBIGUO sui giorni vuoti): un punto per giorno con dati, in ordine crescente")
    void seriesDay() {
        // Q-N15 DECISA (TB-INS-KTS-001)
        put("points_expired", "2034-11-12", 3);
        put("points_expired", "2034-11-10", 1);
        JsonNode s = series("metric=points_expired&from=2034-11-10&to=2034-11-12");
        assertThat(s.path("granularity").asString()).isEqualTo("day");
        List<String> days = new ArrayList<>();
        s.path("points").forEach(p -> days.add(p.path("day").asString() + "=" + p.path("value").asLong()));
        assertThat(days).containsExactly("2034-11-10=1", "2034-11-12=3");
    }

    @Test
    @DisplayName("[TB-INS-KTS-002] granularità week: somma per settimana ISO etichettata col lunedì")
    void seriesWeek() {
        put("members_new", "2034-03-06", 1);
        put("members_new", "2034-03-12", 2);
        put("members_new", "2034-03-13", 4);
        List<String> weeks = new ArrayList<>();
        series("metric=members_new&from=2034-03-06&to=2034-03-19&granularity=week").path("points")
                .forEach(p -> weeks.add(p.path("day").asString() + "=" + p.path("value").asLong()));
        assertThat(weeks).containsExactly("2034-03-06=3", "2034-03-13=4");
    }

    @Test
    @DisplayName("[TB-INS-KTS-003] settimana a cavallo d'anno (lun 31/12/2035 – dom 6/1/2036): un solo punto etichettato 2035-12-31")
    void seriesWeekAcrossYear() {
        put("tier_changes", "2035-12-30", 8);
        put("tier_changes", "2035-12-31", 1);
        put("tier_changes", "2036-01-01", 2);
        put("tier_changes", "2036-01-06", 4);
        List<String> weeks = new ArrayList<>();
        series("metric=tier_changes&from=2035-12-24&to=2036-01-06&granularity=week").path("points")
                .forEach(p -> weeks.add(p.path("day").asString() + "=" + p.path("value").asLong()));
        assertThat(weeks).containsExactly("2035-12-24=8", "2035-12-31=7");
    }

    @Test
    @DisplayName("[TB-INS-KTS-004] marcatura synthetic per giorno: giorno sintetico con reale sommato = true, solo reale = false")
    void seriesSyntheticFlag() {
        metrics.putSynthetic(LocalDate.parse("2034-12-01"), "plays", MetricRepository.TOTAL, MetricRepository.TOTAL, 50);
        put("plays", "2034-12-01", 2);
        put("plays", "2034-12-02", 3);
        JsonNode pts = series("metric=plays&from=2034-12-01&to=2034-12-02").path("points");
        assertThat(pts.get(0).path("value").asLong()).isEqualTo(52);
        assertThat(pts.get(0).path("synthetic").asBoolean()).isTrue();
        assertThat(pts.get(1).path("synthetic").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("[TB-INS-KTS-005] settimana con un giorno sintetico e uno reale (AMBIGUO): synthetic = true")
    void seriesWeekSynthetic() {
        // Q-N15 DECISA (TB-INS-KTS-005)
        metrics.putSynthetic(LocalDate.parse("2034-12-11"), "wins", MetricRepository.TOTAL, MetricRepository.TOTAL, 5);
        put("wins", "2034-12-12", 1);
        JsonNode pts = series("metric=wins&from=2034-12-11&to=2034-12-17&granularity=week").path("points");
        assertThat(pts.size()).isEqualTo(1);
        assertThat(pts.get(0).path("value").asLong()).isEqualTo(6);
        assertThat(pts.get(0).path("synthetic").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("[TB-INS-KTS-006] granularity=WEEK maiuscolo (AMBIGUO): accettato come week")
    void seriesWeekUpper() {
        // Q-N15 DECISA (TB-INS-KTS-006)
        assertThat(series("metric=wins&from=2034-12-11&to=2034-12-17&granularity=WEEK").path("granularity").asString())
                .isEqualTo("week");
    }

    @Test
    @DisplayName("[TB-INS-KTS-007] granularity=month fuori da day|week: 400 BAD_REQUEST (Q-N15 DECISA)")
    void seriesMonth() {
        // Q-N15 DECISA
        Resp r = call("GET", "/v1/kpi/timeseries?metric=wins&from=2034-12-11&to=2034-12-17&granularity=month", null, null);
        assertThat(r.status()).as(r.text()).isEqualTo(400);
        assertThat(r.code()).isEqualTo("BAD_REQUEST");
    }

    @Test
    @DisplayName("[TB-INS-KTS-008] metric assente: 400 (docs/06 §2 parametri errati)")
    void seriesNoMetric() {
        Resp r = call("GET", "/v1/kpi/timeseries?from=2034-12-11&to=2034-12-17", null, null);
        assertThat(r.status()).as(r.text()).isEqualTo(400);
    }

    @Test
    @DisplayName("[TB-INS-KTS-009] metrica sconosciuta: 400 BAD_REQUEST (Q-N15 DECISA: non una serie vuota)")
    void seriesUnknownMetric() {
        // Q-N15 DECISA
        Resp r = call("GET", "/v1/kpi/timeseries?metric=sconosciuta&from=2034-12-11&to=2034-12-17", null, null);
        assertThat(r.status()).as(r.text()).isEqualTo(400);
        assertThat(r.code()).isEqualTo("BAD_REQUEST");
    }

    @Test
    @DisplayName("[TB-INS-KTS-010] from non è una data ISO: 400")
    void seriesBadFrom() {
        assertThat(call("GET", "/v1/kpi/timeseries?metric=actions&from=10/12/2034", null, null).status()).isEqualTo(400);
    }

    // ---------- KBR: ripartizioni ----------

    private void putDim(String metric, String dimension, String value, String day, long v) {
        metrics.increment(LocalDate.parse(day), metric, dimension, value, v);
    }

    private List<String> slices(JsonNode b) {
        List<String> out = new ArrayList<>();
        b.path("slices").forEach(s -> out.add(s.path("dimValue").asString() + "=" + s.path("value").asLong()));
        return out;
    }

    @Test
    @DisplayName("[TB-INS-KBR-001] top N (limit=2) in ordine decrescente")
    void breakdownTop() {
        putDim("actions", "source", "a", "2037-01-10", 5);
        putDim("actions", "source", "b", "2037-01-10", 9);
        putDim("actions", "source", "c", "2037-01-10", 7);
        assertThat(slices(get("/v1/kpi/breakdown?metric=actions&dimension=source&from=2037-01-10&to=2037-01-10&limit=2")))
                .containsExactly("b=9", "c=7");
    }

    @Test
    @DisplayName("[TB-INS-KBR-002] limit di default 5 (top 5 di BO-01): 6 valori ⇒ 5 righe, escluso il minore")
    void breakdownDefaultLimit() {
        for (int i = 1; i <= 6; i++) {
            putDim("actions", "source", "s" + i, "2037-02-10", i * 10L);
        }
        List<String> s = slices(get("/v1/kpi/breakdown?metric=actions&dimension=source&from=2037-02-10&to=2037-02-10"));
        assertThat(s).hasSize(5).doesNotContain("s1=10");
    }

    @Test
    @DisplayName("[TB-INS-KBR-003] limit=0: 400 BAD_REQUEST (Q-N12 DECISA)")
    void breakdownLimitZero() {
        // Q-N12 DECISA
        putDim("actions", "source", "x", "2037-03-10", 1);
        putDim("actions", "source", "y", "2037-03-10", 2);
        Resp r = call("GET", "/v1/kpi/breakdown?metric=actions&dimension=source&from=2037-03-10&to=2037-03-10&limit=0",
                null, null);
        assertThat(r.status()).as(r.text()).isEqualTo(400);
        assertThat(r.code()).isEqualTo("BAD_REQUEST");
    }

    @Test
    @DisplayName("[TB-INS-KBR-004] total (AMBIGUO): somma delle sole righe restituite")
    void breakdownTotal() {
        // Q-N15 DECISA (TB-INS-KBR-004)
        putDim("actions", "source", "a", "2037-04-10", 5);
        putDim("actions", "source", "b", "2037-04-10", 9);
        putDim("actions", "source", "c", "2037-04-10", 7);
        assertThat(get("/v1/kpi/breakdown?metric=actions&dimension=source&from=2037-04-10&to=2037-04-10&limit=2")
                .path("total").asLong()).isEqualTo(16);
    }

    @Test
    @DisplayName("[TB-INS-KBR-005] dimensione di default: source")
    void breakdownDefaultDimension() {
        putDim("actions", "source", "billing", "2037-05-10", 3);
        JsonNode b = get("/v1/kpi/breakdown?metric=actions&from=2037-05-10&to=2037-05-10");
        assertThat(b.path("dimension").asString()).isEqualTo("source");
        assertThat(slices(b)).containsExactly("billing=3");
    }

    @Test
    @DisplayName("[TB-INS-KBR-006] dimensione senza dati: nessuna riga e total 0")
    void breakdownEmpty() {
        JsonNode b = get("/v1/kpi/breakdown?metric=actions&dimension=nessuna&from=2037-06-10&to=2037-06-10");
        assertThat(b.path("slices").size()).isZero();
        assertThat(b.path("total").asLong()).isZero();
    }

    @Test
    @DisplayName("[TB-INS-KBR-007] dimensione vuota: il totale della metrica non compare come riga")
    void breakdownBlankDimension() {
        put("actions", "2037-07-10", 40);
        assertThat(get("/v1/kpi/breakdown?metric=actions&dimension=&from=2037-07-10&to=2037-07-10").path("slices").size())
                .isZero();
    }

    @Test
    @DisplayName("[TB-INS-KBR-008] metric assente: 400 (docs/06 §2 parametri errati)")
    void breakdownNoMetric() {
        Resp r = call("GET", "/v1/kpi/breakdown?dimension=source&from=2037-07-10&to=2037-07-10", null, null);
        assertThat(r.status()).as(r.text()).isEqualTo(400);
    }

    @Test
    @DisplayName("[TB-INS-KBR-009] più giorni nella finestra: valori della stessa voce sommati")
    void breakdownSumDays() {
        putDim("points_earned", "currency", "PTS", "2037-08-10", 3);
        putDim("points_earned", "currency", "PTS", "2037-08-11", 4);
        assertThat(slices(get("/v1/kpi/breakdown?metric=points_earned&dimension=currency&from=2037-08-10&to=2037-08-11")))
                .containsExactly("PTS=7");
    }
}
