package io.loyaltyhub.ingressadapters.api;

import io.loyaltyhub.common.event.EventTypes;
import io.loyaltyhub.common.event.RewardingAction;
import io.loyaltyhub.common.test.PostgresIntegrationTest;
import io.loyaltyhub.ingressadapters.publish.ActionPublisher;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.willDoNothing;
import static org.mockito.BDDMockito.willThrow;

/**
 * Un'azione non deve sparire perché il broker è fermo (RI-01). Prima la chiave di idempotenza veniva
 * consumata <em>prima</em> della pubblicazione, e l'invio non ne attendeva l'esito: la fonte riceveva
 * 202, l'azione non entrava in piattaforma e ogni rinvio veniva respinto come duplicato.
 */
@SpringBootTest(properties = "spring.kafka.listener.auto-startup=false")
class ActionIngressIntegrationTest extends PostgresIntegrationTest {

    @Autowired ActionIngressController ingress;

    /** Il vero publisher parlerebbe con Kafka: qui interessa solo l'esito che restituisce. */
    @MockitoBean ActionPublisher publisher;

    private static ActionIngressController.IngressBatch batchWith(String key) {
        var action = new RewardingAction(EventTypes.ACTION_PROFILE_COMPLETED, key, "rif-1", Instant.now(), null, Map.of());
        return new ActionIngressController.IngressBatch(List.of(new ActionIngressController.IngressItem("m-1", action)));
    }

    /** Convenzione RI-01: {@code <fonte>:<riferimento>:<evento>}. */
    private static String chiave() {
        return "test:" + UUID.randomUUID() + ":PROFILE_COMPLETED";
    }

    @Test
    void seIlBrokerNonConfermaLaFonteLoSaELAzioneNonRisultaAccettata() {
        willThrow(new ActionPublisher.PublishFailed("broker giù", new IllegalStateException()))
                .given(publisher).publish(any());

        var risposta = ingress.ingest(batchWith(chiave()));

        assertThat(risposta.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(risposta.getBody()).isNotNull();
        assertThat(risposta.getBody().failed()).isEqualTo(1);
        assertThat(risposta.getBody().accepted()).isZero();
        assertThat(risposta.getBody().results().get(0).status()).isEqualTo("FAILED");
    }

    @Test
    void dopoUnaPubblicazioneFallitaIlRinvioDellaStessaAzioneVienePresoInCarico() {
        String chiave = chiave();
        willThrow(new ActionPublisher.PublishFailed("broker giù", new IllegalStateException()))
                .given(publisher).publish(any());
        ingress.ingest(batchWith(chiave));

        // Il broker torna: la fonte rinvia la stessa azione, con la stessa chiave.
        willDoNothing().given(publisher).publish(any());
        var risposta = ingress.ingest(batchWith(chiave));

        assertThat(risposta.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(risposta.getBody()).isNotNull();
        assertThat(risposta.getBody().accepted()).isEqualTo(1);
    }

    @Test
    void unaAzionePubblicataDavveroRestaDuplicataAlSecondoInvio() {
        String chiave = chiave();
        willDoNothing().given(publisher).publish(any());
        ingress.ingest(batchWith(chiave));

        var risposta = ingress.ingest(batchWith(chiave));

        assertThat(risposta.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(risposta.getBody()).isNotNull();
        assertThat(risposta.getBody().duplicates()).isEqualTo(1);
        assertThat(risposta.getBody().accepted()).isZero();
    }
}
