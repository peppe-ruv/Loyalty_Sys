package io.loyaltyhub.common.web;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.loyaltyhub.common.config.LhCommonAutoConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.servlet.context.AnnotationConfigServletWebServerApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.HttpMessageConverters;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.DispatcherServlet;
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
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Codice dell'errore (ADR-052 decisione 3, Q-684, Q-716, F2-QA-06): su un Tomcat vero con la catena dei servizi
 * ({@link CorrelationIdFilter}, {@link ActorFilter}, {@link GlobalExceptionHandler}) il codice di {@code X-Correlation-Id}
 * (o uno generato) sta nell'intestazione di risposta, nella proprietà {@code correlationId} di ogni problema e nell'MDC
 * della riga di log {@code error} con lo stack di un 5xx; l'MDC è pulito a fine richiesta.
 */
class CorrelationIdTest {

    private static final Pattern ULID = Pattern.compile("[0-9A-HJKMNP-TV-Z]{26}");

    private static AnnotationConfigServletWebServerApplicationContext context;
    private static String base;
    private static ListAppender<ILoggingEvent> logs;
    private static Logger handlerLogger;

    @BeforeAll
    static void start() {
        context = new AnnotationConfigServletWebServerApplicationContext();
        context.registerBean("tomcat", TomcatServletWebServerFactory.class, () -> new TomcatServletWebServerFactory(0));
        context.registerBean("dispatcherServlet", DispatcherServlet.class, () -> new DispatcherServlet());
        context.registerBean("probe", Probe.class, Probe::new);
        context.registerBean("advice", GlobalExceptionHandler.class, GlobalExceptionHandler::new);
        LhCommonAutoConfiguration wiring = new LhCommonAutoConfiguration();
        context.registerBean("correlation", CorrelationIdFilter.class, wiring::correlationIdFilter);
        context.registerBean("identity", ActorFilter.class, () -> new ActorFilter("probe"));
        context.register(JacksonAutoConfiguration.class, Mvc.class);
        context.refresh();
        base = "http://localhost:" + context.getWebServer().getPort();
        handlerLogger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        logs = new ListAppender<>();
        logs.start();
        handlerLogger.addAppender(logs);
    }

    @AfterAll
    static void stop() {
        if (handlerLogger != null) {
            handlerLogger.detachAppender(logs);
        }
        if (context != null) {
            context.close();
        }
    }

