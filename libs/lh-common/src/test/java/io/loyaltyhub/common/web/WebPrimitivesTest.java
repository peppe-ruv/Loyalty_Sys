package io.loyaltyhub.common.web;

import io.loyaltyhub.common.event.LhEventTypes;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WebPrimitivesTest {

    @Test
    void parsesActorHeader() {
        ActorContext a = ActorContext.parse("MARKETING:luca.marketing");
        assertThat(a.role()).isEqualTo(Role.MARKETING);
        assertThat(a.username()).isEqualTo("luca.marketing");
        assertThat(a.asActorString()).isEqualTo("MARKETING:luca.marketing");
    }

    @Test
    void missingHeaderIsAnonymousAnalyst() {
        assertThat(ActorContext.parse(null)).isEqualTo(ActorContext.ANONYMOUS);
        assertThat(ActorContext.parse("  ")).isEqualTo(ActorContext.ANONYMOUS);
        assertThat(ActorContext.ANONYMOUS.role().isReadOnly()).isTrue();
    }

    @Test
    void unknownRoleFallsBackToAnalyst() {
        assertThat(ActorContext.parse("WIZARD:merlin").role()).isEqualTo(Role.ANALYST);
    }

    @Test
    void parsesSourceActorHeaderAndDerivesTheSourceCode() {
        ActorContext a = ActorContext.parse("SOURCE:src-crm");
        assertThat(a.role()).isEqualTo(Role.SOURCE);
        assertThat(a.username()).isEqualTo("src-crm");
        assertThat(a.asActorString()).isEqualTo("SOURCE:src-crm");
        assertThat(a.sourceCode()).contains("crm");
        assertThat(Role.SOURCE.isReadOnly()).isFalse();
        assertThat(Role.SOURCE.isIntegration()).isTrue();
        assertThat(Role.ADMIN.isIntegration()).isFalse();
    }

    @Test
    void sourceCodeIsEmptyForAnythingButAPrefixedSourceClient() {
        assertThat(ActorContext.parse("SOURCE:crm").sourceCode()).isEmpty();
        assertThat(ActorContext.parse("SOURCE:src-").sourceCode()).isEmpty();
        assertThat(ActorContext.parse("SOURCE:web").sourceCode()).isEmpty();
        assertThat(ActorContext.parse("ADMIN:src-crm").sourceCode()).isEmpty();
        assertThat(ActorContext.parse("source:src-crm").role()).isEqualTo(Role.ANALYST);
        assertThat(ActorContext.ANONYMOUS.sourceCode()).isEmpty();
    }

    @Test
    void pageResponseComputesTotalPages() {
        PageResponse<String> page = PageResponse.of(List.of("a", "b"), 0, 20, 41);
        assertThat(page.page().totalPages()).isEqualTo(3);
        assertThat(page.page().totalItems()).isEqualTo(41);
        assertThat(page.items()).containsExactly("a", "b");
    }

    @Test
    void eventFamilyIsDerivedFromType() {
        assertThat(io.loyaltyhub.common.event.LhFamily.of(LhEventTypes.Effect.POINTS_GRANT))
                .isEqualTo(io.loyaltyhub.common.event.LhFamily.EFFECT);
        assertThat(io.loyaltyhub.common.event.LhFamily.of("pippo")).isNull();
    }
}
