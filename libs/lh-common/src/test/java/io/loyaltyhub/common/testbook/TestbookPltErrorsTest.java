package io.loyaltyhub.common.testbook;

import io.loyaltyhub.common.web.ActorFilter;
import io.loyaltyhub.common.web.GlobalExceptionHandler;
import io.loyaltyhub.common.web.LhException;
import io.loyaltyhub.common.web.RequiresRole;
import io.loyaltyhub.common.web.RequiresRoleInterceptor;
import io.loyaltyhub.common.web.Role;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.sql.SQLTransientConnectionException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static io.loyaltyhub.common.testbook.TestbookPltSupport.decode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

/**
 * TB-PLT §ERR — errori RFC 9457 {@code application/problem+json} di ogni famiglia (docs/06 §2): {@code type}
 * {@code urn:loyaltyhub:problem:<suffisso>}, {@code title}, {@code status}, {@code detail} in italiano, {@code code}
 * stabile, {@code instance}, {@code errors[]} per i campi. Livello web puro (MockMvc) con il vero
 * {@link GlobalExceptionHandler}, filtro dell'attore e guardia dei ruoli; nessun dettaglio interno esce nel corpo.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TestbookPltErrorsTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private MockMvc mvc;

    @BeforeAll
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new Probe())
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilters(new ActorFilter("probe"))
                .addInterceptors(new RequiresRoleInterceptor())
                .build();
    }

    /** Endpoint di prova: ognuno solleva un errore di una famiglia. */
    @RestController
    public static class Probe {
        @GetMapping("/v1/probe/lh/{kind}")
        public Map<String, Object> lh(@PathVariable String kind) {
            throw switch (kind) {
                case "badRequest" -> LhException.badRequest("Parametro memberId obbligatorio.");
                case "forbidden" -> LhException.forbiddenRole("Il ruolo ANALYST non può eseguire questa operazione");
                case "notFound" -> LhException.notFound("Membro non trovato: MBR-999999");
                case "conflict" -> LhException.conflict("INVALID_TRANSITION", "Transizione non ammessa da LIVE con SUBMIT");
                case "validation" -> LhException.validation("REWARD_OUT_OF_STOCK", "Il premio non è più disponibile",
                        List.of(new LhException.FieldError("rewardCode", "esaurito")));
                case "validationNoFields" -> LhException.validation("SHIPPING_REQUIRED", "Indirizzo di spedizione obbligatorio");
                case "gone" -> LhException.gone("COUPON_EXPIRED", "Coupon scaduto");
                case "dependency" -> LhException.dependencyUnavailable("Il servizio ingestion non risponde");
                default -> new IllegalArgumentException(kind);
            };
        }

        @GetMapping("/v1/probe/unexpected")
        public Map<String, Object> unexpected() {
            throw new IllegalStateException("segreto interno: password=s3gr3t0 in com.example.Internal");
        }

        @GetMapping("/v1/probe/db-down")
        public Map<String, Object> dbDown() {
            throw new CannotGetJdbcConnectionException("Failed to obtain JDBC Connection",
                    new SQLTransientConnectionException("HikariPool-1 - Connection is not available, request timed out after 20000ms"));
        }

        @GetMapping("/v1/probe/tx-down")
        public Map<String, Object> txDown() {
            throw new CannotCreateTransactionException("Could not open JDBC Connection for transaction");
        }

        @PostMapping("/v1/probe/body")
        public Map<String, Object> body(@RequestBody Payload payload) {
            return Map.of("n", payload.n());
        }

        @GetMapping("/v1/probe/param")
        public Map<String, Object> param(@RequestParam int n) {
            return Map.of("n", n);
        }

        @GetMapping("/v1/probe/only-get")
        public Map<String, Object> onlyGet() {
            return Map.of("ok", true);
        }

        @PostMapping("/v1/probe/bean")
        public Map<String, Object> bean() throws Exception {
            BeanPropertyBindingResult result = new BeanPropertyBindingResult(new Payload(0, ""), "payload");
            result.rejectValue("n", "Min", "deve essere maggiore di 0");
            result.rejectValue("label", "NotBlank", "obbligatorio");
            throw new MethodArgumentNotValidException(
                    new MethodParameter(Probe.class.getMethod("bean"), -1), result);
        }

        @PostMapping("/v1/probe/write")
        @RequiresRole
        public Map<String, Object> write() {
            return Map.of("ok", true);
        }

        @PostMapping("/v1/probe/admin")
        @RequiresRole(Role.ADMIN)
        public Map<String, Object> admin() {
            return Map.of("ok", true);
        }
    }

    public record Payload(int n, String label) {
    }

    @ParameterizedTest(name = "[{0}] {1}", quoteTextArguments = false)
    @CsvFileSource(resources = "/testbook/plt/errors.csv", numLinesToSkip = 1)
    void problem(String id, String description, String method, String path, String contentType, String body, String actor,
                 int status, String type, String code, String title, String detail, String errors) throws Exception {
        MockHttpServletRequestBuilder req = request(HttpMethod.valueOf(method), path);
        if (!"-".equals(contentType)) {
            req = req.contentType(contentType);
        }
        String payload = decode(body);
        if (payload != null && !"-".equals(payload)) {
            req = req.content(payload);
        }
        if (!"-".equals(actor)) {
            req = req.header("X-LH-Actor", actor);
        }
        MockHttpServletResponse res = mvc.perform(req).andReturn().getResponse();
        String text = res.getContentAsString(StandardCharsets.UTF_8);
        String what = id + ": " + description + " → " + res.getStatus() + " " + text;
        assertThat(res.getStatus()).as(what).isEqualTo(status);
        if ("-".equals(type)) {
            return; // risposta di successo: nessun problem
        }
        assertThat(res.getContentType()).as(what).startsWith("application/problem+json");
        JsonNode p = mapper.readTree(text);
        assertThat(p.path("type").asString()).as(what).isEqualTo("urn:loyaltyhub:problem:" + type);
        assertThat(p.path("status").asInt()).as(what).isEqualTo(status);
        assertThat(p.path("code").asString()).as(what).isEqualTo(code);
        assertThat(p.path("title").asString()).as(what).isEqualTo(title);
        assertThat(p.path("detail").asString()).as(what).contains(detail);
        assertThat(p.path("instance").asString()).as(what).isEqualTo(path.replaceAll("\\?.*$", ""));
        List<String> fields = new ArrayList<>();
        p.path("errors").forEach(e -> fields.add(e.path("field").asString() + ":" + e.path("message").asString()));
        assertThat(fields.isEmpty() ? "-" : String.join(" ", fields)).as(what).isEqualTo(errors);
        // Nessun dettaglio interno (messaggi del parser, classi, segreti) nel corpo del problem.
        assertThat(text).as(what).doesNotContain("segreto", "password", "Exception", "HikariPool", "com.example");
    }
}