    @Test
    @DisplayName("un X-Correlation-Id valido è restituito nell'intestazione, anche su una risposta 200")
    void validIdIsEchoed() throws IOException {
        Response r = call("/v1/probe/ok", "01JC8Q3V7M2K9TQX4R1N5B6Y0Z");
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.correlationHeader()).isEqualTo("01JC8Q3V7M2K9TQX4R1N5B6Y0Z");
    }

    @Test
    @DisplayName("senza intestazione il filtro genera un ULID, restituito nella risposta")
    void missingIdIsGenerated() throws IOException {
        Response r = call("/v1/probe/ok", null);
        assertThat(r.correlationHeader()).matches(ULID);
    }

    @Test
    @DisplayName("un valore fuori forma ([A-Za-z0-9-]{1,64}) è scartato: si genera un ULID, mai testo arbitrario")
    void malformedIdIsReplaced() throws IOException {
        for (String bad : List.of("a b", "x".repeat(65), "id;rm -rf", "così")) {
            Response r = call("/v1/probe/ok", bad);
            assertThat(r.correlationHeader()).as(bad).matches(ULID);
        }
    }

    @Test
    @DisplayName("un 404 porta correlationId nel problema, uguale all'intestazione, e non produce righe error")
    void clientErrorCarriesIdWithoutErrorLog() throws IOException {
        logs.list.clear();
        Response r = call("/v1/probe/missing", "ABC-123");
        assertThat(r.status()).isEqualTo(404);
        assertThat(r.problem().path("correlationId").asString()).isEqualTo("ABC-123");
        assertThat(r.correlationHeader()).isEqualTo("ABC-123");
        assertThat(logs.list).noneMatch(e -> e.getLevel().isGreaterOrEqual(Level.ERROR));
    }

    @Test
    @DisplayName("un 422 con errors[] porta comunque correlationId (la schermata lo mostra solo sui guasti)")
    void validationErrorCarriesId() throws IOException {
        Response r = call("/v1/probe/invalid", "VAL-1");
        assertThat(r.status()).isEqualTo(422);
        assertThat(r.problem().path("errors").isArray()).isTrue();
        assertThat(r.problem().path("correlationId").asString()).isEqualTo("VAL-1");
    }

    @Test
    @DisplayName("un 500 imprevisto: problema con correlationId, riga error con lo stack e il codice nell'MDC")
    void unexpectedFailureIsLoggedWithTheId() throws IOException {
        logs.list.clear();
        Response r = call("/v1/probe/boom", "BOOM-1");
        assertThat(r.status()).isEqualTo(500);
        assertThat(r.problem().path("code").asString()).isEqualTo("INTERNAL_ERROR");
        assertThat(r.problem().path("correlationId").asString()).isEqualTo("BOOM-1");
        assertThat(r.correlationHeader()).isEqualTo("BOOM-1");
        assertErrorLoggedWithStack("BOOM-1");
        // nessun dato personale né il messaggio dell'eccezione nella risposta
        assertThat(r.body()).doesNotContain("segreto-interno");
    }

    @Test
    @DisplayName("un 503 (LhException 5xx) è loggato a livello error con lo stack e il codice")
    void serviceUnavailableIsLoggedAsError() throws IOException {
        logs.list.clear();
        Response r = call("/v1/probe/down", "DOWN-1");
        assertThat(r.status()).isEqualTo(503);
        assertThat(r.problem().path("correlationId").asString()).isEqualTo("DOWN-1");
        assertErrorLoggedWithStack("DOWN-1");
    }

    @Test
    @DisplayName("l'MDC è pulito a fine richiesta: due richieste in sequenza non condividono il codice")
    void mdcIsClearedBetweenRequests() throws IOException {
        Response first = call("/v1/probe/ok", "ONE-1");
        Response second = call("/v1/probe/ok", "TWO-2");
        assertThat(first.correlationHeader()).isEqualTo("ONE-1");
        assertThat(second.correlationHeader()).isEqualTo("TWO-2");
        assertThat(org.slf4j.MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
        assertThat(Probe.seenInHandler).containsEntry("ONE-1", "ONE-1").containsEntry("TWO-2", "TWO-2");
    }

    private static void assertErrorLoggedWithStack(String id) {
        List<ILoggingEvent> errors = logs.list.stream().filter(e -> e.getLevel() == Level.ERROR).toList();
        assertThat(errors).hasSize(1);
        ILoggingEvent event = errors.get(0);
        assertThat(event.getMDCPropertyMap()).containsEntry(CorrelationIdFilter.MDC_KEY, id);
        assertThat(event.getThrowableProxy()).as("lo stack è nella riga").isNotNull();
    }

    record Response(int status, String correlationHeader, JsonNode problem, String body) {
    }

    private static Response call(String path, String correlationId) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(base + path).openConnection();
        c.setRequestMethod("GET");
        c.setConnectTimeout(5_000);
        c.setReadTimeout(10_000);
        c.setRequestProperty(ActorFilter.HEADER, "ADMIN:tester");
        if (correlationId != null) {
            c.setRequestProperty(CorrelationIdFilter.HEADER, correlationId);
        }
        int status = c.getResponseCode();
        try (InputStream in = status >= 400 ? c.getErrorStream() : c.getInputStream()) {
            String body = in == null ? "" : new String(in.readAllBytes(), StandardCharsets.UTF_8);
            JsonNode problem = body.isBlank() ? null : context.getBean(JsonMapper.class).readTree(body);
            return new Response(status, c.getHeaderField(CorrelationIdFilter.HEADER), problem, body);
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

    @RestController
    public static class Probe {

        static final Map<String, String> seenInHandler = new java.util.concurrent.ConcurrentHashMap<>();

        @GetMapping("/v1/probe/ok")
        public Map<String, Object> ok() {
            String id = org.slf4j.MDC.get(CorrelationIdFilter.MDC_KEY);
            if (id != null) {
                seenInHandler.put(id, id);
            }
            return Map.of("ok", true);
        }

        @GetMapping("/v1/probe/missing")
        public Map<String, Object> missing() {
            throw LhException.notFound("non c'è");
        }

        @GetMapping("/v1/probe/invalid")
        public Map<String, Object> invalid() {
            throw LhException.validation("VALIDATION", "campo non valido",
                    List.of(new LhException.FieldError("name", "obbligatorio")));
        }

        @GetMapping("/v1/probe/boom")
        public Map<String, Object> boom() {
            throw new IllegalStateException("segreto-interno");
        }

        @GetMapping("/v1/probe/down")
        public Map<String, Object> down() {
            throw LhException.dependencyUnavailable("servizio giù");
        }
    }
}
