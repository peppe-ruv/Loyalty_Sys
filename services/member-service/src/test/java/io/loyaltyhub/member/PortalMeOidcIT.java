package io.loyaltyhub.member;

import io.loyaltyhub.testsupport.OidcTestTokens;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Il profilo e il referral del membro dal token (PT-08, PT-11, F-MBR-07, F2-SEC-09, Q-410, Q-553, Q-554, Q-556, ADR-048):
 * token RS256 veri, membri A e B registrati dall'API. Il membro viene solo dal token: nessun {@code memberId} in query,
 * corpo o header, nessun id nel percorso; un operatore non agisce da membro; l'attore è {@code member:<id>} e mai
 * {@code preferred_username} o e-mail (regola 20).
 */
class PortalMeOidcIT extends OidcPortalSupport {

    @Test
    @DisplayName("[M8.2] A legge il proprio profilo e il proprio referral: 200, mai i dati di B")
    void ownProfileAndReferral() {
        String subA = newSub();
        String subB = newSub();
        String idA = register(subA);
        String idB = register(subB);

        Reply profile = call("GET", "/v1/portal/me/profile", TOKENS.member(subA));
        assertThat(profile.status()).as(profile.text()).isEqualTo(200);
        assertThat(profile.body().path("memberId").asString()).isEqualTo(idA);
        assertThat(profile.body().path("status").asString()).isEqualTo("ACTIVE");
        assertThat(profile.body().path("email").asString()).isEqualTo(profileEmail(subA));
        assertThat(profile.text()).doesNotContain(idB).doesNotContain(profileEmail(subB));

        Reply referral = call("GET", "/v1/portal/me/referral", TOKENS.member(subA));
        assertThat(referral.status()).as(referral.text()).isEqualTo(200);
        assertThat(referral.body().path("code").asString()).isEqualTo(profile.body().path("referralCode").asString());
        assertThat(referral.text()).doesNotContain(idB);

        // B vede B.
        assertThat(call("GET", "/v1/portal/me/profile", TOKENS.member(subB)).body().path("memberId").asString())
                .isEqualTo(idB);
        assertThat(ACTORS).anyMatch(a -> a.startsWith("GET /v1/portal/me/profile -> holder=member:" + idA + " mdc=member:" + idA));
    }

    @Test
    @DisplayName("[M8.2] BOLA: memberId di B in query o header → 400, anche il proprio; id nel percorso legacy → 403")
    void memberFromRequestIsRejected() {
        String subA = newSub();
        String subB = newSub();
        String idA = register(subA);
        String idB = register(subB);
        String token = TOKENS.member(subA);

        for (String query : List.of("?memberId=" + idB, "?memberId=" + idA, "?MEMBERID=" + idB, "?member_id=" + idB,
                "?memberId=")) {
            for (String path : List.of("/v1/portal/me/profile", "/v1/portal/me/referral")) {
                Reply r = call("GET", path + query, token);
                assertThat(r.status()).as(path + query + " " + r.text()).isEqualTo(400);
                assertThat(r.code()).isEqualTo("MEMBER_FROM_TOKEN");
                assertThat(r.text()).doesNotContain(idB);
            }
        }
        Reply header = call("GET", "/v1/portal/me/profile", token, null, "X-LH-Member", idB);
        assertThat(header.status()).as(header.text()).isEqualTo(400);
        assertThat(header.code()).isEqualTo("MEMBER_FROM_TOKEN");

        // Percorsi legacy con l'id nel percorso: 403 con l'id di B come col proprio.
        for (String id : List.of(idB, idA)) {
            for (String path : List.of("/v1/portal/members/" + id, "/v1/portal/members/" + id + "/referral")) {
                Reply r = call("GET", path, token);
                assertThat(r.status()).as(path + " " + r.text()).isEqualTo(403);
                assertThat(r.code()).isEqualTo("MEMBER_FROM_TOKEN");
                assertThat(r.body().path("detail").asString()).doesNotContain(idB).doesNotContain(idA);
            }
            Reply patch = call("PATCH", "/v1/portal/members/" + id, token, Map.of("city", "Napoli"));
            assertThat(patch.status()).as(patch.text()).isEqualTo(403);
        }
        assertThat(jdbc.sql("SELECT city FROM member WHERE id = ?").param(idB).query(String.class).single())
                .isEqualTo("Modena");
    }

