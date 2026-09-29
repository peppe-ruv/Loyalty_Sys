package io.loyaltyhub.hub;

import io.loyaltyhub.common.web.EndpointAccessInterceptor;
import io.swagger.v3.oas.models.Operation;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.dataformat.yaml.YAMLMapper;
import tools.jackson.dataformat.yaml.YAMLWriteFeature;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * OpenAPI generata e verificata (M8.8, F2-API-01, ADR-046, docs/18 §3.6). Avvia l'hub (tutti i servizi in un JVM,
 * profilo {@code demo,inproc}) e chiede a springdoc un gruppo per servizio, definito dal package dei controller
 * ({@code io.loyaltyhub.<servizio>}): è la stessa OpenAPI che il servizio espone da solo su {@code /v3/api-docs}, con
 * gli schemi risolti per servizio (nel documento unico dell'hub due record omonimi di servizi diversi collidono).
 * Produce in {@code contracts/api/}:
 * <ul>
 *   <li>{@code <servizio>-service.openapi.yaml}, uno per servizio;</li>
 *   <li>{@code platform.openapi.yaml}, gli endpoint comuni di lh-common ({@code io.loyaltyhub.common}: reset e info
 *       della demo, approvazioni) e dell'hub ({@code io.loyaltyhub.hub});</li>
 *   <li>{@code portal.openapi.yaml}, l'unione delle sole API {@code /v1/portal/**} dei file dei servizi.</li>
 * </ul>
 * YAML deterministico: chiavi in ordine alfabetico, niente {@code servers} (porta casuale), {@code info} fissa,
 * elenchi {@code required} ordinati, solo i componenti raggiunti dai percorsi del file.
 * <p>
 * Di default <strong>confronta</strong> il risultato con i file versionati e fallisce se differiscono (drift). Per
 * rigenerare, dalla radice del repository: {@value #REGENERATE}.
 */
@SpringBootTest(
        classes = {HubApplication.class, OpenApiExportIT.Groups.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.config.name=hub")
@ActiveProfiles({"demo", "inproc"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OpenApiExportIT {

    static final String WRITE_PROPERTY = "lh.openapi.write";
    static final String REGENERATE = "./mvnw -pl deploy/hub -am verify -Dtest=NoSuchTest -Dsurefire.failIfNoSpecifiedTests=false"
            + " -Dit.test=OpenApiExportIT -Dfailsafe.failIfNoSpecifiedTests=false -Dlh.openapi.write=true";
    static final String SUFFIX = ".openapi.yaml";
    static final String PLATFORM = "platform";
    static final String PORTAL = "portal";
    static final String PORTAL_PREFIX = "/v1/portal/";
    private static final List<String> SERVICES = List.of(
            "campaign", "engagement", "gamification", "ingestion", "insight", "member", "reward", "wallet");
    private static final Pattern REF = Pattern.compile("^#/components/([^/]+)/(.+)$");

    private static final EmbeddedPostgres PG = startPg();

    private final JsonMapper json = JsonMapper.builder().build();
    private final YAMLMapper yaml = YAMLMapper.builder()
            .disable(YAMLWriteFeature.WRITE_DOC_START_MARKER)
            .disable(YAMLWriteFeature.SPLIT_LINES)
            .enable(YAMLWriteFeature.MINIMIZE_QUOTES)
            .enable(YAMLWriteFeature.ALLOW_LONG_KEYS)
            .build();

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private ApplicationContext context;

    /** Un gruppo springdoc per servizio e uno per la piattaforma: solo nel test, l'hub in esercizio resta invariato. */
    @TestConfiguration(proxyBeanMethods = false)
    static class Groups {
        @Bean
        List<GroupedOpenApi> loyaltyHubServiceGroups(List<OperationCustomizer> conventions) {
            List<GroupedOpenApi> groups = new ArrayList<>();
            for (String service : SERVICES) {
                groups.add(GroupedOpenApi.builder().group(service + "-service").packagesToScan("io.loyaltyhub." + service)
                        .addOperationCustomizer(chain(conventions)).build());
            }
            groups.add(GroupedOpenApi.builder().group(PLATFORM).packagesToScan("io.loyaltyhub.common", "io.loyaltyhub.hub")
                    .addOperationCustomizer(chain(conventions)).build());
            return groups;
        }

        /** Le convenzioni di lh-common (tag = area, summary; ADR-046) non si applicano da sole ai gruppi. */
        private static OperationCustomizer chain(List<OperationCustomizer> conventions) {
            return (operation, handler) -> {
                Operation op = operation;
                for (OperationCustomizer c : conventions) {
                    op = c.customize(op, handler);
                }
                return op;
            };
        }
    }

    private static EmbeddedPostgres startPg() {
        try {
            return EmbeddedPostgres.builder().start();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        String base = PG.getJdbcUrl("postgres", "postgres");
        registry.add("spring.datasource.url", () -> base + "&currentSchema=ingestion,member,campaign,wallet,insight,reward,gamification,engagement");
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "");
        // Nessun broker: profilo inproc (ADR-024). L'OpenAPI non dipende dal bus.
    }

    @AfterAll
    void tearDown() throws IOException {
        PG.close();
    }

    @Test
    void openApiMatchesContractsApi() throws IOException {
        assertThat(controllersOutsideGroups())
                .as("controller fuori da io.loyaltyhub.<servizio>|common|hub: nessun file di contracts/api li descriverebbe")
                .isEmpty();
        RestClient client = RestClient.builder().baseUrl("http://localhost:" + port).build();
        List<String> groups = new ArrayList<>(SERVICES.stream().map(s -> s + "-service").toList());
        groups.add(PLATFORM);
        Map<String, ObjectNode> specs = new TreeMap<>();
        int operations = 0;
        for (String group : groups) {
            JsonNode root = json.readTree(client.get().uri("/v3/api-docs/" + group).retrieve().body(String.class));
            specs.put(group, spec(root, group, (ObjectNode) root.path("paths").deepCopy()));
            for (JsonNode item : root.path("paths")) {
                operations += item.size();
            }
        }
        assertThat(operations).as("operazioni documentate").isGreaterThan(50);
        specs.put(PORTAL, portal(specs));
        // Il membro dal token nel contratto generato (Q-410, ADR-048): si attiva per gli handler @MemberEndpoint.
        assertThat(MemberContractChecks.contractProblems(specs.get(PORTAL), MemberContractChecks.memberOperations(handlerMethods())))
                .as("contratto del portale e membro dal token").isEmpty();

        Map<String, String> generated = render(specs);
        Path dir = repoRoot().resolve("contracts").resolve("api");
        Set<String> committed = new TreeSet<>();
        if (Files.isDirectory(dir)) {
            try (Stream<Path> files = Files.list(dir)) {
                files.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(SUFFIX)).forEach(committed::add);
            }
        }

        if (Boolean.getBoolean(WRITE_PROPERTY)) {
            Files.createDirectories(dir);
            for (String stale : committed) {
                if (!generated.containsKey(stale)) {
                    Files.delete(dir.resolve(stale));
                }
            }
            for (Map.Entry<String, String> e : generated.entrySet()) {
                Files.writeString(dir.resolve(e.getKey()), e.getValue(), StandardCharsets.UTF_8);
            }
            return;
        }

        List<String> drift = new ArrayList<>();
        for (String name : committed) {
            if (!generated.containsKey(name)) {
                drift.add(name + ": versionato ma non più generato dal codice");
            }
        }
        for (Map.Entry<String, String> e : generated.entrySet()) {
            Path file = dir.resolve(e.getKey());
            if (!Files.exists(file)) {
                drift.add(e.getKey() + ": assente in contracts/api/");
                continue;
            }
            String current = Files.readString(file, StandardCharsets.UTF_8).replace("\r\n", "\n");
            if (!current.equals(e.getValue())) {
                drift.add(e.getKey() + ": " + firstDifference(current, e.getValue()));
            }
        }
        if (!drift.isEmpty()) {
            fail("OpenAPI cambiata: rigenera con `" + REGENERATE + "`, rivedi il diff e versionalo.\n  - "
                    + String.join("\n  - ", drift));
        }
    }

    /**
     * Deny by default sul contesto reale (F2-SEC-09, ADR-042): ogni handler registrato che non è di un framework
     * risolve una dichiarazione valida con la stessa logica dell'interceptor
     * ({@link EndpointAccessInterceptor#resolve}). Complementare alla regola ArchUnit, che lavora sulle classi.
     */
    @Test
    void everyRegisteredHandlerResolvesAValidAccessDeclaration() {
        List<String> undeclared = new ArrayList<>();
        int checked = 0;
        for (RequestMappingHandlerMapping mapping : context.getBeansOfType(RequestMappingHandlerMapping.class).values()) {
            for (HandlerMethod handler : mapping.getHandlerMethods().values()) {
                if (EndpointAccessInterceptor.isFramework(handler.getBeanType())) {
                    continue;
                }
                checked++;
                if (!EndpointAccessInterceptor.resolve(handler).valid()) {
                    undeclared.add(handler.getBeanType().getName() + "#" + handler.getMethod().getName());
                }
            }
        }
        assertThat(undeclared).as("handler senza @RequiresRole né @PublicEndpoint con motivo").isEmpty();
        assertThat(checked).as("handler di prodotto controllati").isGreaterThan(150);
    }

    /**
     * Il membro dal token sui handler registrati (Q-410, ADR-048): ciò che ArchUnit non vede, cioè i nomi impliciti dei
     * parametri ({@code @RequestParam String memberId}, con i nomi a runtime di {@code -parameters}); {@code demoPathVariable}
     * solo su handler deprecati; ogni {@code @MemberEndpoint} sotto {@code /v1/portal/}. Si attiva da solo per controller:
     * vale per ogni handler {@code @MemberEndpoint}. La configurazione all'avvio la verifica anche
     * {@code MemberEndpointGuard}.
     */
    @Test
    void memberEndpointsNeverBindTheMemberFromTheRequest() {
        assertThat(MemberContractChecks.handlerProblems(handlerMethods()))
                .as("handler del membro che legano memberId dalla richiesta").isEmpty();
    }

    /** Tutti i handler registrati, con la loro mappatura. */
    private Map<org.springframework.web.servlet.mvc.method.RequestMappingInfo, HandlerMethod> handlerMethods() {
        Map<org.springframework.web.servlet.mvc.method.RequestMappingInfo, HandlerMethod> all = new java.util.LinkedHashMap<>();
        for (RequestMappingHandlerMapping mapping : context.getBeansOfType(RequestMappingHandlerMapping.class).values()) {
            all.putAll(mapping.getHandlerMethods());
        }
        return all;
    }

    // ================= composizione dei file =================

    /** Controller applicativi il cui package non appartiene a nessun gruppo. */
    private List<String> controllersOutsideGroups() {
        Set<String> out = new TreeSet<>();
        for (RequestMappingHandlerMapping mapping : context.getBeansOfType(RequestMappingHandlerMapping.class).values()) {
            for (HandlerMethod handler : mapping.getHandlerMethods().values()) {
                String name = handler.getBeanType().getName();
                String[] parts = name.split("\\.");
                boolean ours = parts.length > 2 && parts[0].equals("io") && parts[1].equals("loyaltyhub");
                if (ours && !SERVICES.contains(parts[2]) && !parts[2].equals("common") && !parts[2].equals("hub")) {
                    out.add(name);
                }
            }
        }
        return new ArrayList<>(out);
    }

    private static ObjectNode spec(JsonNode root, String name, ObjectNode paths) {
        ObjectNode spec = JsonNodeFactory.instance.objectNode();
        spec.set("openapi", root.path("openapi"));
        ObjectNode info = spec.putObject("info");
        info.put("title", "Loyalty Hub · " + name);
        info.put("version", "v1");
        info.put("description", (PORTAL.equals(name)
                ? "API del portale membri (/v1/portal/**): unione dei servizi."
                : "API HTTP di " + name + " (docs/06 §2).")
                + " Generata da OpenApiExportIT (M8.8, docs/18 §3.6): non modificare a mano.");
        spec.set("paths", paths);
        ObjectNode components = reachable(root.path("components"), paths);
        if (!components.isEmpty()) {
            spec.set("components", components);
        }
        return spec;
    }

    /** Unione delle operazioni {@code /v1/portal/**}; uno schema omonimo con forme diverse tra servizi è un errore. */
    private static ObjectNode portal(Map<String, ObjectNode> specs) {
        ObjectNode paths = JsonNodeFactory.instance.objectNode();
        ObjectNode components = JsonNodeFactory.instance.objectNode();
        Map<String, String> origin = new TreeMap<>();
        List<String> clashes = new ArrayList<>();
        String openapi = null;
        for (Map.Entry<String, ObjectNode> s : specs.entrySet()) {
            ObjectNode own = JsonNodeFactory.instance.objectNode();
            for (Map.Entry<String, JsonNode> p : s.getValue().path("paths").properties()) {
                if (!p.getKey().startsWith(PORTAL_PREFIX)) {
                    continue;
                }
                if (paths.has(p.getKey())) {
                    clashes.add("percorso " + p.getKey() + " in " + origin.get("path:" + p.getKey()) + " e " + s.getKey());
                }
                own.set(p.getKey(), p.getValue().deepCopy());
                paths.set(p.getKey(), p.getValue().deepCopy());
                origin.put("path:" + p.getKey(), s.getKey());
            }
            if (own.isEmpty()) {
                continue;
            }
            openapi = s.getValue().path("openapi").asString();
            for (Map.Entry<String, JsonNode> section : reachable(s.getValue().path("components"), own).properties()) {
                ObjectNode target = components.has(section.getKey())
                        ? (ObjectNode) components.get(section.getKey()) : components.putObject(section.getKey());
                for (Map.Entry<String, JsonNode> c : section.getValue().properties()) {
                    String key = section.getKey() + "/" + c.getKey();
                    if (target.has(c.getKey()) && !target.get(c.getKey()).equals(c.getValue())) {
                        clashes.add("componente " + key + " diverso in " + origin.get(key) + " e " + s.getKey());
                    }
                    target.set(c.getKey(), c.getValue().deepCopy());
                    origin.putIfAbsent(key, s.getKey());
                }
            }
        }
        assertThat(clashes).as("collisioni nell'unione del portale (rinomina uno dei record)").isEmpty();
        ObjectNode root = JsonNodeFactory.instance.objectNode();
        root.put("openapi", openapi);
        root.set("components", components);
        return spec(root, PORTAL, paths);
    }

    /** Solo i componenti raggiunti (anche transitivamente) da {@code from}. */
    private static ObjectNode reachable(JsonNode all, JsonNode from) {
        Set<String> seen = new HashSet<>();
        Deque<JsonNode> todo = new ArrayDeque<>();
        todo.push(from);
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        while (!todo.isEmpty()) {
            for (String ref : refs(todo.pop())) {
                Matcher m = REF.matcher(ref);
                if (!m.matches() || !seen.add(ref)) {
                    continue;
                }
                JsonNode target = all.path(m.group(1)).path(m.group(2));
                assertThat(target.isMissingNode()).as("riferimento non risolto: %s", ref).isFalse();
                ObjectNode section = out.has(m.group(1)) ? (ObjectNode) out.get(m.group(1)) : out.putObject(m.group(1));
                section.set(m.group(2), target.deepCopy());
                todo.push(target);
            }
        }
        return out;
    }

    private static List<String> refs(JsonNode node) {
        List<String> refs = new ArrayList<>();
        Deque<JsonNode> todo = new ArrayDeque<>();
        todo.push(node);
        while (!todo.isEmpty()) {
            JsonNode n = todo.pop();
            if (n.isObject()) {
                for (Map.Entry<String, JsonNode> e : n.properties()) {
                    if (e.getKey().equals("$ref") && e.getValue().isString()) {
                        refs.add(e.getValue().asString());
                    } else {
                        todo.push(e.getValue());
                    }
                }
            } else if (n.isArray()) {
                n.forEach(todo::push);
            }
        }
        return refs;
    }

    // ================= YAML deterministico =================

    private Map<String, String> render(Map<String, ObjectNode> specs) {
        Map<String, String> out = new TreeMap<>();
        for (Map.Entry<String, ObjectNode> e : specs.entrySet()) {
            String text = yaml.writeValueAsString(canonical(e.getValue(), null)).replace("\r\n", "\n");
            out.put(e.getKey() + SUFFIX, text.endsWith("\n") ? text : text + "\n");
        }
        return out;
    }

    /** Chiavi in ordine alfabetico a ogni livello; gli elenchi {@code required} (insiemi) ordinati. */
    private static JsonNode canonical(JsonNode node, String key) {
        if (node.isObject()) {
            Map<String, JsonNode> byKey = new TreeMap<>();
            node.properties().forEach(e -> byKey.put(e.getKey(), e.getValue()));
            ObjectNode sorted = JsonNodeFactory.instance.objectNode();
            byKey.forEach((k, v) -> sorted.set(k, canonical(v, k)));
            return sorted;
        }
        if (node.isArray()) {
            ArrayNode arr = JsonNodeFactory.instance.arrayNode();
            if ("required".equals(key) && allStrings(node)) {
                Set<String> names = new TreeSet<>();
                node.forEach(v -> names.add(v.asString()));
                names.forEach(arr::add);
            } else {
                node.forEach(v -> arr.add(canonical(v, null)));
            }
            return arr;
        }
        return node;
    }

    private static boolean allStrings(JsonNode arr) {
        for (JsonNode v : arr) {
            if (!v.isString()) {
                return false;
            }
        }
        return true;
    }

    private static String firstDifference(String committed, String generated) {
        String[] a = committed.split("\n", -1);
        String[] b = generated.split("\n", -1);
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            String x = i < a.length ? a[i] : "<fine file>";
            String y = i < b.length ? b[i] : "<fine file>";
            if (!x.equals(y)) {
                return "riga " + (i + 1) + ": versionato «" + x.strip() + "», generato «" + y.strip() + "»";
            }
        }
        return "differenza di fine riga";
    }

    /** Radice del repository: la prima cartella, risalendo dalla directory di lavoro, che contiene contracts/events. */
    private static Path repoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null && !Files.isDirectory(dir.resolve("contracts").resolve("events"))) {
            dir = dir.getParent();
        }
        assertThat(dir).as("radice del repository (contracts/events)").isNotNull();
        return dir;
    }
}
