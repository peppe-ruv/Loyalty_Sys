package io.loyaltyhub.identitymapping.app;

import io.loyaltyhub.common.test.PostgresIntegrationTest;
import io.loyaltyhub.identitymapping.domain.IdentityGraph.Identifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Il merge di due identità tocca tre domini: il grafo qui, il registro punti nel ledger, l'anagrafica nel
 * member-service. Un errore fra il trasferimento delle unità e la chiusura del membro assorbito lasciava uno stato
 * che nessuno recuperava: identificatori e punti già spostati, membro assorbito ancora aperto, e un riprovo che
 * generava una chiave nuova e trasferiva una seconda volta. Questi test riproducono l'interruzione a ogni passo.
 */
@SpringBootTest(properties = {"spring.kafka.listener.auto-startup=false", "identity.merge.reconcile.enabled=false"})
class MergeResumeIntegrationTest extends PostgresIntegrationTest {

    /** Ledger e anagrafica finti: contano le chiamate e sanno rompersi su comando. */
    static class EffettiSpia implements IdentityService.MergeEffects {
        final List<String> trasferimenti = new ArrayList<>();
        final List<String> ripristini = new ArrayList<>();
        final List<String> chiusure = new ArrayList<>();
        final List<String> riaperture = new ArrayList<>();
        final Map<String, Long> saldoAssorbito = new LinkedHashMap<>();
        /** Quanto il membro sopravvissuto ha ancora, per wallet, quando si annulla il merge. */
        final Map<String, Long> saldoSopravvissuto = new LinkedHashMap<>();
        boolean rompiTrasferimento, rompiChiusura, rompiRipristino;

        @Override public Map<String, Long> transferUnits(String from, String into, String mergeId) {
            if (rompiTrasferimento) throw new IllegalStateException("ledger non raggiungibile");
            trasferimenti.add(mergeId);
            return Map.copyOf(saldoAssorbito);
        }

        @Override public Map<String, Long> restoreUnits(String into, String from, Map<String, Long> units, String mergeId) {
            if (rompiRipristino) throw new IllegalStateException("ledger non raggiungibile");
            ripristini.add(mergeId);
            Map<String, Long> back = new LinkedHashMap<>();
            units.forEach((wallet, moved) -> {
                long disponibile = saldoSopravvissuto.getOrDefault(wallet, 0L);
                long importo = Math.min(moved, disponibile);
                if (importo > 0) back.put(wallet, importo);
            });
            return back;
        }

        @Override public void closeMember(String memberId, String reason) {
            if (rompiChiusura) throw new IllegalStateException("member-service non raggiungibile");
            chiusure.add(memberId);
        }

        @Override public void reopenMember(String memberId, String reason) { riaperture.add(memberId); }

        void azzera() {
            trasferimenti.clear(); ripristini.clear(); chiusure.clear(); riaperture.clear();
            saldoAssorbito.clear(); saldoSopravvissuto.clear();
            rompiTrasferimento = rompiChiusura = rompiRipristino = false;
        }
    }

    @TestConfiguration
    static class Adattatori {
        @Bean @Primary EffettiSpia effettiSpia() { return new EffettiSpia(); }
    }

    @Autowired IdentityService identities;
    @Autowired EffettiSpia effetti;
    @Autowired JdbcTemplate jdbc;

    @MockitoBean KafkaTemplate<String, byte[]> kafka;

    @BeforeEach
    void pulisci() {
        effetti.azzera();
        jdbc.update("DELETE FROM identitymapping.member_alias");
        jdbc.update("DELETE FROM identitymapping.merge_history");
        jdbc.update("DELETE FROM identitymapping.identity_link");
    }

    /** Due identità dello stesso cliente: una dal portale, una dalla cassa del negozio. */
    private void dueIdentita(String vecchio, String nuovo) {
        identities.link(vecchio, new Identifier("pos_card", "CARD-" + vecchio), "pos", 100);
        identities.link(vecchio, new Identifier("device_id", "DEV-" + vecchio), "app", 60);
        identities.link(nuovo, new Identifier("oidc_sub", nuovo), "portale", 100);
    }

    private String stato(String mergeId) {
        return jdbc.queryForObject("SELECT status FROM identitymapping.merge_history WHERE merge_id = ?", String.class, mergeId);
    }

