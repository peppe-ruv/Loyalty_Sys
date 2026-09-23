package io.loyaltyhub.ingestion.application;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import io.loyaltyhub.common.ids.Ulid;
import io.loyaltyhub.common.web.LhException;
import io.loyaltyhub.ingestion.api.InboundEventRequest;
import io.loyaltyhub.ingestion.domain.IngestResult;
import io.loyaltyhub.ingestion.domain.Scenario;
import io.loyaltyhub.ingestion.domain.ScenarioRun;
import io.loyaltyhub.ingestion.infra.ScenarioRepository;
import io.loyaltyhub.ingestion.infra.ScenarioRunRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Esecutore degli scenari demo (docs/servizi/ingestion-service.md §5, BO-29): esegue i passi in sequenza
 * rispettando i ritardi (max 10 s per passo) e li invia nella stessa pipeline con origine {@code SIMULATOR}.
 * Asincrono: {@code run} torna subito il {@code runId}; l'avanzamento si legge da {@link #getRun(String)}.
 * Un passo può dichiarare {@code expect} ({@code REJECTED/DUPLICATE/UNMATCHED}) per gli scenari negativi.
 */
@Service
public class ScenarioService {

    private static final Logger log = LoggerFactory.getLogger(ScenarioService.class);
    private static final long MAX_STEP_DELAY_MS = 10_000;

    private final ScenarioRepository scenarios;
    private final ScenarioRunRepository runs;
    private final IngestionService ingestion;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final ExecutorService executor =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "scenario-runner");
                t.setDaemon(true);
                return t;
            });

    public ScenarioService(ScenarioRepository scenarios, ScenarioRunRepository runs,
                           IngestionService ingestion, ObjectMapper mapper, Clock clock) {
        this.scenarios = scenarios;
        this.runs = runs;
        this.ingestion = ingestion;
        this.mapper = mapper;
        this.clock = clock;
    }

    public List<Scenario> list() {
        return scenarios.findAll();
    }

    public Optional<ScenarioRun> getRun(String runId) {
        return runs.findById(runId);
    }

    /** Avvia l'esecuzione asincrona di uno scenario; ritorna il {@code runId} da consultare. */
    public String run(String code, String actor) {
        Scenario scenario = scenarios.findByCode(code)
                .orElseThrow(() -> LhException.notFound("Scenario non trovato: " + code));
        String runId = Ulid.next(clock);
        runs.insert(runId, code, clock.instant(), scenario.stepCount(), actor);
        executor.submit(() -> execute(runId, scenario));
        return runId;
    }

    private void execute(String runId, Scenario scenario) {
        ArrayNode results = mapper.createArrayNode();
        try {
            int index = 0;
            for (JsonNode step : scenario.steps()) {
                sleep(step.path("delayMs").asLong(0));
                results.add(runStep(index, step));
                runs.updateProgress(runId, index + 1, mapper.writeValueAsString(results));
                index++;
            }
            runs.finish(runId, "DONE", clock.instant(), mapper.writeValueAsString(results));
        } catch (RuntimeException e) {
            log.warn("Scenario {} interrotto: {}", scenario.code(), e.toString());
            runs.finish(runId, "FAILED", clock.instant(), mapper.writeValueAsString(results));
        }
    }

    /** Esegue un passo nella pipeline (origine SIMULATOR) e ne descrive l'esito, confrontandolo con {@code expect}. */
    private ObjectNode runStep(int index, JsonNode step) {
        String memberId = step.path("memberId").asString("");
        String type = step.path("type").asString("");
        String source = step.path("source").asString("simulator");
        String expect = step.path("expect").asString(null);
        String eventId = step.hasNonNull("eventId") ? step.get("eventId").asString() : Ulid.next(clock);
        JsonNode data = step.hasNonNull("data") ? step.get("data") : mapper.createObjectNode();

        InboundEventRequest request = new InboundEventRequest(
                "1.0", eventId, source, type, "member:" + memberId,
                io.loyaltyhub.ingestion.domain.ScenarioTime.resolve(step.path("at").asString(null), clock.instant()).toString(), data);
        IngestResult result = ingestion.ingest(request, IngestionService.ORIGIN_SIMULATOR);

        String status = result.status().name();
        boolean ok = expect == null ? status.equals("ACCEPTED") : status.equalsIgnoreCase(expect);

        ObjectNode node = mapper.createObjectNode();
        node.put("index", index);
        node.put("note", step.path("note").asString(""));
        node.put("memberId", memberId);
        node.put("type", type);
        node.put("status", status);
        node.put("eventId", result.eventId());
        node.put("correlationId", result.correlationId());
        if (result.rejectCode() != null) {
            node.put("rejectCode", result.rejectCode().name());
        }
        if (expect != null) {
            node.put("expected", expect);
        }
        node.put("ok", ok);
        return node;
    }

    private static void sleep(long delayMs) {
        long capped = Math.max(0, Math.min(delayMs, MAX_STEP_DELAY_MS));
        if (capped == 0) {
            return;
        }
        try {
            Thread.sleep(capped);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Esecuzione scenario interrotta", e);
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
