package io.loyaltyhub.hub;

import io.loyaltyhub.testsupport.SourceActors;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TB-SEC nell'hub consolidato (docs/testbook/TB-SEC-sicurezza.md, docs/18 §3.10 punti 4, 5 e 12, ADR-042; M8.11c):
 * input ostili sulle API vere e errori che non rivelano nulla. Tre regole (R-01…R-03 del documento):
 * <ol>
 *   <li>nessun input produce un {@code 5xx};</li>
 *   <li>un'iniezione non ha effetto: le letture lasciano invariato lo stato, un filtro ostile non allarga il risultato,
 *       i template e i campi non sono valutati, nessuna intestazione iniettata nella risposta;</li>
 *   <li>le risposte d'errore non contengono stack trace né dettagli interni (SQL, driver, percorsi, versioni).</li>
 * </ol>
 * Un solo contesto Spring (profili {@code demo,inproc}); righe in {@code /testbook/sec/*.csv}. Il fuzzing notturno di
 * Schemathesis e lo ZAP API scan (workflow {@code security-nightly}) coprono le stesse regole su ogni operazione di
 * {@code contracts/api}; questa classe è il minimo deterministico che gira a ogni {@code ./mvnw verify}.
 * Non chiama gli endpoint {@code /v1/portal/**}: li sta rifacendo M8.10f (BOLA resta pianificato lì).
 */
@SpringBootTest(
        classes = {HubApplication.class, TestbookSecHubIT.Today.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.config.name=hub", "loyaltyhub.outbox.relay-interval-ms=50"})
@ActiveProfiles({"demo", "inproc"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TestbookSecHubIT extends TestbookPltSupportIT {

    private static final EmbeddedPostgres PG = startPg();

    private static final Set<String> LIST_TARGETS = Set.of("members-q", "campaigns-q", "rewards-q", "events-q");
    private static final Set<String> READ_TARGETS = Set.of("members-q", "campaigns-q", "rewards-q", "events-q", "member-id", "render-tpl");
    private static final Set<String> WRITE_TARGETS = Set.of("member-create", "ingest-data");

    /** Frammenti che in una risposta rivelano l'interno: eccezioni, driver, contenitore, percorsi dell'immagine. */
    private static final List<String> LEAKS = List.of("\"trace\"", "\"exception\"", "stacktrace", "at io.loyaltyhub", "at org.",
            "at java.", "java.lang.", "org.springframework", "org.postgresql", "psqlexception", "sqlstate", "jdbc:", "hikari",
            "apache tomcat", "/opt/lh");
    private static final Pattern SQL_TEXT = Pattern.compile("\\bselect\\b[^\\n]{0,200}\\bfrom\\b");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        datasource(registry, PG);
    }

    @AfterAll
    void stop() throws IOException {
        PG.close();
    }

    static class Today {
        @Bean
        @Primary
        Clock testbookClock() {
            return startingAt("2026-09-24T10:00:00Z");
        }
    }

    // ================= righe =================

    /**
     * Un caso per riga di {@code /testbook/sec/<file>}: nome «[ID] descrizione». Una riga con divergenza registrata si
     * salta (l'esito è «saltata», non «passata»), come dice docs/16 §12.
     */
    static Stream<DynamicTest> sec(String file, Consumer<Row> body) {
        return sec(file, r -> true, body);
    }

    /** Come {@link #sec(String, Consumer)}, sulle sole righe che passano il filtro (una riga = un solo caso). */
    static Stream<DynamicTest> sec(String file, Predicate<Row> filter, Consumer<Row> body) {
        String resource = "/testbook/sec/" + file;
        return read(resource).stream().filter(filter).map(r -> DynamicTest.dynamicTest("[" + r.id() + "] " + r.desc(),
                URI.create("classpath:" + resource + "?line=" + r.line()), () -> {
                    // SPEC-GAP: Q-538 — una riga con divergenza è documentata e saltata, non nascosta: docs/16 §12.
                    if (!r.blank("divergenza")) {
                        Assumptions.abort("DIVERGENZA " + r.get("divergenza") + ": docs/16 §12");
                    }
                    body.accept(r);
                }));
    }

    // ================= HTTP =================

    /** Risposta grezza: stato, intestazioni e corpo testuale. */
    record Reply(int status, HttpHeaders headers, String body) {
        String describe() {
            String text = body == null ? "" : body;
            return status + " " + (text.length() > 300 ? text.substring(0, 300) + "…" : text);
        }
    }

    private Reply http(String method, String path, String actor, String contentType, String body) {
        try {
            var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).timeout(Duration.ofSeconds(60));
            if (actor != null) {
                builder.header("X-LH-Actor", actor);
            }
            if (contentType != null) {
                builder.header("Content-Type", contentType);
            }
            builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
            try (var client = HttpClient.newHttpClient()) {
                HttpResponse<String> res = client.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                return new Reply(res.statusCode(), res.headers(), res.body());
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** Codifica di un valore in query o percorso: UTF-8, spazio come {@code %20}. */
    static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /** Il testo del carico per il suo nome in {@code fuz.csv} (docs/testbook/TB-SEC-sicurezza.md §3). */
    static String payload(String token) {
        return switch (token) {
            case "SQL_TAUTOLOGIA" -> "' OR '1'='1' --";
            case "SQL_IMPILATA" -> "x'; DROP TABLE member.member; --";
            case "SQL_UNION" -> "') UNION SELECT current_user, version() --";
            case "NUL" -> "a\0b";
            case "LUNGO_10K" -> "a".repeat(10_000);
            case "TRAVERSAL" -> "../../../../etc/passwd";
            case "CRLF" -> "x\r\nX-Injected: 1";
            case "ESPRESSIONE" -> "${7*7}#{7*7}{{7*7}}";
            default -> throw new IllegalArgumentException("carico sconosciuto: " + token);
        };
    }

    /** {@code null} se la risposta non rivela nulla dell'interno; altrimenti il frammento che la tradisce. */
    static String disclosure(String body) {
        if (body == null) {
            return null;
        }
        String text = body.toLowerCase(Locale.ROOT);
        for (String leak : LEAKS) {
            if (text.contains(leak)) {
                return leak;
            }
        }
        return SQL_TEXT.matcher(text).find() ? "testo SQL (select … from)" : null;
    }

    /** Controlli comuni a ogni riga: nessun 5xx (R-01), nessuna fuga (R-03), nessuna intestazione iniettata (R-02). */
    private static void assertSafe(String id, Reply r) {
        String what = id + " → " + r.describe();
        assertThat(r.status()).as("nessun 5xx: " + what).isLessThan(500);
        assertThat(disclosure(r.body())).as("nessuna fuga di dettagli interni: " + what).isNull();
        assertThat(r.headers().firstValue("X-Injected")).as("intestazione iniettata: " + what).isEmpty();
    }

    /** Il carico è arrivato all'applicazione: una scrittura respinta prima (401, 403, 404, 429) non prova nulla. */
    private static void assertReachedApp(String id, Reply r) {
        assertThat(r.status()).as("il carico arriva all'applicazione: " + id + " → " + r.describe())
                .isNotIn(401, 403, 404, 429);
    }

    // ================= @Order(1): letture ostili =================

    @TestFactory
    @Order(1)
    Stream<DynamicTest> hostileReads() {
        return sec("fuz.csv", row -> READ_TARGETS.contains(row.get("bersaglio")), row -> {
            String target = row.get("bersaglio");
            String token = row.get("carico");
            String p = payload(token);
            quiet();
            var before = fingerprint();

            Reply r = switch (target) {
                case "members-q" -> http("GET", "/v1/members?q=" + enc(p), ADMIN, null, null);
                case "campaigns-q" -> http("GET", "/v1/campaigns?q=" + enc(p), ADMIN, null, null);
                case "rewards-q" -> http("GET", "/v1/rewards?q=" + enc(p), ADMIN, null, null);
                case "events-q" -> http("GET", "/v1/events?q=" + enc(p), ADMIN, null, null);
                case "member-id" -> http("GET", "/v1/members/" + enc(p), ADMIN, null, null);
                case "render-tpl" -> {
                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("titleTpl", p);
                    body.put("bodyTpl", p);
                    body.put("memberId", "MBR-000002");
                    yield http("POST", "/v1/message-templates/MSG-POINTS-EARNED/render", ADMIN, "application/json",
                            mapper.writeValueAsString(body));
                }
                default -> throw new IllegalArgumentException(target);
            };
            assertSafe(row.id(), r);

            if (LIST_TARGETS.contains(target) && token.startsWith("SQL_")) {
                // Con l'identità ADMIN l'elenco risponde 200: un 401/403/429 vorrebbe dire che il carico non è arrivato
                // al filtro e il controllo «non allargato» qui sotto sarebbe vuoto.
                assertThat(r.status()).as("l'elenco risponde 200 al carico SQL: " + r.describe()).isEqualTo(200);
            }
            if (LIST_TARGETS.contains(target) && r.status() == 200) {
                // Nessun record del seed contiene questi testi: un elenco non vuoto vuol dire che il filtro è stato
                // allargato (per esempio da un OR '1'='1' arrivato al testo SQL).
                JsonNode json = mapper.readTree(r.body());
                JsonNode items = json.isArray() ? json : json.path("items");
                assertThat(items.isArray()).as("forma dell'elenco: " + r.describe()).isTrue();
                assertThat(items.size()).as("il filtro ostile non allarga il risultato: " + r.describe()).isZero();
            }
            if ("render-tpl".equals(target) && "ESPRESSIONE".equals(token) && r.status() == 200) {
                JsonNode json = mapper.readTree(r.body());
                assertThat(json.path("title").asString("") + "|" + json.path("body").asString(""))
                        .as("il template non valuta espressioni: " + r.describe()).doesNotContain("49");
            }

            quiet();
            assertSame(fingerprint(), before, row.id());
        });
    }

    // ================= @Order(2): scritture ostili =================

    @TestFactory
    @Order(2)
    Stream<DynamicTest> hostileWrites() {
        return sec("fuz.csv", row -> WRITE_TARGETS.contains(row.get("bersaglio")), row -> {
            String target = row.get("bersaglio");
            String p = payload(row.get("carico"));
            String tag = row.id().toLowerCase(Locale.ROOT);
            if ("member-create".equals(target)) {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("firstName", p);
                body.put("lastName", "Prova");
                body.put("email", "tb-sec-" + tag + "@example.org");
                body.put("channel", "PORTAL");
                Reply r = http("POST", "/v1/members", ADMIN, "application/json", mapper.writeValueAsString(body));
                assertSafe(row.id(), r);
                assertReachedApp(row.id(), r);
                if (r.status() / 100 == 2) {
                    String id = mapper.readTree(r.body()).path("id").asString();
                    Reply back = http("GET", "/v1/members/" + enc(id), ADMIN, null, null);
                    assertThat(back.status()).as("lettura del membro creato: " + back.describe()).isEqualTo(200);
                    assertThat(mapper.readTree(back.body()).path("firstName").asString(""))
                            .as("il nome non è valutato come espressione").doesNotContain("49");
                }
            } else {
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("orderId", p);
                data.put("amount", 10);
                data.put("currency", "EUR");
                data.put("channel", "ONLINE");
                Map<String, Object> event = new LinkedHashMap<>();
                event.put("specversion", "1.0");
                event.put("id", "tb-sec-" + tag);
                event.put("source", "urn:loyaltyhub:source:ecommerce");
                event.put("type", "purchase.completed");
                event.put("subject", "member:MBR-000002");
                event.put("time", clock.instant().toString());
                event.put("data", data);
                Reply r = http("POST", "/v1/events", SourceActors.FALLBACK, "application/json", mapper.writeValueAsString(event));
                assertSafe(row.id(), r);
                assertReachedApp(row.id(), r);
            }
            quiet();
        });
    }

    // ================= @Order(3): errori senza fughe =================

    @TestFactory
    @Order(3)
    Stream<DynamicTest> errorsWithoutLeaks() {
        return sec("err.csv", row -> {
            Reply r = http(row.get("metodo"), row.get("percorso"), row.blank("attore") ? null : row.get("attore"),
                    row.blank("contentType") ? null : row.get("contentType"), row.blank("corpo") ? null : row.get("corpo"));
            String what = row.id() + " → " + r.describe();
            if (!"qualsiasi".equals(row.get("stato"))) {
                assertThat(String.valueOf(r.status())).as(what).isEqualTo(row.get("stato"));
            }
            assertThat(disclosure(r.body())).as("nessuna fuga di dettagli interni: " + what).isNull();
        });
    }
}
