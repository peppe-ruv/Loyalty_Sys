package io.loyaltyhub.testsupport;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaCall;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.domain.JavaParameter;
import com.tngtech.archunit.core.domain.properties.HasAnnotations;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

/**
 * Deny by default verificato dalla build (F2-SEC-09, ADR-042, CLAUDE.md regola 18, docs/18 §3.10 punto 3 e §3.11):
 * ogni handler di un controller dichiara chi può chiamarlo con {@code @RequiresRole}, con
 * {@code @PublicEndpoint(reason = "…")} o con {@code @MemberEndpoint} (il membro dal token, Q-410, ADR-048), sul metodo o
 * sulla classe: l'elenco delle dichiarazioni è chiuso a tre. La regola replica ciò che applica
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
 * (che lo usa nei propri test). Sono accettate <strong>solo</strong> {@code @RequiresRole}, {@code @PublicEndpoint}
 * (con motivo non vuoto) e {@code @MemberEndpoint}: un'annotazione qualunque segnata {@code @EndpointAccess} non basta,
 * perché l'interceptor non la applicherebbe e l'endpoint sarebbe rifiutato a runtime.
 *
 * <p>{@link #rule()} verifica anche il membro dal token (Q-410, ADR-048): {@code @MemberEndpoint} non convive con
 * {@code @RequiresRole} o {@code @PublicEndpoint}; un parametro {@code MemberPrincipal} o {@code MemberSubject} richiede
 * {@code @MemberEndpoint} col modo giusto (un solo {@code MemberPrincipal} per {@code REQUIRED} e {@code OPTIONAL}, un
 * solo {@code MemberSubject} per {@code REGISTRATION}) e l'handler lo usa; un handler del membro non lega
 * {@code memberId} né {@code X-LH-Member} con un nome esplicito ({@code @RequestParam}, {@code @PathVariable},
 * {@code @RequestHeader}, {@code @CookieValue}); {@code @RequiresRole(members = true)} è ammesso solo su {@code GET} sotto
 * {@code /v1/portal/}, non sotto {@code /v1/portal/me}. ArchUnit non conosce i nomi <em>impliciti</em> dei parametri
 * ({@code @RequestParam String memberId}): li copre il controllo dei handler registrati nell'hub ({@code OpenApiExportIT},
 * nomi a runtime con {@code -parameters}).
 *
 * <p>{@link #portalRule()} (opt-in con {@link #checkPortal}, dall'adozione del membro dal token in ogni servizio):
 * ogni handler con percorso {@code /v1/portal/**} è {@code @MemberEndpoint} oppure
 * {@code @RequiresRole(members = true)} senza parametri legati alla richiesta.
 */
public final class EndpointAccessRules {

    public static final String REQUIRES_ROLE = "io.loyaltyhub.common.web.RequiresRole";
    public static final String PUBLIC_ENDPOINT = "io.loyaltyhub.common.web.PublicEndpoint";
    public static final String MEMBER_ENDPOINT = "io.loyaltyhub.common.web.MemberEndpoint";
    public static final String MEMBER_PRINCIPAL = "io.loyaltyhub.common.web.MemberPrincipal";
    public static final String MEMBER_SUBJECT = "io.loyaltyhub.common.web.MemberSubject";

    /** Le sole dichiarazioni di accesso ammesse: l'elenco è chiuso (F2-SEC-09, ADR-048). */
    public static final List<String> DECLARATIONS = List.of(REQUIRES_ROLE, PUBLIC_ENDPOINT, MEMBER_ENDPOINT);

