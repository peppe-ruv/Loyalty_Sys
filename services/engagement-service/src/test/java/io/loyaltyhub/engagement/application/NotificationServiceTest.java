package io.loyaltyhub.engagement.application;

import io.loyaltyhub.common.event.LhEvent;
import io.loyaltyhub.common.event.LhEventTypes;
import io.loyaltyhub.engagement.domain.InboxMessage;
import io.loyaltyhub.engagement.domain.MessageTemplate;
import io.loyaltyhub.engagement.domain.NotificationRule;
import io.loyaltyhub.engagement.infra.RuleRepository;
import io.loyaltyhub.engagement.infra.TemplateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Regole di notifica (docs/03 §9, docs/servizi/engagement-service.md §5; F-MSG-01, docs/06 §9): i template delle regole
 * corrispondenti si leggono con una sola {@code findByCodes} (mai {@code find} per regola), la consegna segue l'ordine
 * delle regole, una condizione falsa esclude la regola dalla lettura, più regole sullo stesso template restano
 * consegne distinte e un template inesistente salta solo la propria regola. Senza database: repository finti.
 */
class NotificationServiceTest {

    private static final String FACT_TYPE = "io.loyaltyhub.fact.coupon.used";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RuleRepository rules;
    private TemplateRepository templates;
    private InboxService inbox;
    private NotificationService service;

    @BeforeEach
    void setUp() {
        rules = mock(RuleRepository.class);
        templates = mock(TemplateRepository.class);
        inbox = mock(InboxService.class);
        service = new NotificationService(rules, templates, inbox);
        when(inbox.deliver(anyString(), any(), any(), any(), anyString())).thenAnswer(inv -> {
            MessageTemplate t = inv.getArgument(1);
            return Optional.of(message(t.code()));
        });
    }

    @Test
    void readsAllTemplatesWithOneQueryAndDeliversInRuleOrder() {
        when(rules.findEnabledFor("coupon.used")).thenReturn(List.of(
                rule("NR-1", "TPL-B", null),
                rule("NR-2", "TPL-A", json("{\"field\":\"data.role\",\"cmp\":\"eq\",\"value\":\"NONE\"}")),
                rule("NR-3", "TPL-C", json("{\"field\":\"data.role\",\"cmp\":\"eq\",\"value\":\"REFERRER\"}"))));
        when(templates.findByCodes(any())).thenReturn(List.of(template("TPL-C"), template("TPL-B"), template("TPL-A")));

        List<InboxMessage> out = service.apply(fact(FACT_TYPE, "member:MBR-1"));

        assertThat(out).extracting(InboxMessage::templateCode).as("ordine delle regole, non del database")
                .containsExactly("TPL-B", "TPL-C");
        ArgumentCaptor<Collection<String>> codes = ArgumentCaptor.captor();
        verify(templates, times(1)).findByCodes(codes.capture());
        assertThat(codes.getValue()).as("la regola con condizione falsa non entra nella lettura")
                .containsExactlyInAnyOrder("TPL-B", "TPL-C");
        verify(templates, never()).find(any());
    }

    @Test
    void twoRulesOnTheSameTemplateAreDeliveredOneByOne() {
        when(rules.findEnabledFor("coupon.used")).thenReturn(List.of(rule("NR-1", "TPL-A", null), rule("NR-2", "TPL-A", null)));
        when(templates.findByCodes(any())).thenReturn(List.of(template("TPL-A")));

        List<InboxMessage> out = service.apply(fact(FACT_TYPE, "member:MBR-1"));

        assertThat(out).extracting(InboxMessage::templateCode).containsExactly("TPL-A", "TPL-A");
        ArgumentCaptor<Collection<String>> codes = ArgumentCaptor.captor();
        verify(templates, times(1)).findByCodes(codes.capture());
        assertThat(codes.getValue()).as("un solo codice, letto una volta").containsExactly("TPL-A");
        verify(inbox, times(2)).deliver(eq("MBR-1"), any(MessageTemplate.class), any(), any(), eq("EVT-1"));
    }

    @Test
    void duplicateDeliveriesAreNotReturned() {
        when(rules.findEnabledFor("coupon.used")).thenReturn(List.of(rule("NR-1", "TPL-A", null), rule("NR-2", "TPL-B", null)));
        when(templates.findByCodes(any())).thenReturn(List.of(template("TPL-A"), template("TPL-B")));
        doAnswer(inv -> {
            MessageTemplate t = inv.getArgument(1);
            return "TPL-A".equals(t.code()) ? Optional.empty() : Optional.of(message(t.code()));
        }).when(inbox).deliver(anyString(), any(), any(), any(), anyString());

        assertThat(service.apply(fact(FACT_TYPE, "member:MBR-1"))).extracting(InboxMessage::templateCode)
                .containsExactly("TPL-B");
    }

    @Test
    void missingTemplateSkipsOnlyItsRule() {
        when(rules.findEnabledFor("coupon.used")).thenReturn(List.of(rule("NR-1", "TPL-GONE", null), rule("NR-2", "TPL-A", null)));
        when(templates.findByCodes(any())).thenReturn(List.of(template("TPL-A")));

        assertThat(service.apply(fact(FACT_TYPE, "member:MBR-1"))).extracting(InboxMessage::templateCode)
                .containsExactly("TPL-A");
    }

    @Test
    void noMatchingRuleReadsNoTemplate() {
        when(rules.findEnabledFor("coupon.used")).thenReturn(List.of(
                rule("NR-1", "TPL-A", json("{\"field\":\"data.role\",\"cmp\":\"eq\",\"value\":\"NONE\"}"))));

        assertThat(service.apply(fact(FACT_TYPE, "member:MBR-1"))).isEmpty();
        verify(templates, never()).findByCodes(any());
        verify(templates, never()).find(any());
        verifyNoInteractions(inbox);
    }

    @Test
    void deliveredFactsAndFactsWithoutMemberAreNeverRuleSubjects() {
        assertThat(service.apply(fact(LhEventTypes.Fact.MESSAGE_DELIVERED, "member:MBR-1"))).isEmpty();
        assertThat(service.apply(fact(FACT_TYPE, "program:aurora"))).isEmpty();
        verifyNoInteractions(rules, templates, inbox);
    }

    // ---------- helper ----------

    private static JsonNode json(String s) {
        return MAPPER.readTree(s);
    }

    private static NotificationRule rule(String code, String templateCode, JsonNode condition) {
        return new NotificationRule("ID-" + code, code, "coupon.used", condition, templateCode, true, 0, Instant.EPOCH, "test");
    }

    private static MessageTemplate template(String code) {
        return new MessageTemplate(code, code, "INAPP", "t", "b", null, null, "REWARD", 0, Instant.EPOCH, "test");
    }

    private static InboxMessage message(String templateCode) {
        return new InboxMessage("MSG-" + templateCode, "MBR-1", templateCode, "INAPP", "t", "b", null, null, "REWARD",
                "EVT-1", "coupon.used", "EVT-1", Instant.EPOCH, null);
    }

    private static LhEvent<JsonNode> fact(String type, String subject) {
        return new LhEvent<>(LhEvent.SPEC_VERSION, "EVT-1", "urn:loyaltyhub:test", type, subject, Instant.EPOCH,
                LhEvent.DATA_CONTENT_TYPE, null, LhEvent.TENANT, null, null, 0, null,
                json("{\"role\":\"REFERRER\"}"));
    }
}
