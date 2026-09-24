package io.loyaltyhub.member.api;

import io.loyaltyhub.common.web.RequiresRole;
import io.loyaltyhub.common.web.Role;
import io.loyaltyhub.member.application.AttributeService;
import io.loyaltyhub.member.domain.AttributeDefinition;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Attributi personalizzati (docs/servizi/member-service.md §3, F-MBR-03): lettura per tutti (costruttore di condizioni
 * di BO-06, criteri di BO-04, BO-03); il PUT sostituisce l'elenco con la capacità {@code segment.write}
 * ("segmenti, attributi custom" = ADMIN, MARKETING, docs/08 §2).
 */
@RestController
@RequestMapping("/v1/attribute-definitions")
public class AttributeDefinitionsController {

    private final AttributeService service;

    public AttributeDefinitionsController(AttributeService service) {
        this.service = service;
    }

    @GetMapping
    public List<AttributeDefinition> list() {
        return service.list();
    }

    @PutMapping
    @RequiresRole({Role.MARKETING})
    public List<AttributeDefinition> replace(@RequestBody List<AttributeDefinition> body) {
        return service.replace(body);
    }
}
