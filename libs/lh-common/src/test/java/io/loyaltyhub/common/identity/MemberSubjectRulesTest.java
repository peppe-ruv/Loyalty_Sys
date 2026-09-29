package io.loyaltyhub.common.identity;

import io.loyaltyhub.common.identity.MemberSubjectRules.Action;
import io.loyaltyhub.common.identity.MemberSubjectRules.Claim;
import io.loyaltyhub.common.identity.MemberSubjectRules.Current;
import io.loyaltyhub.common.identity.MemberSubjectRules.Decision;
import io.loyaltyhub.common.identity.MemberSubjectRules.Holder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** {@link MemberSubjectRules}: la decisione pura della proiezione {@code subjectRef → membro} (Q-550, ADR-048). */
class MemberSubjectRulesTest {

    private static final String REF = "a".repeat(64);
    private static final Instant T0 = Instant.parse("2026-09-29T10:00:00Z");
    private static final Instant T1 = T0.plusSeconds(60);
    private static final Instant T2 = T0.plusSeconds(120);

    private static Decision decide(String member, Instant at, boolean anonymization, Claim claim, Current current,
                                   Holder holder) {
        return MemberSubjectRules.decide(member, at, anonymization, claim, current, holder);
    }

    @Test
    @DisplayName("1. anonimizzazione: lapide, anche con un subjectRef nel fatto e su un legame già cancellato")
    void anonymizationErases() {
        assertThat(decide("MBR-1", T1, true, Claim.link(REF), Current.fresh(), null).action()).isEqualTo(Action.ERASE);
        assertThat(decide("MBR-1", T1, true, Claim.absent(), new Current(false, T2), null).action()).isEqualTo(Action.ERASE);
        assertThat(decide("MBR-1", T0, true, Claim.unlink(), new Current(true, T2), null).action()).isEqualTo(Action.ERASE);
    }

    @Test
    @DisplayName("2. subjectRef assente: nessun effetto (un member-service vecchio non slega nessuno)")
    void absentIsNoEffect() {
        assertThat(decide("MBR-1", T1, false, Claim.absent(), Current.fresh(), null).action()).isEqualTo(Action.NONE);
        assertThat(decide("MBR-1", T1, false, Claim.absent(), new Current(false, T0), null).action()).isEqualTo(Action.NONE);
        assertThat(decide("MBR-1", T1, false, null, Current.fresh(), null).action()).isEqualTo(Action.NONE);
    }

    @Test
    @DisplayName("3. lapide o fatto più vecchio dell'ultimo legame: nessun effetto (un replay non ri-lega)")
    void erasedOrStaleIsNoEffect() {
        assertThat(decide("MBR-1", T2, false, Claim.link(REF), new Current(true, T0), null).action()).isEqualTo(Action.NONE);
        assertThat(decide("MBR-1", T2, false, Claim.unlink(), new Current(true, T0), null).action()).isEqualTo(Action.NONE);
        assertThat(decide("MBR-1", T0, false, Claim.link(REF), new Current(false, T1), null).action()).isEqualTo(Action.NONE);
        assertThat(decide("MBR-1", T0, false, Claim.unlink(), new Current(false, T1), null).action()).isEqualTo(Action.NONE);
        // Stesso istante: non è più vecchio, si applica.
        assertThat(decide("MBR-1", T1, false, Claim.link(REF), new Current(false, T1), null).action()).isEqualTo(Action.LINK);
    }

    @Test
    @DisplayName("4. subjectRef null: slega")
    void nullUnlinks() {
        assertThat(decide("MBR-1", T1, false, Claim.unlink(), Current.fresh(), null)).isEqualTo(new Decision(Action.UNLINK, false));
        assertThat(decide("MBR-1", T1, false, Claim.unlink(), new Current(false, T0), null).action()).isEqualTo(Action.UNLINK);
    }

    @Test
    @DisplayName("5. già legato a un altro membro con istante più recente: il fatto è vecchio")
    void olderThanTheHolderIsNoEffect() {
        Holder newer = new Holder("MBR-2", T2);
        assertThat(decide("MBR-1", T1, false, Claim.link(REF), Current.fresh(), newer).action()).isEqualTo(Action.NONE);
    }

