package it.iren.loyalty.ingressadapters.schema;

import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** Catalogo dei tipi azione con schema (RF-01, RF-98) e validazione di prova per il backoffice. */
@RestController
@RequestMapping("/v1/schemas")
public class SchemaController {
    private final SchemaRegistry registry;
    public SchemaController(SchemaRegistry registry) { this.registry = registry; }

    @GetMapping public List<EventSchema> all() { return registry.all(); }

    @PostMapping("/{actionType}/validate")
    public Map<String, Object> validate(@PathVariable String actionType, @RequestBody Map<String, Object> attributes) {
        var s = registry.byActionType(actionType);
        if (s.isEmpty()) return Map.of("known", false, "errors", List.of());
        var errors = s.get().validate(attributes);
        return Map.of("known", true, "valid", errors.isEmpty(), "errors", errors);
    }
}
