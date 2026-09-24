package io.loyaltyhub.ingestion.api;

import io.loyaltyhub.ingestion.domain.Source;
import io.loyaltyhub.ingestion.infra.SourceRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Registri in sola lettura per il backoffice (docs/servizi/ingestion-service.md §3, BO-09). Tipi azione in {@link EventTypesController}. */
@RestController
@RequestMapping("/v1")
public class RegistryController {

    private final SourceRepository sources;

    public RegistryController(SourceRepository sources) {
        this.sources = sources;
    }

    @GetMapping("/sources")
    public List<Source> sources() {
        return sources.findAll();
    }
}
