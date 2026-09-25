package io.loyaltyhub.insight.application;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import io.loyaltyhub.common.event.LhEvent;
import io.loyaltyhub.common.event.LhFamily;
import io.loyaltyhub.common.event.LhHeaders;
import io.loyaltyhub.common.ids.Ulid;
import io.loyaltyhub.common.time.BusinessCalendar;
import io.loyaltyhub.insight.domain.AuditRecord;
import io.loyaltyhub.insight.domain.DlqEntry;
import io.loyaltyhub.insight.domain.StoredEvent;
import io.loyaltyhub.insight.infra.AuditRepository;
import io.loyaltyhub.insight.infra.DlqRepository;
import io.loyaltyhub.insight.infra.EventStoreRepository;
import io.loyaltyhub.common.privacy.PersonalData;
import io.loyaltyhub.insight.infra.MemberRedactionRepository;
import io.loyaltyhub.insight.infra.MetricRepository;
import io.loyaltyhub.insight.infra.TopicStatRepository;
import io.loyaltyhub.insight.live.EventSummaries;
import io.loyaltyhub.insight.live.LiveEvent;
import io.loyaltyhub.insight.live.LiveEventHub;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Locale;

/**
 * Registra nell'event store gli eventi osservati sui topic (docs/servizi/insight-service.md §5).
 * Idempotente su {@code event_id}: un duplicato non crea una seconda riga né gonfia le statistiche.
 */
@Service
public class EventIngestService {

    private static final Logger log = LoggerFactory.getLogger(EventIngestService.class);
    private static final TypeReference<LhEvent<JsonNode>> EVENT_TYPE = new TypeReference<>() {
    };

    private final EventStoreRepository events;
    private final TopicStatRepository topicStats;
    private final MetricRepository metrics;
    private final AuditRepository audits;
    private final DlqRepository dlq;
    private final LiveEventHub liveHub;
    private final ObjectMapper mapper;
    private final MemberRedactionRepository redaction;
    private final Clock clock;
    private final int payloadMaxBytes;

    public EventIngestService(EventStoreRepository events, TopicStatRepository topicStats,
                              MetricRepository metrics, AuditRepository audits, DlqRepository dlq,
                              LiveEventHub liveHub, ObjectMapper mapper, MemberRedactionRepository redaction,
                              Clock clock,
                              @Value("${loyaltyhub.insight.retention.payload-max-bytes:8192}") int payloadMaxBytes) {
        this.payloadMaxBytes = payloadMaxBytes;
        this.redaction = redaction;
        this.clock = clock;
        this.events = events;
        this.topicStats = topicStats;
        this.metrics = metrics;
        this.audits = audits;
        this.dlq = dlq;
        this.liveHub = liveHub;
        this.mapper = mapper;
    }

