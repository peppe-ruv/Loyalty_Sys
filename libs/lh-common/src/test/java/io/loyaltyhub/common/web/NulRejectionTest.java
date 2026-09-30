package io.loyaltyhub.common.web;

import io.loyaltyhub.common.config.LhCommonAutoConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.servlet.context.AnnotationConfigServletWebServerApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.HttpMessageConverters;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Il byte NUL ({@code U+0000}) non arriva mai al database (Q-532 causa (3), F2-SEC-12, ADR-042): {@code 400} RFC 9457 su
 * valore e nome di un parametro di query o di un corpo form, sul percorso, e su valori e chiavi di un corpo JSON, anche
 * annidati. Come {@link UnnamedParameterTest}, gira su un Tomcat vero (la decodifica dei parametri è del contenitore) con
 * la catena dei servizi: il filtro e il modulo Jackson sono creati dalla stessa {@link LhCommonAutoConfiguration} che li
 * registra nei servizi, e il {@code JsonMapper} è quello di Spring Boot ({@link JacksonAutoConfiguration}), che raccoglie
 * il modulo come bean.
 */
class NulRejectionTest {

    private static AnnotationConfigServletWebServerApplicationContext context;
    private static String base;

    @BeforeAll
    static void start() {
        context = new AnnotationConfigServletWebServerApplicationContext();
        context.registerBean("tomcat", TomcatServletWebServerFactory.class, () -> new TomcatServletWebServerFactory(0));
        context.registerBean("dispatcherServlet", DispatcherServlet.class, () -> new DispatcherServlet());
        context.registerBean("probe", Probe.class, () -> new Probe(context.getBean(JsonMapper.class)));
        context.registerBean("tolerantClass", TolerantClass.class, TolerantClass::new);
        context.registerBean("advice", GlobalExceptionHandler.class, GlobalExceptionHandler::new);
        context.registerBean("identity", ActorFilter.class, () -> new ActorFilter("probe"));
        LhCommonAutoConfiguration wiring = new LhCommonAutoConfiguration();
        context.registerBean("nulFilter", NulRejectingFilter.class, () -> wiring.nulRejectingFilter(context));
        context.registerBean("lhNulRejectingModule", NulRejectingModule.class, wiring::lhNulRejectingModule);
        context.registerBean("nulBodyScope", NulBodyScopeAdvice.class, wiring::nulBodyScopeAdvice);
        context.register(JacksonAutoConfiguration.class, Mvc.class);
        context.refresh();
        base = "http://localhost:" + context.getWebServer().getPort();
    }

    @AfterAll
    static void stop() {
        if (context != null) {
            context.close();
        }
    }

    // ---- query e form: nome e valore ----

    @ParameterizedTest(name = "GET /v1/probe/search{0} → 400")
    @ValueSource(strings = {"?q=a%00b", "?q=%00", "?q=ok%00", "?q=%00ok", "?q=a%00b&r=ok", "?r=ok&q=a%00b", "?q=a&q=b%00",
            "?qa%00b=x", "?%00=x", "?q%00=x", "?q=%e2%82%ac%00"})
    @DisplayName("NUL nel nome o nel valore di un parametro di query: 400 RFC 9457, dettaglio fisso")
    void nulInQueryIs400(String query) throws IOException {
        assertNulRejected(call("GET", base + "/v1/probe/search" + query, null, null), NulCharacters.REQUEST_MESSAGE);
    }

    @Test
    @DisplayName("il 400 non riporta il valore ricevuto né dettagli interni, e dà l'istanza")
    void nulResponseLeaksNothing() throws IOException {
        Response r = call("GET", base + "/v1/probe/search?q=secret-value%00rest-of", null, null);
        assertNulRejected(r, NulCharacters.REQUEST_MESSAGE);
        assertThat(r.body()).doesNotContain("secret-value").doesNotContain("rest-of").doesNotContain("\0")
                .doesNotContainIgnoringCase("tomcat").doesNotContainIgnoringCase("exception").doesNotContain("\tat ");
        assertThat(r.body()).contains("\"instance\":\"/v1/probe/search\"");
    }

    @Test
    @DisplayName("il rifiuto avviene prima del controller: un handler che non legge parametri non viene raggiunto")
    void rejectedBeforeBinding() throws IOException {
        Probe.plainCalls = 0;
        assertNulRejected(call("GET", base + "/v1/probe/plain?q=%00", null, null), NulCharacters.REQUEST_MESSAGE);
        assertThat(Probe.plainCalls).isZero();
        assertThat(call("GET", base + "/v1/probe/plain?q=ok", null, null).status()).isEqualTo(200);
        assertThat(Probe.plainCalls).isEqualTo(1);
    }

