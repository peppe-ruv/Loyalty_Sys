package io.loyaltyhub.member;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TB-GOV §8.3 — il membro dal token in member-service, profilo {@code enterprise} (MID): registrazione dal portale
 * ({@code POST /v1/portal/members}), profilo e referral senza id ({@code /v1/portal/me/**}), BOLA su query, header,
 * percorso legacy e corpo, operatori e token misti, anonimizzazione. Token RS256 veri ({@code OidcTestTokens}), Postgres e
 * Kafka embedded. Una riga = un caso ({@code member-identity.csv}); i percorsi con più passi (idempotenza con outbox, corsa
 * tra richieste, audit) sono in {@link PortalRegistrationOidcIT} e {@link PortalMeOidcIT}.
 * <p>Oracolo: docs/servizi/member-service.md §3 e §8, docs/06 §3.2 e §3.4, docs/15 (Q-157, Q-551, Q-553, Q-554, Q-556,
 * Q-558), ADR-048. L'atteso è {@code stato[:codice][:extra]}.
 */
class TestbookGovMemberIdentityIT extends OidcPortalSupport {

    private String otherSub;
    private String otherId;

    @BeforeAll
    void otherMember() {
        otherSub = newSub();
        otherId = register(otherSub);
    }

    @ParameterizedTest(name = "[{0}] {1}", quoteTextArguments = false)
    @CsvFileSource(resources = "/testbook/gov/member-identity.csv", numLinesToSkip = 1)
    void identity(String id, String description, String scenario, String expected) {
        assertThat(run(scenario)).isEqualTo(expected);
    }

    private String run(String scenario) {
        String sub = newSub();
        String token = TOKENS.member(sub);
        switch (scenario) {
            case "REG_NEW":
                return status(call("POST", "/v1/portal/members", token, registration(sub)));
            case "REG_AGAIN": {
                register(sub);
                return status(call("POST", "/v1/portal/members", token, registration(sub)));
            }
            case "REG_IGNORED_FIELDS": {
                Map<String, Object> body = new HashMap<>(registration(sub));
                body.put("externalId", "CRM-999999");
                body.put("status", "BLOCKED");
                body.put("channel", "STORE");
                Reply r = call("POST", "/v1/portal/members", token, body);
                if (r.status() != 201) {
                    return status(r);
                }
                Map<String, String> row = jdbc.sql("SELECT external_id, channel, status FROM member WHERE id = ?")
                        .param(r.body().path("memberId").asString())
                        .query((rs, n) -> Map.of("ext", String.valueOf(rs.getString(1)), "channel", rs.getString(2),
                                "status", rs.getString(3))).single();
                return "201:" + row.get("channel") + ":" + row.get("status") + ":" + ("null".equals(row.get("ext")) ? "noext" : "ext");
            }
            case "REG_QUERY":
                return status(call("POST", "/v1/portal/members?memberId=MBR-000001", token, registration(sub)));
            case "REG_HEADER":
                return status(call("POST", "/v1/portal/members", token, registration(sub), "X-LH-Member", "MBR-000001"));
            case "REG_ADMIN":
                return status(call("POST", "/v1/portal/members", TOKENS.operator("marta", "ADMIN"), registration(sub)));
            case "REG_CARE":
                return status(call("POST", "/v1/portal/members", TOKENS.operator("paolo", "CARE"), registration(sub)));
            case "REG_MIXED":
                return status(call("POST", "/v1/portal/members", TOKENS.mixed(sub, "CARE"), registration(sub)));
            case "REG_SOURCE":
                return String.valueOf(call("POST", "/v1/portal/members", TOKENS.memberSource(sub, "src-ecommerce"),
                        registration(sub)).status());
            case "REG_NO_TOKEN":
                return status(call("POST", "/v1/portal/members", null, registration(sub)));
            case "REG_EXPIRED":
                return status(call("POST", "/v1/portal/members", TOKENS.expired(sub), registration(sub)));
            case "REG_FOREIGN":
                return status(call("POST", "/v1/portal/members", TOKENS.foreignKey(sub), registration(sub)));
            case "REG_NO_EMAIL":
                return status(call("POST", "/v1/portal/members", token, Map.of("firstName", "Senza", "lastName", "Posta")));
            case "REG_EMAIL_TAKEN":
                return status(call("POST", "/v1/portal/members", token, registration(otherSub)));
            case "REG_BAD_REFERRAL": {
                Map<String, Object> body = new HashMap<>(registration(sub));
                body.put("referralCode", "ZZZZZZZZ");
                return status(call("POST", "/v1/portal/members", token, body));
            }
            case "ME_UNREGISTERED":
                return status(call("GET", "/v1/portal/me/profile", token));
            case "ME_ADMIN":
                return status(call("GET", "/v1/portal/me/profile", TOKENS.operator("marta", "ADMIN")));
            case "ME_MIXED":
                return status(call("GET", "/v1/portal/me/profile", TOKENS.mixed(sub, "CARE")));
            case "ME_NO_TOKEN":
                return status(call("GET", "/v1/portal/me/profile", null));
            default:
                return registered(scenario, sub, token);
        }
    }

    /** Scenari di un account già registrato (A) accanto all'altro membro (B). */
    private String registered(String scenario, String sub, String token) {
        String idA = register(sub);
        switch (scenario) {
            case "ME_PROFILE": {
                Reply r = call("GET", "/v1/portal/me/profile", token);
                return status(r) + (r.body().path("memberId").asString().equals(idA) ? ":own" : ":OTHER");
            }
            case "ME_REFERRAL":
                return status(call("GET", "/v1/portal/me/referral", token));
            case "ME_QUERY_OTHER":
                return status(call("GET", "/v1/portal/me/profile?memberId=" + otherId, token));
            case "ME_QUERY_OWN":
                return status(call("GET", "/v1/portal/me/profile?memberId=" + idA, token));
            case "ME_QUERY_UPPER":
                return status(call("GET", "/v1/portal/me/profile?MEMBERID=" + otherId, token));
            case "ME_HEADER_OTHER":
                return status(call("GET", "/v1/portal/me/profile", token, null, "X-LH-Member", otherId));
            case "LEGACY_OTHER":
                return status(call("GET", "/v1/portal/members/" + otherId, token));
            case "LEGACY_OWN":
                return status(call("GET", "/v1/portal/members/" + idA, token));
            case "LEGACY_REFERRAL_OTHER":
                return status(call("GET", "/v1/portal/members/" + otherId + "/referral", token));
            case "LEGACY_PATCH_OTHER":
                return status(call("PATCH", "/v1/portal/members/" + otherId, token, Map.of("city", "Napoli")));
            case "ME_PATCH": {
                Reply r = call("PATCH", "/v1/portal/me/profile", token, Map.of("city", "Bologna"));
                return status(r) + (r.body().path("memberId").asString().equals(idA) ? ":own" : ":OTHER");
            }
            case "ME_PATCH_QUERY_OTHER":
                return status(call("PATCH", "/v1/portal/me/profile?memberId=" + otherId, token, Map.of("city", "Napoli")));
            case "BO_LIST":
                return status(call("GET", "/v1/members", token));
            case "BO_CREATE":
                return status(call("POST", "/v1/members", token, Map.of("firstName", "X", "email", "x." + sub + "@profili.test")));
            case "ANON_PROFILE": {
                anonymize(idA);
                return status(call("GET", "/v1/portal/me/profile", token));
            }
            case "ANON_REREGISTER": {
                anonymize(idA);
                Reply r = call("POST", "/v1/portal/members", token, registration(sub));
                return status(r) + (r.status() == 201 && !r.body().path("memberId").asString().equals(idA) ? ":newid" : ":SAME");
            }
            default:
                throw new IllegalArgumentException("Scenario sconosciuto: " + scenario);
        }
    }

    private void anonymize(String memberId) {
        Reply r = call("POST", "/v1/members/" + memberId + "/anonymize", TOKENS.operator("marta", "ADMIN"),
                Map.of("confirm", memberId));
        assertThat(r.status()).as(r.text()).isEqualTo(200);
    }

    private static String status(Reply r) {
        String code = r.status() >= 400 ? r.body().path("code").asString() : "";
        return r.status() + (code.isBlank() ? "" : ":" + code);
    }
}