    /**
     * Record su {@code lh.dlq.v1} → voce DLQ (docs/servizi/insight-service.md §2, §5; BO-27) con i dati degli header
     * {@code lh-*} di docs/04 §5 (in mancanza, quelli standard {@code kafka_dlt-*} del recoverer). Il valore è
     * l'evento originale, che l'event store ha già (stesso {@code event_id}): per questo il record DLQ non entra in
     * {@code event_store} (lo oscurerebbe) ma in {@code dlq_entry}. Il parsing è tollerante: un valore non JSON
     * diventa {@code {"raw": …}} — un errore qui rimanderebbe il record in DLQ, in ciclo.
     *
     * @return {@code true} se la voce è nuova (un record DLQ riletto non ne crea una seconda)
     */
    // SPEC-GAP: Q-111 — §2 elenca la famiglia DLQ in event_store, ma col medesimo event_id dell'originale la riga non
    // potrebbe coesistere: il record DLQ vive in dlq_entry (e nel flusso live), l'event store tiene l'originale.
    @Transactional
    public boolean ingestDlq(ConsumerRecord<String, String> record) {
        JsonNode payload = lenientJson(record.value());
        String type = textOrNull(payload, "type");
        String eventId = textOrNull(payload, "id");
        if (eventId == null) {
            eventId = "dlq-" + record.topic() + "-" + record.partition() + "-" + record.offset();
        }
        String originalTopic = firstHeader(record, LhHeaders.ORIGINAL_TOPIC, "kafka_dlt-original-topic");
        LhFamily family = LhFamily.of(type);
        String familyCode = family != null ? family.name() : familyOfTopic(originalTopic);
        String subject = textOrNull(payload, "subject");
        String memberId = subject != null && subject.startsWith("member:") ? subject.substring("member:".length()) : null;
        String consumer = firstHeader(record, LhHeaders.CONSUMER, "kafka_dlt-original-consumer-group");
        String errorClass = firstHeader(record, LhHeaders.ERROR_CLASS, "kafka_dlt-exception-cause-fqcn",
                "kafka_dlt-exception-fqcn");
        String errorCode = header(record, LhHeaders.ERROR_CODE);
        if (errorCode == null && errorClass != null) {
            errorCode = errorClass.substring(errorClass.lastIndexOf('.') + 1);
        }
        String retryable = header(record, LhHeaders.ERROR_RETRYABLE);
        Instant seenAt = record.timestamp() > 0 ? Instant.ofEpochMilli(record.timestamp()) : clock.instant();
        DlqEntry entry = new DlqEntry(
                Ulid.next(clock), eventId, originalTopic, type, familyCode, consumer == null ? "unknown" : consumer,
                errorCode, errorClass,
                firstHeader(record, LhHeaders.ERROR_MESSAGE, "kafka_dlt-exception-message"),
                shortStack(firstHeader(record, LhHeaders.ERROR_STACK, "kafka_dlt-exception-stacktrace")),
                retryable == null ? null : Boolean.valueOf(retryable), intOrNull(header(record, LhHeaders.ATTEMPTS)),
                memberId, textOrNull(payload, "lhcorrelationid"), payload, seenAt,
                DlqEntry.OPEN, null, null, null);

        boolean isNew = dlq.insert(entry, record.partition(), record.offset());
        if (isNew) {
            topicStats.record(record.topic(), seenAt, record.partition(), record.offset(), clock.instant(),
                    lagMs(record));
            increment(businessDay(seenAt), "dlq", 1);
            // Evento live con l'id della voce (non quello dell'originale, già usato dal suo topic).
            String shortType = entry.shortType() == null ? "dlq" : entry.shortType();
            liveHub.publish(new LiveEvent(entry.id(), record.topic(), "DLQ", shortType, memberId,
                    entry.correlationId(), seenAt,
                    "DLQ · " + entry.consumer() + " · " + (errorCode == null ? "errore" : errorCode)));
        } else {
            log.debug("Voce DLQ già aperta per {} / {}: record riletto, ignorato", eventId, entry.consumer());
        }
        return isNew;
    }

    @Transactional
    public void ingest(String topic, String family, ConsumerRecord<String, String> record) {
        LhEvent<JsonNode> event = mapper.readValue(record.value(), EVENT_TYPE);
        String type = event.type() == null ? "" : event.type();
        String shortType = shortType(type);
        StoredEvent stored = new StoredEvent(
                event.id(), topic, family, type, shortType, event.source(), event.memberId(),
                event.lhcorrelationid(), event.lhcausationid(), event.lhhop(), event.lhactor(),
                header(record, LhHeaders.ERROR_CODE), event.time(), null,
                record.partition(), record.offset(), truncate(record.value()));

        boolean isNew = events.insert(stored);
        if (isNew) {
            topicStats.record(topic, event.time(), record.partition(), record.offset(), clock.instant(),
                    lagMs(record));
            String producer = serviceOf(event.source());
            if ("FACT".equals(family) && producer != null) {
                topicStats.recordFact(producer, clock.instant(), shortType, event.id());
            }
            updateMetrics(family, shortType, event, stored);
            if ("AUDIT".equals(family)) {
                recordAudit(event);
            }
            // Anonimizzazione (F-MBR-05, M7.5): le copie del membro perdono i dati personali (anche questo evento).
            if ("FACT".equals(family) && event.memberId() != null && PersonalData.isAnonymization(event)) {
                int rows = redaction.redact(event.memberId());
                log.info("Membro {} anonimizzato: {} copie ripulite", event.memberId(), rows);
            }
            liveHub.publish(new LiveEvent(event.id(), topic, family, shortType, event.memberId(),
                    event.lhcorrelationid(), event.time(), EventSummaries.of(shortType, event.data())));
        } else {
            log.debug("Evento già in store (duplicato), ignorato: {} su {}", event.id(), topic);
        }
    }