    @Test
    void il_merge_completo_sposta_identificatori_unita_e_chiude_il_membro_assorbito() {
        dueIdentita("vecchio-1", "nuovo-1");
        effetti.saldoAssorbito.put("PREMIO", 300L);

        var m = identities.merge("merge-1", "vecchio-1", "nuovo-1", "stesso cliente", "operatore");

        assertThat(m.status()).isEqualTo("COMPLETED");
        assertThat(m.completed()).isTrue();
        assertThat(m.unitsMoved()).containsEntry("PREMIO", 300L);
        assertThat(identities.canonical("vecchio-1")).isEqualTo("nuovo-1");
        assertThat(identities.linksOf("nuovo-1")).hasSize(3);
        assertThat(effetti.chiusure).containsExactly("vecchio-1");
    }

    /** Stessa chiave = stesso merge: il secondo tentativo non trasferisce una seconda volta. */
    @Test
    void ripetere_il_merge_con_la_stessa_chiave_non_trasferisce_due_volte() {
        dueIdentita("vecchio-2", "nuovo-2");
        effetti.saldoAssorbito.put("PREMIO", 120L);

        identities.merge("merge-2", "vecchio-2", "nuovo-2", "duplicato", "operatore");
        var secondo = identities.merge("merge-2", "vecchio-2", "nuovo-2", "duplicato", "operatore");

        assertThat(secondo.status()).isEqualTo("COMPLETED");
        assertThat(effetti.trasferimenti).containsExactly("merge-2");
        assertThat(effetti.chiusure).containsExactly("vecchio-2");
    }

    /** Senza chiave esplicita la chiave si deriva dalla coppia: due richieste identiche restano un merge solo. */
    @Test
    void senza_chiave_due_richieste_identiche_sono_lo_stesso_merge() {
        dueIdentita("vecchio-3", "nuovo-3");
        effetti.saldoAssorbito.put("PREMIO", 50L);

        var primo = identities.merge("vecchio-3", "nuovo-3", "senza chiave", "operatore");
        var secondo = identities.merge("vecchio-3", "nuovo-3", "senza chiave", "operatore");

        assertThat(secondo.mergeId()).isEqualTo(primo.mergeId());
        assertThat(effetti.trasferimenti).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM identitymapping.merge_history", Integer.class)).isEqualTo(1);
    }

    /** Il ledger non risponde: nessun alias, nessun membro chiuso, e il riprovo riparte dal trasferimento. */
    @Test
    void se_il_trasferimento_fallisce_il_merge_resta_recuperabile() {
        dueIdentita("vecchio-4", "nuovo-4");
        effetti.saldoAssorbito.put("PREMIO", 80L);
        effetti.rompiTrasferimento = true;

        assertThatThrownBy(() -> identities.merge("merge-4", "vecchio-4", "nuovo-4", "ledger giù", "operatore"))
                .isInstanceOf(IdentityService.MergeIncomplete.class)
                .hasMessageContaining("LINKS_MOVED");

        assertThat(stato("merge-4")).isEqualTo("LINKS_MOVED");
        assertThat(identities.canonical("vecchio-4")).isEqualTo("vecchio-4");   // nessun alias finché il merge non è completo
        assertThat(effetti.chiusure).isEmpty();

        effetti.rompiTrasferimento = false;
        var ripreso = identities.merge("merge-4", "vecchio-4", "nuovo-4", "ledger giù", "operatore");

        assertThat(ripreso.status()).isEqualTo("COMPLETED");
        assertThat(ripreso.unitsMoved()).containsEntry("PREMIO", 80L);
        assertThat(effetti.trasferimenti).containsExactly("merge-4");
    }

    /**
     * Il caso che lasciava lo stato incoerente: unità già trasferite e chiusura del membro assorbito fallita.
     * Ora l'errore risale al chiamante, lo stato resta scritto e la ripresa chiude il membro senza ri-trasferire.
     */
    @Test
    void se_la_chiusura_del_membro_fallisce_la_ripresa_finisce_il_lavoro() {
        dueIdentita("vecchio-5", "nuovo-5");
        effetti.saldoAssorbito.put("PREMIO", 500L);
        effetti.rompiChiusura = true;

        assertThatThrownBy(() -> identities.merge("merge-5", "vecchio-5", "nuovo-5", "anagrafica giù", "operatore"))
                .isInstanceOf(IdentityService.MergeIncomplete.class)
                .hasMessageContaining("ALIASED");
        assertThat(stato("merge-5")).isEqualTo("ALIASED");
        assertThat(jdbc.queryForObject("SELECT failure FROM identitymapping.merge_history WHERE merge_id = ?", String.class, "merge-5"))
                .contains("member-service non raggiungibile");

        effetti.rompiChiusura = false;
        var ripreso = identities.advance("merge-5");

        assertThat(ripreso.status()).isEqualTo("COMPLETED");
        assertThat(effetti.trasferimenti).containsExactly("merge-5");   // una volta sola
        assertThat(effetti.chiusure).containsExactly("vecchio-5");
    }

