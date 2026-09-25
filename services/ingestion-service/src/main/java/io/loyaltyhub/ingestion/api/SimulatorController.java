package io.loyaltyhub.ingestion.api;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import io.loyaltyhub.common.ids.Ulid;
import io.loyaltyhub.common.web.RequiresRole;
import io.loyaltyhub.common.web.Role;
import io.loyaltyhub.ingestion.application.IngestionService;
import io.loyaltyhub.ingestion.domain.IngestResult;
import io.loyaltyhub.ingestion.infra.EventTypeRepository;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

/**
 * Simulatore eventi della demo (docs/servizi/ingestion-service.md §3, BO-28, F-DEMO-03): invia azioni vere
 * nella pipeline. Se {@code data} è assente usa il {@code sample_data} del tipo. Riservato a chi può simulare.
 */
@RestController
@RequestMapping("/v1/demo/simulator")
@RequiresRole({Role.ADMIN, Role.MARKETING, Role.LEGAL, Role.CARE})
public class SimulatorController {

    private final IngestionService ingestion;
    private final EventTypeRepository eventTypes;
    private final ObjectMapper mapper;
    private final Clock clock;

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
        JsonNode data = req.data() != null && !req.data().isNull() ? req.data() : sampleData(req.type());

        List<FireResult> results = new ArrayList<>();
        for (int i = 0; i < count; i++) {
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

    private JsonNode sampleData(String type) {
        String shortType = type.startsWith("io.loyaltyhub.action.")
                ? type.substring("io.loyaltyhub.action.".length()) : type;
        return eventTypes.sampleData(shortType)
                .map(mapper::readTree)
                .orElseGet(mapper::createObjectNode);
    }
}
