package io.loyaltyhub.common.web;

import io.loyaltyhub.common.web.MemberTestSupport.InMemoryLookup;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static io.loyaltyhub.common.web.MemberTestSupport.ID_A;
import static io.loyaltyhub.common.web.MemberTestSupport.ID_B;
import static io.loyaltyhub.common.web.MemberTestSupport.SUB_A;
import static io.loyaltyhub.common.web.MemberTestSupport.SUB_B;
import static io.loyaltyhub.common.web.MemberTestSupport.TOKENS;
import static io.loyaltyhub.common.web.MemberTestSupport.ref;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Il membro dal token in {@link EndpointAccessInterceptor} (Q-410, Q-553, Q-554, Q-556, ADR-048): matrice chiamante ×
 * dichiarazione, con token RS256 veri, sui due profili. Un token di membro raggiunge solo gli handler
 * {@link MemberEndpoint} e le letture {@code members = true}; il membro viene solo dal token; un operatore non agisce
 * mai come membro.
 */
class MemberEndpointAccessTest {

    private final InMemoryLookup service = MemberTestSupport.linkedLookup(false);
    private final InMemoryLookup memberService = MemberTestSupport.linkedLookup(true);
    private final MockMvc oidc = MemberTestSupport.oidc(MemberTestSupport.oidcPrincipals(service));
    private final MockMvc oidcAuthoritative = MemberTestSupport.oidc(MemberTestSupport.oidcPrincipals(memberService));
    private final MockMvc demo = MemberTestSupport.demo();

    @AfterEach
    void clear() {
        ActorHolder.clear();
    }

    private static MockHttpServletRequestBuilder bearer(MockHttpServletRequestBuilder request, String token) {
        return request.header("Authorization", "Bearer " + token);
    }

    // ================= enterprise: token di solo membro =================

