package io.loyaltyhub.common.web;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.EvaluationResult;
import io.loyaltyhub.testsupport.EndpointAccessRules;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

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

    /** Base che non è un controller: la mappatura passa ai controller che la estendono. */
    abstract static class MappedBase {
        @GetMapping("/h")
        public void inheritedMapping() {
        }
    }

    @RestController
    static class InheritsMappingUndeclared extends MappedBase {
    }

    @RestController
    @RequiresRole(Role.ADMIN)
    static class InheritsMappingDeclaredOnClass extends MappedBase {
    }

    @RestController
    static class OverridesMappingAndDeclares extends MappedBase {
        @Override
        @RequiresRole(Role.ADMIN)
        public void inheritedMapping() {
        }
    }

    /** Interfaccia con la mappatura: chi la implementa espone l'endpoint. */
    interface MappedApi {
        @GetMapping("/i")
        void mapped();
    }

    @RestController
    static class ImplementsMappedApiUndeclared implements MappedApi {
        @Override
        public void mapped() {
        }
    }

    @RestController
    static class ImplementsMappedApiDeclared implements MappedApi {
        @Override
        @PublicEndpoint(reason = "sonda")
        public void mapped() {
        }
    }

    /** Annotazione qualunque segnata {@code @EndpointAccess}: l'interceptor non la applica, quindi non basta. */
    @EndpointAccess
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.METHOD, ElementType.TYPE})
    @interface Fake {
    }

    @RestController
    static class FakeOnMethod {
        @GetMapping("/j")
        @Fake
        public void fake() {
        }
    }

    @RestController
    @Fake
    static class FakeOnClass {
        @GetMapping("/k")
        public void fake() {
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

    @Test
    void mappingInheritedFromANonControllerBaseIsChecked() {
        EvaluationResult result = evaluate(MappedBase.class, InheritsMappingUndeclared.class);
        assertThat(result.hasViolation()).isTrue();
        assertThat(result.getFailureReport().getDetails())
                .anySatisfy(d -> assertThat(d).contains("MappedBase.inheritedMapping()")
                        .contains("ereditato da").contains("InheritsMappingUndeclared").contains("non dichiara"));
        assertThat(evaluate(MappedBase.class, InheritsMappingDeclaredOnClass.class).hasViolation()).isFalse();
        assertThat(evaluate(MappedBase.class, OverridesMappingAndDeclares.class).hasViolation()).isFalse();
    }

    @Test
    void mappingOnAnImplementedInterfaceIsChecked() {
        EvaluationResult result = evaluate(MappedApi.class, ImplementsMappedApiUndeclared.class);
        assertThat(result.hasViolation()).isTrue();
        assertThat(result.getFailureReport().getDetails())
                .anySatisfy(d -> assertThat(d).contains("mapped()").contains("non dichiara"));
        assertThat(evaluate(MappedApi.class, ImplementsMappedApiDeclared.class).hasViolation()).isFalse();
    }

    @Test
    void onlyRequiresRoleAndPublicEndpointCountAsDeclarations() {
        assertThat(evaluate(FakeOnMethod.class).getFailureReport().getDetails())
                .anySatisfy(d -> assertThat(d).contains("FakeOnMethod.fake()").contains("non dichiara"));
        assertThat(evaluate(FakeOnClass.class).getFailureReport().getDetails())
                .anySatisfy(d -> assertThat(d).contains("FakeOnClass.fake()").contains("non dichiara"));
    }
}
