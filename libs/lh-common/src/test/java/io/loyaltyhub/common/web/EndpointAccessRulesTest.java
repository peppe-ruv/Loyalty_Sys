package io.loyaltyhub.common.web;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.EvaluationResult;
import io.loyaltyhub.testsupport.EndpointAccessRules;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * La regola ArchUnit del deny by default ({@link EndpointAccessRules}, F2-SEC-09) riconosce le violazioni: endpoint
 * senza dichiarazione, {@code @PublicEndpoint} senza motivo, entrambe le annotazioni sullo stesso elemento.
 */
class EndpointAccessRulesTest {

    @RestController
    static class Declared {
        @GetMapping("/a")
        @RequiresRole({Role.ADMIN, Role.MARKETING, Role.LEGAL, Role.CARE, Role.ANALYST})
        public void read() {
        }

        @PostMapping("/b")
        @PublicEndpoint(reason = "sonda")
        public void open() {
        }
    }

    @RestController
    @RequiresRole(Role.ADMIN)
    static class DeclaredOnClass {
        @GetMapping("/c")
        public void inherited() {
        }
    }

    @RestController
    static class Undeclared {
        @GetMapping("/d")
        public void missing() {
        }
    }

    @RestController
    static class BlankReason {
        @GetMapping("/e")
        @PublicEndpoint(reason = " ")
        public void blank() {
        }
    }

    @RestController
    static class Both {
        @GetMapping("/f")
        @RequiresRole(Role.ADMIN)
        @PublicEndpoint(reason = "ambiguo")
        public void both() {
        }
    }

    @RestController
    @PublicEndpoint(reason = "")
    static class BlankReasonOnClass {
        @GetMapping("/g")
        public void inherited() {
        }
    }

    private static EvaluationResult evaluate(Class<?>... classes) {
        return EndpointAccessRules.rule().evaluate(new ClassFileImporter().importClasses(classes));
    }

    @Test
    void declaredEndpointsPass() {
        assertThat(evaluate(Declared.class, DeclaredOnClass.class).hasViolation()).isFalse();
    }

    @Test
    void undeclaredEndpointFails() {
        EvaluationResult result = evaluate(Declared.class, Undeclared.class);
        assertThat(result.hasViolation()).isTrue();
        assertThat(result.getFailureReport().getDetails())
                .anySatisfy(d -> assertThat(d).contains("Undeclared.missing()").contains("non dichiara"));
    }

    @Test
    void publicEndpointNeedsAReason() {
        assertThat(evaluate(BlankReason.class).getFailureReport().getDetails())
                .anySatisfy(d -> assertThat(d).contains("senza motivo"));
        assertThat(evaluate(BlankReasonOnClass.class).getFailureReport().getDetails())
                .anySatisfy(d -> assertThat(d).contains("senza motivo"));
    }

    @Test
    void bothAnnotationsOnTheSameElementFail() {
        assertThat(evaluate(Both.class).getFailureReport().getDetails())
                .anySatisfy(d -> assertThat(d).contains("sia @RequiresRole sia @PublicEndpoint"));
    }
}
