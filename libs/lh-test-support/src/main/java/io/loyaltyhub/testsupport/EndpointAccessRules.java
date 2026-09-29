package io.loyaltyhub.testsupport;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.domain.properties.HasAnnotations;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

/**
 * Deny by default verificato dalla build (F2-SEC-09, ADR-042, CLAUDE.md regola 18, docs/18 §3.10 punto 3 e §3.11):
 * ogni handler di un controller dichiara chi può chiamarlo con {@code @RequiresRole} o
 * {@code @PublicEndpoint(reason = "…")}, sul metodo o sulla classe. La regola replica ciò che applica
 * {@code EndpointAccessInterceptor}: un handler è un metodo con una mappatura di Spring MVC dichiarata sul metodo
 * stesso, su una superclasse (anche non controller) o su un'interfaccia che il controller implementa; la dichiarazione
 * vale se sta su quel metodo (o sul metodo che lo sovrascrive), oppure sulla classe, sulle sue superclassi o
 * interfacce.
 *
 * <p>Ogni modulo con controller la applica con un test di poche righe:
 * <pre>{@code
 * @Test
 * void everyEndpointDeclaresAccess() {
 *     EndpointAccessRules.check("io.loyaltyhub.wallet");
 * }
 * }</pre>
 *
 * <p>Le annotazioni di {@code lh-common} si nominano per nome, perché questo modulo non dipende da {@code lh-common}
 * (che lo usa nei propri test). Sono accettate <strong>solo</strong> {@code @RequiresRole} e {@code @PublicEndpoint}
 * (con motivo non vuoto): un'annotazione qualunque segnata {@code @EndpointAccess} non basta, perché l'interceptor non
 * la applicherebbe e l'endpoint sarebbe rifiutato a runtime.
 */
public final class EndpointAccessRules {

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
     * Ogni classe concreta {@code @Controller}/{@code @RestController} (anche per ereditarietà) ha, per ogni suo
     * handler (metodi propri, ereditati da superclassi e dichiarati da interfacce, con mappatura anche come
     * meta-annotazione), una dichiarazione valida; un {@code @PublicEndpoint} ha un motivo non vuoto; lo stesso
     * elemento non porta sia {@code @RequiresRole} sia {@code @PublicEndpoint}. Fallisce anche se non trova nessun
     * controller (package sbagliato).
     */
    public static ArchRule rule() {
        return classes()
                .that(controller())
                .should(declareAccessOnEveryHandler())
                .because("deny by default: ogni endpoint dichiara @RequiresRole o @PublicEndpoint con un motivo"
                        + " (F2-SEC-09, ADR-042, docs/06 §3.2)");
    }

    private static DescribedPredicate<JavaClass> controller() {
        return new DescribedPredicate<>("are concrete controllers") {
            @Override
            public boolean test(JavaClass c) {
                if (c.isInterface() || c.getModifiers().contains(JavaModifier.ABSTRACT)) {
                    return false;
                }
                return hierarchy(c).stream()
                        .anyMatch(t -> metaOrDirect(t, CONTROLLER) || metaOrDirect(t, REQUEST_MAPPING));
            }
        };
    }

    private static ArchCondition<JavaClass> declareAccessOnEveryHandler() {
        return new ArchCondition<>("declare @RequiresRole or @PublicEndpoint(reason) on every handler or its class") {
            @Override
            public void check(JavaClass controller, ConditionEvents events) {
                Map<String, List<JavaMethod>> chains = new LinkedHashMap<>();
                for (JavaMethod m : controller.getAllMethods()) {
                    chains.computeIfAbsent(signature(m), k -> new ArrayList<>()).add(m);
                }
                for (List<JavaMethod> chain : chains.values()) {
                    Optional<JavaMethod> mapped = chain.stream().filter(m -> metaOrDirect(m, REQUEST_MAPPING)).findFirst();
                    if (mapped.isEmpty()) {
                        continue;
                    }
                    JavaMethod first = mapped.get();
                    String where = first.getFullName()
                            + (first.getOwner().equals(controller) ? "" : " (ereditato da " + controller.getName() + ")");
                    evaluate(controller, chain, where, events);
                }
            }
        };
    }

    private static void evaluate(JavaClass controller, List<JavaMethod> chain, String where, ConditionEvents events) {
        for (JavaMethod m : chain) {
            String problem = problem(m);
            if (problem != null) {
                events.add(SimpleConditionEvent.violated(controller, where + ": " + problem));
                return;
            }
        }
        if (chain.stream().anyMatch(EndpointAccessRules::declares)) {
            events.add(SimpleConditionEvent.satisfied(controller, where + " dichiara l'accesso"));
            return;
        }
        List<JavaClass> types = hierarchy(controller);
        for (JavaClass type : types) {
            String problem = problem(type);
            if (problem != null) {
                events.add(SimpleConditionEvent.violated(controller,
                        where + " (classe " + type.getName() + "): " + problem));
                return;
            }
        }
        if (types.stream().anyMatch(EndpointAccessRules::declares)) {
            events.add(SimpleConditionEvent.satisfied(controller, where + " eredita l'accesso dalla classe"));
            return;
        }
        events.add(SimpleConditionEvent.violated(controller, where
                + " non dichiara @RequiresRole né @PublicEndpoint (sul metodo o sulla classe)"));
    }

    /** La classe, le sue superclassi e le interfacce (come cerca {@code findMergedAnnotation} sulla classe). */
    private static List<JavaClass> hierarchy(JavaClass c) {
        List<JavaClass> all = new ArrayList<>();
        all.add(c);
        all.addAll(c.getAllRawSuperclasses());
        all.addAll(c.getAllRawInterfaces());
        return all;
    }

    /** Nome e tipi dei parametri: chiave di un metodo lungo la gerarchia (sovrascritture incluse). */
    private static String signature(JavaMethod m) {
        return m.getName() + m.getRawParameterTypes().stream().map(JavaClass::getName).toList();
    }

    private static boolean metaOrDirect(HasAnnotations<?> element, String type) {
        return element.isAnnotatedWith(type) || element.isMetaAnnotatedWith(type);
    }

    /** Una dichiarazione valida per l'interceptor: solo {@code @RequiresRole} o {@code @PublicEndpoint} diretti. */
    private static boolean declares(HasAnnotations<?> element) {
        return element.isAnnotatedWith(REQUIRES_ROLE) || element.isAnnotatedWith(PUBLIC_ENDPOINT);
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
}
