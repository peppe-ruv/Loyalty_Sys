package io.loyaltyhub.campaign.testbook;

import io.loyaltyhub.campaign.domain.Campaign;
import io.loyaltyhub.campaign.engine.Evaluation;
import io.loyaltyhub.campaign.engine.MemberSnapshot;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static io.loyaltyhub.campaign.testbook.CmpEngineFixture.JSON;
import static io.loyaltyhub.campaign.testbook.CmpEngineFixture.StubCounters;
import static io.loyaltyhub.campaign.testbook.CmpEngineFixture.TUESDAY;
import static io.loyaltyhub.campaign.testbook.CmpEngineFixture.TYPE;
import static io.loyaltyhub.campaign.testbook.CmpEngineFixture.action;
import static io.loyaltyhub.campaign.testbook.CmpEngineFixture.campaign;
import static io.loyaltyhub.campaign.testbook.CmpEngineFixture.engine;
import static io.loyaltyhub.campaign.testbook.CmpEngineFixture.full;
import static io.loyaltyhub.campaign.testbook.CmpEngineFixture.json;
import static io.loyaltyhub.campaign.testbook.CmpEngineFixture.result;
import static io.loyaltyhub.campaign.testbook.CmpEngineFixture.silver;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * TB-CMP §5 (docs/testbook/TB-CMP-campagne.md): condizioni della campagna (docs/03 §3.3, F-CMP-03). Ogni riga è una
 * foglia (o un albero) valutata dal motore: foglia vera ⇒ la campagna scatta, falsa ⇒ scartata con {@code CONDITION}
 * e l'elenco delle foglie fallite (docs/03 §3.5 passo 2.3).
 */
class TestbookCmpConditionTest {

    private static final String CODE = "CMP-TB-CND";

    /**
     * TB-CMP-OPS: ogni comparatore × tipo del campo, valori limite, campo assente/null, tipi incompatibili.
     * TESTBOOK: ambiguo, vedi TB-CMP-OPS-011, OPS-032 (testo numerico contro numero), OPS-072 (between con estremi
     * invertiti), OPS-078 (comparatore sconosciuto), OPS-079 (comparatore assente): si asserisce il comportamento attuale.
     */
    @ParameterizedTest(name = "[{0}] {1}", quoteTextArguments = false)
    @CsvFileSource(resources = "/testbook/cmp/operators.csv", numLinesToSkip = 1, delimiter = '|', quoteCharacter = '`')
    void operator(String id, String description, String field, String cmp, String value, String data, boolean expected) {
        assertLeaf(id, leaf(field, cmp, value), action(TYPE, TUESDAY, data), silver(), StubCounters.zero(), expected);
    }

    /**
     * TB-CMP-ARR/MEM/CTX/HIS: spazi dei campi {@code data.*} (array e percorsi annidati), {@code member.*},
     * {@code context.*} (Europe/Rome, mezzanotte, cambio dell'ora, fine anno, 29 febbraio) e {@code history.*}.
     * TESTBOOK: ambiguo, vedi TB-CMP-MEM-018 (compleanno del 29 febbraio), MEM-023 (registeredDaysAgo a giorni di 24 h
     * o di calendario): si asserisce il comportamento attuale.
     */
    @ParameterizedTest(name = "[{0}] {1}", quoteTextArguments = false)
    @CsvFileSource(resources = "/testbook/cmp/fields.csv", numLinesToSkip = 1, delimiter = '|', quoteCharacter = '`')
    void field(String id, String description, String field, String cmp, String value, String data, String time,
               String birthDate, String registeredAt, String labels, String history, boolean expected) {
        Instant t = time == null ? TUESDAY : Instant.parse(time);
        MemberSnapshot m = full(
                "-".equals(labels) ? List.of() : List.of("VIP"),
                "{\"kids\":2,\"newsletter\":true,\"since\":\"2026-05-01\"}",
                registeredAt == null ? Instant.parse("2025-01-10T10:00:00Z")
                        : "NULL".equals(registeredAt) ? null : Instant.parse(registeredAt),
                birthDate == null ? LocalDate.parse("1990-05-20")
                        : "NULL".equals(birthDate) ? null : LocalDate.parse(birthDate));
        StubCounters counters = StubCounters.zero();
        if (history != null) {
            String[] h = history.split(";");
            counters.historyCount = Long.parseLong(h[0].trim());
            counters.daysSince = Long.parseLong(h[1].trim());
        }
        assertLeaf(id, leaf(field, cmp, value), action(TYPE, t, data), m, counters, expected);
    }