    @Test
    @DisplayName("[oidc] membro legato: REQUIRED e OPTIONAL risolvono il proprio id; attore e MDC member:<id>")
    void memberTokenResolvesTheOwnMember() throws Exception {
        String a = TOKENS.member(SUB_A);
        for (String path : new String[] {"/v1/portal/required", "/v1/portal/optional", "/v1/portal/class-level"}) {
            oidc.perform(bearer(get(path), a)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.member").value(ID_A))
                    .andExpect(jsonPath("$.origin").value("TOKEN"))
                    .andExpect(jsonPath("$.actor").value("member:" + ID_A))
                    .andExpect(jsonPath("$.mdc").value("member:" + ID_A));
        }
        // Il membro B vede B, mai A: il legame è per sub.
        oidc.perform(bearer(get("/v1/portal/required"), TOKENS.member(SUB_B))).andExpect(status().isOk())
                .andExpect(jsonPath("$.member").value(ID_B));
    }

    @Test
    @DisplayName("[oidc] membro non legato: 409 MEMBER_NOT_LINKED con Retry-After: 2 (servizio non autorevole); OPTIONAL vista generica")
    void unlinkedMemberIsNotLinkedYet() throws Exception {
        String stranger = TOKENS.member("sub-sconosciuto");
        oidc.perform(bearer(get("/v1/portal/required"), stranger)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MEMBER_NOT_LINKED"))
                .andExpect(jsonPath("$.type").value("urn:loyaltyhub:problem:member-not-linked"))
                .andExpect(header().string("Retry-After", "2"));
        oidc.perform(bearer(get("/v1/portal/optional"), stranger)).andExpect(status().isOk())
                .andExpect(jsonPath("$.origin").value("NONE")).andExpect(jsonPath("$.member").doesNotExist());
    }

    @Test
    @DisplayName("[oidc] membro non registrato: 404 MEMBER_NOT_REGISTERED dalla lookup autorevole (member-service), senza Retry-After")
    void unregisteredMemberIsNotFoundOnTheAuthoritativeSource() throws Exception {
        String stranger = TOKENS.member("sub-sconosciuto");
        oidcAuthoritative.perform(bearer(get("/v1/portal/required"), stranger)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MEMBER_NOT_REGISTERED"))
                .andExpect(header().doesNotExist("Retry-After"));
    }

    @Test
    @DisplayName("[oidc] registrazione: il MemberSubject porta lo pseudonimo, mai il sub; già legato ⇒ il membro esistente")
    void registrationSubject() throws Exception {
        String fresh = TOKENS.member("sub-nuovo");
        oidcAuthoritative.perform(bearer(post("/v1/portal/members"), fresh)).andExpect(status().isOk())
                .andExpect(jsonPath("$.demo").value(false)).andExpect(jsonPath("$.linked").doesNotExist())
                .andExpect(jsonPath("$.ref").value(ref("sub-nuovo")))
                .andExpect(jsonPath("$.actor").value("member:-"));
        oidcAuthoritative.perform(bearer(post("/v1/portal/members"), TOKENS.member(SUB_A))).andExpect(status().isOk())
                .andExpect(jsonPath("$.linked").value(ID_A)).andExpect(jsonPath("$.actor").value("member:" + ID_A));
        // Un servizio che non è la fonte autorevole non registra: chiude, mai aperto.
        oidc.perform(bearer(post("/v1/portal/members"), fresh)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ENDPOINT_NOT_DECLARED"));
    }

    @Test
    @DisplayName("[oidc] memberId in query, in qualunque grafia, anche proprio, e X-LH-Member: 400 MEMBER_FROM_TOKEN")
    void memberIdInTheRequestIsRefused() throws Exception {
        String a = TOKENS.member(SUB_A);
        for (String query : new String[] {"memberId=" + ID_B, "memberId=" + ID_A, "MEMBERID=" + ID_B, "member_id=" + ID_B,
                "Member-Id=" + ID_B, "memberId=", "filter.memberId=" + ID_B, "items[0].memberId=" + ID_B}) {
            for (String path : new String[] {"/v1/portal/required", "/v1/portal/optional", "/v1/portal/theme",
                    "/v1/portal/backoffice-read", "/v1/portal/public", "/v1/portal/wallets/" + ID_A}) {
                oidc.perform(bearer(get(path + "?" + query), a)).andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.code").value("MEMBER_FROM_TOKEN"));
            }
        }
        oidc.perform(bearer(get("/v1/portal/required").header(MemberPrincipals.MEMBER_HEADER, ID_B), a))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MEMBER_FROM_TOKEN"));
        oidc.perform(bearer(get("/v1/portal/theme").header(MemberPrincipals.MEMBER_HEADER, ID_A), a))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MEMBER_FROM_TOKEN"));
        // Anche un campo form: Spring lo legherebbe come parametro.
        oidc.perform(bearer(post("/v1/portal/write").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .content("memberId=" + ID_B), a)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MEMBER_FROM_TOKEN"));
    }

    @Test
    @DisplayName("[oidc] il detail dell'errore non ripete mai l'id ricevuto")
    void errorDetailNeverEchoesTheId() throws Exception {
        // Il problem porta `instance` (l'URI chiamato, come per ogni errore); il detail mai l'id: né quello ricevuto né il proprio.
        oidc.perform(bearer(get("/v1/portal/required?memberId=" + ID_B), TOKENS.member(SUB_A)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", not(containsString(ID_B))))
                .andExpect(jsonPath("$.detail", not(containsString(ID_A))));
        oidc.perform(bearer(get("/v1/portal/wallets/" + ID_B), TOKENS.member(SUB_A)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail", not(containsString(ID_B))));
    }

    @Test
    @DisplayName("[oidc] id nel percorso (legacy): 403 MEMBER_FROM_TOKEN, anche proprio; la lookup non è interrogata")
    void legacyPathIsRefused() throws Exception {
        for (String id : new String[] {ID_A, ID_B}) {
            oidc.perform(bearer(get("/v1/portal/wallets/" + id), TOKENS.member(SUB_A))).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("MEMBER_FROM_TOKEN"));
        }
        assertThat(service.calls).isZero();
    }

    @Test
    @DisplayName("[oidc] un token di membro su un handler di backoffice, senza members=true o con la regola «scrittura»: 403 FORBIDDEN_ROLE")
    void memberTokenNeverReachesBackofficeHandlers() throws Exception {
        String a = TOKENS.member(SUB_A);
        oidc.perform(bearer(get("/v1/portal/backoffice-read"), a)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN_ROLE"));
        oidc.perform(bearer(post("/v1/portal/write-rule"), a)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN_ROLE"));
        // members=true con la regola «scrittura» (nessun ANALYST): ANALYST non vi passa.
        oidc.perform(bearer(get("/v1/portal/members-write-rule"), a)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN_ROLE"));
        // Fuori dal portale il filtro chiude prima.
        oidc.perform(bearer(get("/v1/campaigns"), a)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN_ROLE"));
    }

    @Test
    @DisplayName("[oidc] letture con members=true e @PublicEndpoint: passano al membro; la lookup non serve")
    void memberReadsPass() throws Exception {
        String a = TOKENS.member(SUB_A);
        oidc.perform(bearer(get("/v1/portal/theme"), a)).andExpect(status().isOk());
        oidc.perform(bearer(get("/v1/portal/public"), a)).andExpect(status().isOk());
        assertThat(service.calls).isZero();
    }

    @Test
    @DisplayName("[oidc] handler non dichiarato o con dichiarazioni combinate: 403 ENDPOINT_NOT_DECLARED per tutti, membro compreso")
    void undeclaredAndCombinedAreRefusedToEveryone() throws Exception {
        for (String token : new String[] {TOKENS.member(SUB_A), TOKENS.operator("marta.admin", "ADMIN"),
                TOKENS.operator("paolo.care", "CARE")}) {
            for (String path : new String[] {"/v1/portal/undeclared", "/v1/portal/combined", "/v1/portal/combined-public"}) {
                oidc.perform(bearer(get(path), token)).andExpect(status().isForbidden())
                        .andExpect(jsonPath("$.code").value("ENDPOINT_NOT_DECLARED"));
            }
        }
    }

    @Test
    @DisplayName("[oidc] un parametro MemberPrincipal su un handler non del membro: il resolver risponde 403 ENDPOINT_NOT_DECLARED")
    void principalWithoutDeclarationFailsClosed() throws Exception {
        oidc.perform(bearer(get("/v1/portal/principal-without-declaration"), TOKENS.operator("marta.admin", "ADMIN")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ENDPOINT_NOT_DECLARED"));
    }

    @Test
    @DisplayName("[oidc] @MemberEndpoint sulla classe vale per i metodi che non dichiarano altro; il metodo prevale")
    void classLevelDeclaration() throws Exception {
        oidc.perform(bearer(get("/v1/portal/class-level"), TOKENS.member(SUB_A))).andExpect(status().isOk())
                .andExpect(jsonPath("$.member").value(ID_A));
        oidc.perform(bearer(get("/v1/portal/class-level/open"), TOKENS.member(SUB_A))).andExpect(status().isOk());
        oidc.perform(bearer(get("/v1/portal/class-level"), TOKENS.operator("paolo.care", "CARE")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("MEMBER_REQUIRED"));
    }

    @Test
    @DisplayName("[oidc] oggetto di un altro membro: checkOwner ⇒ 404 NOT_FOUND; il proprio passa")
    void ownerCheck() throws Exception {
        // «owner-of-x»: il proprietario dell'oggetto non è il membro del token.
        oidc.perform(bearer(get("/v1/portal/owned/x"), TOKENS.member(SUB_A))).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    // ================= enterprise: operatore e token misti =================

    @Test
    @DisplayName("[oidc, Q-554] un operatore non agisce mai come membro: REQUIRED e REGISTRATION 403 MEMBER_REQUIRED, OPTIONAL vista generica")
    void operatorNeverActsAsMember() throws Exception {
        for (String token : new String[] {TOKENS.operator("paolo.care", "CARE"), TOKENS.operator("marta.admin", "ADMIN"),
                TOKENS.operator("sara.analyst", "ANALYST"), TOKENS.mixed(SUB_A, "CARE"), TOKENS.mixed(SUB_A, "ADMIN")}) {
            oidcAuthoritative.perform(bearer(get("/v1/portal/required"), token)).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("MEMBER_REQUIRED"));
            oidcAuthoritative.perform(bearer(post("/v1/portal/members"), token)).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("MEMBER_REQUIRED"));
            oidcAuthoritative.perform(bearer(get("/v1/portal/optional"), token)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.origin").value("NONE")).andExpect(jsonPath("$.member").doesNotExist());
            // Le letture di backoffice e di programma restano per ruolo.
            oidcAuthoritative.perform(bearer(get("/v1/portal/theme"), token)).andExpect(status().isOk());
        }
        assertThat(memberService.calls).as("nessun legame cercato per un operatore").isZero();
    }

    @Test
    @DisplayName("[oidc, Q-554] operatore con memberId in query o id nel percorso: 400 / 403 MEMBER_FROM_TOKEN, mai il membro indicato")
    void operatorCannotPickAMember() throws Exception {
        String care = TOKENS.operator("paolo.care", "CARE");
        oidc.perform(bearer(get("/v1/portal/required?memberId=" + ID_A), care)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MEMBER_FROM_TOKEN"));
        oidc.perform(bearer(get("/v1/portal/optional?memberId=" + ID_A), care)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MEMBER_FROM_TOKEN"));
        oidc.perform(bearer(get("/v1/portal/wallets/" + ID_A), care)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MEMBER_FROM_TOKEN"));
    }

    @Test
    @DisplayName("[oidc, Q-554] token misto (MEMBER più operatore): vale l'operatore, con i suoi ruoli, sulle letture di backoffice")
    void mixedTokenIsAnOperator() throws Exception {
        String mixed = TOKENS.mixed(SUB_A, "CARE");
        oidc.perform(bearer(get("/v1/portal/backoffice-read"), mixed)).andExpect(status().isOk());
        oidc.perform(bearer(get("/v1/campaigns"), mixed)).andExpect(status().isOk());
        oidc.perform(bearer(post("/v1/portal/write-rule"), mixed)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("[oidc, Q-554] MEMBER+SOURCE: 403 FORBIDDEN_ROLE su tutto il portale (fromToken restituisce SOURCE)")
    void memberPlusSourceIsRefused() throws Exception {
        String both = TOKENS.memberSource(SUB_A, "src-crm");
        for (String path : new String[] {"/v1/portal/required", "/v1/portal/optional", "/v1/portal/theme",
                "/v1/portal/backoffice-read", "/v1/portal/public", "/v1/campaigns"}) {
            oidc.perform(bearer(get(path), both)).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("FORBIDDEN_ROLE"));
        }
        oidc.perform(bearer(post("/v1/portal/members"), both)).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("[oidc] senza token, scaduto, firmato con un'altra chiave, audience o emittente diversi: 401, mai il membro")
    void invalidTokensAreUnauthorized() throws Exception {
        oidc.perform(get("/v1/portal/required")).andExpect(status().isUnauthorized());
        for (String token : new String[] {TOKENS.expired(SUB_A), TOKENS.foreignKey(SUB_A), TOKENS.wrongAudience(SUB_A),
                TOKENS.wrongIssuer(SUB_A), "non-un-jwt"}) {
            oidc.perform(bearer(get("/v1/portal/required"), token)).andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        }
        assertThat(service.calls).isZero();
    }

    // ================= enterprise: corpo =================

    @Test
    @DisplayName("[oidc] corpo con memberId non nullo, a qualunque profondità o come JsonNode: 400 MEMBER_FROM_TOKEN; nullo o assente passa")
    void bodyMemberIdIsRefused() throws Exception {
        String a = TOKENS.member(SUB_A);
        for (String own : new String[] {ID_B, ID_A}) {
            oidc.perform(bearer(post("/v1/portal/write").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"rewardCode\":\"RWD-1\",\"memberId\":\"" + own + "\"}"), a))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MEMBER_FROM_TOKEN"));
        }
        oidc.perform(bearer(post("/v1/portal/nested").contentType(MediaType.APPLICATION_JSON)
                .content("{\"rewardCode\":\"RWD-1\",\"shipping\":{\"city\":\"Roma\",\"memberId\":\"" + ID_B + "\"}}"), a))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MEMBER_FROM_TOKEN"));
        oidc.perform(bearer(post("/v1/portal/tree").contentType(MediaType.APPLICATION_JSON)
                .content("{\"a\":{\"b\":[{\"MemberID\":\"" + ID_B + "\"}]}}"), a))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MEMBER_FROM_TOKEN"));
        // Assente o null esplicito: passa, e il membro è quello del token.
        oidc.perform(bearer(post("/v1/portal/write").contentType(MediaType.APPLICATION_JSON)
                .content("{\"rewardCode\":\"RWD-1\"}"), a)).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(ID_A));
        oidc.perform(bearer(post("/v1/portal/write").contentType(MediaType.APPLICATION_JSON)
                .content("{\"rewardCode\":\"RWD-1\",\"memberId\":null}"), a)).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(ID_A));
        oidc.perform(bearer(post("/v1/portal/tree").contentType(MediaType.APPLICATION_JSON)
                .content("{\"a\":{\"memberId\":null}}"), a)).andExpect(status().isOk());
    }

    // ================= demo =================

    @Test
    @DisplayName("[demo] memberId esplicito o X-LH-Member: il membro come oggi, attore e MDC member:<id>")
    void demoResolvesTheExplicitMember() throws Exception {
        demo.perform(get("/v1/portal/required?memberId=MBR-000003")).andExpect(status().isOk())
                .andExpect(jsonPath("$.member").value("MBR-000003")).andExpect(jsonPath("$.origin").value("DEMO"))
                .andExpect(jsonPath("$.actor").value("member:MBR-000003"))
                .andExpect(jsonPath("$.mdc").value("member:MBR-000003"));
        demo.perform(get("/v1/portal/required").header(MemberPrincipals.MEMBER_HEADER, "MBR-000003"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.member").value("MBR-000003"));
        demo.perform(get("/v1/portal/optional?memberId=MBR-000003")).andExpect(status().isOk())
                .andExpect(jsonPath("$.member").value("MBR-000003"));
        demo.perform(get("/v1/portal/wallets/MBR-000003")).andExpect(status().isOk())
                .andExpect(jsonPath("$.member").value("MBR-000003")).andExpect(jsonPath("$.actor").value("member:MBR-000003"));
        // L'attore di un operatore che agisce sul portale diventa il membro (docs/06 §3).
        demo.perform(get("/v1/portal/required?memberId=MBR-000003").header(ActorFilter.HEADER, "CARE:paolo.care"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.actor").value("member:MBR-000003"));
    }

    @Test
    @DisplayName("[demo] senza id l'interceptor non impone nulla: principal DEMO senza id; requireParam ⇒ 400 «Parametro obbligatorio assente: memberId»")
    void demoWithoutAnIdKeepsTodaysBehaviour() throws Exception {
        demo.perform(get("/v1/portal/required")).andExpect(status().isOk())
                .andExpect(jsonPath("$.origin").value("DEMO")).andExpect(jsonPath("$.member").doesNotExist())
                .andExpect(jsonPath("$.actor").value("ANALYST:anonymous"));
        demo.perform(get("/v1/portal/optional")).andExpect(status().isOk());
        demo.perform(get("/v1/portal/required-param")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.type").value("urn:loyaltyhub:problem:bad-request"))
                .andExpect(jsonPath("$.detail").value("Parametro obbligatorio assente: memberId"));
        demo.perform(get("/v1/portal/required-param?memberId=MBR-000003")).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("MBR-000003"));
    }

    @Test
    @DisplayName("[demo] X-LH-Member malformato: 400; due fonti diverse (header, parametro, percorso): 400 MEMBER_MISMATCH; uguali passano")
    void demoSourcesMustAgree() throws Exception {
        for (String bad : new String[] {"MBR-1", "mbr-000003", "MBR-0000031", "x", "MBR-00000A"}) {
            demo.perform(get("/v1/portal/required").header(MemberPrincipals.MEMBER_HEADER, bad))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("BAD_REQUEST"));
        }
        demo.perform(get("/v1/portal/required?memberId=MBR-000004").header(MemberPrincipals.MEMBER_HEADER, "MBR-000003"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MEMBER_MISMATCH"));
        demo.perform(get("/v1/portal/wallets/MBR-000004").header(MemberPrincipals.MEMBER_HEADER, "MBR-000003"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MEMBER_MISMATCH"));
        demo.perform(get("/v1/portal/wallets/MBR-000004?memberId=MBR-000003"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MEMBER_MISMATCH"));
        demo.perform(get("/v1/portal/wallets/MBR-000003?memberId=MBR-000003").header(MemberPrincipals.MEMBER_HEADER, "MBR-000003"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.member").value("MBR-000003"));
        // Un header vuoto vale «assente».
        demo.perform(get("/v1/portal/required?memberId=MBR-000003").header(MemberPrincipals.MEMBER_HEADER, " "))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("[demo] attore SOURCE: 403 FORBIDDEN_ROLE su REQUIRED, OPTIONAL e REGISTRATION (parità con le letture R5)")
    void demoSourceIsRefused() throws Exception {
        String source = "SOURCE:src-crm";
        demo.perform(get("/v1/portal/required?memberId=MBR-000003").header(ActorFilter.HEADER, source))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN_ROLE"));
        demo.perform(get("/v1/portal/optional").header(ActorFilter.HEADER, source)).andExpect(status().isForbidden());
        demo.perform(post("/v1/portal/members").header(ActorFilter.HEADER, source)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN_ROLE"));
        demo.perform(get("/v1/portal/backoffice-read").header(ActorFilter.HEADER, source)).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("[demo] registrazione: MemberSubject demo (nessun account), come oggi crea un nuovo membro")
    void demoRegistration() throws Exception {
        demo.perform(post("/v1/portal/members")).andExpect(status().isOk()).andExpect(jsonPath("$.demo").value(true))
                .andExpect(jsonPath("$.ref").doesNotExist()).andExpect(jsonPath("$.linked").doesNotExist())
                .andExpect(jsonPath("$.actor").value("ANALYST:anonymous"));
    }

    @Test
    @DisplayName("[demo] corpo legacy: memberId uguale o assente passa, solo nel corpo vale, diverso ⇒ 400 MEMBER_MISMATCH; nessun rifiuto del campo")
    void demoBodyMerge() throws Exception {
        demo.perform(post("/v1/portal/write?memberId=MBR-000003").contentType(MediaType.APPLICATION_JSON)
                .content("{\"rewardCode\":\"RWD-1\",\"memberId\":\"MBR-000003\"}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("MBR-000003"));
        demo.perform(post("/v1/portal/write").header(MemberPrincipals.MEMBER_HEADER, "MBR-000003")
                .contentType(MediaType.APPLICATION_JSON).content("{\"rewardCode\":\"RWD-1\"}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("MBR-000003"));
        demo.perform(post("/v1/portal/write").contentType(MediaType.APPLICATION_JSON)
                .content("{\"rewardCode\":\"RWD-1\",\"memberId\":\"MBR-000003\"}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("MBR-000003")).andExpect(jsonPath("$.origin").value("DEMO"));
        demo.perform(post("/v1/portal/write?memberId=MBR-000004").contentType(MediaType.APPLICATION_JSON)
                .content("{\"rewardCode\":\"RWD-1\",\"memberId\":\"MBR-000003\"}")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MEMBER_MISMATCH"));
    }

    @Test
    @DisplayName("[demo] letture di programma e backoffice come oggi: ANALYST anonimo passa, un operatore per ruolo")
    void demoReadsAreUnchanged() throws Exception {
        demo.perform(get("/v1/portal/theme")).andExpect(status().isOk());
        demo.perform(get("/v1/portal/backoffice-read")).andExpect(status().isOk());
        demo.perform(get("/v1/portal/write-rule")).andExpect(status().isMethodNotAllowed());
        demo.perform(post("/v1/portal/write-rule")).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN_ROLE"));
        demo.perform(post("/v1/portal/write-rule").header(ActorFilter.HEADER, "CARE:paolo.care")).andExpect(status().isOk());
        demo.perform(get("/v1/portal/undeclared")).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ENDPOINT_NOT_DECLARED"));
    }

    @Test
    @DisplayName("[demo] X-LH-Actor non può produrre un membro: l'header member:MBR-1 vale ANALYST")
    void actorHeaderCannotForgeAMember() {
        // Ruolo minuscolo o sconosciuto: ANALYST (Q-298); il flag member non si imposta mai da un header.
        assertThat(ActorContext.parse("member:MBR-000003")).isEqualTo(new ActorContext(Role.ANALYST, "MBR-000003"));
        assertThat(ActorContext.parse("member:MBR-000003").member()).isFalse();
        assertThat(ActorContext.parse("member:MBR-000003").asActorString()).isEqualTo("ANALYST:MBR-000003");
        assertThat(ActorContext.parse("ANALYST:member:MBR-000003").member()).isFalse();
        assertThat(ActorContext.parse("ANALYST:x").asActorString()).isEqualTo("ANALYST:x");
    }

    @Test
    @DisplayName("il dispatch ASYNC non si ricontrolla e non lega il membro")
    void asyncDispatchIsSkipped() throws Exception {
        org.springframework.web.method.HandlerMethod handler = new org.springframework.web.method.HandlerMethod(
                new MemberTestSupport.Portal(), MemberTestSupport.Portal.class.getMethod("required", MemberPrincipal.class));
        org.springframework.mock.web.MockHttpServletRequest async = new org.springframework.mock.web.MockHttpServletRequest();
        async.setDispatcherType(jakarta.servlet.DispatcherType.ASYNC);
        assertThat(new EndpointAccessInterceptor(MemberPrincipals.header())
                .preHandle(async, new org.springframework.mock.web.MockHttpServletResponse(), handler)).isTrue();
        assertThat(async.getAttribute(MemberPrincipal.ATTRIBUTE)).isNull();
    }
}
