package it.iren.loyalty.rulesengine;

import it.iren.loyalty.common.event.Currency;
import it.iren.loyalty.rulesengine.domain.RuleEvaluator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Due campagne che premiano lo stesso wallet per lo stesso evento devono produrre due movimenti:
 * con la sola chiave dell'azione si scontravano sul vincolo (chiave, wallet) del ledger.
 */
class PostingKeyTest {

    private static RuleEvaluator.Posting posting(String campagna, Currency wallet, long unita) {
        return new RuleEvaluator.Posting(campagna, "1", wallet, unita);
    }

    @Test
    void ogniCampagnaHaLaSuaChiaveMaTutteCominciamoConQuellaDellAzione() {
        String a = RulesConfig.postingKey("azione-1", posting("autolettura", Currency.PREMIO, 200));
        String b = RulesConfig.postingKey("azione-1", posting("bolletta-digitale", Currency.PREMIO, 50));

        assertThat(a).isNotEqualTo(b);
        assertThat(a).startsWith("azione-1:");
        assertThat(b).startsWith("azione-1:");
    }

    @Test
    void gliEffettiDellaStessaCampagnaSulloStessoWalletDiventanoUnMovimentoSolo() {
        var merged = RulesConfig.merge(List.of(
                posting("autolettura", Currency.PREMIO, 200),
                posting("autolettura", Currency.PREMIO, 50)));

        assertThat(merged).hasSize(1);
        assertThat(merged.get(0).amount()).isEqualTo(250);
    }

    @Test
    void walletDiversiDellaStessaCampagnaRestanoSeparati() {
        var merged = RulesConfig.merge(List.of(
                posting("autolettura", Currency.PREMIO, 200),
                posting("autolettura", Currency.STATUS, 200)));

        assertThat(merged).hasSize(2);
    }

    @Test
    void campagneDiverseRestanoSeparate() {
        var merged = RulesConfig.merge(List.of(
                posting("autolettura", Currency.PREMIO, 200),
                posting("bolletta-digitale", Currency.PREMIO, 50)));

        assertThat(merged).hasSize(2);
        assertThat(merged.stream().map(RuleEvaluator.Posting::ruleId)).containsExactly("autolettura", "bolletta-digitale");
    }
}