    /**
     * Aggiorna gli aggregati giornalieri di insight §2 (docs §5) nel giorno di business ({@code Europe/Rome}, docs/03)
     * dell'evento: {@code actions} (per fonte e tipo), punti emessi/spesi/scaduti (per valuta), punti per campagna,
     * membri nuovi, richieste premio (per stato e premio), giocate, vincite, cambi di livello (per direzione), messaggi.
     */
    private void updateMetrics(String family, String shortType, LhEvent<JsonNode> event, StoredEvent stored) {
        LocalDate day = businessDay(event.time() != null ? event.time() : clock.instant());
        JsonNode data = event.data() == null ? mapper.createObjectNode() : event.data();
        switch (family) {
            case "ACTION" -> {
                increment(day, "actions", 1);
                metrics.increment(day, "actions", "source", sourceCode(stored.source()), 1);
                metrics.increment(day, "actions", "type", shortType, 1);
            }
            case "DLQ" -> increment(day, "dlq", 1);
            default -> {
                // fatti e effetti sotto
            }
        }
        if (!"FACT".equals(family)) {
            return; // le metriche di valore contano solo i fatti (un effetto points.grant non è un accredito)
        }
        switch (shortType) {
            case "wallet.points.earned" -> {
                long amount = data.path("amount").asLong(0);
                increment(day, "points_earned", amount);
                metrics.increment(day, "points_earned", "currency", currencyOf(data), amount);
                String campaign = data.path("campaignCode").asString(null);
                if (campaign != null && !campaign.isBlank()) {
                    metrics.increment(day, "points_by_campaign", "campaign", campaign, amount);
                }
            }
            // Spesa netta dei rimborsi (M4, KPI di BO-01).
            case "wallet.points.spent" -> {
                long amount = data.path("amount").asLong(0);
                increment(day, "points_spent", amount);
                metrics.increment(day, "points_spent", "currency", currencyOf(data), amount);
            }
            case "wallet.points.refunded" -> {
                long amount = data.path("amount").asLong(0);
                increment(day, "points_spent", -amount);
                metrics.increment(day, "points_spent", "currency", currencyOf(data), -amount);
            }
            case "wallet.points.expired" -> {
                long amount = data.path("amount").asLong(0);
                increment(day, "points_expired", amount);
                metrics.increment(day, "points_expired", "currency", currencyOf(data), amount);
            }
            case "member.registered" -> increment(day, "members_new", 1);
            // Richieste premio: il totale conta le confermate (KPI «Richieste premio» di BO-01); la dimensione
            // `status` conta ogni passaggio del ciclo di vita, `reward` le confermate per premio.
            case "reward.redemption.confirmed" -> {
                increment(day, "redemptions", 1);
                metrics.increment(day, "redemptions", "status", "CONFIRMED", 1);
                String reward = data.path("rewardCode").asString(null);
                if (reward != null && !reward.isBlank()) {
                    metrics.increment(day, "redemptions", "reward", reward, 1);
                }
            }
            case "reward.redemption.requested", "reward.redemption.fulfilled", "reward.redemption.cancelled",
                 "reward.redemption.rejected" -> metrics.increment(day, "redemptions", "status",
                    shortType.substring("reward.redemption.".length()).toUpperCase(Locale.ROOT), 1);
            case "contest.played" -> increment(day, "plays", 1);
            case "contest.won" -> increment(day, "wins", 1);
            case "tier.upgraded", "tier.downgraded", "tier.changed" -> {
                increment(day, "tier_changes", 1);
                String direction = switch (shortType) {
                    case "tier.upgraded" -> "UP";
                    case "tier.downgraded" -> "DOWN";
                    default -> "CHANGED";
                };
                metrics.increment(day, "tier_changes", "direction", direction, 1);
            }
            case "message.delivered" -> increment(day, "messages", 1);
            default -> {
                // altri fatti: nessuna metrica
            }
        }
    }

    private void increment(LocalDate day, String metric, long delta) {
        metrics.increment(day, metric, MetricRepository.TOTAL, MetricRepository.TOTAL, delta);
    }

