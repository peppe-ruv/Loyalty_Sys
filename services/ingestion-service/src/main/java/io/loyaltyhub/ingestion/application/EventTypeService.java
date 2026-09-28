package io.loyaltyhub.ingestion.application;

import io.loyaltyhub.common.audit.AuditEntry;
import io.loyaltyhub.common.audit.AuditPublisher;
import io.loyaltyhub.common.event.JsonSchemaValidator;
import io.loyaltyhub.common.web.ActorHolder;
import io.loyaltyhub.common.web.LhException;
import io.loyaltyhub.common.web.Role;
import io.loyaltyhub.ingestion.domain.EventType;
import io.loyaltyhub.ingestion.infra.EventTypeRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Gestione dei tipi azione da BO-09 (docs/servizi/ingestion-service.md §3, F-ING-06, M6.7).
 * <ul>
 *   <li>I tipi {@code CUSTOM} si creano e si modificano in tutto tranne {@code code} (ADMIN, MARKETING:
 *       {@code actiontype.custom}); lo schema vale da subito per le azioni in ingresso, senza rilascio.</li>
 *   <li>I tipi {@code SYSTEM} ammettono solo {@code name, description, enabled, icon}; cambiarli è configurazione del
 *       programma ({@code program.config}, solo ADMIN). SPEC-GAP: Q-89 — la matrice dei permessi non dice chi modifica un
 *       tipo di sistema: scelta la più restrittiva.</li>
 * </ul>
 * Codice dei custom: minuscolo a punti come i tipi di sistema ({@code meter.reading.sent}). SPEC-GAP: Q-89.
 * Il primo segmento non può essere {@code io} né {@code loyaltyhub}: sono riservati ai {@code type} completi
 * ({@code io.loyaltyhub.<famiglia>.<nome>}, docs/05 §2), e un codice che li imita uscirebbe dalla famiglia azioni.
 * SPEC-GAP: Q-439. La creazione è atomica (inserimento solo se assente): di due creazioni concorrenti dello stesso
 * codice una sola vince e lascia l'audit, l'altra riceve {@code 409 EVENT_TYPE_EXISTS} (Q-440).
 */
@Service
public class EventTypeService {

    public record EventTypeRequest(String code, String name, String description, String category, String icon,
                                   JsonNode dataSchema, JsonNode sampleData, Boolean enabled) {
    }

    static final Pattern CODE = Pattern.compile("^[a-z][a-z0-9]*(\\.[a-z][a-z0-9]*){1,3}$");
    /** Primi segmenti riservati ai {@code type} completi {@code io.loyaltyhub.<famiglia>.<nome>} (Q-439). */
    static final Set<String> RESERVED_FIRST_SEGMENTS = Set.of("io", "loyaltyhub");
    static final String CODE_FORMAT_MESSAGE =
            "minuscolo a punti, da 2 a 4 parti (es. meter.reading.sent), al massimo 60 caratteri";
    static final String CODE_RESERVED_MESSAGE = "la prima parte non può essere io né loyaltyhub: "
            + "sono riservate ai type completi io.loyaltyhub.<famiglia>.<nome>";
    static final Set<String> CUSTOM_CATEGORIES = Set.of("TRANSACTION", "ENGAGEMENT", "SERVICE");

    private final EventTypeRepository types;
    private final JsonSchemaValidator validator;
    private final AuditPublisher audit;
    private final ObjectMapper mapper;

    public EventTypeService(EventTypeRepository types, JsonSchemaValidator validator, AuditPublisher audit,
                            ObjectMapper mapper) {
        this.types = types;
        this.validator = validator;
        this.audit = audit;
        this.mapper = mapper;
    }

    @Transactional
    public EventType create(EventTypeRequest req) {
        if (req == null) {
            throw LhException.validation("EVENT_TYPE_INVALID", "Corpo mancante.");
        }
        String code = req.code() == null ? "" : req.code().trim();
        List<LhException.FieldError> errors = new ArrayList<>();
        String codeProblem = codeProblem(code);
        if (codeProblem != null) {
            errors.add(new LhException.FieldError("code", codeProblem));
        }
        EventType draft = customDraft(code, req, errors);
        if (!errors.isEmpty()) {
            throw LhException.validation("EVENT_TYPE_INVALID", "Tipo azione non valido.", errors);
        }
        // Q-440: inserimento solo se assente, deciso dal database. Chi perde la gara riceve 409 senza sovrascrivere il
        // vincitore e senza lasciare audit (l'eccezione annulla la transazione).
        if (!types.insertIfAbsent(draft)) {
            throw LhException.conflict("EVENT_TYPE_EXISTS", "Esiste già un tipo azione " + code + ".");
        }
        audit.record("event_type", code, AuditEntry.Action.CREATE, "Creato il tipo azione custom " + code,
                null, snapshot(draft));
        return draft;
    }

