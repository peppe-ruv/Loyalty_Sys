package io.loyaltyhub.campaign.testbook;

import io.loyaltyhub.campaign.domain.Campaign;
import io.loyaltyhub.campaign.engine.Evaluation;
import io.loyaltyhub.campaign.engine.MemberSnapshot;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;

import java.util.List;

import static io.loyaltyhub.campaign.testbook.CmpEngineFixture.StubCounters;
import static io.loyaltyhub.campaign.testbook.CmpEngineFixture.TUESDAY;
import static io.loyaltyhub.campaign.testbook.CmpEngineFixture.TYPE;
import static io.loyaltyhub.campaign.testbook.CmpEngineFixture.action;
import static io.loyaltyhub.campaign.testbook.CmpEngineFixture.campaign;
import static io.loyaltyhub.campaign.testbook.CmpEngineFixture.engine;
import static io.loyaltyhub.campaign.testbook.CmpEngineFixture.list;
import static io.loyaltyhub.campaign.testbook.CmpEngineFixture.member;
import static io.loyaltyhub.campaign.testbook.CmpEngineFixture.outcomeOf;
import static io.loyaltyhub.campaign.testbook.CmpEngineFixture.silver;
import static io.loyaltyhub.campaign.testbook.CmpEngineFixture.simple;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * TB-CMP §2–§4 (docs/testbook/TB-CMP-campagne.md): candidatura per tipo di trigger (docs/03 §3.5 passo 2), pubblico
 * (passo 2.2, F-CMP-06) e snapshot del membro (passo 1). Motore puro, nessun orologio di sistema.
 */
class TestbookCmpTriggerAudienceTest {

    /** TB-CMP-TRG: la campagna è candidata solo se il {@code type} dell'azione è esattamente uno dei trigger. */
    @ParameterizedTest(name = "[{0}] {1}", quoteTextArguments = false)
    @CsvFileSource(resources = "/testbook/cmp/trigger.csv", numLinesToSkip = 1, delimiter = '|', quoteCharacter = '`')
    void trigger(String id, String description, String triggers, String actionType, String expected) {
        Campaign c = campaign("CMP-TB-TRG", 100, list(triggers), null, null, null, null, null, null, null);

        Evaluation ev = engine().evaluate(action(actionType, TUESDAY, "{}"), silver(), List.of(c), StubCounters.zero());

        assertThat(outcomeOf(ev, "CMP-TB-TRG")).as(id).isEqualTo(expected);
        assertThat(ev.outcome()).as(id + " esito dell'azione")
                .isEqualTo("MATCHED".equals(expected) ? Evaluation.Outcome.MATCHED : Evaluation.Outcome.NO_MATCH);
    }

    /**
     * TB-CMP-AUD: tabella completa all × tiers × segments (27) + casi singoli.
     * TESTBOOK: ambiguo, vedi TB-CMP-AUD-002…009, 010, 015, 017, 019, 024, 026, 028, 031, 032 (docs/03 §3.2 non dice
     * come si combinano all/tiers/segments né cosa valga un pubblico senza restrizioni con all=false): si asserisce il
     * comportamento attuale.
     */
    @ParameterizedTest(name = "[{0}] {1}", quoteTextArguments = false)
    @CsvFileSource(resources = "/testbook/cmp/audience.csv", numLinesToSkip = 1, delimiter = '|', quoteCharacter = '`')
    void audience(String id, String description, String audience, String tier, String segments, String expected) {
        Campaign c = campaign("CMP-TB-AUD", 100, List.of(TYPE), audience, null, null, null, null, null, null);
        MemberSnapshot m = member("ACTIVE", tier, list(segments));

        Evaluation ev = engine().evaluate(action(TYPE, TUESDAY, "{}"), m, List.of(c), StubCounters.zero());

        assertThat(outcomeOf(ev, "CMP-TB-AUD")).as(id).isEqualTo(expected);
    }

    /** TB-CMP-STA: solo uno snapshot ACTIVE è valutato; altrimenti NO_MEMBER senza risultati né effetti. */
    @ParameterizedTest(name = "[{0}] {1}", quoteTextArguments = false)
    @CsvFileSource(resources = "/testbook/cmp/member-status.csv", numLinesToSkip = 1, delimiter = '|', quoteCharacter = '`')
    void memberStatus(String id, String description, String status, String expected) {
        MemberSnapshot m = switch (status) {
            case "SNAPSHOT_ASSENTE" -> null;
            case "NULL" -> member(null, "SILVER", List.of());
            default -> member(status, "SILVER", List.of());
        };

        Evaluation ev = engine().evaluate(action(TYPE, TUESDAY, "{}"), m, List.of(simple("CMP-TB-STA")), StubCounters.zero());

        assertThat(ev.outcome().name()).as(id).isEqualTo(expected);
        if (ev.outcome() == Evaluation.Outcome.NO_MEMBER) {
            assertThat(ev.results()).as(id + " nessun risultato per campagna").isEmpty();
            assertThat(ev.effects()).as(id + " nessun effetto").isEmpty();
            assertThat(ev.actionEffects()).as(id + " nessun altro effetto").isEmpty();
        }
    }
}
