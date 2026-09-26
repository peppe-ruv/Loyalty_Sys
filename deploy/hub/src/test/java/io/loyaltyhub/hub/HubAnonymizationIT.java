package io.loyaltyhub.hub;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;

import javax.sql.DataSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Accettazione M7 (docs/12 §M7): "Anonimizzazione di un membro di prova: nessun servizio espone più nome/e-mail (test
 * che interroga tutte le API di gestione); i movimenti restano." (F-MBR-05, BO-03, M7.5).
 * <p>Il test crea un membro con nome, e-mail e telefono riconoscibili, gli dà attività in tutti i servizi (benvenuto,
 * acquisto risolto per e-mail, richiesta di un premio fisico con indirizzo, messaggi, classifica, consegna webhook,
 * audit), verifica che i dati personali <em>ci siano</em> in quelle API, lo anonimizza e verifica che non compaiano più
 * in nessuna risposta, mentre movimenti, richiesta premio e valutazioni restano. Profilo {@code inproc}: nessun broker.
 */
@SpringBootTest(
        classes = HubApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.config.name=hub")
@ActiveProfiles({"demo", "inproc"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HubAnonymizationIT {

    private static final EmbeddedPostgres PG = startPg();
    private static final String ADMIN = "ADMIN:marta.admin";
    private static final String FIRST = "Zefirino";
    private static final String LAST = "Quarantotti";
    private static final String PHONE = "+39 347 1234599";
    private static final String WEBHOOK = "WH-ANON-TEST";

    private final ObjectMapper mapper = new ObjectMapper();
    private final String email = "zefirino.quarantotti." + System.nanoTime() + "@example.org";

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private DataSource dataSource;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        String base = PG.getJdbcUrl("postgres", "postgres");
        registry.add("spring.datasource.url", () -> base + "&currentSchema=ingestion,member,campaign,wallet,insight,reward,gamification,engagement");
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "");
    }

    @AfterAll
    void tearDown() throws Exception {
        PG.close();
    }

    @Test
    void anonymizedMemberDisappearsFromEveryManagementApiButMovementsRemain() {
        // Un webhook sui fatti del membro: la consegna conserva il CloudEvent (con i dati personali).
        send("POST", "/v1/webhooks", Map.of("code", WEBHOOK, "name", "Prova anonimizzazione",
                "url", "https://anonymization-test.invalid/hook",
                "factTypes", List.of("member.registered", "member.updated", "wallet.points.earned")), 201);

        // 1. Membro di prova con dati personali riconoscibili.
        JsonNode created = send("POST", "/v1/members", Map.of("firstName", FIRST, "lastName", LAST, "email", email,
                "phone", PHONE, "city", "Frascati", "channel", "PORTAL"), 201);
        String id = created.path("id").asString();
        await("wallet creato con il bonus di benvenuto", () -> pts(id) >= 100);

        // 2. Attività: acquisto risolto per e-mail (subject email:…), poi un premio fisico con indirizzo di spedizione.
        long before = pts(id);
        Map<String, Object> purchase = Map.of(
                "specversion", "1.0", "id", "anon-" + System.nanoTime(), "source", "urn:loyaltyhub:source:ecommerce",
                "type", "purchase.completed", "subject", "email:" + email, "time", Instant.now().toString(),
                "data", Map.of("orderId", "ORD-ANON-1", "amount", 2000, "currency", "EUR", "channel", "ONLINE"));
        assertThat(send("POST", "/v1/events", purchase, 202, 200).path("status").asString()).isEqualTo("ACCEPTED");
        await("punti dell'acquisto", () -> pts(id) >= before + 1500);

        JsonNode requested = send("POST", "/v1/portal/redemptions", Map.of("memberId", id, "rewardCode", "RWD-BORRACCIA",
                "shipping", Map.of("name", FIRST + " " + LAST, "street", "Via dei Test 9", "zip", "00044", "city", "Frascati")), 202);
        String redemptionId = requested.path("redemptionId").asString();
        await("richiesta premio confermata dal wallet",
                () -> !"PENDING".equals(get("/v1/redemptions/" + redemptionId).path("status").asString()));
        await("messaggio di benvenuto col nome", () -> get("/v1/messages?size=100&memberId=" + id).toString().contains(FIRST));
        await("consegna webhook del member.registered",
                () -> get("/v1/webhooks/" + WEBHOOK + "/deliveries?size=100").toString().contains(id));
        await("insight ha l'audit di creazione", () -> get("/v1/audit?entityId=" + id).path("page").path("totalItems").asInt() >= 1);
        await("in classifica col nickname", () -> get("/v1/leaderboards/LDB-MONTH-PTS/ranking?limit=100").toString()
                .contains(FIRST + " Q."));

        // Prima: i dati personali ci sono davvero, servizio per servizio (il test non è a vuoto).
        String first = FIRST.toLowerCase(Locale.ROOT);
        String mail = email.toLowerCase(Locale.ROOT);
        Map<String, String> personal = dump(id, redemptionId);
        assertThat(personal.get("member")).contains(first).contains(mail);                   // member
        assertThat(personal.get("redemption")).contains("via dei test");                     // reward
        assertThat(personal.get("leaderboard")).contains(first);                             // gamification
        assertThat(personal.get("messages")).contains(first);                                // engagement
        assertThat(personal.get("webhookDetails")).contains(mail);                           // engagement (webhook)
        assertThat(storedInbound(id)).contains(mail).contains(first);                        // ingestion (payload conservato)
        assertThat(personal.get("eventDetails")).contains(first).contains(mail);             // insight
        assertThat(personal.get("auditDetails")).contains(first);                            // insight (audit)
        int ledgerBefore = settledLedgerSize(id);
        assertThat(ledgerBefore).isGreaterThanOrEqualTo(2);

        // 3. Anonimizzazione (BO-03): solo ADMIN, conferma con l'id digitato.
        send("POST", "/v1/members/" + id + "/anonymize", "CARE:paolo.care", Map.of("confirm", id), 403);
        JsonNode anonymized = send("POST", "/v1/members/" + id + "/anonymize", ADMIN, Map.of("confirm", id), 200);
        assertThat(anonymized.path("status").asString()).isEqualTo("ANONYMIZED");
        assertThat(anonymized.path("nickname").asString()).isEqualTo("Membro anonimo");

        // 4. Nessuna API di gestione espone più nome, cognome, e-mail o telefono.
        long deadline = System.currentTimeMillis() + 30_000;
        List<String> leaks = leaks(dump(id, redemptionId));
        while (!leaks.isEmpty() && System.currentTimeMillis() < deadline) {
            sleep();
            leaks = leaks(dump(id, redemptionId));
        }
        assertThat(leaks).as("API che espongono ancora dati personali (sezione:valore)").isEmpty();
        assertThat(get("/v1/events?size=100&q=" + FIRST).path("page").path("totalItems").asInt()).as("ricerca libera nell'event store").isZero();
        assertThat(get("/v1/members?q=" + FIRST).path("page").path("totalItems").asInt()).isZero();

        // Segnaposto dove serviva un nome.
        assertThat(get("/v1/members/" + id).path("nickname").asString()).isEqualTo("Membro anonimo");
        // Le classifiche mostrano solo i membri attivi (LeaderboardRepository.ranking): l'anonimizzato ne esce.
        assertThat(get("/v1/leaderboards/LDB-MONTH-PTS/ranking?limit=100").toString()).doesNotContain(id);
        assertThat(get("/v1/messages?size=100&memberId=" + id).toString()).contains("Membro anonimo");

        // 5. I movimenti restano: libro mastro, saldo, richiesta premio, valutazioni, messaggi, tracciati.
        assertThat(get("/v1/wallets/" + id + "/ledger?limit=100").size()).isEqualTo(ledgerBefore);
        assertThat(pts(id)).isGreaterThan(0);
        JsonNode redemption = get("/v1/redemptions/" + redemptionId);
        assertThat(redemption.path("memberId").asString()).isEqualTo(id);
        assertThat(redemption.hasNonNull("shipping")).as("indirizzo di spedizione cancellato").isFalse();
        assertThat(get("/v1/evaluations?memberId=" + id).size()).isGreaterThanOrEqualTo(1);
        assertThat(get("/v1/events?size=100&memberId=" + id).path("page").path("totalItems").asInt()).isGreaterThan(3);
        assertThat(get("/v1/inbound-events?memberId=" + id).size()).isGreaterThanOrEqualTo(1);

        // 6. Un membro anonimizzato non accumula, non spende, non si rettifica (F-MBR-04).
        Map<String, Object> late = Map.of(
                "specversion", "1.0", "id", "anon-late-" + System.nanoTime(), "source", "urn:loyaltyhub:source:ecommerce",
                "type", "purchase.completed", "subject", "member:" + id, "time", Instant.now().toString(),
                "data", Map.of("orderId", "ORD-ANON-2", "amount", 50, "currency", "EUR", "channel", "ONLINE"));
        assertThat(send("POST", "/v1/events", late, 202, 200, 422).toString()).contains("MEMBER_NOT_ACTIVE");
        assertThat(send("POST", "/v1/wallets/" + id + "/adjustments", Map.of("currency", "PTS", "direction", "CREDIT",
                "amount", 10, "reason", "GOODWILL", "note", "Rettifica di prova dopo l'anonimizzazione"), 409)
                .path("code").asString()).isEqualTo("MEMBER_ANONYMIZED");
        assertThat(send("POST", "/v1/portal/redemptions", Map.of("memberId", id, "rewardCode", "RWD-COFFEE-5"), 422)
                .path("code").asString()).isEqualTo("MEMBER_NOT_ACTIVE");
    }

    // ---------- API di gestione (e del portale) che possono mostrare dati del membro ----------

    private Map<String, String> dump(String id, String redemptionId) {
        Map<String, String> sections = new LinkedHashMap<>();
        Map<String, String> paths = new LinkedHashMap<>();
        paths.put("members", "/v1/members?size=100&q=" + id);
        paths.put("membersAll", "/v1/members?size=100");
        paths.put("member", "/v1/members/" + id);
        paths.put("memberSegments", "/v1/members/" + id + "/segments");
        paths.put("referrals", "/v1/members/" + id + "/referrals");
        paths.put("referralOverview", "/v1/referral/overview");
        paths.put("portalProfile", "/v1/portal/members/" + id);
        paths.put("personas", "/v1/demo/personas");
        paths.put("wallet", "/v1/wallets/" + id);
        paths.put("ledger", "/v1/wallets/" + id + "/ledger?limit=100");
        paths.put("activity", "/v1/portal/wallets/" + id + "/activity");
        paths.put("redemptions", "/v1/redemptions?size=100&memberId=" + id);
        paths.put("redemptionsAll", "/v1/redemptions?size=100");
        paths.put("redemption", "/v1/redemptions/" + redemptionId);
        paths.put("portalRedemptions", "/v1/portal/redemptions?memberId=" + id);
        paths.put("coupons", "/v1/portal/coupons?memberId=" + id);
        paths.put("leaderboard", "/v1/leaderboards/LDB-MONTH-PTS/ranking?limit=100");
        paths.put("leaderboardSts", "/v1/leaderboards/LDB-EDITION-STS/ranking?limit=100");
        paths.put("portalLeaderboard", "/v1/portal/leaderboards/LDB-MONTH-PTS?memberId=" + id);
        paths.put("messages", "/v1/messages?size=100&memberId=" + id);
        paths.put("messagesAll", "/v1/messages?size=100");
        paths.put("inbox", "/v1/portal/inbox?size=50&memberId=" + id);
        paths.put("webhookDeliveries", "/v1/webhooks/" + WEBHOOK + "/deliveries?size=100");
        paths.put("inbound", "/v1/inbound-events?memberId=" + id);
        paths.put("inboundAll", "/v1/inbound-events?limit=500");
        paths.put("events", "/v1/events?size=100&memberId=" + id);
        paths.put("eventsEmail", "/v1/events?size=100&q=" + email);
        paths.put("traces", "/v1/traces?size=100&memberId=" + id);
        paths.put("audit", "/v1/audit?size=100&entityId=" + id);
        paths.put("auditAll", "/v1/audit?size=100");
        paths.put("evaluations", "/v1/evaluations?limit=200&memberId=" + id);
        paths.put("dlq", "/v1/dlq?size=100");
        paths.forEach((name, path) -> sections.put(name, get(path).toString()));

        // Dettagli: consegne webhook (CloudEvent firmato), eventi (payload), tracciati, voci di audit, ingressi.
        sections.put("webhookDetails", details(get(paths.get("webhookDeliveries")).path("items"), "id", "/v1/webhook-deliveries/"));
        sections.put("eventDetails", details(get(paths.get("events")).path("items"), "eventId", "/v1/events/"));
        sections.put("traceDetails", details(get(paths.get("traces")).path("items"), "correlationId", "/v1/traces/"));
        sections.put("auditDetails", details(get(paths.get("audit")).path("items"), "id", "/v1/audit/"));
        sections.put("inboundDetails", details(get(paths.get("inbound")), "id", "/v1/inbound-events/"));
        // Il monitor ingressi non espone (ancora) il payload conservato: lo si legge dal DB dell'hub, stesso JVM.
        sections.put("inboundStored", storedInbound(id));
        sections.replaceAll((k, v) -> v.toLowerCase(Locale.ROOT));
        return sections;
    }

    /** Subject e payload delle righe {@code inbound_event} del membro (anche quelle risolte per e-mail) e l'indice. */
    private String storedInbound(String id) {
        JdbcClient jdbc = JdbcClient.create(dataSource);
        List<String> rows = new ArrayList<>(jdbc.sql(
                        "SELECT subject || ' ' || payload::text || ' ' || coalesce(reject_detail, '') FROM inbound_event WHERE member_id = ?")
                .param(id).query(String.class).list());
        rows.addAll(jdbc.sql("SELECT coalesce(email_lower, '') || ' ' || coalesce(external_id, '') FROM member_index WHERE member_id = ?")
                .param(id).query(String.class).list());
        return String.join("\n", rows).toLowerCase(Locale.ROOT);
    }

    private String details(JsonNode items, String idField, String prefix) {
        List<String> out = new ArrayList<>();
        items.forEach(i -> out.add(get(prefix + i.path(idField).asString()).toString()));
        return String.join("\n", out);
    }

    /** Le sezioni che contengono ancora un dato personale del membro di prova (vuoto = pulito). */
    private List<String> leaks(Map<String, String> sections) {
        List<String> tokens = List.of(FIRST.toLowerCase(Locale.ROOT), LAST.toLowerCase(Locale.ROOT),
                email.toLowerCase(Locale.ROOT), "1234599", "via dei test");
        List<String> out = new ArrayList<>();
        sections.forEach((name, text) -> tokens.stream().filter(text::contains).forEach(t -> out.add(name + ":" + t)));
        return out;
    }

    // ---------- helper ----------

    /**
     * Numero di movimenti del libro mastro a riposo: stesso valore per tre letture consecutive. Le campagne
     * dell'acquisto accreditano in momenti diversi; senza attesa il conteggio "prima" poteva perderne uno.
     */
    private int settledLedgerSize(String memberId) {
        long deadline = System.currentTimeMillis() + 30_000;
        int last = -1;
        int stable = 0;
        while (System.currentTimeMillis() < deadline) {
            int size = get("/v1/wallets/" + memberId + "/ledger?limit=100").size();
            stable = size == last ? stable + 1 : 0;
            last = size;
            if (stable >= 2) {
                return size;
            }
            sleep();
        }
        throw new AssertionError("libro mastro non a riposo entro 30 s: " + last + " movimenti");
    }

    private long pts(String memberId) {
        JsonNode w = client().get().uri("/v1/portal/wallets/" + memberId)
                .exchange((req, res) -> res.getStatusCode().is2xxSuccessful()
                        ? mapper.readTree(new String(res.getBody().readAllBytes())) : mapper.createObjectNode());
        return w.path("balances").path("PTS").path("active").asLong();
    }

    private JsonNode get(String path) {
        return send("GET", path, ADMIN, null, 200);
    }

    private JsonNode send(String method, String path, Object body, int... expected) {
        return send(method, path, ADMIN, body, expected);
    }

    private JsonNode send(String method, String path, String actor, Object body, int... expected) {
        var spec = client().method(HttpMethod.valueOf(method)).uri(path).header("X-LH-Actor", actor);
        if (body != null) {
            spec = spec.contentType(MediaType.APPLICATION_JSON).body(body);
        }
        return spec.exchange((req, res) -> {
            String text = new String(res.getBody().readAllBytes());
            int status = res.getStatusCode().value();
            assertThat(java.util.Arrays.stream(expected).anyMatch(s -> s == status))
                    .as(method + " " + path + " → " + status + " " + text).isTrue();
            return text.isBlank() ? mapper.createObjectNode() : mapper.readTree(text);
        });
    }

    private void await(String what, BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            sleep();
        }
        throw new AssertionError("Condizione non raggiunta entro 30 s: " + what);
    }

    private RestClient client() {
        return RestClient.create("http://localhost:" + port);
    }

    private static void sleep() {
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static EmbeddedPostgres startPg() {
        try {
            return EmbeddedPostgres.builder().start();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
