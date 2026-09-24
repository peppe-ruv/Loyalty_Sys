package io.loyaltyhub.ingestion.messaging;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import io.loyaltyhub.common.event.LhEvent;
import io.loyaltyhub.common.event.LhEventFactory;
import io.loyaltyhub.common.event.LhEventTypes;
import io.loyaltyhub.common.inbox.EventHandler;
import io.loyaltyhub.common.kafka.LoopGuardException;
import io.loyaltyhub.common.outbox.OutboxWriter;
import io.loyaltyhub.ingestion.domain.InternalMapping;
import io.loyaltyhub.ingestion.infra.InboundEventRepository;
import io.loyaltyhub.ingestion.infra.InternalMappingRepository;
import io.loyaltyhub.ingestion.infra.MemberErasureRepository;
import io.loyaltyhub.ingestion.infra.MemberIndexRepository;
import io.loyaltyhub.common.privacy.PersonalData;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Fatti consumati da ingestion (docs/servizi/ingestion-service.md §4), in un solo handler perché
 * {@code member.registered} serve a entrambi gli scopi e il router ammette un handler per {@code type}:
 * <ul>
 *   <li><b>indice membri</b>: {@code member.registered/updated/status.changed} → {@code member_index};</li>
 *   <li><b>ponte interno</b> (docs/05 §7, ADR in docs/13): i fatti di {@link InternalMapping#BRIDGEABLE_FACTS} con
 *       mappatura abilitata diventano l'azione corrispondente ({@code source=internal}, stessa correlazione,
 *       {@code lhhop+1}); oltre {@code lhhop 3} → {@link LoopGuardException} → DLQ {@code LOOP_GUARD}.</li>
 * </ul>
 * I {@code type} gestiti sono fissi (non letti dal DB): il router li registra una volta all'avvio, prima del seed.
 * Gli altri fatti non sono reclamati, quindi non lasciano righe in {@code processed_event}.
 */
@Component
public class FactsHandler implements EventHandler {

    static final int MAX_HOP = 3;

    private static final Set<String> MEMBER_FACTS = Set.of(
            LhEventTypes.Fact.MEMBER_REGISTERED, LhEventTypes.Fact.MEMBER_UPDATED, LhEventTypes.Fact.MEMBER_STATUS_CHANGED);

    private final InternalMappingRepository mappings;
    private final MemberIndexRepository memberIndex;
    private final InboundEventRepository inbound;
    private final LhEventFactory events;
    private final OutboxWriter outbox;
    private final ObjectMapper mapper;
    private final MemberErasureRepository erasure;

    public FactsHandler(InternalMappingRepository mappings, MemberIndexRepository memberIndex,
                        InboundEventRepository inbound, LhEventFactory events, OutboxWriter outbox, ObjectMapper mapper,
                        MemberErasureRepository erasure) {
        this.erasure = erasure;
        this.mappings = mappings;
        this.memberIndex = memberIndex;
        this.inbound = inbound;
        this.events = events;
        this.outbox = outbox;
        this.mapper = mapper;
    }

    @Override
    public Set<String> handledTypes() {
        Set<String> types = new HashSet<>(InternalMapping.BRIDGEABLE_FACTS);
        types.addAll(MEMBER_FACTS);
        return types;
    }

    @Override
    public void handle(LhEvent<JsonNode> fact) {
        if (MEMBER_FACTS.contains(fact.type())) {
            updateMemberIndex(fact);
        }
        if (InternalMapping.BRIDGEABLE_FACTS.contains(fact.type())) {
            bridge(fact);
        }
    }

    private void updateMemberIndex(LhEvent<JsonNode> fact) {
        String memberId = memberIdOf(fact);
        JsonNode d = fact.data();
        if (memberId == null || d == null) {
            return;
        }
        // Anonimizzazione (F-MBR-05, M7.5): prima che lo snapshot ripulito sovrascriva l'indice, così le righe in
        // ingresso con subject email:/external: del membro si ritrovano e si ripuliscono.
        if (PersonalData.isAnonymization(fact)) {
            erasure.erase(memberId);
            return;
        }
        if (LhEventTypes.Fact.MEMBER_STATUS_CHANGED.equals(fact.type())) {
            String status = text(d, "newStatus");
            if (status != null) {
                memberIndex.updateStatus(memberId, status);
            }
            return;
        }
        // registered / updated portano lo snapshot completo del membro (member-service).
        memberIndex.upsert(memberId, text(d, "externalId"), text(d, "email"),
                Optional.ofNullable(text(d, "status")).orElse("ACTIVE"));
    }

    private void bridge(LhEvent<JsonNode> fact) {
        Optional<String> actionType = mappings.actionTypeFor(InternalMapping.shortType(fact.type()));
        if (actionType.isEmpty()) {
            return; // mappatura assente o disabilitata (BO-09): fatto consumato, nessuna azione
        }
        if (fact.hopOrZero() + 1 > MAX_HOP) {
            throw new LoopGuardException("Catena interna troppo profonda (lhhop " + fact.hopOrZero() + ") per " + fact.id());
        }
        LhEvent<JsonNode> action = events.bridgeAction(fact, InternalMapping.fullActionType(actionType.get()), fact.data());
        inbound.insertAccepted(action.id(), action.id(), "internal", actionType.get(), action.subject(),
                memberIdOf(action), action.time(), mapper.writeValueAsString(action),
                action.lhcorrelationid(), "INTERNAL");
        outbox.write(action);
    }

    private static String memberIdOf(LhEvent<?> e) {
        String s = e.subject();
        return s != null && s.startsWith("member:") ? s.substring("member:".length()) : null;
    }

    private static String text(JsonNode d, String field) {
        JsonNode n = d.get(field);
        return n == null || n.isNull() || n.asString("").isBlank() ? null : n.asString();
    }
}