    /**
     * TB-CMP-GRP: gruppi all/any/not annidati fino a profondità 3 e spiegabilità delle foglie fallite.
     * TESTBOOK: ambiguo, vedi TB-CMP-GRP-007 (any vuoto), GRP-019 (operatore sconosciuto), GRP-020 (foglia senza campo),
     * GRP-021 (condizioni null); GRP-010 segue la scelta registrata in Q-90 (not = "non tutte vere").
     */
    @ParameterizedTest(name = "[{0}] {1}", quoteTextArguments = false)
    @CsvFileSource(resources = "/testbook/cmp/groups.csv", numLinesToSkip = 1, delimiter = '|', quoteCharacter = '`')
    void group(String id, String description, String conditions, boolean expected, String failedFields, String firstActual) {
        String tree = conditions == null ? null : conditions
                .replace("$A", "{\"field\":\"data.amount\",\"cmp\":\"gte\",\"value\":10}")
                .replace("$B", "{\"field\":\"data.channel\",\"cmp\":\"eq\",\"value\":\"WEB\"}")
                .replace("$C", "{\"field\":\"data.qty\",\"cmp\":\"gt\",\"value\":5}");
        Campaign c = campaign(CODE, 100, List.of(TYPE), null, tree, null, null, null, null, null);

        Evaluation ev = engine().evaluate(action(TYPE, TUESDAY, "{\"amount\":50,\"channel\":\"APP\",\"qty\":1}"),
                silver(), List.of(c), StubCounters.zero());
        Evaluation.CampaignResult r = result(ev, CODE);

        assertThat(r.matched()).as(id + " condizioni vere").isEqualTo(expected);
        if (!expected) {
            assertThat(r.reason()).as(id).isEqualTo(Evaluation.SkipReason.CONDITION);
        }
        if (!"-".equals(failedFields)) {
            List<String> fields = r.failedConditions().stream().map(Evaluation.FailedCondition::field).toList();
            assertThat(fields).as(id + " foglie fallite")
                    .containsExactlyElementsOf(failedFields == null ? List.of() : List.of(failedFields.split(",")));
        }
        if (firstActual != null) {
            Object actual = r.failedConditions().get(0).actual();
            if ("null".equals(firstActual)) {
                assertThat(actual).as(id + " valore osservato").isNull();
            } else if (firstActual.matches("-?\\d+(\\.\\d+)?")) {
                assertThat(((Number) actual).doubleValue()).as(id + " valore osservato").isEqualTo(Double.parseDouble(firstActual));
            } else {
                assertThat(actual).as(id + " valore osservato").isEqualTo(firstActual);
            }
        }
    }

    // ---------- supporto ----------

    private static String leaf(String field, String cmp, String value) {
        ObjectNode leaf = JSON.createObjectNode();
        leaf.put("field", field);
        if (cmp != null) {
            leaf.put("cmp", cmp);
        }
        if (value != null) {
            leaf.set("value", json(value));
        }
        return "{\"op\":\"all\",\"rules\":[" + leaf + "]}";
    }

    private static void assertLeaf(String id, String conditions, io.loyaltyhub.campaign.engine.EvalAction action,
                                   MemberSnapshot member, StubCounters counters, boolean expected) {
        Campaign c = campaign(CODE, 100, List.of(TYPE), null, conditions, null, null, null, null, null);

        Evaluation ev = engine().evaluate(action, member, List.of(c), counters);
        Evaluation.CampaignResult r = result(ev, CODE);

        assertThat(r.matched()).as(id + " foglia " + conditions).isEqualTo(expected);
        if (!expected) {
            assertThat(r.reason()).as(id).isEqualTo(Evaluation.SkipReason.CONDITION);
            assertThat(r.failedConditions()).as(id + " foglia fallita nella spiegabilità").hasSize(1);
        }
    }
}
