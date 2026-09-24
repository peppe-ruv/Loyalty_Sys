package io.loyaltyhub.ingestion.api;

import io.loyaltyhub.common.web.LhException;
import io.loyaltyhub.common.web.RequiresRole;
import io.loyaltyhub.common.web.Role;
import io.loyaltyhub.ingestion.application.EventTypeService;
import io.loyaltyhub.ingestion.application.EventTypeService.EventTypeRequest;
import io.loyaltyhub.ingestion.domain.EventType;
import io.loyaltyhub.ingestion.domain.SchemaFields;
import io.loyaltyhub.ingestion.infra.EventTypeRepository;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * Tipi azione (docs/servizi/ingestion-service.md §3, BO-09, F-ING-06):
 * {@code GET/POST /v1/event-types}, {@code GET/PUT /v1/event-types/{code}}, {@code GET /v1/event-types/{code}/fields}
 * (campi {@code data.*} per il costruttore di condizioni di BO-06).
 */
@RestController
@RequestMapping("/v1/event-types")
public class EventTypesController {

    public record EventTypeView(String code, String name, String description, String origin, String category,
                                String icon, boolean enabled, JsonNode dataSchema, JsonNode sampleData) {
    }

    private final EventTypeRepository types;
    private final EventTypeService service;
    private final ObjectMapper mapper;

    public EventTypesController(EventTypeRepository types, EventTypeService service, ObjectMapper mapper) {
        this.types = types;
        this.service = service;
        this.mapper = mapper;
    }

    @GetMapping
    public List<EventTypeView> list() {
        return types.findAll().stream().map(this::view).toList();
    }

    @GetMapping("/{code}")
    public EventTypeView get(@PathVariable String code) {
        return view(find(code));
    }

    @GetMapping("/{code}/fields")
    public List<SchemaFields.Field> fields(@PathVariable String code) {
        EventType t = find(code);
        return t.hasSchema() ? SchemaFields.of(mapper.readTree(t.dataSchema())) : List.of();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresRole({Role.ADMIN, Role.MARKETING})
    public EventTypeView create(@RequestBody EventTypeRequest body) {
        return view(service.create(body));
    }

    @PutMapping("/{code}")
    @RequiresRole({Role.ADMIN, Role.MARKETING})
    public EventTypeView update(@PathVariable String code, @RequestBody EventTypeRequest body) {
        return view(service.update(code, body));
    }

    private EventType find(String code) {
        return types.findByCode(code).orElseThrow(() -> LhException.notFound("Tipo azione non trovato: " + code));
    }

    private EventTypeView view(EventType t) {
        return new EventTypeView(t.code(), t.name(), t.description(), t.origin(), t.category(), t.icon(), t.enabled(),
                t.dataSchema() == null ? null : mapper.readTree(t.dataSchema()),
                t.sampleData() == null ? null : mapper.readTree(t.sampleData()));
    }
}