    @Test
    @DisplayName("[M8.2] PATCH /me/profile: aggiorna solo il titolare, audit con attore member:<id> senza username né e-mail del token")
    void patchIsAuditedAsTheMember() {
        String subA = newSub();
        String subB = newSub();
        String idA = register(subA);
        String idB = register(subB);
        String token = TOKENS.member(subA);

        Reply patch = call("PATCH", "/v1/portal/me/profile", token,
                Map.of("city", "Bologna", "phone", "+39 333 1234567", "consents", Map.of("profiling", true)));
        assertThat(patch.status()).as(patch.text()).isEqualTo(200);
        assertThat(patch.body().path("memberId").asString()).isEqualTo(idA);
        assertThat(patch.body().path("city").asString()).isEqualTo("Bologna");
        assertThat(patch.body().path("consents").path("profiling").asBoolean()).isTrue();
        assertThat(jdbc.sql("SELECT city FROM member WHERE id = ?").param(idB).query(String.class).single())
                .isEqualTo("Modena");

        List<JsonNode> audit = published(AUDIT, "MEMBER:" + idA, AUDIT_ENTRY).stream()
                .filter(e -> e.path("data").path("action").asString().equals("UPDATE")).toList();
        assertThat(audit).hasSize(1);
        assertThat(audit.get(0).path("lhactor").asString()).isEqualTo("member:" + idA);
        assertThat(audit.get(0).toString()).doesNotContain(subA)
                .doesNotContain(OidcTestTokens.usernameOf(subA)).doesNotContain(OidcTestTokens.emailOf(subA));
        // L'attore e l'MDC a fine richiesta sono member:<id>, mai lo username del token.
        assertThat(ACTORS).anyMatch(a -> a.startsWith("PATCH /v1/portal/me/profile -> holder=member:" + idA + " mdc=member:" + idA));
        assertThat(ACTORS).noneMatch(a -> a.contains(OidcTestTokens.usernameOf(subA))
                || a.contains(OidcTestTokens.emailOf(subA)));
        // Lo snapshot aggiornato porta lo stesso pseudonimo del legame.
        List<JsonNode> updated = published(FACTS, "member:" + idA, UPDATED);
        assertThat(updated).isNotEmpty();
        assertThat(updated.get(updated.size() - 1).path("data").path("subjectRef").asString()).isEqualTo(refOf(subA));
    }

    @Test
    @DisplayName("[M8.2] PATCH con memberId di B in query o header: 400 MEMBER_FROM_TOKEN; nel corpo è ignorato; B non cambia e non ha audit")
    void patchWithAnotherMemberIsRejected() {
        String subA = newSub();
        String subB = newSub();
        String idA = register(subA);
        String idB = register(subB);
        String token = TOKENS.member(subA);
        int auditBefore = published(AUDIT, "MEMBER:" + idB, AUDIT_ENTRY).size();

        Reply query = call("PATCH", "/v1/portal/me/profile?memberId=" + idB, token, Map.of("city", "Torino"));
        assertThat(query.status()).isEqualTo(400);
        assertThat(query.code()).isEqualTo("MEMBER_FROM_TOKEN");
        Reply ownQuery = call("PATCH", "/v1/portal/me/profile?memberId=" + idA, token, Map.of("city", "Torino"));
        assertThat(ownQuery.status()).as("anche il proprio id: il membro viene solo dal token").isEqualTo(400);
        Reply header = call("PATCH", "/v1/portal/me/profile", token, Map.of("city", "Torino"), "X-LH-Member", idB);
        assertThat(header.status()).isEqualTo(400);
        assertThat(jdbc.sql("SELECT city FROM member WHERE id IN (?, ?)").params(idA, idB).query(String.class).list())
                .containsOnly("Modena");

        // SPEC-GAP: Q-573 — nel corpo il campo non esiste nel contratto: ignorato (non 400 come vorrebbe docs/06 §3.4), la modifica è del titolare e B non cambia.
        Reply body = call("PATCH", "/v1/portal/me/profile", token, Map.of("memberId", idB, "city", "Torino"));
        assertThat(body.status()).as(body.text()).isEqualTo(200);
        assertThat(body.body().path("memberId").asString()).isEqualTo(idA);
        assertThat(jdbc.sql("SELECT city FROM member WHERE id = ?").param(idA).query(String.class).single()).isEqualTo("Torino");
        assertThat(jdbc.sql("SELECT city FROM member WHERE id = ?").param(idB).query(String.class).single()).isEqualTo("Modena");
        assertThat(published(AUDIT, "MEMBER:" + idB, AUDIT_ENTRY)).hasSize(auditBefore);
    }

