package io.loyaltyhub.hub;

import io.loyaltyhub.common.web.EndpointAccessInterceptor;
import io.loyaltyhub.common.web.MemberEndpoint;
import io.loyaltyhub.common.web.MemberPrincipal;
import io.loyaltyhub.common.web.MemberPrincipals;
import io.loyaltyhub.common.web.MemberSubject;
import org.springframework.beans.BeanUtils;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.MatrixVariable;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.SessionAttribute;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import tools.jackson.databind.JsonNode;

import java.lang.reflect.Parameter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Il membro dal token verificato sul contesto reale dell'hub e sul contratto generato (Q-410, ADR-048, docs/06 §3.2).
 * Copre ciò che ArchUnit non vede: i nomi <em>impliciti</em> dei parametri ({@code @RequestParam String memberId}, con i
 * nomi a runtime di {@code -parameters}) e le operazioni OpenAPI. Si attiva da solo per controller: vale per ogni handler
 * {@link MemberEndpoint}; finché un servizio non ne ha, non controlla nulla.
 *
 * <p>Handler ({@link #handlerProblems}): nessun parametro risolto {@code memberId} (a qualunque grafia) su un handler
 * del membro, né un oggetto legato dalla richiesta ({@code @ModelAttribute} o un tipo non annotato) con una proprietà
 * {@code memberId}, anche annidata: il binder di Spring la lega da query e form anche con i prefissi {@code !} e {@code _}
 * ({@code ?!memberId=…}); {@code demoPathVariable} solo su un handler {@link Deprecated}; ogni handler del membro sta sotto
 * {@code /v1/portal/}.
 *
 * <p>Attivazione: per <em>operazione</em> {@link MemberEndpoint} ({@link #memberOperations}), non per controller: un
 * handler di backoffice nello stesso controller non è controllato (scostamento dal progetto §3.5, da registrare in ADR-048 e
 * docs/06 §3.2); il parametro di percorso {@code {id}} conta come id di un membro solo sotto {@code /members/}.
 *
 * <p>Contratto ({@link #contractProblems}): nessuna operazione sotto {@code /v1/portal/me} ha un parametro o una
 * proprietà di corpo {@code memberId}, a nessuna profondità; su un'operazione di un handler del membro un parametro di
 * percorso {@code memberId} (o {@code id} sotto {@code /members/}) e una proprietà di corpo {@code memberId} compaiono solo
 * se marcati {@code deprecated: true} (i percorsi legacy e i campi legacy solo demo).
 */
final class MemberContractChecks {

    private static final String PORTAL = "/v1/portal/";
    private static final String ME = "/v1/portal/me";
    private static final int MAX_SCHEMA_DEPTH = 12;

    /** Un'operazione HTTP: verbo in minuscolo come nelle chiavi di OpenAPI, e percorso. */
    record Operation(String method, String path) {
    }

    private MemberContractChecks() {
    }

    /** {@code memberId} in qualunque grafia, anche con i prefissi del binder ({@code !}, {@code _}): una sola definizione. */
    static boolean isMemberIdName(String name) {
        return MemberPrincipals.isMemberIdName(name);
    }

    // ================= handler registrati =================

    /** Le operazioni (verbo, percorso) degli handler del membro. */
    static Set<Operation> memberOperations(Map<RequestMappingInfo, HandlerMethod> handlers) {
        Set<Operation> out = new LinkedHashSet<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> e : handlers.entrySet()) {
            if (EndpointAccessInterceptor.isFramework(e.getValue().getBeanType())
                    || EndpointAccessInterceptor.resolve(e.getValue()).member() == null) {
                continue;
            }
            for (String pattern : patterns(e.getKey())) {
                Set<RequestMethod> methods = e.getKey().getMethodsCondition().getMethods();
                for (RequestMethod method : methods.isEmpty() ? Set.of(RequestMethod.values()) : methods) {
                    out.add(new Operation(method.name().toLowerCase(Locale.ROOT), pattern));
                }
            }
        }
        return out;
    }

    static List<String> handlerProblems(Map<RequestMappingInfo, HandlerMethod> handlers) {
        List<String> problems = new ArrayList<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> e : handlers.entrySet()) {
            HandlerMethod handler = e.getValue();
            if (EndpointAccessInterceptor.isFramework(handler.getBeanType())) {
                continue;
            }
            MemberEndpoint member = EndpointAccessInterceptor.resolve(handler).member();
            if (member == null) {
                continue;
            }
            String where = handler.getBeanType().getSimpleName() + "#" + handler.getMethod().getName();
            for (String pattern : patterns(e.getKey())) {
                if (!pattern.startsWith(PORTAL)) {
                    problems.add(where + ": un handler del membro sta solo sotto " + PORTAL + " (" + pattern + ")");
                }
            }
            if (!member.demoPathVariable().isBlank() && !handler.hasMethodAnnotation(Deprecated.class)
                    && !handler.getBeanType().isAnnotationPresent(Deprecated.class)) {
                problems.add(where + ": demoPathVariable solo su un handler @Deprecated");
            }
            for (Parameter parameter : handler.getMethod().getParameters()) {
                String bound = boundName(parameter);
                if (bound != null && isMemberIdName(bound)) {
                    problems.add(where + ": il parametro «" + bound + "» lega il memberId dalla richiesta: il membro viene "
                            + "solo dal MemberPrincipal");
                }
                for (String property : boundObjectMemberIds(parameter)) {
                    problems.add(where + ": il parametro «" + parameter.getName() + "» (" + parameter.getType().getSimpleName()
                            + ") lega dalla richiesta la proprietà memberId (" + property + "), anche con i prefissi del binder"
                            + " (?!memberId=…): il membro viene solo dal MemberPrincipal");
                }
            }
        }
        return problems;
    }

    /**
     * Il nome con cui Spring lega il parametro a un valore della richiesta: il nome esplicito dell'annotazione, o il nome
     * a runtime per un parametro annotato senza nome o un tipo semplice non annotato; {@code null} per il membro del token,
     * per i corpi e per gli oggetti di framework.
     */
    private static String boundName(Parameter parameter) {
        Class<?> type = parameter.getType();
        if (type == MemberPrincipal.class || type == MemberSubject.class) {
            return null;
        }
        RequestParam requestParam = AnnotatedElementUtils.findMergedAnnotation(parameter, RequestParam.class);
        PathVariable pathVariable = AnnotatedElementUtils.findMergedAnnotation(parameter, PathVariable.class);
        RequestHeader header = AnnotatedElementUtils.findMergedAnnotation(parameter, RequestHeader.class);
        CookieValue cookie = AnnotatedElementUtils.findMergedAnnotation(parameter, CookieValue.class);
        String explicit = null;
        boolean annotated = true;
        if (requestParam != null) {
            explicit = requestParam.name();
        } else if (pathVariable != null) {
            explicit = pathVariable.name();
        } else if (header != null) {
            explicit = header.name();
        } else if (cookie != null) {
            explicit = cookie.name();
        } else {
            annotated = false;
        }
        if (annotated) {
            return explicit.isBlank() ? parameter.getName() : explicit;
        }
        // Non annotato: Spring lega per nome solo i tipi semplici (come un @RequestParam implicito).
        return parameter.getAnnotations().length == 0 && BeanUtils.isSimpleProperty(type) ? parameter.getName() : null;
    }

    /** Annotazioni che legano un parametro ad altro che al modello: con una di queste il parametro non è un oggetto del binder. */
    private static final List<Class<? extends java.lang.annotation.Annotation>> NOT_A_MODEL_ATTRIBUTE = List.of(
            RequestParam.class, PathVariable.class, RequestHeader.class, CookieValue.class, RequestBody.class,
            RequestPart.class, RequestAttribute.class, SessionAttribute.class, MatrixVariable.class);
    /** Tipi che il binder non popola dalla richiesta come oggetto di dominio: framework e librerie standard. */
    private static final List<String> FRAMEWORK_TYPES = List.of("java.", "javax.", "jakarta.", "org.springframework.",
            "tools.jackson.", "com.fasterxml.");
    private static final int MAX_BOUND_DEPTH = 3;

    /**
     * Le proprietà {@code memberId} (in qualunque grafia) di un oggetto che Spring lega dalla richiesta: un parametro
     * {@code @ModelAttribute}, oppure senza annotazioni di binding e di un tipo non semplice e non di framework (Spring lo
     * tratta come {@code @ModelAttribute}). Il binder lega i campi di query e di form ({@code ?memberId=…}, {@code ?!memberId=…}
     * per il valore di default, {@code ?_memberId=…} per il marcatore) alle componenti dei {@code record}, alle proprietà
     * scrivibili dei bean e ai parametri del costruttore, anche annidati ({@code filter.memberId}, {@code items[0].memberId}).
     * Un corpo ({@code @RequestBody}) non conta: lo controllano {@code MemberBodyAdvice} e il contratto generato.
     */
    static List<String> boundObjectMemberIds(Parameter parameter) {
        Class<?> type = parameter.getType();
        if (type == MemberPrincipal.class || type == MemberSubject.class || BeanUtils.isSimpleProperty(type)
                || isFrameworkType(type)) {
            return List.of();
        }
        boolean model = AnnotatedElementUtils.findMergedAnnotation(parameter, ModelAttribute.class) != null;
        if (!model && NOT_A_MODEL_ATTRIBUTE.stream().anyMatch(a -> AnnotatedElementUtils.findMergedAnnotation(parameter, a) != null)) {
            return List.of();
        }
        List<String> found = new ArrayList<>();
        collectMemberIds(type, "", 0, new HashSet<>(), found);
        return found;
    }

    private static boolean isFrameworkType(Class<?> type) {
        return FRAMEWORK_TYPES.stream().anyMatch(type.getName()::startsWith);
    }

    private static void collectMemberIds(Class<?> type, String path, int depth, Set<Class<?>> visiting, List<String> found) {
        if (depth > MAX_BOUND_DEPTH || BeanUtils.isSimpleProperty(type) || isFrameworkType(type) || !visiting.add(type)) {
            return;
        }
        Map<String, java.lang.reflect.Type> properties = new java.util.LinkedHashMap<>();
        if (type.isRecord()) {
            for (java.lang.reflect.RecordComponent component : type.getRecordComponents()) {
                properties.put(component.getName(), component.getGenericType());
            }
        } else {
            for (java.beans.PropertyDescriptor descriptor : BeanUtils.getPropertyDescriptors(type)) {
                if (descriptor.getWriteMethod() != null) {
                    properties.put(descriptor.getName(), descriptor.getWriteMethod().getGenericParameterTypes()[0]);
                }
            }
            for (java.lang.reflect.Constructor<?> constructor : type.getDeclaredConstructors()) {
                for (Parameter parameter : constructor.getParameters()) {
                    properties.putIfAbsent(parameter.getName(), parameter.getParameterizedType());
                }
            }
        }
        for (Map.Entry<String, java.lang.reflect.Type> property : properties.entrySet()) {
            String at = path + property.getKey();
            if (isMemberIdName(property.getKey())) {
                found.add(at);
            }
            for (Class<?> nested : classesOf(property.getValue())) {
                collectMemberIds(nested, at + ".", depth + 1, visiting, found);
            }
        }
        visiting.remove(type);
    }

    /** Le classi raggiunte da un tipo: se stesso, l'elemento di un array, gli argomenti di un tipo generico ({@code List<Item>}). */
    private static List<Class<?>> classesOf(java.lang.reflect.Type type) {
        List<Class<?>> out = new ArrayList<>();
        if (type instanceof Class<?> c) {
            out.add(c.isArray() ? c.getComponentType() : c);
        } else if (type instanceof java.lang.reflect.ParameterizedType p) {
            for (java.lang.reflect.Type argument : p.getActualTypeArguments()) {
                out.addAll(classesOf(argument));
            }
        }
        return out;
    }

    private static Set<String> patterns(RequestMappingInfo info) {
        return info.getPathPatternsCondition() == null ? Set.of() : info.getPathPatternsCondition().getPatternValues();
    }

    // ================= contratto generato =================

    /**
     * @param spec             un file OpenAPI generato ({@code paths} e {@code components})
     * @param memberOperations le operazioni degli handler {@link MemberEndpoint} ({@link #memberOperations})
     */
    static List<String> contractProblems(JsonNode spec, Set<Operation> memberOperations) {
        List<String> problems = new ArrayList<>();
        JsonNode components = spec.path("components");
        for (Map.Entry<String, JsonNode> path : spec.path("paths").properties()) {
            String url = path.getKey();
            boolean underMe = url.equals(ME) || url.startsWith(ME + "/");
            List<JsonNode> pathParameters = list(path.getValue().path("parameters"));
            for (Map.Entry<String, JsonNode> op : path.getValue().properties()) {
                if (!op.getValue().isObject() || op.getKey().equals("parameters")) {
                    continue;
                }
                String where = op.getKey().toUpperCase(Locale.ROOT) + " " + url;
                boolean deprecated = op.getValue().path("deprecated").asBoolean(false);
                boolean member = memberOperations.contains(new Operation(op.getKey(), url));
                List<JsonNode> parameters = new ArrayList<>(pathParameters);
                parameters.addAll(list(op.getValue().path("parameters")));
                for (JsonNode raw : parameters) {
                    JsonNode parameter = resolve(raw, components);
                    String name = parameter.path("name").asString("");
                    String in = parameter.path("in").asString("");
                    if (underMe && isMemberIdName(name)) {
                        problems.add(where + ": sotto /v1/portal/me il parametro «" + name + "» non è ammesso");
                    }
                    if (member && !deprecated && in.equals("path") && (isMemberIdName(name) || isMemberSegmentId(url, name))) {
                        problems.add(where + ": il parametro di percorso «" + name + "» del membro compare solo su "
                                + "operazioni deprecated: true");
                    }
                }
                JsonNode schema = op.getValue().path("requestBody").path("content").path("application/json").path("schema");
                if (schema.isMissingNode()) {
                    schema = firstSchema(op.getValue().path("requestBody").path("content"));
                }
                if (!schema.isMissingNode()) {
                    for (String property : memberIdProperties(schema, components)) {
                        boolean flagged = property.endsWith("!deprecated");
                        if (underMe) {
                            problems.add(where + ": sotto /v1/portal/me una proprietà di corpo memberId non è ammessa ("
                                    + property.replace("!deprecated", "") + ")");
                        } else if (member && !flagged) {
                            problems.add(where + ": la proprietà di corpo memberId (" + property
                                    + ") compare solo se deprecated: true");
                        }
                    }
                }
            }
        }
        return problems;
    }

    /** {@code {id}} sotto {@code /members/}: l'id del membro di una risorsa del membro (non l'id di un oggetto). */
    private static boolean isMemberSegmentId(String url, String name) {
        return name.equals("id") && url.contains("/members/{" + name + "}");
    }

    private static JsonNode firstSchema(JsonNode content) {
        for (Map.Entry<String, JsonNode> e : content.properties()) {
            return e.getValue().path("schema");
        }
        return content.path("x-missing");
    }

    private static List<JsonNode> list(JsonNode array) {
        List<JsonNode> out = new ArrayList<>();
        array.forEach(out::add);
        return out;
    }

    private static JsonNode resolve(JsonNode node, JsonNode components) {
        String ref = node.path("$ref").asString("");
        String prefix = "#/components/";
        if (ref.startsWith(prefix)) {
            String[] parts = ref.substring(prefix.length()).split("/", 2);
            if (parts.length == 2) {
                return components.path(parts[0]).path(parts[1]);
            }
        }
        return node;
    }

    /**
     * Le proprietà {@code memberId} raggiungibili dallo schema, a nessuna profondità (segue {@code $ref}, {@code items},
     * {@code allOf/oneOf/anyOf}, {@code additionalProperties}); il suffisso {@code !deprecated} segnala la proprietà
     * marcata {@code deprecated: true}.
     */
    static List<String> memberIdProperties(JsonNode schema, JsonNode components) {
        List<String> found = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Deque<Object[]> todo = new ArrayDeque<>();
        todo.push(new Object[] {schema, "$", 0});
        while (!todo.isEmpty()) {
            Object[] item = todo.pop();
            JsonNode node = (JsonNode) item[0];
            String at = (String) item[1];
            int depth = (int) item[2];
            if (depth > MAX_SCHEMA_DEPTH || node == null || node.isMissingNode()) {
                continue;
            }
            String ref = node.path("$ref").asString("");
            if (!ref.isEmpty()) {
                if (seen.add(ref)) {
                    todo.push(new Object[] {resolve(node, components), at + "→" + ref.substring(ref.lastIndexOf('/') + 1), depth + 1});
                }
                continue;
            }
            for (Map.Entry<String, JsonNode> property : node.path("properties").properties()) {
                String where = at + "." + property.getKey();
                if (isMemberIdName(property.getKey())) {
                    found.add(where + (isDeprecated(property.getValue(), components) ? "!deprecated" : ""));
                }
                todo.push(new Object[] {property.getValue(), where, depth + 1});
            }
            for (String combinator : List.of("allOf", "oneOf", "anyOf")) {
                for (JsonNode part : node.path(combinator)) {
                    todo.push(new Object[] {part, at, depth + 1});
                }
            }
            if (node.has("items")) {
                todo.push(new Object[] {node.get("items"), at + "[]", depth + 1});
            }
            if (node.path("additionalProperties").isObject()) {
                todo.push(new Object[] {node.get("additionalProperties"), at + ".*", depth + 1});
            }
        }
        return found;
    }

    private static boolean isDeprecated(JsonNode property, JsonNode components) {
        return resolve(property, components).path("deprecated").asBoolean(false) || property.path("deprecated").asBoolean(false);
    }
}
