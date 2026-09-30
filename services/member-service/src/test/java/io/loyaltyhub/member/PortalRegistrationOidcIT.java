package io.loyaltyhub.member;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Registrazione dal portale nel profilo {@code enterprise} (PT-16, F-MBR-06, F2-IAM-03, Q-157, Q-551, Q-552, ADR-048):
 * token RS256 veri. Il membro nasce dal {@code sub} del token con id e canale assegnati dal servizio; la registrazione è
 * idempotente sul {@code sub}; sul bus viaggia solo {@code subjectRef}; un operatore non registra membri.
 */
class PortalRegistrationOidcIT extends OidcPortalSupport {

    @Test
    @DisplayName("[M8.2] 201 alla prima registrazione, 200 con lo stesso id dopo; un solo member.registered con subjectRef e senza il sub")
    void registersOnceAndIsIdempotent() throws Exception {
        String sub = newSub();
        Map<String, Object> body = new HashMap<>(registration(sub));
        // Campi che il client non può decidere: ignorati (id, canale e stato li assegna il servizio).
        body.put("externalId", "CRM-999999");
        body.put("status", "BLOCKED");
        body.put("channel", "STORE");
        body.put("gender", "F");

        Reply first = call("POST", "/v1/portal/members", TOKENS.member(sub), body);
        assertThat(first.status()).as(first.text()).isEqualTo(201);
        assertThat(first.headers().getFirst("Location")).isEqualTo("/v1/portal/me/profile");
        String id = first.body().path("memberId").asString();
        assertThat(id).matches("MBR-\\d{6}");
        assertThat(first.body().path("status").asString()).isEqualTo("ACTIVE");
        assertThat(first.body().path("email").asString()).isEqualTo(profileEmail(sub));
        assertThat(first.body().path("referralCode").asString()).hasSize(8);

        Reply second = call("POST", "/v1/portal/members", TOKENS.member(sub), registration(sub));
        assertThat(second.status()).as(second.text()).isEqualTo(200);
        assertThat(second.body().path("memberId").asString()).isEqualTo(id);
        // Anche con un altro corpo: il legame è quello del token, nessun secondo membro.
        Reply third = call("POST", "/v1/portal/members", TOKENS.member(sub),
                Map.of("firstName", "Altro", "lastName", "Nome", "email", "altro." + profileEmail(sub)));
        assertThat(third.status()).as(third.text()).isEqualTo(200);
        assertThat(third.body().path("memberId").asString()).isEqualTo(id);

        // Il membro ha id e canale del servizio, nessun externalId, lo stato ACTIVE e un solo legame.
        Map<String, Object> row = jdbc.sql("SELECT external_id, channel, status, gender FROM member WHERE id = ?").param(id)
                .query((rs, n) -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("external_id", rs.getString("external_id"));
                    m.put("channel", rs.getString("channel"));
                    m.put("status", rs.getString("status"));
                    m.put("gender", rs.getString("gender"));
                    return m;
                }).single();
        assertThat(row.get("external_id")).isNull();
        assertThat(row.get("channel")).isEqualTo("PORTAL");
        assertThat(row.get("status")).isEqualTo("ACTIVE");
        assertThat(count("SELECT count(*) FROM member WHERE lower(email) = lower(?)", profileEmail(sub))).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM member_identity WHERE member_id = ?", id)).isEqualTo(1);
        // Il sub sta solo nel database del servizio; lo pseudonimo è l'HMAC atteso.
        Map<String, Object> link = jdbc.sql("SELECT issuer, subject, subject_ref FROM member_identity WHERE member_id = ?")
                .param(id).query((rs, n) -> Map.<String, Object>of("issuer", rs.getString(1), "subject", rs.getString(2),
                        "ref", rs.getString(3))).single();
        assertThat(link.get("issuer")).isEqualTo(TOKENS.issuer());
        assertThat(link.get("subject")).isEqualTo(sub);
        assertThat(link.get("ref")).isEqualTo(refOf(sub));