    private static final String PORTAL_PREFIX = "/v1/portal";
    private static final String CONTROLLER = "org.springframework.stereotype.Controller";
    private static final String WEB_BIND = "org.springframework.web.bind.annotation.";
    private static final String REQUEST_MAPPING = WEB_BIND + "RequestMapping";
    /** Mappature con il verbo HTTP implicito. */
    private static final Map<String, String> VERB_MAPPINGS = Map.of(
            WEB_BIND + "GetMapping", "GET", WEB_BIND + "PostMapping", "POST", WEB_BIND + "PutMapping", "PUT",
            WEB_BIND + "DeleteMapping", "DELETE", WEB_BIND + "PatchMapping", "PATCH");
    /** Annotazioni che legano un parametro a un nome della richiesta, con nome esplicito in {@code name}/{@code value}. */
    private static final List<String> NAMED_BINDINGS = List.of(WEB_BIND + "RequestParam", WEB_BIND + "PathVariable",
            WEB_BIND + "RequestHeader", WEB_BIND + "CookieValue");
    /** Annotazioni che legano un parametro alla richiesta (regola del portale). */
    private static final List<String> REQUEST_BINDINGS = List.of(WEB_BIND + "RequestParam", WEB_BIND + "PathVariable",
            WEB_BIND + "RequestBody", WEB_BIND + "RequestHeader", WEB_BIND + "CookieValue", WEB_BIND + "ModelAttribute",
            WEB_BIND + "RequestPart", WEB_BIND + "RequestAttribute", WEB_BIND + "SessionAttribute",
            WEB_BIND + "MatrixVariable");
    /** Tipi di framework che un parametro non annotato può avere senza legare dati della richiesta. */
    private static final List<String> FRAMEWORK_TYPES = List.of("jakarta.servlet.", "org.springframework.", "java.security.",
            "java.util.Locale", "java.io.", "java.time.ZoneId", "java.util.TimeZone");

    private EndpointAccessRules() {
    }

    /** Importa le classi di produzione dei package indicati (niente classi di test) e applica {@link #rule()}. */
    public static void check(String... packages) {
        rule().check(importMainClasses(packages));
    }

