package io.loyaltyhub.notifier.delivery;

import io.loyaltyhub.common.test.PostgresIntegrationTest;
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

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Quando il fornitore di un canale non risponde, la consegna resta fallita e senza un reinvio manuale nessuno la
 * recupera: il messaggio è semplicemente perso, e chi lo ha deciso non lo sa. Questi test provano la coda del
 * reinvio e il rinvio su un canale diverso da quello che aveva fallito.
 */
@SpringBootTest(properties = "spring.kafka.listener.auto-startup=false")
class DeliveryResendIntegrationTest extends PostgresIntegrationTest {

    /** Canale finto: fallisce o riesce su comando e tiene il conto delle consegne ricevute. */
    static class CanaleFinto implements ChannelAdapter {
        private final String channel;
        final List<String> consegne = new ArrayList<>();
        boolean rotto;

        CanaleFinto(String channel, boolean rotto) { this.channel = channel; this.rotto = rotto; }

        @Override public String channel() { return channel; }
        @Override public Result deliver(Delivery d) {
            if (rotto) return Result.failed("fornitore " + channel + " non raggiungibile");
            consegne.add(d.deliveryId());
            return Result.sent("ref-" + channel + "-" + consegne.size());
        }
    }

    @TestConfiguration
    static class Adattatori {
        static final CanaleFinto PUSH = new CanaleFinto("push", true);
        static final CanaleFinto APP = new CanaleFinto("app", false);

        @Bean @Primary List<ChannelAdapter> canaliFinti() { return List.of(PUSH, APP); }

        /** Instradamento di prova: SEND_MESSAGE va su push, e basta — così il fallimento non viene mascherato. */
        @Bean @Primary java.util.function.Supplier<DeliveryRouting> routingDiProva() {
            var seed = DeliveryRouting.example();
            var r = new DeliveryRouting("prova", "1", Map.of("SEND_MESSAGE", List.of("push")), List.of("push", "app"),
                    Map.of(), null, null, null, seed.templateByAction(), false);
            return () -> r;
        }
    }

    @Autowired DeliveryService deliveries;
    @Autowired JdbcTemplate jdbc;

    @MockitoBean KafkaTemplate<String, byte[]> kafka;

    @BeforeEach
    void pulisci() {
        jdbc.update("DELETE FROM notifier.delivery_log");
        Adattatori.PUSH.rotto = true;
        Adattatori.PUSH.consegne.clear();
        Adattatori.APP.consegne.clear();
    }

    private String consegnaFallita(String memberId, String decisionId) {
        var res = deliveries.deliver(memberId, decisionId, "SEND_MESSAGE", "msg-1", "push", Map.of("nome", "Rossi"), null, "corr-1", Instant.now());
        assertThat(res.status()).isEqualTo("FAILED");
        return jdbc.queryForObject("SELECT delivery_id FROM notifier.delivery_log WHERE member_id = ? AND status = 'FAILED' ORDER BY created_at DESC LIMIT 1", String.class, memberId);
    }

    @Test void una_consegna_fallita_finisce_nella_coda_del_reinvio() {
        String id = consegnaFallita("m-1", "d-1");

        var coda = deliveries.failed(50);

        assertThat(coda).extracting(r -> r.get("delivery_id")).contains(id);
        assertThat(coda).first().extracting(r -> r.get("detail")).asString().contains("non raggiungibile");
    }

    @Test void il_reinvio_su_un_altro_canale_consegna_e_toglie_la_riga_dalla_coda() {
        String id = consegnaFallita("m-2", "d-2");

        var res = deliveries.resend(id, "app", "operatore.rossi");

        assertThat(res.status()).isEqualTo("SENT");
        assertThat(Adattatori.APP.consegne).hasSize(1);
        assertThat(deliveries.failed(50)).extracting(r -> r.get("delivery_id")).doesNotContain(id);
        assertThat(jdbc.queryForObject("SELECT detail FROM notifier.delivery_log WHERE delivery_id = ?", String.class, id))
                .contains("rispedita da operatore.rossi");
    }

    /** Il reinvio salta il controllo di «già consegnata»: rifare è proprio lo scopo. */
    @Test void il_reinvio_funziona_anche_quando_il_canale_originale_e_tornato() {
        String id = consegnaFallita("m-3", "d-3");
        Adattatori.PUSH.rotto = false;

        var res = deliveries.resend(id, null, "console");

        assertThat(res.status()).isEqualTo("SENT");
        assertThat(Adattatori.PUSH.consegne).hasSize(1);
    }

    @Test void una_consegna_non_si_rispedisce_due_volte() {
        String id = consegnaFallita("m-4", "d-4");
        deliveries.resend(id, "app", "console");

        assertThatThrownBy(() -> deliveries.resend(id, "app", "console"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("già rispedita");
    }

    /** Un'offerta scaduta non si rispedisce: arriverebbe al cliente una proposta che non può più accettare. */
    @Test void una_consegna_scaduta_non_si_rispedisce() {
        deliveries.deliver("m-5", "d-5", "SEND_MESSAGE", "msg-1", "push", Map.of(), Instant.now().minus(Duration.ofHours(1)), "corr", Instant.now());
        String id = jdbc.queryForObject("SELECT delivery_id FROM notifier.delivery_log WHERE member_id = 'm-5' AND status = 'FAILED' ORDER BY created_at DESC LIMIT 1", String.class);

        assertThatThrownBy(() -> deliveries.resend(id, "app", "console"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("scaduta");
    }

    @Test void una_consegna_sconosciuta_e_un_errore_del_chiamante() {
        assertThatThrownBy(() -> deliveries.resend("non-esiste", null, "console"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