        List<JsonNode> registered = published(FACTS, "member:" + id, REGISTERED);
        assertThat(registered).as("un solo member.registered, anche dopo i tentativi ripetuti").hasSize(1);
        JsonNode data = registered.get(0).path("data");
        assertThat(data.path("memberId").asString()).isEqualTo(id);
        assertThat(data.path("subjectRef").asString()).isEqualTo(refOf(sub));
        assertThat(data.path("channel").asString()).isEqualTo("PORTAL");
        assertThat(data.path("status").asString()).isEqualTo("ACTIVE");
        assertThat(data.hasNonNull("externalId")).isFalse();
        // Nessun sub né dati del token sul bus, nei fatti come nell'audit (regola 20, ADR-032).
        List<JsonNode> audit = published(AUDIT, "MEMBER:" + id, AUDIT_ENTRY);
        assertThat(audit).hasSize(1);
        assertThat(audit.get(0).path("data").path("action").asString()).isEqualTo("CREATE");
        assertThat(audit.get(0).path("lhactor").asString()).isEqualTo("member:" + id);
        for (JsonNode event : List.of(registered.get(0), audit.get(0))) {
            assertThat(event.toString()).doesNotContain(sub)
                    .doesNotContain(io.loyaltyhub.testsupport.OidcTestTokens.usernameOf(sub))
                    .doesNotContain(io.loyaltyhub.testsupport.OidcTestTokens.emailOf(sub));
        }
        assertThat(ACTORS).anyMatch(a -> a.startsWith("POST /v1/portal/members -> holder=member:" + id));
    }

    @Test
    @DisplayName("[M8.2] un memberId nel corpo non conta (l'id lo assegna il servizio); in query o header è 400 MEMBER_FROM_TOKEN")
    void memberIdFromTheRequestIsNeverHonoured() {
        String sub = newSub();
        Map<String, Object> body = new HashMap<>(registration(sub));
        body.put("memberId", "MBR-000001");
        Reply query = call("POST", "/v1/portal/members?memberId=MBR-000001", TOKENS.member(sub), registration(sub));
        assertThat(query.status()).isEqualTo(400);
        assertThat(query.code()).isEqualTo("MEMBER_FROM_TOKEN");
        Reply header = call("POST", "/v1/portal/members", TOKENS.member(sub), registration(sub), "X-LH-Member", "MBR-000001");
        assertThat(header.status()).isEqualTo(400);
        assertThat(header.code()).isEqualTo("MEMBER_FROM_TOKEN");
        assertThat(count("SELECT count(*) FROM member_identity WHERE subject = ?", sub)).isZero();
        assertThat(count("SELECT count(*) FROM member WHERE lower(email) = lower(?)", profileEmail(sub))).isZero();

        // SPEC-GAP: Q-573 — nel corpo il campo non esiste nel contratto: è ignorato (non 400, docs/06 §3.4), il membro nuovo non è MBR-000001.
        Reply r = call("POST", "/v1/portal/members", TOKENS.member(sub), body);
        assertThat(r.status()).as(r.text()).isEqualTo(201);
        assertThat(r.body().path("memberId").asString()).isNotEqualTo("MBR-000001").matches("MBR-\\d{6}");
        assertThat(count("SELECT count(*) FROM member_identity WHERE subject = ? AND member_id = ?", sub,
                r.body().path("memberId").asString())).isEqualTo(1);
    }

    @Test
    @DisplayName("[M8.2] un operatore, un token misto o MEMBER+SOURCE non registrano membri: 403, nulla salvato")
    void operatorsDoNotRegisterMembers() {
        String sub = newSub();
        for (String token : List.of(TOKENS.operator("marta", "ADMIN"), TOKENS.operator("paolo", "CARE"),
                TOKENS.mixed(sub, "CARE"), TOKENS.mixed(sub, "ADMIN"))) {
            Reply r = call("POST", "/v1/portal/members", token, registration(sub));
            assertThat(r.status()).as(r.text()).isEqualTo(403);
            assertThat(r.code()).isEqualTo("MEMBER_REQUIRED");
        }
        Reply source = call("POST", "/v1/portal/members", TOKENS.memberSource(sub, "src-ecommerce"), registration(sub));
        assertThat(source.status()).as(source.text()).isEqualTo(403);
        assertThat(count("SELECT count(*) FROM member WHERE lower(email) = lower(?)", profileEmail(sub))).isZero();
        assertThat(count("SELECT count(*) FROM member_identity WHERE subject = ?", sub)).isZero();
    }

    @Test
    @DisplayName("[M8.2] senza token, scaduto o firmato con un'altra chiave: 401")
    void invalidTokens() {
        String sub = newSub();
        assertThat(call("POST", "/v1/portal/members", null, registration(sub), "X-LH-Actor", "ADMIN:intruso").status())
                .isEqualTo(401);
        assertThat(call("POST", "/v1/portal/members", TOKENS.expired(sub), registration(sub)).status()).isEqualTo(401);
        assertThat(call("POST", "/v1/portal/members", TOKENS.foreignKey(sub), registration(sub)).status()).isEqualTo(401);
        assertThat(call("POST", "/v1/portal/members", TOKENS.wrongAudience(sub), registration(sub)).status()).isEqualTo(401);
        assertThat(count("SELECT count(*) FROM member WHERE lower(email) = lower(?)", profileEmail(sub))).isZero();
    }

    @Test
    @DisplayName("[M8.2] un account non registrato: /me/profile e /me/referral danno 404 MEMBER_NOT_REGISTERED")
    void unregisteredAccountIsNotFound() {
        String token = TOKENS.member(newSub());
        for (String path : List.of("/v1/portal/me/profile", "/v1/portal/me/referral")) {
            Reply r = call("GET", path, token);
            assertThat(r.status()).as(path + " " + r.text()).isEqualTo(404);
            assertThat(r.code()).isEqualTo("MEMBER_NOT_REGISTERED");
        }
        assertThat(call("PATCH", "/v1/portal/me/profile", token, Map.of("city", "Roma")).status()).isEqualTo(404);
    }

    @Test
    @DisplayName("[M8.2] l'e-mail di un altro membro: 409 EMAIL_TAKEN, il primo resta intatto e il secondo account non è legato")
    void emailOfAnotherMemberConflicts() {
        String subA = newSub();
        String idA = register(subA);
        String subB = newSub();
        Reply r = call("POST", "/v1/portal/members", TOKENS.member(subB), registration(subA));
        assertThat(r.status()).as(r.text()).isEqualTo(409);
        assertThat(r.code()).isEqualTo("EMAIL_TAKEN");
        assertThat(count("SELECT count(*) FROM member_identity WHERE subject = ?", subB)).isZero();
        assertThat(call("GET", "/v1/portal/me/profile", TOKENS.member(subA)).body().path("memberId").asString())
                .isEqualTo(idA);
        // Non c'è nessun collegamento per e-mail (Q-557, D11): B non vede A.
        assertThat(call("GET", "/v1/portal/me/profile", TOKENS.member(subB)).status()).isEqualTo(404);
    }

    @Test
    @DisplayName("[M8.2] richieste concorrenti dello stesso account: un solo membro, un solo 201, un solo member.registered")
    void concurrentRegistrationsCreateOneMember() throws Exception {
        String sub = newSub();
        ACTORS.clear();
        int threads = 4;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<Reply>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                Callable<Reply> task = () -> {
                    start.await();
                    return call("POST", "/v1/portal/members", TOKENS.member(sub), registration(sub));
                };
                futures.add(pool.submit(task));
            }
            start.countDown();
            List<Reply> replies = new ArrayList<>();
            for (Future<Reply> f : futures) {
                replies.add(f.get());
            }
            assertThat(replies).extracting(Reply::status).as(replies.toString()).allMatch(s -> s == 200 || s == 201);
            assertThat(replies.stream().filter(r -> r.status() == 201).count()).isEqualTo(1);
            Set<String> ids = new java.util.HashSet<>();
            replies.forEach(r -> ids.add(r.body().path("memberId").asString()));
            assertThat(ids).hasSize(1);
            String id = ids.iterator().next();
            assertThat(count("SELECT count(*) FROM member WHERE lower(email) = lower(?)", profileEmail(sub))).isEqualTo(1);
            assertThat(count("SELECT count(*) FROM member_identity WHERE subject = ?", sub)).isEqualTo(1);
            assertThat(published(FACTS, "member:" + id, REGISTERED)).hasSize(1);
            // Anche chi ha perso la corsa risponde con il membro esistente: l'attore (e l'MDC del log) non è mai un id annullato.
            long deadline = System.currentTimeMillis() + 5_000;
            while (ACTORS.stream().filter(a -> a.startsWith("POST /v1/portal/members -> ")).count() < threads
                    && System.currentTimeMillis() < deadline) {
                Thread.sleep(20);
            }
            List<String> actors = ACTORS.stream().filter(a -> a.startsWith("POST /v1/portal/members -> ")).toList();
            assertThat(actors).hasSize(threads)
                    .allMatch(a -> a.equals("POST /v1/portal/members -> holder=member:" + id + " mdc=member:" + id), actors.toString());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("[M8.2] l'e-mail è obbligatoria: 400, nessun membro né legame")
    void emailIsRequired() {
        String sub = newSub();
        Reply r = call("POST", "/v1/portal/members", TOKENS.member(sub), Map.of("firstName", "Senza", "lastName", "Posta"));
        assertThat(r.status()).as(r.text()).isEqualTo(400);
        assertThat(count("SELECT count(*) FROM member_identity WHERE subject = ?", sub)).isZero();
    }

    @Test
    @DisplayName("[M8.2] il codice invito lega l'invitante come in demo; un codice inesistente è 422 e nulla nasce")
    void referralCodeIsHonoured() {
        String subA = newSub();
        String idA = register(subA);
        String code = call("GET", "/v1/portal/me/referral", TOKENS.member(subA)).body().path("code").asString();
        assertThat(code).hasSize(8);

        String subB = newSub();
        Map<String, Object> body = new HashMap<>(registration(subB));
        body.put("referralCode", "ZZZZZZZZ");
        Reply bad = call("POST", "/v1/portal/members", TOKENS.member(subB), body);
        assertThat(bad.status()).as(bad.text()).isEqualTo(422);
        assertThat(count("SELECT count(*) FROM member_identity WHERE subject = ?", subB)).isZero();

        body.put("referralCode", code);
        Reply ok = call("POST", "/v1/portal/members", TOKENS.member(subB), body);
        assertThat(ok.status()).as(ok.text()).isEqualTo(201);
        String idB = ok.body().path("memberId").asString();
        assertThat(jdbc.sql("SELECT referred_by FROM member WHERE id = ?").param(idB).query(String.class).single())
                .isEqualTo(idA);
    }
}