    @Transactional
    public EventType update(String code, EventTypeRequest req) {
        EventType before = types.findByCode(code)
                .orElseThrow(() -> LhException.notFound("Tipo azione non trovato: " + code));
        if (req == null) {
            throw LhException.validation("EVENT_TYPE_INVALID", "Corpo mancante.");
        }
        if (req.code() != null && !req.code().equals(code)) {
            throw LhException.validation("EVENT_TYPE_IMMUTABLE_FIELD", "Il codice di un tipo azione non si cambia.",
                    List.of(new LhException.FieldError("code", "non modificabile")));
        }
        EventType after;
        if (before.isCustom()) {
            List<LhException.FieldError> errors = new ArrayList<>();
            after = customDraft(code, req, errors);
            if (!errors.isEmpty()) {
                throw LhException.validation("EVENT_TYPE_INVALID", "Tipo azione non valido.", errors);
            }
        } else {
            if (ActorHolder.get().role() != Role.ADMIN) {
                throw LhException.forbiddenRole("I tipi di sistema si modificano solo con il ruolo ADMIN.");
            }
            List<LhException.FieldError> locked = new ArrayList<>();
            if (req.category() != null && !req.category().equals(before.category())) {
                locked.add(new LhException.FieldError("category", "non modificabile su un tipo di sistema"));
            }
            if (differs(req.dataSchema(), before.dataSchema())) {
                locked.add(new LhException.FieldError("dataSchema", "non modificabile su un tipo di sistema"));
            }
            if (differs(req.sampleData(), before.sampleData())) {
                locked.add(new LhException.FieldError("sampleData", "non modificabile su un tipo di sistema"));
            }
            if (!locked.isEmpty()) {
                throw LhException.validation("EVENT_TYPE_SYSTEM_LOCKED",
                        "Di un tipo di sistema si cambiano solo nome, descrizione, icona e abilitazione.", locked);
            }
            String name = req.name() == null || req.name().isBlank() ? before.name() : req.name().trim();
            after = new EventType(code, name, blankToNull(req.description()), before.origin(), before.category(),
                    before.dataSchema(), before.sampleData(),
                    req.enabled() == null ? before.enabled() : req.enabled(), blankToNull(req.icon()));
        }
        types.save(after);
        audit.record("event_type", code, AuditEntry.Action.UPDATE, "Modificato il tipo azione " + code,
                snapshot(before), snapshot(after));
        return after;
    }

    /**
     * Problema del codice di un nuovo tipo custom, {@code null} se valido: formato minuscolo a punti da 2 a 4 parti,
     * al massimo 60 caratteri (Q-89), primo segmento diverso da {@code io} e {@code loyaltyhub} (Q-439).
     */
    static String codeProblem(String code) {
        if (code == null || code.length() > 60 || !CODE.matcher(code).matches()) {
            return CODE_FORMAT_MESSAGE;
        }
        String first = code.substring(0, code.indexOf('.'));
        return RESERVED_FIRST_SEGMENTS.contains(first) ? CODE_RESERVED_MESSAGE : null;
    }

    private EventType customDraft(String code, EventTypeRequest req, List<LhException.FieldError> errors) {
        String name = req.name() == null ? "" : req.name().trim();
        if (name.isEmpty() || name.length() > 60) {
            errors.add(new LhException.FieldError("name", "obbligatorio, al massimo 60 caratteri"));
        }
        String category = req.category() == null ? "ENGAGEMENT" : req.category();
        if (!CUSTOM_CATEGORIES.contains(category)) {
            errors.add(new LhException.FieldError("category", "una tra TRANSACTION, ENGAGEMENT, SERVICE"));
        }
        JsonNode schema = req.dataSchema();
        String schemaJson = null;
        if (schema == null || !schema.isObject() || !"object".equals(schema.path("type").asString(""))) {
            errors.add(new LhException.FieldError("dataSchema", "JSON Schema con \"type\": \"object\""));
        } else {
            // F-ING-06: lo schema deve essere un JSON Schema valido (meta-schema 2020-12), non solo JSON ben formato.
            List<String> invalid = validator.metaSchemaErrors(schema.toString());
            if (invalid.isEmpty()) {
                schemaJson = schema.toString();
            } else {
                errors.add(new LhException.FieldError("dataSchema", "JSON Schema non valido: " + String.join("; ", invalid)));
            }
        }
        String sampleJson = null;
        if (schemaJson != null) {
            JsonNode sample = req.sampleData() == null || req.sampleData().isNull()
                    ? mapper.createObjectNode() : req.sampleData();
            try {
                List<String> problems = validator.validate("draft#" + schemaJson.hashCode(), schemaJson, sample.toString());
                if (!problems.isEmpty()) {
                    errors.add(new LhException.FieldError("sampleData", String.join("; ", problems)));
                }
                sampleJson = sample.toString();
            } catch (IllegalArgumentException e) {
                errors.add(new LhException.FieldError("dataSchema", "JSON Schema non valido"));
            }
        }
        return new EventType(code, name, blankToNull(req.description()), EventType.CUSTOM, category, schemaJson,
                sampleJson, req.enabled() == null || req.enabled(), blankToNull(req.icon()));
    }

    private boolean differs(JsonNode requested, String stored) {
        if (requested == null || requested.isNull()) {
            return false;
        }
        JsonNode current = stored == null ? null : mapper.readTree(stored);
        return !Objects.equals(requested, current);
    }

    private Map<String, Object> snapshot(EventType t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", t.name());
        m.put("description", t.description());
        m.put("origin", t.origin());
        m.put("category", t.category());
        m.put("enabled", t.enabled());
        m.put("icon", t.icon());
        m.put("dataSchema", t.dataSchema() == null ? null : mapper.readTree(t.dataSchema()));
        return m;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
