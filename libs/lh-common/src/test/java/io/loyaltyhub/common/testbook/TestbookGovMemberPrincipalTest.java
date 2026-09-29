package io.loyaltyhub.common.testbook;

import com.jayway.jsonpath.JsonPath;
import io.loyaltyhub.common.web.ActorHolder;
import io.loyaltyhub.common.web.MemberTestSupport;
import io.loyaltyhub.testsupport.OidcTestTokens;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.nio.charset.StandardCharsets;

import static io.loyaltyhub.common.web.MemberTestSupport.SUB_A;
import static io.loyaltyhub.common.web.MemberTestSupport.SUB_B;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * TB-GOV §3.3 — il membro dal token (Q-410, Q-553, Q-554, Q-556, ADR-048; docs/06 §3.2 e §3.4): matrice chiamante ×
 * dichiarazione ({@code @MemberEndpoint} nei tre modi, {@code @RequiresRole(members = true)}, backoffice, non dichiarato)
 * sui due profili, con token RS256 veri ({@link OidcTestTokens}), lo stesso filtro di identità, lo stesso interceptor e lo
 * stesso argument resolver dei servizi. Un token di membro raggiunge solo gli handler del membro e le letture aperte; il
 * membro viene solo dal token; un operatore non agisce mai come membro; in demo le risposte restano quelle di oggi.
 * Colonna {@code esito}: {@code <http>[:<code>]}; colonna {@code controlli}: coppie {@code chiave=valore} sul corpo JSON
 * ({@code NULL} = assente).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TestbookGovMemberPrincipalTest {

    private MockMvc oidcService;
    private MockMvc oidcAuthoritative;
    private MockMvc demo;

    @BeforeAll
    void build() {
        oidcService = MemberTestSupport.oidc(MemberTestSupport.oidcPrincipals(MemberTestSupport.linkedLookup(false)));
        oidcAuthoritative = MemberTestSupport.oidc(MemberTestSupport.oidcPrincipals(MemberTestSupport.linkedLookup(true)));
        demo = MemberTestSupport.demo();
    }

    @AfterEach
    void clear() {
        ActorHolder.clear();
    }

    @ParameterizedTest(name = "[{0}] {1}", quoteTextArguments = false)
    @CsvFileSource(resources = "/testbook/gov/member-principal.csv", numLinesToSkip = 1, delimiter = '|', quoteCharacter = '§')
    void principal(String id, String description, String profile, String caller, String lookup, String method, String path,
                   String header, String body, String expected, String checks) throws Exception {
        MockMvc mvc = "DEMO".equals(profile) ? demo : "AUTH".equals(lookup) ? oidcAuthoritative : oidcService;
        MockHttpServletRequestBuilder request;
        if ("FORM".equals(method)) {
            request = MockMvcRequestBuilders.post(path).contentType(MediaType.APPLICATION_FORM_URLENCODED).content(body);
        } else {
            request = MockMvcRequestBuilders.request(HttpMethod.valueOf(method), path);
            if (!"NONE".equals(body)) {
                request.contentType(MediaType.APPLICATION_JSON).content(body);
            }
        }
        if ("DEMO".equals(profile)) {
            if (!"-".equals(caller)) {
                request.header("X-LH-Actor", caller);
            }
        } else if (!"NONE".equals(caller)) {
            request.header("Authorization", "Bearer " + token(caller));
        }
        if (!"NONE".equals(header)) {
            int eq = header.indexOf('=');
            request.header(header.substring(0, eq), header.substring(eq + 1));
        }

        MockHttpServletResponse response = mvc.perform(request).andReturn().getResponse();
        String json = response.getContentAsString(StandardCharsets.UTF_8);
        String[] want = expected.split(":", 2);
        assertThat(response.getStatus()).as("%s: %s — stato (%s)", id, description, json).isEqualTo(Integer.parseInt(want[0]));
        if (want.length == 2) {
            assertThat((String) JsonPath.read(json, "$.code")).as("%s: %s — code", id, description).isEqualTo(want[1]);
        }
        if (!"-".equals(checks)) {
            for (String check : checks.split(";")) {
                int eq = check.indexOf('=');
                String key = check.substring(0, eq);
                String value = check.substring(eq + 1);
                Object got = JsonPath.parse(json).read("$." + key + "", Object.class, new com.jayway.jsonpath.Predicate[0]);
                if ("NULL".equals(value)) {
                    assertThat(got).as("%s: %s — %s", id, description, key).isNull();
                } else {
                    assertThat(String.valueOf(got)).as("%s: %s — %s", id, description, key).isEqualTo(value);
                }
            }
        }
        // Mai l'id ricevuto nel dettaglio di un errore del membro (Q-553): l'id compare solo in `instance`.
        if (response.getStatus() >= 400 && json.contains("\"detail\"")) {
            String detail = JsonPath.read(json, "$.detail");
            assertThat(detail).as("%s: detail senza id", id).doesNotContain("MBR-0001").doesNotContain("MBR-00000");
        }
    }

    private static String token(String caller) {
        OidcTestTokens tokens = MemberTestSupport.TOKENS;
        return switch (caller) {
            case "A" -> tokens.member(SUB_A);
            case "B" -> tokens.member(SUB_B);
            case "UNLINKED" -> tokens.member("sub-sconosciuto");
            case "CARE" -> tokens.operator("paolo.care", "CARE");
            case "ADMIN" -> tokens.operator("marta.admin", "ADMIN");
            case "MIXED" -> tokens.mixed(SUB_A, "CARE");
            case "MEMBER_SOURCE" -> tokens.memberSource(SUB_A, "src-crm");
            case "EXPIRED" -> tokens.expired(SUB_A);
            case "FOREIGN" -> tokens.foreignKey(SUB_A);
            case "WRONG_AUDIENCE" -> tokens.wrongAudience(SUB_A);
            default -> throw new IllegalArgumentException("chiamante sconosciuto: " + caller);
        };
    }
}
