package io.loyaltyhub.insight.trace;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import io.loyaltyhub.insight.domain.DlqEntry;
import io.loyaltyhub.insight.domain.StoredEvent;
import io.loyaltyhub.insight.infra.DlqRepository;
import io.loyaltyhub.insight.infra.EventStoreRepository;
import io.loyaltyhub.insight.live.EventSummaries;
import io.loyaltyhub.insight.trace.Trace.Outcome;
import io.loyaltyhub.insight.trace.Trace.PointAmount;
import io.loyaltyhub.insight.trace.Trace.TierChange;
import io.loyaltyhub.insight.trace.Trace.TraceNode;
import io.loyaltyhub.insight.trace.Trace.TraceSummary;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Ricostruisce i tracciati dall'event store (docs/servizi/insight-service.md §5): albero per {@code causationId},
 * stato (COMPLETE dopo 5 s di quiete, FAILED se c'è una voce DLQ, altrimenti IN_PROGRESS) ed esito sintetico.
 * Le voci DLQ del tracciato ({@code dlq_entry}, M7.3) compaiono come nodi {@code DLQ} figli dell'evento fallito,
 * nella corsia del consumer che non è riuscito a elaborarlo.
 */
@Service
public class TraceService {

    private static final String SERVICE_PREFIX = "urn:loyaltyhub:service:";
    private static final long QUIET_MS = 5_000;

    private final EventStoreRepository events;
    private final DlqRepository dlqEntries;
    private final ObjectMapper mapper;

    public TraceService(EventStoreRepository events, DlqRepository dlqEntries, ObjectMapper mapper) {
        this.events = events;
        this.dlqEntries = dlqEntries;
        this.mapper = mapper;
    }

    public Optional<Trace> trace(String correlationId) {
        List<StoredEvent> rows = events.byCorrelation(correlationId);
        List<DlqEntry> dlqs = dlqEntries.byCorrelation(correlationId);
        if (rows.isEmpty() && dlqs.isEmpty()) {
            return Optional.of(new Trace(correlationId, null, null, 0, "IN_PROGRESS", List.of(),
                    new Outcome(List.of(), null, 0, 0, 0, 0)));
        }
        List<Instant> times = new ArrayList<>();
        rows.forEach(e -> times.add(e.receivedAt()));
        dlqs.forEach(d -> times.add(d.firstSeenAt()));
        Instant start = times.stream().min(Instant::compareTo).orElseThrow();
        Instant last = times.stream().max(Instant::compareTo).orElse(start);
        String memberId = rows.stream().map(StoredEvent::memberId).filter(m -> m != null).findFirst()
                .orElse(dlqs.stream().map(DlqEntry::memberId).filter(m -> m != null).findFirst().orElse(null));

        List<TraceNode> nodes = new ArrayList<>();
        for (StoredEvent e : rows) {
            JsonNode data = dataOf(e);
            nodes.add(new TraceNode(e.eventId(), e.family(), e.shortType(), serviceOf(e), e.eventTime(),
                    Duration.between(start, e.receivedAt()).toMillis(), e.causationId(),
                    EventSummaries.of(e.shortType(), data)));
        }

        for (DlqEntry d : dlqs) {
            nodes.add(new TraceNode(d.id(), "DLQ", d.shortType() == null ? "dlq" : d.shortType(),
                    consumerService(d.consumer()), d.firstSeenAt(), Duration.between(start, d.firstSeenAt()).toMillis(),
                    d.eventId(), "DLQ · " + (d.errorCode() == null ? "errore" : d.errorCode()) + " · " + d.status()));
        }

        // SPEC-GAP: Q-B3 — "FAILED se esiste DLQ": una voce riprocessata (ingestion l'ha ripubblicata) non fa più fallire
        // il tracciato; aperta o scartata sì. Un nuovo fallimento dopo il riprocessa apre una nuova voce.
        boolean hasDlq = rows.stream().anyMatch(e -> "DLQ".equals(e.family()))
                || dlqs.stream().anyMatch(d -> !DlqEntry.REPROCESSED.equals(d.status()));
        String status = hasDlq ? "FAILED"
                : (Duration.between(last, Instant.now()).toMillis() >= QUIET_MS ? "COMPLETE" : "IN_PROGRESS");

        Outcome outcome = outcomeOf(rows);
        outcome = new Outcome(outcome.points(), outcome.tierChange(), outcome.messages(), outcome.coupons(),
                outcome.plays(), outcome.dlq() + dlqs.size());
        return Optional.of(new Trace(correlationId, memberId, start,
                Duration.between(start, last).toMillis(), status, nodes, outcome));
    }

    public List<TraceSummary> recent(String memberId, Instant from, Instant to, int limit) {
        List<TraceSummary> out = new ArrayList<>();
        for (String correlationId : events.recentCorrelationIds(memberId, from, to, limit)) {
            trace(correlationId).ifPresent(t -> out.add(summary(t)));
        }
        return out;
    }

    // ---------- esito ----------

    private static String firstText(JsonNode data, String... fields) {
        for (String f : fields) {
            String v = data.path(f).asString(null);
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }

    private Outcome outcomeOf(List<StoredEvent> rows) {
        Map<String, Long> points = new LinkedHashMap<>();
        TierChange tierChange = null;
        int dlq = 0;
        for (StoredEvent e : rows) {
            JsonNode data = dataOf(e);
            switch (e.shortType()) {
                case "wallet.points.earned" -> {
                    String currency = data.path("currency").asString("PTS");
                    long amount = data.path("amount").asLong(0);
                    points.merge(currency, amount, Long::sum);
                }
                // Contratto EVT-FACT-28/29 (docs/05): previousTier → newTier; from/to/tier restano come ripiego.
                case "tier.upgraded", "tier.downgraded", "tier.changed" -> tierChange = new TierChange(
                        firstText(data, "previousTier", "from"), firstText(data, "newTier", "to", "tier"));
                default -> {
                    // altri tipi: nessun contributo all'esito in M2
                }
            }
            if ("DLQ".equals(e.family())) {
                dlq++;
            }
        }
        List<PointAmount> pointList = points.entrySet().stream()
                .map(en -> new PointAmount(en.getKey(), en.getValue())).toList();
        return new Outcome(pointList, tierChange, 0, 0, 0, dlq);
    }

    private TraceSummary summary(Trace t) {
        String root = t.nodes().stream().filter(n -> n.parentEventId() == null)
                .map(TraceNode::shortType).findFirst()
                .orElse(t.nodes().isEmpty() ? "?" : t.nodes().get(0).shortType());
        return new TraceSummary(t.correlationId(), t.memberId(), root, t.startedAt(), t.durationMs(),
                t.status(), outcomeSummary(t.outcome()));
    }

    private static String outcomeSummary(Outcome o) {
        List<String> parts = new ArrayList<>();
        o.points().forEach(p -> parts.add("+" + p.amount() + " " + p.currency()));
        if (o.tierChange() != null && o.tierChange().to() != null) {
            parts.add("tier " + o.tierChange().to());
        }
        if (o.dlq() > 0) {
            parts.add(o.dlq() + " DLQ");
        }
        return String.join(" · ", parts);
    }

    // ---------- helper ----------

    private JsonNode dataOf(StoredEvent e) {
        try {
            return mapper.readTree(e.payloadJson() == null ? "{}" : e.payloadJson()).path("data");
        } catch (RuntimeException ex) {
            return mapper.createObjectNode();
        }
    }

    /** Corsia del consumer che ha fallito: {@code lh-campaign} → {@code campaign}. */
    private static String consumerService(String consumer) {
        if (consumer == null) {
            return "dlq";
        }
        return consumer.startsWith("lh-") ? consumer.substring(3) : consumer;
    }

    private static String serviceOf(StoredEvent e) {
        String src = e.source();
        if (src != null && src.startsWith(SERVICE_PREFIX)) {
            return src.substring(SERVICE_PREFIX.length());
        }
        if ("ACTION".equals(e.family())) {
            return "ingestion";
        }
        return e.family() == null ? "?" : e.family().toLowerCase();
    }
}
