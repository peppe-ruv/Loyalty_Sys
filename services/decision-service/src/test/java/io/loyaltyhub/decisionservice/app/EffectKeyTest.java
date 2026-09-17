package io.loyaltyhub.decisionservice.app;

import io.loyaltyhub.decisionservice.domain.DecisionPolicy;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * La chiave dell'effetto è ciò che tiene separati due premi nati dallo stesso evento: il ledger è
 * idempotente per (chiave, wallet), quindi con la chiave della sola azione il secondo accredito
 * sparirebbe. Deve però restare riconoscibile allo storno, che cerca per prefisso (RI-08).
 */
class EffectKeyTest {

    private static final String AZIONE = "bolletta:2026-09:m-1";
    private static final String DECISIONE = "9f1c2d3e-aaaa-bbbb-cccc-000000000001";

    @Test
    void duePremiDellaStessaDecisioneHannoChiaviDiverse() {
        String punti = DecisionService.effectKey(AZIONE, DECISIONE, DecisionPolicy.ActionType.AWARD_POINTS);
        String premio = DecisionService.effectKey(AZIONE, DECISIONE, DecisionPolicy.ActionType.ISSUE_REWARD);

        assertThat(punti).isNotEqualTo(premio);
    }

    @Test
    void laChiaveCominciaConQuellaDellAzioneCosiLoStornoLaRitrova() {
        String chiave = DecisionService.effectKey(AZIONE, DECISIONE, DecisionPolicy.ActionType.AWARD_POINTS);

        assertThat(chiave).startsWith(AZIONE + ":");
    }

    @Test
    void decisioniDiverseNonSiSovrappongono() {
        String prima = DecisionService.effectKey(AZIONE, DECISIONE, DecisionPolicy.ActionType.AWARD_POINTS);
        String dopo = DecisionService.effectKey(AZIONE, "0a1b2c3d-dddd-eeee-ffff-000000000002", DecisionPolicy.ActionType.AWARD_POINTS);

        assertThat(prima).isNotEqualTo(dopo);
    }

    @Test
    void reggeUnIdentificativoPiuCortoDiOttoCaratteri() {
        assertThat(DecisionService.effectKey(AZIONE, "abc", DecisionPolicy.ActionType.AWARD_POINTS))
                .isEqualTo(AZIONE + ":abc:AWARD_POINTS");
    }
}
