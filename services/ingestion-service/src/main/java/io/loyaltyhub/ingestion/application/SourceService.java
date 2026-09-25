package io.loyaltyhub.ingestion.application;

import io.loyaltyhub.common.audit.AuditEntry;
import io.loyaltyhub.common.audit.AuditPublisher;
import io.loyaltyhub.common.web.LhException;
import io.loyaltyhub.ingestion.domain.Source;
import io.loyaltyhub.ingestion.infra.EventTypeRepository;
import io.loyaltyhub.ingestion.infra.SourceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Creazione di una fonte da BO-09 ({@code POST /v1/sources}, docs/servizi/ingestion-service.md §3, F-ING-05):
 * capacità {@code program.config} (solo ADMIN, docs/08 §2), audit {@code CREATE} su {@code lh.audit.v1} (ingestion §4:
 * "modifiche a fonti, tipi, mapping"). Il codice diventa {@code urn:loyaltyhub:source:<code>} (docs/05 §2).
 * <p>
 * SPEC-GAP: Q-263 — la scheda elenca {@code POST /v1/sources} senza fissarne il corpo: scelte le opzioni più
 * conservative (codice minuscolo con cifre e trattini, 2–40 caratteri; nome obbligatorio ≤ 80; solo fonti
 * {@code HTTP}, perché {@code INTERNAL} è la sola {@code internal} del ponte; tipi ammessi esistenti; abilitata se
 * {@code enabled} è assente; codice già usato → {@code 409 SOURCE_EXISTS}).
 */
@Service
public class SourceService {

    /** Corpo di {@code POST /v1/sources}. {@code allowedTypes} assente o vuoto = tutti i tipi (ingestion §2). */
    public record NewSourceRequest(String code, String name, String kind, Boolean enabled, List<String> allowedTypes,
                                   String description) {
    }

    static final Pattern CODE = Pattern.compile("^[a-z][a-z0-9-]{1,39}$");
    static final String KIND_HTTP = "HTTP";
    private static final int MAX_NAME = 80;

    private final SourceRepository sources;
    private final EventTypeRepository types;
    private final AuditPublisher audit;

    public SourceService(SourceRepository sources, EventTypeRepository types, AuditPublisher audit) {
        this.sources = sources;
        this.types = types;
        this.audit = audit;
    }

    @Transactional
    public Source create(NewSourceRequest req) {
        if (req == null) {
            throw LhException.validation("SOURCE_INVALID", "Corpo mancante.");
        }
        String code = req.code() == null ? "" : req.code().trim();
        List<LhException.FieldError> errors = new ArrayList<>();
        if (!CODE.matcher(code).matches()) {
            errors.add(new LhException.FieldError("code", "minuscolo, cifre o trattini, da 2 a 40 caratteri (es. pos-negozi)"));
        }
        String name = req.name() == null ? "" : req.name().trim();
        if (name.isEmpty() || name.length() > MAX_NAME) {
            errors.add(new LhException.FieldError("name", "obbligatorio, al massimo " + MAX_NAME + " caratteri"));
        }
        if (req.kind() != null && !KIND_HTTP.equals(req.kind())) {
            errors.add(new LhException.FieldError("kind", "da API si creano solo fonti HTTP"));
        }
        LinkedHashSet<String> allowed = new LinkedHashSet<>();
        List<String> requested = req.allowedTypes() == null ? List.of() : req.allowedTypes();
        for (int i = 0; i < requested.size(); i++) {
            String t = requested.get(i) == null ? "" : requested.get(i).trim();
            if (t.isEmpty() || types.findByCode(t).isEmpty()) {
                errors.add(new LhException.FieldError("allowedTypes[" + i + "]", "tipo azione sconosciuto"));
            } else {
                allowed.add(t);
            }
        }
        if (!errors.isEmpty()) {
            throw LhException.validation("SOURCE_INVALID", "Fonte non valida.", errors);
        }
        if (sources.findByCode(code).isPresent()) {
            throw LhException.conflict("SOURCE_EXISTS", "Esiste già una fonte " + code + ".");
        }
        String description = req.description() == null || req.description().isBlank() ? null : req.description().trim();
        Source created = new Source(code, name, KIND_HTTP, req.enabled() == null || req.enabled(), List.copyOf(allowed),
                description);
        sources.upsert(created);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("name", created.name());
        after.put("kind", created.kind());
        after.put("enabled", created.enabled());
        after.put("allowedTypes", created.allowedTypes());
        after.put("description", created.description());
        audit.record("source", code, AuditEntry.Action.CREATE, "Creata la fonte " + code, null, after);
        return created;
    }
}
