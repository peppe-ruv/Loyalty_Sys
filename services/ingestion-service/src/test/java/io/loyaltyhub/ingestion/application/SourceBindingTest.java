package io.loyaltyhub.ingestion.application;

import io.loyaltyhub.common.web.ActorContext;
import io.loyaltyhub.common.web.LhException;
import io.loyaltyhub.common.web.Role;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Legame client {@code src-<codice>} ⇔ fonte dichiarata (Q-492), senza contesto Spring. */
class SourceBindingTest {

    private static final ActorContext CRM = new ActorContext(Role.SOURCE, "src-crm");
    private final ObjectMapper mapper = new ObjectMapper();

    private static void assertMismatch(Runnable call) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(LhException.class, e -> {
            assertThat(e.status().value()).isEqualTo(403);
            assertThat(e.code()).isEqualTo("SOURCE_MISMATCH");
            assertThat(e.typeSuffix()).isEqualTo("source-mismatch");
        });
    }

    @Test
    void ownSourceInUrnOrShortFormPasses() {
        assertThatCode(() -> SourceBinding.requireMatch(CRM, "urn:loyaltyhub:source:crm")).doesNotThrowAnyException();
        assertThatCode(() -> SourceBinding.requireMatch(CRM, "crm")).doesNotThrowAnyException();
    }

    @Test
    void anotherSourceOrAnAlteredFormIsAMismatch() {
        for (String declared : new String[] {"urn:loyaltyhub:source:app", "app", "urn:loyaltyhub:source:crm:x",
                "urn:loyaltyhub:source:CRM", "urn:loyaltyhub:source:crm ", " urn:loyaltyhub:source:crm", "urn:other:crm",
                "urn:loyaltyhub:source:", "urn:loyaltyhub:source:src-crm", "src-crm"}) {
            assertMismatch(() -> SourceBinding.requireMatch(CRM, declared));
        }
    }

    @Test
    void theMessageNeverEchoesTheDeclaredValue() {
        assertThatThrownBy(() -> SourceBinding.requireMatch(CRM, "urn:loyaltyhub:source:evil-one"))
                .hasMessageNotContaining("evil-one").hasMessageNotContaining("urn:loyaltyhub");
    }

    @Test
    void aSourceClientWithoutThePrefixOrWithOnlyThePrefixHasNoSource() {
        for (String client : new String[] {"crm", "web", "src-", "SRC-crm", "service-account-src-crm"}) {
            ActorContext actor = new ActorContext(Role.SOURCE, client);
            assertMismatch(() -> SourceBinding.requireMatch(actor, "urn:loyaltyhub:source:crm"));
            assertMismatch(() -> SourceBinding.requireMatch(actor, "crm"));
        }
    }

    @Test
    void aMissingOrBlankSourceIsAFormErrorNotAMismatch() {
        assertThatCode(() -> SourceBinding.requireMatch(CRM, null)).doesNotThrowAnyException();
        assertThatCode(() -> SourceBinding.requireMatch(CRM, "  ")).doesNotThrowAnyException();
    }

    @Test
    void otherRolesAreNotSubjectToTheBinding() {
        for (Role role : new Role[] {Role.ADMIN, Role.MARKETING, Role.ANALYST}) {
            ActorContext actor = new ActorContext(role, "someone");
            assertThatCode(() -> SourceBinding.requireMatch(actor, "urn:loyaltyhub:source:app")).doesNotThrowAnyException();
        }
        assertThatCode(() -> SourceBinding.requireMatch(ActorContext.ANONYMOUS, "app")).doesNotThrowAnyException();
    }

    @Test
    void oneForeignElementRejectsTheWholeBatch() {
        var ok = mapper.readTree("[{\"source\":\"urn:loyaltyhub:source:crm\"},{\"source\":\"urn:loyaltyhub:source:crm\"}]");
        var mixed = mapper.readTree("[{\"source\":\"urn:loyaltyhub:source:crm\"},{\"source\":\"urn:loyaltyhub:source:app\"}]");
        var mixedLast = mapper.readTree("[{\"source\":\"urn:loyaltyhub:source:crm\"},{\"id\":\"x\"},{\"source\":\"app\"}]");
        assertThatCode(() -> SourceBinding.requireMatchAll(CRM, ok)).doesNotThrowAnyException();
        assertMismatch(() -> SourceBinding.requireMatchAll(CRM, mixed));
        assertMismatch(() -> SourceBinding.requireMatchAll(CRM, mixedLast));
        assertThatCode(() -> SourceBinding.requireMatchAll(new ActorContext(Role.ADMIN, "marta"), mixed)).doesNotThrowAnyException();
    }

    @Test
    void elementsWithoutASourceAreLeftToTheFormValidation() {
        var noSource = mapper.readTree("[{\"id\":\"x\"},\"not-an-object\",null,{\"source\":null},{\"source\":{}}]");
        assertThatCode(() -> SourceBinding.requireMatchAll(CRM, noSource)).doesNotThrowAnyException();
        assertThatCode(() -> SourceBinding.requireMatchAll(CRM, null)).doesNotThrowAnyException();
        assertThatCode(() -> SourceBinding.requireMatchAll(CRM, mapper.readTree("{\"source\":\"app\"}"))).doesNotThrowAnyException();
    }
}
