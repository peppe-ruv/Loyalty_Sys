package io.loyaltyhub.testsupport;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.properties.HasAnnotations;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

import java.util.Optional;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;

/**
 * Deny by default verificato dalla build (F2-SEC-09, ADR-042, CLAUDE.md regola 18, docs/18 §3.10 punto 3 e §3.11):
 * ogni metodo mappato di un controller dichiara chi può chiamarlo con {@code @RequiresRole} o
 * {@code @PublicEndpoint(reason = "…")}, sul metodo o sulla classe.
 *
 * <p>Ogni modulo con controller lo applica con un test di poche righe:
 * <pre>{@code
 * @Test
 * void everyEndpointDeclaresAccess() {
 *     EndpointAccessRules.check("io.loyaltyhub.wallet");
 * }
 * }</pre>
 *
 * <p>Le annotazioni di {@code lh-common} si nominano per nome, perché questo modulo non dipende da {@code lh-common}
 * (che lo usa nei propri test). Una dichiarazione è un'annotazione segnata con {@code @EndpointAccess}: la regola
 * accetta anche le dichiarazioni future (per esempio quella del membro dal token, Q-410) senza cambiare qui.
 */
public final class EndpointAccessRules {

    /** Meta-annotazione che segna una dichiarazione di accesso. */
    public static final String ENDPOINT_ACCESS = "io.loyaltyhub.common.web.EndpointAccess";
    public static final String REQUIRES_ROLE = "io.loyaltyhub.common.web.RequiresRole";
    public static final String PUBLIC_ENDPOINT = "io.loyaltyhub.common.web.PublicEndpoint";

    private static final String CONTROLLER = "org.springframework.stereotype.Controller";
    private static final String REQUEST_MAPPING = "org.springframework.web.bind.annotation.RequestMapping";

    private EndpointAccessRules() {
    }

    /** Importa le classi di produzione dei package indicati (niente classi di test) e applica {@link #rule()}. */
    public static void check(String... packages) {
        rule().check(importMainClasses(packages));
    }

    /** Classi di produzione dei package indicati, senza le classi di test. */
    public static JavaClasses importMainClasses(String... packages) {
        return new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(packages);
    }

    /**
     * Ogni metodo annotato con una mappatura di Spring MVC ({@code @GetMapping}, {@code @PostMapping}…, anche come
     * meta-annotazione) in una classe {@code @Controller} o {@code @RestController} dichiara l'accesso; un
     * {@code @PublicEndpoint} ha un motivo non vuoto; lo stesso elemento non porta sia {@code @RequiresRole} sia
     * {@code @PublicEndpoint}. Fallisce anche se non trova nessun endpoint (package sbagliato).
     */
    public static ArchRule rule() {
        return methods()
                .that(annotated(REQUEST_MAPPING))
                .and().areDeclaredInClassesThat(controller())
                .should(declareAccess())
                .because("deny by default: ogni endpoint dichiara @RequiresRole o @PublicEndpoint con un motivo"
                        + " (F2-SEC-09, ADR-042, docs/06 §3.2)");
    }

    private static DescribedPredicate<HasAnnotations<?>> annotated(String type) {
        return new DescribedPredicate<>("are annotated or meta-annotated with @" + simple(type)) {
            @Override
            public boolean test(HasAnnotations<?> element) {
                return element.isAnnotatedWith(type) || element.isMetaAnnotatedWith(type);
            }
        };
    }

    private static DescribedPredicate<JavaClass> controller() {
        return new DescribedPredicate<>("are controllers") {
            @Override
            public boolean test(JavaClass c) {
                return c.isAnnotatedWith(CONTROLLER) || c.isMetaAnnotatedWith(CONTROLLER);
            }
        };
    }

    private static ArchCondition<JavaMethod> declareAccess() {
        return new ArchCondition<>("declare @RequiresRole or @PublicEndpoint(reason) on the method or its class") {
            @Override
            public void check(JavaMethod method, ConditionEvents events) {
                String where = method.getFullName();
                String methodProblem = problem(method);
                if (methodProblem != null) {
                    events.add(SimpleConditionEvent.violated(method, where + ": " + methodProblem));
                    return;
                }
                if (declares(method)) {
                    events.add(SimpleConditionEvent.satisfied(method, where + " dichiara l'accesso"));
                    return;
                }
                String classProblem = problem(method.getOwner());
                if (classProblem != null) {
                    events.add(SimpleConditionEvent.violated(method, where + " (classe): " + classProblem));
                    return;
                }
                if (declares(method.getOwner())) {
                    events.add(SimpleConditionEvent.satisfied(method, where + " eredita l'accesso dalla classe"));
                    return;
                }
                events.add(SimpleConditionEvent.violated(method, where
                        + " non dichiara @RequiresRole né @PublicEndpoint (sul metodo o sulla classe)"));
            }
        };
    }

    /** Una dichiarazione di accesso: un'annotazione segnata con {@code @EndpointAccess}. */
    private static boolean declares(HasAnnotations<?> element) {
        return element.isMetaAnnotatedWith(ENDPOINT_ACCESS);
    }

    /** Problema di una dichiarazione su un elemento, o {@code null}. */
    private static String problem(HasAnnotations<?> element) {
        boolean role = element.isAnnotatedWith(REQUIRES_ROLE);
        Optional<? extends JavaAnnotation<?>> open = element.tryGetAnnotationOfType(PUBLIC_ENDPOINT);
        if (role && open.isPresent()) {
            return "porta sia @RequiresRole sia @PublicEndpoint: scegline una";
        }
        if (open.isPresent()) {
            Object reason = open.get().get("reason").orElse("");
            if (reason.toString().isBlank()) {
                return "@PublicEndpoint senza motivo: reason è obbligatoria e non vuota";
            }
        }
        return null;
    }

    private static String simple(String type) {
        return type.substring(type.lastIndexOf('.') + 1);
    }
}