    @Test
    @DisplayName("6. altrimenti lega; il sorpasso di un altro detentore è marcato per il contatore")
    void linksAndRelinks() {
        assertThat(decide("MBR-1", T1, false, Claim.link(REF), Current.fresh(), null)).isEqualTo(new Decision(Action.LINK, false));
        // Nessun altro detentore, o è lo stesso membro: nessun sorpasso.
        assertThat(decide("MBR-1", T1, false, Claim.link(REF), new Current(false, T0), new Holder("MBR-1", T0)))
                .isEqualTo(new Decision(Action.LINK, false));
        // Un detentore più vecchio perde lo pseudonimo a favore del fatto più recente.
        assertThat(decide("MBR-1", T2, false, Claim.link(REF), Current.fresh(), new Holder("MBR-2", T1)))
                .isEqualTo(new Decision(Action.LINK, true));
        // Un detentore senza istante è più vecchio di qualunque fatto.
        assertThat(decide("MBR-1", T0, false, Claim.link(REF), Current.fresh(), new Holder("MBR-2", null)))
                .isEqualTo(new Decision(Action.LINK, true));
    }

    @Test
    @DisplayName("6. a parità di istante decide l'id del membro (il maggiore), uguale su ogni replica")
    void tieBreakOnMemberId() {
        assertThat(decide("MBR-000009", T1, false, Claim.link(REF), Current.fresh(), new Holder("MBR-000010", T1)).action())
                .isEqualTo(Action.NONE);
        assertThat(decide("MBR-000010", T1, false, Claim.link(REF), Current.fresh(), new Holder("MBR-000009", T1)))
                .isEqualTo(new Decision(Action.LINK, true));
    }

    @Test
    @DisplayName("la rilettura di due fatti in ordine inverso converge allo stesso legame (ri-registrazione dopo anonimizzazione)")
    void convergesInAnyOrder() {
        // Il vecchio membro MBR-1 (fatto a T0) e il nuovo MBR-2 (fatto a T2) dichiarano lo stesso pseudonimo.
        Decision oldFirst = decide("MBR-1", T0, false, Claim.link(REF), Current.fresh(), null);
        Decision newAfter = decide("MBR-2", T2, false, Claim.link(REF), Current.fresh(), new Holder("MBR-1", T0));
        assertThat(oldFirst.action()).isEqualTo(Action.LINK);
        assertThat(newAfter).isEqualTo(new Decision(Action.LINK, true));
        // Ordine inverso: prima il nuovo, poi il vecchio (arrivato in ritardo) trova il nuovo più recente e non lo scalza.
        Decision newFirst = decide("MBR-2", T2, false, Claim.link(REF), Current.fresh(), null);
        Decision oldAfter = decide("MBR-1", T0, false, Claim.link(REF), Current.fresh(), new Holder("MBR-2", T2));
        assertThat(newFirst.action()).isEqualTo(Action.LINK);
        assertThat(oldAfter.action()).isEqualTo(Action.NONE);
    }

    @Test
    @DisplayName("Claim.of: assente, null esplicito, valore valido; un valore non riconoscibile non lega nessuno")
    void claimFromPayload() throws Exception {
        JsonMapper mapper = JsonMapper.builder().build();
        assertThat(claim(mapper, "{\"memberId\":\"MBR-1\"}")).isEqualTo(Claim.absent());
        assertThat(claim(mapper, "{\"subjectRef\":null}")).isEqualTo(Claim.unlink());
        assertThat(claim(mapper, "{\"subjectRef\":\"" + REF + "\"}")).isEqualTo(Claim.link(REF));
        assertThat(claim(mapper, "{\"subjectRef\":\"" + REF.toUpperCase() + "\"}")).isEqualTo(Claim.absent());
        assertThat(claim(mapper, "{\"subjectRef\":\"corto\"}")).isEqualTo(Claim.absent());
        assertThat(claim(mapper, "{\"subjectRef\":42}")).isEqualTo(Claim.absent());
        assertThat(Claim.of(null)).isEqualTo(Claim.absent());
    }

    private static Claim claim(JsonMapper mapper, String json) {
        JsonNode node = mapper.readTree(json);
        return Claim.of(node);
    }
}
