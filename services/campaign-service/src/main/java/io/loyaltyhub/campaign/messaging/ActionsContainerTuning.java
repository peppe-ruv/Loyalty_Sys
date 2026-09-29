package io.loyaltyhub.campaign.messaging;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.config.ContainerPostProcessor;
import org.springframework.kafka.listener.AbstractMessageListenerContainer;
import org.springframework.stereotype.Component;

/**
 * {@code pollTimeout} del solo container delle azioni di campaign (Q-489). L'attesa dello snapshot usa
 * {@code Acknowledgment#nack(Duration)}: il container valuta la ripresa solo tra un {@code poll()} e il successivo e,
 * durante la pausa per il nack, usa il {@code pollTimeout} ordinario (5 s di default). Ogni ritardo di 0,5/1/2 s
 * diventerebbe così di circa 5 s (≈ 15 s prima di {@code NO_MEMBER}), con ferme tutte le partizioni di quel consumer.
 * Con 250 ms i ritardi hanno la risoluzione di un quarto di secondo.
 * <p>
 * Costo: un consumer delle azioni inattivo fa al più 4 {@code poll()} al secondo invece di uno ogni 5 s; le fetch vuote
 * restano limitate dal broker ({@code fetch.max.wait.ms}, 500 ms), quindi circa 2 richieste al secondo per consumer:
 * trascurabile anche a 0,1 CPU. Gli altri container (fatti, effetti, altri servizi) non cambiano.
 * Nome del bean esplicito: nell'hub la scansione usa nomi pienamente qualificati (ADR-023).
 */
@Component(ActionsContainerTuning.BEAN_NAME)
public class ActionsContainerTuning
        implements ContainerPostProcessor<String, String, AbstractMessageListenerContainer<String, String>> {

    public static final String BEAN_NAME = "campaignActionsContainerTuning";

    private final long pollTimeoutMs;

    public ActionsContainerTuning(@Value("${loyaltyhub.engine.actions-poll-timeout-ms:250}") long pollTimeoutMs) {
        this.pollTimeoutMs = pollTimeoutMs;
    }

    @Override
    public void postProcess(AbstractMessageListenerContainer<String, String> container) {
        container.getContainerProperties().setPollTimeout(pollTimeoutMs);
    }
}
