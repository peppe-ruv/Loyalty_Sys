package io.loyaltyhub.member.application;

import io.loyaltyhub.common.audit.AuditEntry;
import io.loyaltyhub.common.audit.AuditPublisher;
import io.loyaltyhub.common.web.LhException;
import io.loyaltyhub.member.domain.AttributeDefinition;
import io.loyaltyhub.member.domain.MemberAttributes;
import io.loyaltyhub.member.infra.AttributeDefinitionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Definizioni degli attributi personalizzati ({@code GET/PUT /v1/attribute-definitions}, F-MBR-03, M6.7): il PUT
 * sostituisce l'elenco intero. Una chiave con valori sui membri non si toglie né cambia tipo
 * ({@code 409 ATTRIBUTE_IN_USE}). SPEC-GAP: Q-93 — la scheda non dice cosa succede ai valori esistenti: scelta la
 * più conservativa (nessun valore perso o reinterpretato).
 */
@Service
public class AttributeService {

    private final AttributeDefinitionRepository definitions;
    private final AuditPublisher audit;

    public AttributeService(AttributeDefinitionRepository definitions, AuditPublisher audit) {
        this.definitions = definitions;
        this.audit = audit;
    }

    public List<AttributeDefinition> list() {
        return definitions.findAll();
    }

    @Transactional
    public List<AttributeDefinition> replace(List<AttributeDefinition> requested) {
        List<AttributeDefinition> defs = requested == null ? List.of() : requested.stream()
                .map(d -> new AttributeDefinition(trim(d.key()), trim(d.label()), d.type(), d.options() == null ? null
                        : d.options().stream().map(AttributeService::trim).toList()))
                .toList();
        List<MemberAttributes.Problem> problems = MemberAttributes.validateDefinitions(defs);
        if (!problems.isEmpty()) {
            throw LhException.validation("ATTRIBUTE_DEFINITION_INVALID", "Definizioni degli attributi non valide.",
                    problems.stream().map(p -> new LhException.FieldError(p.field(), p.message())).toList());
        }
        Map<String, AttributeDefinition> before = definitions.byKey();
        List<String> blocked = new ArrayList<>();
        for (AttributeDefinition old : before.values()) {
            AttributeDefinition now = defs.stream().filter(d -> d.key().equals(old.key())).findFirst().orElse(null);
            boolean removedOrRetyped = now == null || !now.type().equals(old.type());
            if (removedOrRetyped && definitions.membersUsing(old.key()) > 0) {
                blocked.add(old.key());
            }
        }
        if (!blocked.isEmpty()) {
            throw LhException.conflict("ATTRIBUTE_IN_USE", "Attributi con valori sui membri: non si tolgono né cambiano tipo ("
                    + String.join(", ", blocked) + ").");
        }
        definitions.replaceAll(defs);
        audit.record("attribute_definition", "all", AuditEntry.Action.UPDATE,
                "Aggiornati gli attributi personalizzati (" + defs.size() + ")", before.values(), defs);
        return definitions.findAll();
    }

    private static String trim(String s) {
        return s == null ? null : s.trim();
    }
}
