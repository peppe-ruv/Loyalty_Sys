package io.loyaltyhub.campaign.testbook;

import io.loyaltyhub.campaign.domain.Campaign;
import io.loyaltyhub.campaign.engine.Evaluation;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;

import java.time.Instant;
import java.util.List;

import static io.loyaltyhub.campaign.testbook.CmpEngineFixture.StubCounters;
import static io.loyaltyhub.campaign.testbook.CmpEngineFixture.TUESDAY;
import static io.loyaltyhub.campaign.testbook.CmpEngineFixture.TYPE;
import static io.loyaltyhub.campaign.testbook.CmpEngineFixture.action;
import static io.loyaltyhub.campaign.testbook.CmpEngineFixture.campaign;
import static io.loyaltyhub.campaign.testbook.CmpEngineFixture.engine;
import static io.loyaltyhub.campaign.testbook.CmpEngineFixture.outcomeOf;
import static io.loyaltyhub.campaign.testbook.CmpEngineFixture.silver;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * TB-CMP §7 (docs/testbook/TB-CMP-campagne.md): limiti per membro e budget globale (docs/03 §3.5 passo 2.5, F-CMP-05),
 * calendario (passo 2.1, F-CMP-02) e cumulabilità con gruppo esclusivo × priorità (passo 2.4, F-CMP-07). Motore puro:
 * i contatori sono letti da uno stub deterministico, gli istanti sono espliciti.
 */
class TestbookCmpLimitScheduleTest {

    /**
     * TB-CMP-LIM: limiti prima/alla/oltre la soglia, chiavi di periodo su Europe/Rome, budget punti e match.
     * TESTBOOK: ambiguo, vedi TB-CMP-LIM-008 e LIM-025 (edizione = anno solare, come Q-59), LIM-010 (periodo assente),
     * LIM-011 (periodo sconosciuto): si asserisce il comportamento attuale.
     * Q-237 DECISA (LIM-026, LIM-035: l'accredito si riduce al budget residuo; {@code MATCHED:n} = punti attesi).
     */
    @ParameterizedTest(name = "[{0}] {1}", quoteTextArguments = false)
    @CsvFileSource(resources = "/testbook/cmp/limits.csv", numLinesToSkip = 1, delimiter = '|', quoteCharacter = '`')
    void limits(String id, String description, String limits, String memberCounts, Long globalPoints, Long globalMatches,
                String time, String expected) {
        Campaign c = campaign("CMP-TB-LIM", 100, List.of(TYPE), null, null, null, limits, null, null, null);
        StubCounters counters = StubCounters.of(memberCounts, globalPoints, globalMatches);

        Evaluation ev = engine().evaluate(action(TYPE, Instant.parse(time), "{}"), silver(), List.of(c), counters);

        String[] exp = expected.split(":");
        assertThat(outcomeOf(ev, "CMP-TB-LIM")).as(id).isEqualTo(exp[0]);
        if ("MATCHED".equals(exp[0])) {
            long points = exp.length > 1 ? Long.parseLong(exp[1]) : 100;
            assertThat(ev.effects()).as(id + " accredito (pieno o ridotto al residuo)").singleElement()
                    .satisfies(g -> assertThat(g.amount()).isEqualTo(points));
        } else {
            assertThat(ev.effects()).as(id + " nessun accredito").isEmpty();
        }
    }

    /**
     * TB-CMP-SCH: startAt/endAt agli istanti limite, giorni della settimana e fasce orarie su Europe/Rome.
     * TESTBOOK: ambiguo, vedi TB-CMP-SCH-019 (fascia a cavallo della mezzanotte), SCH-023 (giorno minuscolo): si
     * asserisce il comportamento attuale.
     * Q-238 DECISA (SCH-017: la fascia [9, 18] finisce alle 18:00, le 18:59:59 sono fuori; SCH-021 usa quindi [3, 4]).
     */
    @ParameterizedTest(name = "[{0}] {1}", quoteTextArguments = false)
    @CsvFileSource(resources = "/testbook/cmp/schedule.csv", numLinesToSkip = 1, delimiter = '|', quoteCharacter = '`')
    void schedule(String id, String description, String schedule, String time, boolean inside) {
        Campaign c = campaign("CMP-TB-SCH", 100, List.of(TYPE), null, null, null, null, schedule, null, null);

        Evaluation ev = engine().evaluate(action(TYPE, Instant.parse(time), "{}"), silver(), List.of(c), StubCounters.zero());

        assertThat(outcomeOf(ev, "CMP-TB-SCH")).as(id).isEqualTo(inside ? "MATCHED" : "NOT_IN_SCHEDULE");
    }

    /**
     * TB-CMP-EXC / TB-CMP-ORD: gruppo esclusivo (vince la prima nell'ordine priorità desc, codice asc) e ordine di
     * valutazione osservato tramite il gruppo. Le campagne arrivano al motore in ordine inverso (B, A): l'ordine è del
     * motore, non del caricamento.
     */
    @ParameterizedTest(name = "[{0}] {1}", quoteTextArguments = false)
    @CsvFileSource(resources = "/testbook/cmp/exclusive.csv", numLinesToSkip = 1, delimiter = '|', quoteCharacter = '`')
    void exclusive(String id, String description, int prioA, int prioB, String groupA, String groupB, String stateA,
                   String stateB, String expectedA, String expectedB) {
        List<Campaign> campaigns = List.of(withState("CMP-TB-B", prioB, groupB, stateB), withState("CMP-TB-A", prioA, groupA, stateA));
        // Budget e limite esauriti solo per le campagne che dichiarano quel tetto (stub comune: match 1 in ALWAYS, 1000 punti).
        StubCounters counters = StubCounters.of("ALWAYS:ALWAYS=1", 1000L, 0L);

        Evaluation ev = engine().evaluate(action(TYPE, TUESDAY, "{\"channel\":\"APP\"}"), silver(), campaigns, counters);

        assertThat(outcomeOf(ev, "CMP-TB-A")).as(id + " A").isEqualTo(expectedA);
        assertThat(outcomeOf(ev, "CMP-TB-B")).as(id + " B").isEqualTo(expectedB);
    }

    private static Campaign withState(String code, int priority, String group, String state) {
        String conditions = "COND".equals(state)
                ? "{\"op\":\"all\",\"rules\":[{\"field\":\"data.channel\",\"cmp\":\"eq\",\"value\":\"WEB\"}]}" : null;
        String limits = switch (state) {
            case "LIMIT" -> "{\"perMember\":[{\"max\":1,\"period\":\"ALWAYS\"}]}";
            case "BUDGET" -> "{\"global\":{\"maxPoints\":1000}}";
            default -> null;
        };
        String schedule = "SCHED".equals(state) ? "{\"startAt\":\"2030-01-01T00:00:00Z\"}" : null;
        String audience = "AUD".equals(state) ? "{\"all\":false,\"tiers\":[\"PLATINUM\"],\"segments\":[]}" : null;
        String effects = "UNSUP".equals(state) ? "[{\"type\":\"DONATE\"}]" : null;
        return campaign(code, priority, List.of(TYPE), audience, conditions, effects, limits, schedule, group, null);
    }
}