    private static String currencyOf(JsonNode data) {
        String c = data.path("currency").asString("PTS");
        return c == null || c.isBlank() ? "PTS" : c;
    }

    /** Giorno di business di un istante: {@code Europe/Rome} (docs/03), non UTC. */
    static LocalDate businessDay(Instant instant) {
        return instant.atZone(BusinessCalendar.ZONE).toLocalDate();
    }

    /** Estrae la voce di audit dall'evento {@code io.loyaltyhub.audit.entry} e la registra (docs/05 §6). */
    private void recordAudit(LhEvent<JsonNode> event) {
        JsonNode data = event.data();
        if (data == null || data.isNull()) {
            log.warn("Evento audit senza data, ignorato: {}", event.id());
            return;
        }
        String actor = event.lhactor() == null ? "" : event.lhactor();
        int colon = actor.indexOf(':');
        String role = colon > 0 ? actor.substring(0, colon) : (actor.isBlank() ? null : actor);
        String name = colon >= 0 && colon < actor.length() - 1 ? actor.substring(colon + 1) : null;
        audits.insert(new AuditRecord(
                Ulid.next(clock), event.id(), event.time(), role, name,
                data.path("service").asString(""), data.path("entityType").asString(""),
                data.path("entityId").asString(""), data.path("action").asString(""),
                data.path("summary").asString(""), nodeOrNull(data.get("before")), nodeOrNull(data.get("after")),
                event.lhcorrelationid()));
    }

    /** Ritardo stimato (insight §3): arrivo qui − timestamp del record Kafka; {@code null} se il record non l'ha. */
    private Long lagMs(ConsumerRecord<String, String> record) {
        return record.timestamp() > 0 ? Math.max(0, clock.millis() - record.timestamp()) : null;
    }

    /** Servizio che ha prodotto l'evento, dal {@code source} {@code urn:loyaltyhub:service:<servizio>}. */
    private static String serviceOf(String source) {
        String prefix = "urn:loyaltyhub:service:";
        return source != null && source.startsWith(prefix) && source.length() > prefix.length()
                ? source.substring(prefix.length()) : null;
    }

    /**
     * Payload conservato al più di {@code payload-max-bytes} (8 KB, insight §5 / RNF-07). Oltre il limite si accorciano
     * i testi più lunghi (con «…») finché l'envelope ci sta, conservando struttura e campi brevi (importi, valute, id)
     * che tracciati e sintesi leggono; se non basta, {@code data} è sostituito da un segnaposto. L'envelope accorciato
     * porta {@code lhtruncatedbytes} = dimensione originale. Metriche e audit usano l'evento intero, letto prima.
     */
    String truncate(String json) {
        if (json == null || utf8Length(json) <= payloadMaxBytes) {
            return json;
        }
        int original = utf8Length(json);
        JsonNode root;
        try {
            root = mapper.readTree(json);
        } catch (RuntimeException e) {
            root = null;
        }
        if (!(root instanceof tools.jackson.databind.node.ObjectNode obj)) {
            return mapper.writeValueAsString(mapper.createObjectNode()
                    .put("raw", json.substring(0, Math.min(json.length(), payloadMaxBytes / 4)))
                    .put("lhtruncatedbytes", original));
        }
        obj.put("lhtruncatedbytes", original);
        // Margine per la forma testuale di jsonb (spazi dopo «:» e «,»).
        int budget = payloadMaxBytes - 512;
        for (int i = 0; i < 64; i++) {
            String out = mapper.writeValueAsString(obj);
            int size = utf8Length(out);
            if (size <= budget) {
                return out;
            }
            if (!shortenLongestText(obj, size - budget)) {
                break;
            }
        }
        obj.set("data", mapper.createObjectNode().put("truncated", true));
        String out = mapper.writeValueAsString(obj);
        return utf8Length(out) <= budget ? out : mapper.writeValueAsString(mapper.createObjectNode()
                .put("id", obj.path("id").asString(null))
                .put("type", obj.path("type").asString(null))
                .put("lhtruncatedbytes", original));
    }

