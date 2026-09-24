package io.loyaltyhub.ingestion.application;

import io.loyaltyhub.common.event.LhEvent;
import io.loyaltyhub.common.event.LhSource;
import io.loyaltyhub.common.outbox.OutboxWriter;
import io.loyaltyhub.common.web.LhException;
import io.loyaltyhub.ingestion.api.InboundEventRequest;
import io.loyaltyhub.ingestion.domain.IngestResult;
import io.loyaltyhub.ingestion.infra.InboundEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Optional;

/**
 * <em>Riprocessa</em> DLQ di un'azione (docs/servizi/insight-service.md §5, ADR-002 eccezione 1): insight re-invia a
 * {@code POST /v1/events} l'azione con lo <strong>stesso {@code id}</strong>, su comando di un ADMIN
 * (header {@code X-LH-Reprocess}). La pipeline normale la scarterebbe come {@code DUPLICATE} (dedup per fonte+id,
 * §5 punto 6) e nessun consumer la rivedrebbe: qui si ripubblica invece l'envelope <em>già accettato</em> così
 * com'era (stesso id, stesso {@code lhcorrelationid}, stesso {@code lhhop}). I consumer che l'avevano già elaborata
 * la ignorano per idempotenza ({@code processed_event}); quello fallito (la sua transazione non era stata
 * confermata) la rielabora. Nessuna nuova riga in {@code inbound_event}: l'ingresso è uno solo.
 */
// SPEC-GAP: Q-B1 — la scheda non dice come conciliare "stesso id" con la dedup: ripubblicazione dell'envelope accettato.
@Service
public class ActionReplayService {

    private static final Logger log = LoggerFactory.getLogger(ActionReplayService.class);
    private static final TypeReference<LhEvent<JsonNode>> EVENT_TYPE = new TypeReference<>() {
    };

    private final InboundEventRepository inbound;
    private final OutboxWriter outbox;
    private final ObjectMapper mapper;

    public ActionReplayService(InboundEventRepository inbound, OutboxWriter outbox, ObjectMapper mapper) {
        this.inbound = inbound;
        this.outbox = outbox;
        this.mapper = mapper;
    }

    /**
     * Ripubblica l'azione accettata con questa (fonte, id); vuoto se non c'è (es. riga già pulita dopo 7 giorni):
     * in quel caso il chiamante la fa passare dalla pipeline normale.
     */
    @Transactional
    public Optional<IngestResult> replay(InboundEventRequest request, String reprocessRef) {
        if (request.id() == null || request.id().isBlank() || request.source() == null || request.source().isBlank()) {
            throw LhException.badRequest("id e source sono obbligatori per riprocessare un'azione");
        }
        String source = request.source().startsWith(LhSource.SOURCE_PREFIX)
                ? request.source() : LhSource.source(request.source());
        String sourceCode = source.substring(source.lastIndexOf(':') + 1);
        Optional<String> payload = inbound.findAcceptedPayload(sourceCode, request.id());
        if (payload.isEmpty()) {
            return Optional.empty();
        }
        LhEvent<JsonNode> event = mapper.readValue(payload.get(), EVENT_TYPE);
        outbox.write(event);
        log.info("Azione {} ({}) ripubblicata su richiesta di riprocessa DLQ {}", event.id(), sourceCode, reprocessRef);
        return Optional.of(IngestResult.accepted(event.id(), event.memberId(), event.lhcorrelationid()));
    }
}
