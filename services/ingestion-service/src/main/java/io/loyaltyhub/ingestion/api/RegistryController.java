package io.loyaltyhub.ingestion.api;

import io.loyaltyhub.ingestion.domain.EventType;
import io.loyaltyhub.ingestion.domain.Source;
import io.loyaltyhub.ingestion.infra.EventTypeRepository;
import io.loyaltyhub.ingestion.infra.SourceRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Registri in sola lettura per il backoffice (docs/servizi/ingestion-service.md §3, BO-09). CRUD in M6. */
@RestController
@RequestMapping("/v1")
public class RegistryController {

    private final EventTypeRepository eventTypes;
    private final SourceRepository sources;

    public RegistryController(EventTypeRepository eventTypes, SourceRepository sources) {
        this.eventTypes = eventTypes;
        this.sources = sources;
    }

    @GetMapping("/event-types")
    public List<EventType> eventTypes() {
        return eventTypes.findAll();
    }

    @GetMapping("/sources")
    public List<Source> sources() {
        return sources.findAll();
    }
}