    /** Accorcia il testo più lungo dell'albero di almeno {@code excess} byte; {@code false} se non ce n'è di utili. */
    private static boolean shortenLongestText(JsonNode root, int excess) {
        tools.jackson.databind.node.ContainerNode<?>[] parent = new tools.jackson.databind.node.ContainerNode<?>[1];
        Object[] key = new Object[1];
        String[] longest = {""};
        findLongest(root, parent, key, longest);
        String text = longest[0];
        if (parent[0] == null || text.length() <= 32) {
            return false;
        }
        int keep = Math.max(16, text.length() - Math.max(excess, text.length() / 2) - 1);
        String cut = text.substring(0, keep) + "…";
        if (parent[0] instanceof tools.jackson.databind.node.ObjectNode o) {
            o.put((String) key[0], cut);
        } else if (parent[0] instanceof tools.jackson.databind.node.ArrayNode a) {
            a.set((Integer) key[0], a.stringNode(cut));
        }
        return true;
    }

    private static void findLongest(JsonNode node, tools.jackson.databind.node.ContainerNode<?>[] parent,
                                    Object[] key, String[] longest) {
        if (node instanceof tools.jackson.databind.node.ObjectNode o) {
            for (java.util.Map.Entry<String, JsonNode> e : o.properties()) {
                if (e.getValue().isString() && e.getValue().asString().length() > longest[0].length()) {
                    longest[0] = e.getValue().asString();
                    parent[0] = o;
                    key[0] = e.getKey();
                } else {
                    findLongest(e.getValue(), parent, key, longest);
                }
            }
        } else if (node instanceof tools.jackson.databind.node.ArrayNode a) {
            for (int i = 0; i < a.size(); i++) {
                JsonNode v = a.get(i);
                if (v.isString() && v.asString().length() > longest[0].length()) {
                    longest[0] = v.asString();
                    parent[0] = a;
                    key[0] = i;
                } else {
                    findLongest(v, parent, key, longest);
                }
            }
        }
    }

    private static int utf8Length(String s) {
        return s.getBytes(StandardCharsets.UTF_8).length;
    }

    private static JsonNode nodeOrNull(JsonNode node) {
        return node == null || node.isNull() ? null : node;
    }

    private static String sourceCode(String source) {
        if (source == null) {
            return "unknown";
        }
        int i = source.lastIndexOf(':');
        return i >= 0 ? source.substring(i + 1) : source;
    }

    private static String shortType(String type) {
        LhFamily f = LhFamily.of(type);
        if (f != null && type.startsWith(f.typePrefix())) {
            return type.substring(f.typePrefix().length());
        }
        return type;
    }

    private JsonNode lenientJson(String value) {
        if (value == null || value.isBlank()) {
            return mapper.createObjectNode().put("raw", "");
        }
        try {
            JsonNode node = mapper.readTree(value);
            return node != null && node.isObject() ? node : mapper.createObjectNode().put("raw", value);
        } catch (RuntimeException e) {
            return mapper.createObjectNode().put("raw", value);
        }
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || !v.isString() || v.asString().isBlank() ? null : v.asString();
    }

    private static Integer intOrNull(String value) {
        try {
            return value == null ? null : Integer.valueOf(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Famiglia dedotta dal topic d'origine quando il {@code type} manca o non è riconoscibile. */
    private static String familyOfTopic(String topic) {
        if (topic != null) {
            for (LhFamily f : LhFamily.values()) {
                if (topic.startsWith("lh." + f.code())) {
                    return f.name();
                }
            }
        }
        return "UNKNOWN";
    }

    /** Stack abbreviato (le prime 16 righe): il recoverer standard mette lo stack completo nell'header. */
    private static String shortStack(String stack) {
        if (stack == null) {
            return null;
        }
        String[] lines = stack.split("\n");
        if (lines.length <= 16 && stack.length() <= 4000) {
            return stack;
        }
        String head = String.join("\n", java.util.Arrays.copyOf(lines, Math.min(lines.length, 16)));
        return (head.length() > 4000 ? head.substring(0, 4000) : head) + "\n\t…";
    }

    private static String firstHeader(ConsumerRecord<String, String> record, String... names) {
        for (String name : names) {
            String v = header(record, name);
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        Header h = record.headers().lastHeader(name);
        return h == null ? null : new String(h.value(), StandardCharsets.UTF_8);
    }
}
