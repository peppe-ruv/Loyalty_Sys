package io.loyaltyhub.common.event;

import io.loyaltyhub.common.ids.Ulid;

import java.time.Clock;
import java.time.Instant;

/**
 * Crea envelope propagando correlazione, causazione, hop e attore lungo la catena (docs/05 §2, §7).
 * Un'istanza per servizio: il {@code source} di default è {@code urn:loyaltyhub:service:<servizio>}.
 */
public class LhEventFactory {

    private final Clock clock;
    private final String serviceName;

    public LhEventFactory(Clock clock, String serviceName) {
        this.clock = clock;
        this.serviceName = serviceName;
    }

    /** Evento radice prodotto da questo servizio (correlation = id, hop 0, causation nullo). */
    public <T> LhEvent<T> newRoot(String type, String subject, T data) {
        return newRoot(type, subject, data, LhSource.service(serviceName), null);
    }

    /** Evento radice con {@code source} e {@code actor} espliciti (usato da ingestion per le azioni esterne). */
    public <T> LhEvent<T> newRoot(String type, String subject, T data, String source, String actor) {
        String id = Ulid.next(clock);
        return new LhEvent<>(
                LhEvent.SPEC_VERSION, id, source, type, subject, clock.instant(),
                LhEvent.DATA_CONTENT_TYPE, LhSource.schemaForType(type, 1), LhEvent.TENANT,
                id, null, 0, actor, data);
    }

    /**
     * Evento derivato da un genitore, prodotto da questo servizio (effetto o fatto).
     * Mantiene {@code subject}, correlazione, {@code hop} e {@code actor} del genitore; {@code causation} = id del genitore.
     * {@code time} = istante di elaborazione.
     */
    public <T> LhEvent<T> childOf(LhEvent<?> parent, String type, T data) {
        return child(parent, type, data, clock.instant());
    }

    /**
     * Come {@link #childOf} ma con {@code time} pari a quello del genitore: per accrediti che
     * rappresentano la <em>stessa data di business</em> dell'azione radice (docs/05 §2).
     */
    public <T> LhEvent<T> childSameBusinessTime(LhEvent<?> parent, String type, T data) {
        return child(parent, type, data, parent.time());
    }

    /**
     * Come {@link #childOf} ma su un altro {@code subject}: un'azione di un membro che produce un fatto per un
     * secondo membro nello stesso tracciato (referral: il fatto dell'invitante nasce dall'acquisto dell'invitato).
     */
    public <T> LhEvent<T> childForSubject(LhEvent<?> parent, String type, String subject, T data) {
        String id = Ulid.next(clock);
        return new LhEvent<>(
                LhEvent.SPEC_VERSION, id, LhSource.service(serviceName), type, subject, clock.instant(),
                LhEvent.DATA_CONTENT_TYPE, LhSource.schemaForType(type, 1), LhEvent.TENANT,
                correlationOf(parent), parent.id(), parent.hopOrZero(), parent.lhactor(), data);
    }

    private <T> LhEvent<T> child(LhEvent<?> parent, String type, T data, Instant time) {
        String id = Ulid.next(clock);
        return new LhEvent<>(
                LhEvent.SPEC_VERSION, id, LhSource.service(serviceName), type, parent.subject(), time,
                LhEvent.DATA_CONTENT_TYPE, LhSource.schemaForType(type, 1), LhEvent.TENANT,
                correlationOf(parent), parent.id(), parent.hopOrZero(), parent.lhactor(), data);
    }

    /**
     * Ponte interno: da un fatto genera un'azione (docs/05 §7). Nuovo id, {@code source} interno,
     * stesso {@code subject}/{@code time}/correlazione, {@code causation} = id del fatto, {@code hop} + 1.
     */
    public <T> LhEvent<T> bridgeAction(LhEvent<?> fact, String actionType, T data) {
        String id = Ulid.next(clock);
        return new LhEvent<>(
                LhEvent.SPEC_VERSION, id, LhSource.INTERNAL, actionType, fact.subject(), fact.time(),
                LhEvent.DATA_CONTENT_TYPE, LhSource.schemaForType(actionType, 1), LhEvent.TENANT,
                correlationOf(fact), fact.id(), fact.hopOrZero() + 1, fact.lhactor(), data);
    }

    private static String correlationOf(LhEvent<?> parent) {
        return parent.lhcorrelationid() != null ? parent.lhcorrelationid() : parent.id();
    }
}