    @ParameterizedTest(name = "POST form {0} → 400")
    @ValueSource(strings = {"reason=a%00b", "reason=ok&x=%00", "re%00ason=x", "%00=x"})
    @DisplayName("NUL in un corpo x-www-form-urlencoded: 400")
    void nulInFormBodyIs400(String form) throws IOException {
        assertNulRejected(call("POST", base + "/v1/probe/submit", "application/x-www-form-urlencoded", form),
                NulCharacters.REQUEST_MESSAGE);
    }

    @Test
    @DisplayName("il testo «%00» codificato due volte, o con altri caratteri, non è un NUL: passa")
    void doubleEncodedNulTextIsNotNul() throws IOException {
        Response r = call("GET", base + "/v1/probe/search?q=%2500", null, null);
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.body()).contains("\"q\":\"%00\"");
        assertThat(call("GET", base + "/v1/probe/search?q=50%25%20sconto", null, null).status()).isEqualTo(200);
        assertThat(call("GET", base + "/v1/probe/search?q=%E2%82%AC", null, null).status()).isEqualTo(200);
        assertThat(call("GET", base + "/v1/probe/search?q=a+b", null, null).body()).contains("\"q\":\"a b\"");
        assertThat(call("POST", base + "/v1/probe/submit", "application/x-www-form-urlencoded", "reason=ok").status())
                .isEqualTo(200);
    }

    // ---- percorso ----

    @ParameterizedTest(name = "PUT /v1/probe/items/{0} → 400")
    @ValueSource(strings = {"a%00b", "%00", "ab%00", "%00ab", "A%00B"})
    @DisplayName("NUL in un segmento di percorso: 400 (mai il codice al database)")
    void nulInPathSegmentIs400(String segment) throws IOException {
        Probe.itemCalls = 0;
        Response r = call("PUT", base + "/v1/probe/items/" + segment, null, null);
        // Tomcat può rifiutare prima del filtro (Invalid URI) con un suo 400; il filtro lo dà come problem+json: in ogni caso 400.
        assertThat(r.status()).as(r.body()).isEqualTo(400);
        assertThat(r.body()).doesNotContain("\0");
        assertThat(Probe.itemCalls).isZero();
    }

    @Test
    @DisplayName("il filtro rifiuta da solo un percorso con %00 (senza affidarsi a Tomcat), in problem+json")
    void filterRejectsEncodedNulInPath() throws Exception {
        NulRejectingFilter filter = new NulRejectingFilter(() -> context.getBean("handlerExceptionResolver",
                HandlerExceptionResolver.class));
        var request = new org.springframework.mock.web.MockHttpServletRequest("PUT", "/v1/probe/items/a%00b");
        request.setRequestURI("/v1/probe/items/a%00b");
        var response = new org.springframework.mock.web.MockHttpServletResponse();
        var chain = new org.springframework.mock.web.MockFilterChain();
        filter.doFilter(request, response, chain);
        assertThat(chain.getRequest()).as("la catena non prosegue").isNull();
        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getContentAsString()).contains("\"code\":\"BAD_REQUEST\"")
                .contains("\"detail\":\"" + NulCharacters.REQUEST_MESSAGE + "\"");
        // un segmento codificato due volte è il testo «%00»: passa
        var clean = new org.springframework.mock.web.MockHttpServletRequest("PUT", "/v1/probe/items/a%2500b");
        clean.setRequestURI("/v1/probe/items/a%2500b");
        var cleanChain = new org.springframework.mock.web.MockFilterChain();
        filter.doFilter(clean, new org.springframework.mock.web.MockHttpServletResponse(), cleanChain);
        assertThat(cleanChain.getRequest()).isNotNull();
    }

    @Test
    @DisplayName("percorso senza NUL: invariato")
    void cleanPathIsUnchanged() throws IOException {
        Response r = call("PUT", base + "/v1/probe/items/TIER-GOLD", null, null);
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.body()).contains("\"code\":\"TIER-GOLD\"");
    }

    // ---- corpo JSON ----

    @ParameterizedTest(name = "POST /v1/probe/body {0} → 400")
    @ValueSource(strings = {
            "{\"name\":\"a\\u0000b\"}",
            "{\"name\":\"\\u0000\"}",
            "{\"name\":\"ok\",\"nested\":{\"label\":\"x\\u0000\"}}",
            "{\"nested\":{\"deeper\":{\"label\":\"\\u0000\"}}}",
            "{\"tags\":[\"ok\",\"a\\u0000\"]}",
            "{\"labels\":{\"k\":\"v\\u0000\"}}",
            "{\"labels\":{\"k\\u0000\":\"v\"}}",
            "{\"extra\":{\"a\":\"\\u0000\"}}",
            "{\"extra\":{\"a\\u0000\":1}}",
            "{\"extra\":{\"a\":{\"b\":[\"ok\",{\"c\":\"\\u0000\"}]}}}",
            "{\"extra\":{\"a\":{\"b\\u0000\":1}}}",
            "{\"extra\":{\"list\":[[\"\\u0000\"]]}}",
            "{\"tree\":{\"street\":\"\\u0000\"}}",
            "{\"tree\":{\"street\\u0000\":\"x\"}}",
            "{\"tree\":{\"a\":[{\"b\":\"\\u0000\"}]}}",
            "{\"tree\":[\"\\u0000\"]}",
            "{\"tree\":\"\\u0000\"}"})
    @DisplayName("NUL in un valore o in una chiave del corpo JSON, a ogni profondità e in ogni tipo: 400, dettaglio fisso")
    void nulInJsonBodyIs400(String json) throws IOException {
        assertNulRejected(call("POST", base + "/v1/probe/body", "application/json", json), NulCharacters.BODY_MESSAGE);
    }

    @ParameterizedTest(name = "POST /v1/probe/tree {0} → 400")
    @ValueSource(strings = {"{\"shipping\":{\"street\":\"Via \\u0000Roma\"}}", "{\"a\\u0000\":1}", "[\"\\u0000\"]", "\"\\u0000\"",
            "{\"a\":{\"b\":{\"c\":[1,2,\"x\\u0000\"]}}}"})
    @DisplayName("un corpo che è un albero libero (JsonNode alla radice): NUL a ogni profondità → 400")
    void nulInRootTreeIs400(String json) throws IOException {
        assertNulRejected(call("POST", base + "/v1/probe/tree", "application/json", json), NulCharacters.BODY_MESSAGE);
    }

    @ParameterizedTest(name = "POST /v1/probe/object {0} → 400")
    @ValueSource(strings = {"{\"a\":\"\\u0000\"}", "[\"\\u0000\"]", "\"\\u0000\"", "{\"a\\u0000\":1}"})
    @DisplayName("un corpo che è un Object (Map/List/String liberi alla radice): NUL → 400")
    void nulInRootObjectIs400(String json) throws IOException {
        assertNulRejected(call("POST", base + "/v1/probe/object", "application/json", json), NulCharacters.BODY_MESSAGE);
    }

    @Test
    @DisplayName("il 400 del corpo non riporta il valore ricevuto")
    void bodyResponseLeaksNothing() throws IOException {
        Response r = call("POST", base + "/v1/probe/body", "application/json", "{\"name\":\"secret-value\\u0000rest-of\"}");
        assertNulRejected(r, NulCharacters.BODY_MESSAGE);
        assertThat(r.body()).doesNotContain("secret-value").doesNotContain("rest-of").doesNotContain("\0")
                .doesNotContainIgnoringCase("jackson").doesNotContainIgnoringCase("tools.jackson");
    }

    @Test
    @DisplayName("un NUL grezzo nel corpo non è JSON valido: 400 (non 500)")
    void rawNulByteInBodyIs400() throws IOException {
        Response r = call("POST", base + "/v1/probe/body", "application/json", "{\"name\":\"a\0b\"}");
        assertThat(r.status()).isEqualTo(400);
        assertThat(r.contentType()).startsWith("application/problem+json");
    }

    @Test
    @DisplayName("corpi leciti: invariati (testo con backslash e «u0000», unicode, null, vuoti, numeri)")
    void cleanBodiesAreUnchanged() throws IOException {
        // «\\u0000» in JSON è un backslash seguito dal testo u0000: non è un NUL
        Response escaped = call("POST", base + "/v1/probe/body", "application/json",
                "{\"name\":\"a\\\\u0000b\",\"labels\":{\"k\":\"v\"},\"tags\":[\"x\",\"\"],\"extra\":{\"n\":1,\"t\":true,\"z\":null},"
                        + "\"tree\":{\"street\":\"Via Roma €\",\"n\":[1,2.5,null]}}");
        assertThat(escaped.status()).as(escaped.body()).isEqualTo(200);
        assertThat(escaped.body()).contains("\"name\":\"a\\\\u0000b\"");
        assertThat(call("POST", base + "/v1/probe/body", "application/json", "{}").status()).isEqualTo(200);
        assertThat(call("POST", base + "/v1/probe/body", "application/json", "{\"name\":null}").status()).isEqualTo(200);
        assertThat(call("POST", base + "/v1/probe/tree", "application/json", "{\"a\":[1,{\"b\":\"ok\"}]}").status())
                .isEqualTo(200);
        assertThat(call("POST", base + "/v1/probe/object", "application/json", "{\"a\":[1,{\"b\":\"ok\"}]}").status())
                .isEqualTo(200);
    }

    @Test
    @DisplayName("un corpo non leggibile per altri motivi resta un 400 col suo dettaglio generico")
    void otherUnreadableBodyKeepsGenericDetail() throws IOException {
        Response r = call("POST", base + "/v1/probe/body", "application/json", "{ non json");
        assertThat(r.status()).isEqualTo(400);
        assertThat(r.body()).contains("Corpo della richiesta assente o non leggibile come JSON valido");
    }

    @Test
    @DisplayName("un handler @NulTolerantBody risponde da sé al NUL nel corpo (come l'ingresso eventi); il resto resta rifiutato")
    void tolerantHandlerKeepsItsOwnNulRule() throws IOException {
        Response method = call("POST", base + "/v1/probe/tolerant", "application/json", "{\"name\":\"a\\u0000b\"}");
        assertThat(method.status()).as(method.body()).isEqualTo(200);
        Response tree = call("POST", base + "/v1/probe/tolerant-tree", "application/json",
                "[{\"a\":\"\\u0000\"},{\"b\\u0000\":1}]");
        assertThat(tree.status()).as(tree.body()).isEqualTo(200);
        Response onClass = call("POST", base + "/v1/probe/tolerant-class", "application/json", "{\"name\":\"a\\u0000b\"}");
        assertThat(onClass.status()).as(onClass.body()).isEqualTo(200);
        // l'eccezione è del corpo: un NUL nella query o nel percorso è rifiutato comunque
        assertNulRejected(call("POST", base + "/v1/probe/tolerant?x=%00", "application/json", "{}"),
                NulCharacters.REQUEST_MESSAGE);
        // e un altro handler, nella stessa applicazione, resta rifiutato
        assertNulRejected(call("POST", base + "/v1/probe/body", "application/json", "{\"name\":\"a\\u0000b\"}"),
                NulCharacters.BODY_MESSAGE);
    }

    @Test
    @DisplayName("il parsing che un handler fa da sé di un testo ricevuto non è toccato: il NUL resta affare dell'handler (Q-371)")
    void handlerOwnParsingIsNotRejected() throws IOException {
        // come il file d'import JSON: il corpo arriva come testo (nessun modulo in gioco), l'handler lo legge col mapper
        String array = "[{\"id\":\"a\\u0000b\"},{\"id\":\"ok\"}]";
        Response tree = call("POST", base + "/v1/probe/own-parse", "text/plain", array);
        assertThat(tree.status()).as(tree.body()).isEqualTo(200);
        assertThat(tree.body()).contains("\"nul\":true").contains("\"elements\":2");
        // streaming come ImportParser.readJsonArray: readValueAsTree su un parser del mapper, dentro la richiesta
        Response streamed = call("POST", base + "/v1/probe/own-parse-stream", "text/plain", array);
        assertThat(streamed.status()).as(streamed.body()).isEqualTo(200);
        assertThat(streamed.body()).contains("\"elements\":2");
        // un handler che legge il corpo JSON dal converter resta rifiutato, anche se poi rilegge un testo da sé
        assertNulRejected(call("POST", base + "/v1/probe/body", "application/json", "{\"name\":\"a\\u0000b\"}"),
                NulCharacters.BODY_MESSAGE);
    }

    @Test
    @DisplayName("il rifiuto si spegne a corpo letto: il parsing dell'handler, dopo un corpo valido, non è rifiutato")
    void scopeEndsWhenBodyIsRead() throws IOException {
        Response r = call("POST", base + "/v1/probe/body-then-parse", "application/json", "{\"name\":\"ok\"}");
        assertThat(r.status()).as(r.body()).isEqualTo(200);
        assertThat(r.body()).contains("\"nul\":true");
    }

    @Test
    @DisplayName("il JsonMapper di Spring Boot rifiuta il NUL solo mentre si legge il corpo: fuori (Kafka, seed, worker) come prima")
    void bootMapperRejectsOnlyWhileReadingTheBody() {
        JsonMapper mapper = context.getBean(JsonMapper.class);
        String json = "{\"name\":\"a\\u0000b\",\"extra\":{\"k\\u0000\":\"v\"},\"tree\":{\"x\":\"\\u0000\"}}";
        // fuori da una richiesta: nessun rifiuto (le regole dei consumer e dei lavori sono le loro)
        assertThat(mapper.readValue(json, Body.class).name()).isEqualTo("a\0b");
        assertThat(mapper.readTree(json).path("name").asString()).isEqualTo("a\0b");
        var attributes = new org.springframework.web.context.request.ServletRequestAttributes(
                new org.springframework.mock.web.MockHttpServletRequest("POST", "/v1/probe/body"));
        org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(attributes);
        try {
            // dentro una richiesta ma fuori dalla lettura del corpo: nessun rifiuto
            assertThat(mapper.readValue(json, Body.class).name()).isEqualTo("a\0b");
            assertThat(mapper.readTree(json).path("name").asString()).isEqualTo("a\0b");
            // durante la lettura del corpo: rifiuto con il dettaglio fisso
            NulScope.enter();
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> mapper.readValue(json, Body.class))
                    .hasMessageContaining(NulCharacters.BODY_MESSAGE);
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> mapper.readTree(json))
                    .isInstanceOf(Exception.class).hasMessageContaining(NulCharacters.BODY_MESSAGE);
            assertThat(mapper.readValue("{\"name\":\"ok\"}", Body.class).name()).isEqualTo("ok");
            NulScope.exit();
            assertThat(mapper.readValue(json, Body.class).name()).isEqualTo("a\0b");
        } finally {
            org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();
        }
    }

    // ---- regressione di Q-532 (a): il parametro senza nome resta gestito, con lo stesso comportamento ----

    @ParameterizedTest(name = "GET /v1/probe/search{0} → 400 (parametro illeggibile, come prima)")
    @ValueSource(strings = {"?=x", "?&=x", "?q=ok&=x", "?q=%zz", "?%zz=x"})
    @DisplayName("il filtro non inghiotte un parametro illeggibile: il controller lo riceve e risponde 400")
    void unreadableParametersStillGive400(String query) throws IOException {
        Response r = call("GET", base + "/v1/probe/search" + query, null, null);
        assertThat(r.status()).as(r.body()).isEqualTo(400);
        assertThat(r.contentType()).startsWith("application/problem+json");
        assertThat(r.body()).contains("Parametri della richiesta non validi: nome assente o codifica non valida");
    }

    @Test
    @DisplayName("un handler che non legge i parametri continua a rispondere 200 con un parametro senza nome")
    void handlerWithoutParametersIsUnchanged() throws IOException {
        assertThat(call("GET", base + "/v1/probe/plain?=x", null, null).status()).isEqualTo(200);
    }

    @Test
    @DisplayName("un parametro illeggibile non nasconde un NUL nella query: 400 col dettaglio del NUL")
    void unreadableParameterDoesNotHideNul() throws IOException {
        assertNulRejected(call("GET", base + "/v1/probe/plain?=x&q=%00", null, null), NulCharacters.REQUEST_MESSAGE);
    }

    // ---- infrastruttura ----

    private static void assertNulRejected(Response r, String detail) {
        assertThat(r.status()).as(r.body()).isEqualTo(400);
        assertThat(r.contentType()).startsWith("application/problem+json");
        assertThat(r.body()).contains("\"type\":\"urn:loyaltyhub:problem:bad-request\"")
                .contains("\"title\":\"Richiesta non valida\"")
                .contains("\"detail\":\"" + detail + "\"")
                .contains("\"code\":\"BAD_REQUEST\"")
                .contains("\"status\":400");
    }

    record Response(int status, String contentType, String body) {
    }

    private static Response call(String method, String url, String contentType, String payload) throws IOException {
        // HttpURLConnection non riscrive la query: «%00» arriva a Tomcat com'è.
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(5_000);
        c.setReadTimeout(10_000);
        c.setRequestProperty(ActorFilter.HEADER, "ADMIN:tester");
        if (payload != null) {
            c.setRequestProperty("Content-Type", contentType);
            c.setDoOutput(true);
            c.getOutputStream().write(payload.getBytes(StandardCharsets.UTF_8));
        }
        int status = c.getResponseCode();
        try (InputStream in = status >= 400 ? c.getErrorStream() : c.getInputStream()) {
            String body = in == null ? "" : new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return new Response(status, String.valueOf(c.getContentType()), body);
        } finally {
            c.disconnect();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    static class Mvc implements WebMvcConfigurer {

        private final JsonMapper mapper;

        Mvc(JsonMapper mapper) {
            this.mapper = mapper;
        }

        @Override
        public void configureMessageConverters(HttpMessageConverters.ServerBuilder builder) {
            builder.withJsonConverter(new JacksonJsonHttpMessageConverter(mapper));
        }
    }

    record Nested(String label, Nested deeper) {
    }

    record Body(String name, Nested nested, List<String> tags, Map<String, String> labels, Map<String, Object> extra,
                JsonNode tree) {
    }

    @RestController
    @NulTolerantBody
    public static class TolerantClass {

        @PostMapping("/v1/probe/tolerant-class")
        public Map<String, Object> tolerant(@RequestBody Body body) {
            return Map.of("ok", true);
        }
    }

    @RestController
    public static class Probe {

        static volatile int plainCalls;
        static volatile int itemCalls;

        private final JsonMapper mapper;

        Probe(JsonMapper mapper) {
            this.mapper = mapper;
        }

        /** Come l'upload d'import: il testo arriva senza passare dal modulo, e lo legge l'handler col mapper. */
        @PostMapping(value = "/v1/probe/own-parse", consumes = "text/plain")
        public Map<String, Object> ownParse(@RequestBody String raw) {
            JsonNode tree = mapper.readTree(raw);
            return Map.of("nul", tree.get(0).path("id").asString().contains("\0"), "elements", tree.size());
        }

        /** Come {@code ImportParser.readJsonArray}: parser in streaming e {@code readValueAsTree} per elemento. */
        @PostMapping(value = "/v1/probe/own-parse-stream", consumes = "text/plain")
        public Map<String, Object> ownParseStream(@RequestBody String raw) {
            int elements = 0;
            try (tools.jackson.core.JsonParser p = mapper.createParser(raw)) {
                p.nextToken();
                for (tools.jackson.core.JsonToken t = p.nextToken(); t != tools.jackson.core.JsonToken.END_ARRAY;
                     t = p.nextToken()) {
                    p.readValueAsTree();
                    elements++;
                }
            }
            return Map.of("elements", elements);
        }

        @PostMapping("/v1/probe/body-then-parse")
        public Map<String, Object> bodyThenParse(@RequestBody Body body) {
            JsonNode tree = mapper.readTree("{\"id\":\"a\\u0000b\"}");
            return Map.of("nul", tree.path("id").asString().contains("\0"));
        }

        @GetMapping("/v1/probe/search")
        public Map<String, Object> search(@RequestParam(required = false) String q) {
            return Map.of("q", q == null ? "" : q);
        }

        @GetMapping("/v1/probe/plain")
        public Map<String, Object> plain() {
            plainCalls++;
            return Map.of("ok", true);
        }

        @PostMapping("/v1/probe/submit")
        public Map<String, Object> submit(@RequestParam(required = false) String reason) {
            return Map.of("ok", true);
        }

        @PutMapping("/v1/probe/items/{code}")
        public Map<String, Object> item(@PathVariable String code) {
            itemCalls++;
            return Map.of("code", code);
        }

        @PostMapping("/v1/probe/body")
        public Body body(@RequestBody Body body) {
            return body;
        }

        @PostMapping("/v1/probe/tree")
        public Map<String, Object> tree(@RequestBody JsonNode body) {
            return Map.of("ok", true);
        }

        @NulTolerantBody
        @PostMapping("/v1/probe/tolerant")
        public Map<String, Object> tolerant(@RequestBody Body body) {
            return Map.of("ok", true);
        }

        @NulTolerantBody
        @PostMapping("/v1/probe/tolerant-tree")
        public Map<String, Object> tolerantTree(@RequestBody JsonNode body) {
            return Map.of("ok", true);
        }

        @PostMapping("/v1/probe/object")
        public Map<String, Object> object(@RequestBody Object body) {
            return Map.of("ok", true);
        }
    }
}
