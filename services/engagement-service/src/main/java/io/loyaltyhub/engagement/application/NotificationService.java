package io.loyaltyhub.engagement.application;

import io.loyaltyhub.common.event.LhEvent;
import io.loyaltyhub.common.event.LhEventTypes;
import io.loyaltyhub.engagement.domain.DataCondition;
import io.loyaltyhub.engagement.domain.InboxMessage;
import io.loyaltyhub.engagement.domain.MessageTemplate;
import io.loyaltyhub.engagement.domain.NotificationRule;
import io.loyaltyhub.engagement.infra.RuleRepository;
import io.loyaltyhub.engagement.infra.TemplateRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Regole di notifica (docs/03 §9, docs/servizi/engagement-service.md §5; F-MSG-01): per un fatto del membro, ogni regola
 * attiva sul suo tipo la cui condizione su {@code data.*} è vera rende il proprio template e lo consegna. Il fatto
 * {@code message.delivered} non è mai oggetto di regole (niente cicli).
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final RuleRepository rules;
    private final TemplateRepository templates;
    private final InboxService inbox;

    public NotificationService(RuleRepository rules, TemplateRepository templates, InboxService inbox) {
        this.rules = rules;
        this.templates = templates;
        this.inbox = inbox;
    }

    /** Applica le regole al fatto; ritorna i messaggi consegnati ora (vuoto se nessuna regola o duplicati). */
    public List<InboxMessage> apply(LhEvent<JsonNode> fact) {
        String memberId = fact.memberId();
        if (memberId == null || LhEventTypes.Fact.MESSAGE_DELIVERED.equals(fact.type())) {
            return List.of();
        }

        List<NotificationRule> matchedRules = new ArrayList<>();
        for (NotificationRule rule : rules.findEnabledFor(MessageContexts.shortType(fact.type()))) {
            if (DataCondition.matches(rule.condition(), fact.data())) {
                matchedRules.add(rule);
            }
        }

        if (matchedRules.isEmpty()) {
            return List.of();
        }

        // Una sola lettura per tutti i template delle regole corrispondenti (niente N+1). L'ordine di consegna resta
        // quello di matchedRules; il merge è solo difensivo: code è chiave primaria, quindi non ci sono doppioni.
        Set<String> templateCodes = matchedRules.stream().map(NotificationRule::templateCode).collect(Collectors.toSet());
        Map<String, MessageTemplate> templateMap = templates.findByCodes(templateCodes).stream()
                .collect(Collectors.toMap(MessageTemplate::code, Function.identity(), (a, b) -> a));

        List<InboxMessage> out = new ArrayList<>();
        for (NotificationRule rule : matchedRules) {
            MessageTemplate template = templateMap.get(rule.templateCode());
            if (template == null) {
                log.warn("Regola {}: template {} inesistente, nessun messaggio", rule.code(), rule.templateCode());
                continue;
            }
            inbox.deliver(memberId, template, fact.data(), fact, fact.id()).ifPresent(out::add);
        }
        return out;
    }
}
