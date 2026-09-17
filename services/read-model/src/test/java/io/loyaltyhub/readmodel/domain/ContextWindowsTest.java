package io.loyaltyhub.readmodel.domain;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Le finestre mobili del Customer 360 erano calcolate solo quando arrivava un evento, e sapevano solo crescere: i
 * 90 giorni non scadevano mai e i contatti a 7 giorni nemmeno. Un cliente fermo da un anno risultava attivo, e uno
 * contattato molto restava sopra il tetto per sempre — il motore non gli trovava più un canale.
 */
class ContextWindowsTest {

    private static final Instant OGGI = Instant.parse("2027-06-01T12:00:00Z");

    private static CustomerContext conTransazioni(Instant... quando) {
        CustomerContext c = ContextProjector.empty("m1");
        for (Instant at : quando) c = ContextProjector.onAction(c, "TRANSACTION", at, "app", 50.0, "t-" + at, Map.of());
        return c;
    }

    @Test void le_transazioni_uscite_dalla_finestra_non_contano_piu() {
        var c = conTransazioni(OGGI.minus(Duration.ofDays(400)), OGGI.minus(Duration.ofDays(120)), OGGI.minus(Duration.ofDays(10)));

        var ricalcolato = ContextProjector.recomputeWindows(c, OGGI);

        assertThat(ricalcolato.behaviour().rfm().frequency90d()).isEqualTo(1);
        assertThat(ricalcolato.behaviour().rfm().frequency365d()).isEqualTo(2);
        assertThat(ricalcolato.behaviour().rfm().monetary365d()).isEqualTo(100.0);
        assertThat(ricalcolato.behaviour().rfm().recencyDays()).isEqualTo(10);
    }

    @Test void i_conteggi_a_30_giorni_si_svuotano_quando_il_cliente_si_ferma() {
        var c = ContextProjector.onAction(ContextProjector.empty("m1"), "CHECK_IN", OGGI.minus(Duration.ofDays(3)), "app", null, "c1", Map.of());
        assertThat(c.behaviour().actionCounts30d()).containsEntry("CHECK_IN", 1);

        var dopoDueMesi = ContextProjector.recomputeWindows(c, OGGI.plus(Duration.ofDays(60)));

        assertThat(dopoDueMesi.behaviour().actionCounts30d()).isEmpty();
    }

    /** Il caso che bloccava le decisioni: i contatti a 7 giorni non decadevano e il tetto per canale restava pieno. */
    @Test void i_contatti_a_sette_giorni_decadono() {
        CustomerContext c = ContextProjector.empty("m1");
        for (int i = 0; i < 3; i++) c = ContextProjector.onDelivery(c, "push", OGGI.minus(Duration.ofDays(1)));
        c = ContextProjector.onDelivery(c, "email", OGGI.minus(Duration.ofDays(9)));

        assertThat(c.engagement().contacts7dByChannel()).containsEntry("push", 3).doesNotContainKey("email");

        var dopoDieciGiorni = ContextProjector.recomputeWindows(c, OGGI.plus(Duration.ofDays(10)));
        assertThat(dopoDieciGiorni.engagement().contacts7dByChannel()).isEmpty();
        assertThat(dopoDieciGiorni.engagement().recentContactsOrEmpty()).hasSize(4);   // il dettaglio resta, la finestra no
    }

    /** Un contesto scritto prima che esistesse il dettaglio delle consegne non deve far esplodere niente. */
    @Test void un_contesto_senza_dettaglio_delle_consegne_si_ricalcola_lo_stesso() {
        var vuoto = ContextProjector.empty("m1");
        var e = vuoto.engagement();
        var vecchio = new CustomerContext(vuoto.memberId(), vuoto.identity(), vuoto.loyalty(), vuoto.behaviour(),
                new CustomerContext.Engagement(e.badges(), e.achievementsCompleted(), e.challengesCompleted(), e.campaignsCompleted30d(),
                        e.recentOffers(), 0, 0, OGGI, Map.of("push", 5), null),
                vuoto.risk(), vuoto.consents(), vuoto.predictions(), OGGI);

        var ricalcolato = ContextProjector.recomputeWindows(vecchio, OGGI);

        assertThat(ricalcolato.engagement().contacts7dByChannel()).isEmpty();
        assertThat(ricalcolato.engagement().recentContactsOrEmpty()).isEmpty();
    }
}