    /**
     * Come {@link #check} e in più {@link #portalRule()}: ogni handler {@code /v1/portal/**} è del membro dal token o
     * una lettura aperta ai membri senza parametri legati alla richiesta. Da adottare per servizio quando i suoi
     * controller del portale passano a {@code @MemberEndpoint} (M8.10f); poi diventa predefinita in {@link #check}.
     */
    public static void checkPortal(String... packages) {
        JavaClasses classes = importMainClasses(packages);
        rule().check(classes);
        portalRule().check(classes);
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
     * elemento non porta due dichiarazioni incompatibili ({@code @MemberEndpoint} con {@code @RequiresRole} o
     * {@code @PublicEndpoint}; {@code @RequiresRole} con {@code @PublicEndpoint}); le regole del membro dal token
     * (parametri, nomi espliciti, {@code members = true}) sono rispettate. Fallisce anche se non trova nessun
     * controller (package sbagliato).
     */
    public static ArchRule rule() {
        return classes()
                .that(controller())
                .should(declareAccessOnEveryHandler())
                .because("deny by default: ogni endpoint dichiara @RequiresRole, @PublicEndpoint con un motivo o"
                        + " @MemberEndpoint (F2-SEC-09, ADR-042, ADR-048, docs/06 §3.2)");
    }

    /**
     * Regola del portale (opt-in, {@link #checkPortal}): ogni handler con percorso composto {@code /v1/portal/**} è
     * {@code @MemberEndpoint} oppure {@code @RequiresRole(members = true)} senza parametri legati alla richiesta. Qualunque
     * parametro legato alla richiesta, compresi gli id di oggetti, richiede quindi {@code @MemberEndpoint}.
     */
    public static ArchRule portalRule() {
        return classes()
                .that(controller())
                .should(keepThePortalOnTheMemberFromTheToken())
                .because("il membro viene solo dal token: ogni handler del portale è @MemberEndpoint o una lettura"
                        + " @RequiresRole(members = true) senza parametri della richiesta (Q-410, ADR-048)");
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

    /** Un handler: la catena dei metodi con la stessa firma lungo la gerarchia (sovrascritture incluse), con la mappatura. */
    private record Handler(JavaClass controller, List<JavaMethod> chain, JavaMethod mapped, String where) {
        /** Il metodo più derivato: quello che Spring MVC invoca. */
        JavaMethod implementation() {
            return chain.get(0);
        }
    }

    private static List<Handler> handlers(JavaClass controller) {
        Map<String, List<JavaMethod>> chains = new LinkedHashMap<>();
        for (JavaMethod m : controller.getAllMethods()) {
            chains.computeIfAbsent(signature(m), k -> new ArrayList<>()).add(m);
        }
        List<Handler> out = new ArrayList<>();
        for (List<JavaMethod> chain : chains.values()) {
            Optional<JavaMethod> mapped = chain.stream().filter(m -> metaOrDirect(m, REQUEST_MAPPING)).findFirst();
            if (mapped.isEmpty()) {
                continue;
            }
            JavaMethod first = mapped.get();
            String where = first.getFullName()
                    + (first.getOwner().equals(controller) ? "" : " (ereditato da " + controller.getName() + ")");
            out.add(new Handler(controller, chain, first, where));
        }
        return out;
    }

    private static ArchCondition<JavaClass> declareAccessOnEveryHandler() {
        return new ArchCondition<>("declare @RequiresRole, @PublicEndpoint(reason) or @MemberEndpoint on every handler or its class") {
            @Override
            public void check(JavaClass controller, ConditionEvents events) {
                for (Handler handler : handlers(controller)) {
                    evaluate(handler, events);
                }
            }
        };
    }

    private static void evaluate(Handler handler, ConditionEvents events) {
        JavaClass controller = handler.controller();
        String where = handler.where();
        for (JavaMethod m : handler.chain()) {
            String problem = problem(m);
            if (problem != null) {
                events.add(SimpleConditionEvent.violated(controller, where + ": " + problem));
                return;
            }
        }
        HasAnnotations<?> declaring = handler.chain().stream().filter(EndpointAccessRules::declares).findFirst()
                .<HasAnnotations<?>>map(m -> m).orElse(null);
        String inherited = "";
        if (declaring == null) {
            List<JavaClass> types = hierarchy(controller);
            for (JavaClass type : types) {
                String problem = problem(type);
                if (problem != null) {
                    events.add(SimpleConditionEvent.violated(controller,
                            where + " (classe " + type.getName() + "): " + problem));
                    return;
                }
            }
            declaring = types.stream().filter(EndpointAccessRules::declares).findFirst().orElse(null);
            inherited = " (dalla classe)";
        }
        // Un parametro del membro senza @MemberEndpoint è un errore anche se l'handler dichiara altro.
        List<String> problems = new ArrayList<>(memberParameterProblems(handler, declaring));
        if (declaring == null) {
            events.add(SimpleConditionEvent.violated(controller, where
                    + " non dichiara @RequiresRole, @PublicEndpoint né @MemberEndpoint (sul metodo o sulla classe)"));
            return;
        }
        if (declaring.isAnnotatedWith(MEMBER_ENDPOINT)) {
            problems.addAll(memberEndpointProblems(handler, declaring));
        }
        if (declaring.isAnnotatedWith(REQUIRES_ROLE) && roleMembers(declaring)) {
            problems.addAll(memberReadProblems(handler));
        }
        if (!problems.isEmpty()) {
            for (String problem : problems) {
                events.add(SimpleConditionEvent.violated(controller, where + ": " + problem));
            }
            return;
        }
        events.add(SimpleConditionEvent.satisfied(controller, where + " dichiara l'accesso" + inherited));
    }

    private static ArchCondition<JavaClass> keepThePortalOnTheMemberFromTheToken() {
        return new ArchCondition<>("keep every /v1/portal/** handler on @MemberEndpoint or a parameterless @RequiresRole(members = true) read") {
            @Override
            public void check(JavaClass controller, ConditionEvents events) {
                for (Handler handler : handlers(controller)) {
                    if (!paths(handler).stream().anyMatch(p -> p.equals(PORTAL_PREFIX) || p.startsWith(PORTAL_PREFIX + "/"))) {
                        continue;
                    }
                    HasAnnotations<?> declaring = declaringElement(handler);
                    if (declaring != null && declaring.isAnnotatedWith(MEMBER_ENDPOINT)) {
                        events.add(SimpleConditionEvent.satisfied(controller, handler.where() + " è @MemberEndpoint"));
                    } else if (declaring != null && declaring.isAnnotatedWith(REQUIRES_ROLE) && roleMembers(declaring)) {
                        String bound = boundParameter(handler);
                        events.add(bound == null
                                ? SimpleConditionEvent.satisfied(controller, handler.where() + " è una lettura per i membri")
                                : SimpleConditionEvent.violated(controller, handler.where() + ": una lettura"
                                        + " @RequiresRole(members = true) non ha parametri legati alla richiesta ("
                                        + bound + "): usa @MemberEndpoint"));
                    } else {
                        events.add(SimpleConditionEvent.violated(controller, handler.where()
                                + ": un handler sotto /v1/portal/ è @MemberEndpoint o @RequiresRole(members = true),"
                                + " il membro viene solo dal token"));
                    }
                }
            }
        };
    }

    // ---- dichiarazione effettiva ----

    /** L'elemento che dichiara l'accesso: il primo metodo della catena che dichiara, altrimenti la prima classe. */
    private static HasAnnotations<?> declaringElement(Handler handler) {
        for (JavaMethod m : handler.chain()) {
            if (declares(m)) {
                return m;
            }
        }
        for (JavaClass type : hierarchy(handler.controller())) {
            if (declares(type)) {
                return type;
            }
        }
        return null;
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

    /** Una dichiarazione valida per l'interceptor: solo una delle tre annotazioni dirette ({@link #DECLARATIONS}). */
    private static boolean declares(HasAnnotations<?> element) {
        return DECLARATIONS.stream().anyMatch(element::isAnnotatedWith);
    }

    /** Problema di una dichiarazione su un elemento, o {@code null}. */
    private static String problem(HasAnnotations<?> element) {
        boolean role = element.isAnnotatedWith(REQUIRES_ROLE);
        boolean member = element.isAnnotatedWith(MEMBER_ENDPOINT);
        Optional<? extends JavaAnnotation<?>> open = element.tryGetAnnotationOfType(PUBLIC_ENDPOINT);
        if (member && (role || open.isPresent())) {
            return "porta @MemberEndpoint insieme a @RequiresRole o @PublicEndpoint: scegline una";
        }
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

    // ---- membro dal token ----

    /** Problemi dei parametri {@code MemberPrincipal}/{@code MemberSubject} rispetto alla dichiarazione (o alla sua assenza). */
    private static List<String> memberParameterProblems(Handler handler, HasAnnotations<?> declaring) {
        List<String> problems = new ArrayList<>();
        boolean member = declaring != null && declaring.isAnnotatedWith(MEMBER_ENDPOINT);
        for (JavaMethod m : handler.chain()) {
            for (JavaParameter p : m.getParameters()) {
                String type = p.getRawType().getName();
                if ((type.equals(MEMBER_PRINCIPAL) || type.equals(MEMBER_SUBJECT)) && !member) {
                    problems.add("un parametro " + p.getRawType().getSimpleName() + " richiede @MemberEndpoint");
                }
            }
        }
        return problems;
    }

    private static List<String> memberEndpointProblems(Handler handler, HasAnnotations<?> declaring) {
        List<String> problems = new ArrayList<>();
        String mode = memberMode(declaring);
        boolean registration = mode.equals("REGISTRATION");
        int principals = 0;
        int subjects = 0;
        for (JavaParameter p : handler.implementation().getParameters()) {
            String type = p.getRawType().getName();
            principals += type.equals(MEMBER_PRINCIPAL) ? 1 : 0;
            subjects += type.equals(MEMBER_SUBJECT) ? 1 : 0;
        }
        if (registration ? (subjects != 1 || principals != 0) : (principals != 1 || subjects != 0)) {
            problems.add("@MemberEndpoint(" + mode + ") vuole esattamente un parametro "
                    + (registration ? "MemberSubject" : "MemberPrincipal") + " e nessun altro del membro");
        } else if (!usesParameterType(handler.implementation(), registration ? MEMBER_SUBJECT : MEMBER_PRINCIPAL)) {
            problems.add("il handler non usa il " + (registration ? "MemberSubject" : "MemberPrincipal")
                    + " (nessuna chiamata a un suo metodo né passaggio come argomento): il membro va usato");
        }
        for (JavaMethod m : handler.chain()) {
            for (JavaParameter p : m.getParameters()) {
                for (String binding : NAMED_BINDINGS) {
                    Optional<? extends JavaAnnotation<?>> annotation = p.tryGetAnnotationOfType(binding);
                    if (annotation.isPresent()) {
                        for (String name : explicitNames(annotation.get())) {
                            if (isMemberName(name)) {
                                problems.add("un handler del membro non lega «" + name + "» con "
                                        + binding.substring(binding.lastIndexOf('.') + 1)
                                        + ": il membro viene solo dal token");
                            }
                        }
                    }
                }
            }
        }
        return problems;
    }

    /** {@code members = true} solo su {@code GET} sotto {@code /v1/portal/}, non sotto {@code /v1/portal/me}. */
    private static List<String> memberReadProblems(Handler handler) {
        List<String> problems = new ArrayList<>();
        List<String> verbs = verbs(handler);
        if (verbs.size() != 1 || !verbs.get(0).equals("GET")) {
            problems.add("@RequiresRole(members = true) è ammesso solo su GET");
        }
        for (String path : paths(handler)) {
            if (!path.startsWith(PORTAL_PREFIX + "/")) {
                problems.add("@RequiresRole(members = true) è ammesso solo sotto /v1/portal/ (percorso " + path + ")");
            } else if (path.equals(PORTAL_PREFIX + "/me") || path.startsWith(PORTAL_PREFIX + "/me/")) {
                problems.add("@RequiresRole(members = true) non è ammesso sotto /v1/portal/me (percorso " + path + ")");
            }
        }
        return problems;
    }

    /** Vero se l'handler chiama un metodo del tipo o lo passa come argomento a un altro metodo o costruttore. */
    private static boolean usesParameterType(JavaMethod method, String type) {
        for (JavaCall<?> call : method.getCallsFromSelf()) {
            if (call.getTargetOwner().getName().equals(type)
                    || call.getTarget().getRawParameterTypes().stream().anyMatch(t -> t.getName().equals(type))) {
                return true;
            }
        }
        return false;
    }

    /** Il primo parametro che lega dati della richiesta (annotato o di un tipo non di framework), o {@code null}. */
    private static String boundParameter(Handler handler) {
        for (JavaMethod m : handler.chain()) {
            for (JavaParameter p : m.getParameters()) {
                for (String binding : REQUEST_BINDINGS) {
                    if (p.isAnnotatedWith(binding)) {
                        return "@" + binding.substring(binding.lastIndexOf('.') + 1) + " " + p.getRawType().getSimpleName();
                    }
                }
                if (p.getAnnotations().isEmpty() && FRAMEWORK_TYPES.stream().noneMatch(p.getRawType().getName()::startsWith)
                        && !p.getRawType().getName().equals(MEMBER_PRINCIPAL)) {
                    return p.getRawType().getSimpleName();
                }
            }
        }
        return null;
    }

    private static String memberMode(HasAnnotations<?> declaring) {
        Optional<? extends JavaAnnotation<?>> annotation = declaring.tryGetAnnotationOfType(MEMBER_ENDPOINT);
        if (annotation.isEmpty()) {
            return "REQUIRED";
        }
        Object value = annotation.get().get("value").orElse(null);
        if (value == null) {
            return "REQUIRED"; // il default di @MemberEndpoint
        }
        return value.toString().substring(value.toString().lastIndexOf('.') + 1);
    }

    private static boolean roleMembers(HasAnnotations<?> declaring) {
        Optional<? extends JavaAnnotation<?>> annotation = declaring.tryGetAnnotationOfType(REQUIRES_ROLE);
        return annotation.isPresent() && Boolean.TRUE.equals(annotation.get().get("members").orElse(false));
    }

    /** {@code memberId} in qualunque grafia ({@code member_id}, {@code MEMBER-ID}) o l'header {@code X-LH-Member}. */
    private static boolean isMemberName(String name) {
        String n = name.replace("-", "").replace("_", "").toLowerCase(Locale.ROOT);
        return n.equals("memberid") || n.equals("xlhmember");
    }

    /** I nomi espliciti di un'annotazione di binding: {@code name} e {@code value} non vuoti. */
    private static List<String> explicitNames(JavaAnnotation<?> annotation) {
        List<String> names = new ArrayList<>();
        for (String property : List.of("name", "value")) {
            Object v = annotation.get(property).orElse(null);
            if (v instanceof String text && !text.isBlank()) {
                names.add(text);
            }
        }
        return names;
    }

    // ---- percorsi e verbi ----

    /** I verbi HTTP della mappatura: {@code GET}…; vuoto se qualsiasi verbo ({@code @RequestMapping} senza {@code method}). */
    private static List<String> verbs(Handler handler) {
        for (JavaMethod m : handler.chain()) {
            for (Map.Entry<String, String> verb : VERB_MAPPINGS.entrySet()) {
                if (m.isAnnotatedWith(verb.getKey())) {
                    return List.of(verb.getValue());
                }
            }
            Optional<? extends JavaAnnotation<?>> mapping = m.tryGetAnnotationOfType(REQUEST_MAPPING);
            if (mapping.isPresent()) {
                return strings(mapping.get().get("method").orElse(null));
            }
        }
        return List.of();
    }

    /** I percorsi composti dell'handler: prefissi del {@code @RequestMapping} di classe per i percorsi del metodo. */
    private static List<String> paths(Handler handler) {
        List<String> prefixes = List.of("");
        for (JavaClass type : hierarchy(handler.controller())) {
            Optional<? extends JavaAnnotation<?>> mapping = type.tryGetAnnotationOfType(REQUEST_MAPPING);
            if (mapping.isPresent()) {
                List<String> found = mappingPaths(mapping.get());
                if (!found.isEmpty()) {
                    prefixes = found;
                }
                break;
            }
        }
        List<String> own = List.of("");
        for (JavaMethod m : handler.chain()) {
            Optional<JavaAnnotation<?>> mapping = mappingAnnotation(m);
            if (mapping.isPresent()) {
                List<String> found = mappingPaths(mapping.get());
                if (!found.isEmpty()) {
                    own = found;
                }
                break;
            }
        }
        List<String> out = new ArrayList<>();
        for (String prefix : prefixes) {
            for (String path : own) {
                out.add(join(prefix, path));
            }
        }
        return out;
    }

    private static Optional<JavaAnnotation<?>> mappingAnnotation(JavaMethod m) {
        for (String type : VERB_MAPPINGS.keySet()) {
            Optional<? extends JavaAnnotation<?>> a = m.tryGetAnnotationOfType(type);
            if (a.isPresent()) {
                return Optional.of(a.get());
            }
        }
        return m.tryGetAnnotationOfType(REQUEST_MAPPING).map(a -> (JavaAnnotation<?>) a);
    }

    private static List<String> mappingPaths(JavaAnnotation<?> mapping) {
        List<String> out = new ArrayList<>(strings(mapping.get("value").orElse(null)));
        out.addAll(strings(mapping.get("path").orElse(null)));
        return out;
    }

    private static String join(String prefix, String path) {
        String a = prefix.endsWith("/") ? prefix.substring(0, prefix.length() - 1) : prefix;
        String b = path.isEmpty() || path.startsWith("/") ? path : "/" + path;
        String joined = a + b;
        return joined.startsWith("/") || joined.isEmpty() ? joined : "/" + joined;
    }

    /** Un valore di annotazione come elenco di testi: un array di stringhe o di costanti enum, o un singolo valore. */
    private static List<String> strings(Object value) {
        List<String> out = new ArrayList<>();
        if (value == null) {
            return out;
        }
        if (value.getClass().isArray()) {
            for (int i = 0; i < Array.getLength(value); i++) {
                out.addAll(strings(Array.get(value, i)));
            }
        } else if (value instanceof String text) {
            out.add(text);
        } else {
            String text = value.toString();
            out.add(text.substring(text.lastIndexOf('.') + 1));
        }
        return out;
    }
}