    /** Lo scheduler porta a termine i merge fermi da almeno un minuto, senza che nessuno se ne accorga. */
    @Test
    void la_riconciliazione_riprende_i_merge_rimasti_a_meta() {
        dueIdentita("vecchio-6", "nuovo-6");
        effetti.saldoAssorbito.put("PREMIO", 10L);
        effetti.rompiChiusura = true;
        assertThatThrownBy(() -> identities.merge("merge-6", "vecchio-6", "nuovo-6", "riavvio", "operatore"))
                .isInstanceOf(IdentityService.MergeIncomplete.class);

        jdbc.update("UPDATE identitymapping.merge_history SET updated_at = now() - interval '5 minutes' WHERE merge_id = ?", "merge-6");
        effetti.rompiChiusura = false;

        assertThat(identities.reconcile()).isEqualTo(1);
        assertThat(stato("merge-6")).isEqualTo("COMPLETED");
        assertThat(identities.reconcile()).isZero();   // niente da riprendere una seconda volta
    }

    /** Unmerge: identificatori indietro, alias rimosso, unità ritrasferite, membro riattivato. */
    @Test
    void unmerge_ripristina_identificatori_e_unita() {
        dueIdentita("vecchio-7", "nuovo-7");
        effetti.saldoAssorbito.put("PREMIO", 200L);
        effetti.saldoAssorbito.put("STATUS", 40L);
        identities.merge("merge-7", "vecchio-7", "nuovo-7", "errore di identificazione", "operatore");

        effetti.saldoSopravvissuto.put("PREMIO", 1000L);
        effetti.saldoSopravvissuto.put("STATUS", 1000L);
        var annullato = identities.unmerge("merge-7", "reclamo del cliente");

        assertThat(annullato.status()).isEqualTo("UNMERGED");
        assertThat(annullato.unitsRestored()).containsEntry("PREMIO", 200L).containsEntry("STATUS", 40L);
        assertThat(annullato.shortfall()).isEmpty();
        assertThat(identities.canonical("vecchio-7")).isEqualTo("vecchio-7");
        assertThat(identities.linksOf("vecchio-7")).hasSize(2);
        assertThat(identities.linksOf("nuovo-7")).hasSize(1);
        assertThat(effetti.riaperture).containsExactly("vecchio-7");
    }

    /**
     * Se nel frattempo il membro sopravvissuto ha speso i punti, l'unmerge non li inventa: restituisce ciò che c'è e
     * dichiara l'ammanco. Il registro resta l'unica verità sui saldi.
     */
    @Test
    void unmerge_dichiara_l_ammanco_quando_le_unita_sono_state_spese() {
        dueIdentita("vecchio-8", "nuovo-8");
        effetti.saldoAssorbito.put("PREMIO", 300L);
        identities.merge("merge-8", "vecchio-8", "nuovo-8", "merge", "operatore");

        effetti.saldoSopravvissuto.put("PREMIO", 120L);   // ne sono stati spesi 180
        var annullato = identities.unmerge("merge-8", "reclamo");

        assertThat(annullato.unitsRestored()).containsEntry("PREMIO", 120L);
        assertThat(annullato.shortfall()).containsEntry("PREMIO", 180L);
        assertThat(identities.canonical("vecchio-8")).isEqualTo("vecchio-8");
    }

    /** Un merge ancora a metà non si annulla: prima va portato a termine, altrimenti si ripristina uno stato che non c'è. */
    @Test
    void un_merge_incompleto_non_si_annulla() {
        dueIdentita("vecchio-9", "nuovo-9");
        effetti.rompiChiusura = true;
        assertThatThrownBy(() -> identities.merge("merge-9", "vecchio-9", "nuovo-9", "x", "operatore"))
                .isInstanceOf(IdentityService.MergeIncomplete.class);

        assertThatThrownBy(() -> identities.unmerge("merge-9", "troppo presto"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("merge non completo");
    }

    /** La stessa chiave non può servire due coppie diverse: sarebbe un merge scambiato per un altro. */
    @Test
    void la_stessa_chiave_su_una_coppia_diversa_e_un_errore() {
        dueIdentita("vecchio-10", "nuovo-10");
        identities.merge("merge-10", "vecchio-10", "nuovo-10", "primo", "operatore");

        assertThatThrownBy(() -> identities.merge("merge-10", "altro-vecchio", "altro-nuovo", "secondo", "operatore"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("già usata");
    }
}