    @Test
    @DisplayName("[M8.2] un operatore o un token misto non agisce da membro: 403 MEMBER_REQUIRED; MEMBER+SOURCE 403")
    void operatorsDoNotActAsMembers() {
        String subA = newSub();
        register(subA);
        for (String token : List.of(TOKENS.operator("marta", "ADMIN"), TOKENS.operator("paolo", "CARE"),
                TOKENS.mixed(subA, "CARE"))) {
            for (String path : List.of("/v1/portal/me/profile", "/v1/portal/me/referral")) {
                Reply r = call("GET", path, token);
                assertThat(r.status()).as(path + " " + r.text()).isEqualTo(403);
                assertThat(r.code()).isEqualTo("MEMBER_REQUIRED");
            }
            assertThat(call("PATCH", "/v1/portal/me/profile", token, Map.of("city", "Roma")).code())
                    .isEqualTo("MEMBER_REQUIRED");
        }
        assertThat(call("GET", "/v1/portal/me/profile", TOKENS.memberSource(subA, "src-ecommerce")).status()).isEqualTo(403);
    }

    @Test
    @DisplayName("[M8.2] un token di membro non raggiunge le API di backoffice: 403 FORBIDDEN_ROLE")
    void memberTokenStaysOnThePortal() {
        String subA = newSub();
        String idA = register(subA);
        String token = TOKENS.member(subA);
        for (String path : List.of("/v1/members", "/v1/members/" + idA, "/v1/referral/overview")) {
            Reply r = call("GET", path, token);
            assertThat(r.status()).as(path + " " + r.text()).isEqualTo(403);
            assertThat(r.code()).isEqualTo("FORBIDDEN_ROLE");
        }
        assertThat(call("POST", "/v1/members", token, Map.of("firstName", "X", "email", "x@profili.test")).status())
                .isEqualTo(403);
    }

    @Test
    @DisplayName("[M8.2] senza token, scaduto o con firma altrui: 401")
    void invalidTokens() {
        assertThat(call("GET", "/v1/portal/me/profile", null, null, "X-LH-Member", "MBR-000001").status()).isEqualTo(401);
        assertThat(call("GET", "/v1/portal/me/profile", TOKENS.expired(newSub())).status()).isEqualTo(401);
        assertThat(call("GET", "/v1/portal/me/profile", TOKENS.foreignKey(newSub())).status()).isEqualTo(401);
    }

    @Test
    @DisplayName("[M8.2] anonimizzazione: il legame si cancella, /me/profile dà 404, una nuova registrazione crea un nuovo membro")
    void anonymizationRemovesTheLink() {
        String sub = newSub();
        String id = register(sub);
        String token = TOKENS.member(sub);
        assertThat(call("GET", "/v1/portal/me/profile", token).status()).isEqualTo(200);
        assertThat(count("SELECT count(*) FROM member_identity WHERE member_id = ?", id)).isEqualTo(1);

        // Solo ADMIN, con l'id digitato: il token di un membro non basta.
        assertThat(call("POST", "/v1/members/" + id + "/anonymize", token, Map.of("confirm", id)).status()).isEqualTo(403);
        Reply anonymized = call("POST", "/v1/members/" + id + "/anonymize", TOKENS.operator("marta", "ADMIN"),
                Map.of("confirm", id));
        assertThat(anonymized.status()).as(anonymized.text()).isEqualTo(200);
        assertThat(anonymized.body().path("status").asString()).isEqualTo("ANONYMIZED");

        assertThat(count("SELECT count(*) FROM member_identity WHERE member_id = ?", id)).isZero();
        assertThat(count("SELECT count(*) FROM member_identity WHERE subject = ?", sub)).isZero();
        Reply gone = call("GET", "/v1/portal/me/profile", token);
        assertThat(gone.status()).as(gone.text()).isEqualTo(404);
        assertThat(gone.code()).isEqualTo("MEMBER_NOT_REGISTERED");
        // Lo snapshot anonimizzato non porta più lo pseudonimo.
        List<JsonNode> updated = published(FACTS, "member:" + id, UPDATED);
        assertThat(updated.get(updated.size() - 1).path("data").path("status").asString()).isEqualTo("ANONYMIZED");
        assertThat(updated.get(updated.size() - 1).path("data").has("subjectRef"))
                .as("subjectRef omesso, non null: assente = legame invariato, null = scollega").isFalse();

        // Lo stesso account si registra di nuovo: un nuovo membro, nuovo legame, nessun ritorno del vecchio.
        Reply again = call("POST", "/v1/portal/members", token, registration(sub));
        assertThat(again.status()).as(again.text()).isEqualTo(201);
        String newId = again.body().path("memberId").asString();
        assertThat(newId).isNotEqualTo(id);
        assertThat(call("GET", "/v1/portal/me/profile", token).body().path("memberId").asString()).isEqualTo(newId);
        assertThat(published(FACTS, "member:" + newId, REGISTERED)).hasSize(1);
        assertThat(published(FACTS, "member:" + newId, REGISTERED).get(0).path("data").path("subjectRef").asString())
                .isEqualTo(refOf(sub));
    }
}
