package io.loyaltyhub.ingestion.api;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import io.loyaltyhub.common.ids.Ulid;
import io.loyaltyhub.common.web.RequiresRole;
import io.loyaltyhub.common.web.Role;
import io.loyaltyhub.ingestion.application.IngestionService;
import io.loyaltyhub.ingestion.domain.EventType;
import io.loyaltyhub.ingestion.domain.IngestResult;
import io.loyaltyhub.ingestion.domain.SampleVariation;
import io.loyaltyhub.ingestion.infra.EventTypeRepository;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.random.RandomGenerator;

/**
 * Simulatore eventi della demo (docs/servizi/ingestion-service.md §3, BO-28, F-DEMO-03): invia azioni vere
 * nella pipeline. Se {@code data} è assente usa il {@code sample_data} del tipo con piccole variazioni casuali
 * ({@link SampleVariation}). Riservato a chi può simulare.
 */
@RestController
@RequestMapping("/v1/demo/simulator")
@RequiresRole({Role.ADMIN, Role.MARKETING, Role.LEGAL, Role.CARE})
public class SimulatorController {

    private final IngestionService ingestion;
    private final EventTypeRepository eventTypes;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final RandomGenerator random = RandomGenerator.of("L64X128MixRandom");

    public SimulatorController(IngestionService ingestion, EventTypeRepository eventTypes,
                              ObjectMapper mapper, Clock clock) {
        this.ingestion = ingestion;
        this.eventTypes = eventTypes;
        this.mapper = mapper;
        this.clock = clock;
    }

    public record FireRequest(String memberId, String type, JsonNode data, String source,
                              String occurredAt, Integer count) {
    }

    public record FireResult(String eventId, String correlationId, String status, String rejectCode) {
    }

    @PostMapping("/fire")
    public List<FireResult> fire(@RequestBody FireRequest req) {
        int count = req.count() == null ? 1 : Math.min(Math.max(req.count(), 1), 20);
        String source = req.source() != null && !req.source().isBlank() ? req.source() : "simulator";
        String time = req.occurredAt() != null && !req.occurredAt().isBlank()
                ? req.occurredAt() : clock.instant().toString();
        boolean explicitData = req.data() != null && !req.data().isNull();

        List<FireResult> results = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            // data assente → sample_data del tipo con piccole variazioni casuali, diverse a ogni invio (ingestion §3).
            JsonNode data = explicitData ? req.data() : variedSample(req.type());
            String id = Ulid.next(clock);
            InboundEventRequest event = new InboundEventRequest(
                    "1.0", id, source, req.type(), "member:" + req.memberId(), time, data);
            // inbound_event.origin = SIMULATOR (ingestion §2): BO-26 distingue le prove dal traffico delle fonti.
            IngestResult r = ingestion.ingest(event, IngestionService.ORIGIN_SIMULATOR);
            results.add(new FireResult(r.eventId(), r.correlationId(), r.status().name(),
                    r.rejectCode() == null ? null : r.rejectCode().name()));
        }
        return results;
    }

    /** Ultimo {@code data} variato per tipo: l'invio successivo ne sceglie uno diverso (niente due prove uguali). */
    private final Map<String, JsonNode> lastVaried = new ConcurrentHashMap<>();

    private JsonNode variedSample(String type) {
        String shortType = type.startsWith("io.loyaltyhub.action.")
                ? type.substring("io.loyaltyhub.action.".length()) : type;
        Optional<EventType> t = eventTypes.findByCode(shortType);
        JsonNode sample = t.map(EventType::sampleData).filter(Objects::nonNull).map(mapper::readTree)
                .orElseGet(mapper::createObjectNode);
        JsonNode schema = t.map(EventType::dataSchema).filter(Objects::nonNull).map(mapper::readTree).orElse(null);
        JsonNode previous = lastVaried.get(shortType);
        JsonNode varied = SampleVariation.vary(sample, schema, random);
        for (int attempt = 0; attempt < 5 && varied.equals(previous); attempt++) {
            varied = SampleVariation.vary(sample, schema, random);
        }
        lastVaried.put(shortType, varied);
        return varied;
    }
}
