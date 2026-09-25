package io.loyaltyhub.wallet.testbook;

import io.loyaltyhub.wallet.domain.EditionCloseRule;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TB-WAL §9.1 — regola di chiusura edizione (docs/03 §4.3, F-TIER-04): tabella decisionale completa livello attuale ×
 * classi di {@code periodSts} (0, soglie −1/=, valore alto), scala e soglie lette da {@code seed/tiers.json}.
 * earned = livello più alto con soglia ≤ periodSts; floor = rank attuale − 1; nuovo = max(earned, floor) senza mai
 * salire in chiusura.
 */
class TestbookWalCloseRuleTest {

    @ParameterizedTest(name = "[{0}] {1}", quoteTextArguments = false)
    @CsvFileSource(resources = "/testbook/wal/close-rule.csv", numLinesToSkip = 1)
    void computeNext(String id, String description, String currentTier, String periodSts,
                     String earnedTier, String newTier, String outcome) {
        // Riga 033 (livello attuale sconosciuto) — Q-149 DECISA: membro invariato, esito UNKNOWN_TIER
        EditionCloseRule.Result r = EditionCloseRule.computeNext(currentTier, WalTestbook.sts(periodSts), WalTestbook.seedScale());

        assertThat(r.earnedTier()).as("%s: livello guadagnato", id).isEqualTo(earnedTier);
        assertThat(r.newTier()).as("%s: nuovo livello", id).isEqualTo(newTier);
        assertThat(r.outcome().name()).as("%s: esito", id).isEqualTo(outcome);
    }
}
