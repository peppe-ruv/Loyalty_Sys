package io.loyaltyhub.hub;

import io.loyaltyhub.hub.MemberContractChecks.Operation;
import io.loyaltyhub.hubchecks.MemberContractFixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockServletContext;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * I controlli di {@link MemberContractChecks} riconoscono le violazioni (Q-410, ADR-048): nomi impliciti di
 * {@code memberId}, {@code demoPathVariable} su un handler non deprecato, handler fuori dal portale, parametri e proprietà
 * di corpo {@code memberId} nel contratto generato. Senza handler del membro non controllano nulla (si attivano per
 * controller).
 */
class MemberContractChecksTest {

    /** Non è {@code @Configuration}: l'hub scandisce {@code io.loyaltyhub.**} anche nei test e la porterebbe nei suoi IT. */
    @EnableWebMvc
    static class Base {
    }

    private static Map<RequestMappingInfo, HandlerMethod> handlers(Class<?>... controllers) {
        try (AnnotationConfigWebApplicationContext context = new AnnotationConfigWebApplicationContext()) {
            context.setServletContext(new MockServletContext());
            context.register(Base.class);
            context.register(controllers);
            context.refresh();
            return Map.copyOf(context.getBean(RequestMappingHandlerMapping.class).getHandlerMethods());
        }
    }

    @Test
    @DisplayName("handler corretti: nessun problema; le loro operazioni sono riconosciute")
    void cleanHandlers() {
        Map<RequestMappingInfo, HandlerMethod> handlers = handlers(MemberContractFixtures.Clean.class);
        assertThat(MemberContractChecks.handlerProblems(handlers)).isEmpty();
        assertThat(MemberContractChecks.memberOperations(handlers)).containsExactlyInAnyOrder(
                new Operation("get", "/v1/portal/me/wallet"), new Operation("get", "/v1/portal/wallets/{memberId}"));
    }

    @Test
    @DisplayName("un parametro con nome implicito memberId (in qualunque grafia, anche di percorso) su un handler del membro è un problema")
    void implicitNames() {
        assertThat(MemberContractChecks.handlerProblems(handlers(MemberContractFixtures.ImplicitName.class)))
                .anySatisfy(p -> assertThat(p).contains("ImplicitName#implicit").contains("memberId"))
                .anySatisfy(p -> assertThat(p).contains("ImplicitName#plain").contains("member_id"))
                .anySatisfy(p -> assertThat(p).contains("ImplicitName#path").contains("memberId"));
    }

    @Test
    @DisplayName("un oggetto legato dalla richiesta (@ModelAttribute, record, bean, costruttore, annidato, in un elenco) con memberId è un problema: il binder lega anche ?!memberId=")
    void boundObjectsCarryingAMemberIdAreProblems() {
        assertThat(MemberContractChecks.handlerProblems(handlers(MemberContractFixtures.BoundModelAttribute.class)))
                .anySatisfy(p -> assertThat(p).contains("BoundModelAttribute#annotated").contains("Query").contains("memberId")
                        .contains("?!memberId"));
        assertThat(MemberContractChecks.handlerProblems(handlers(MemberContractFixtures.BoundUnannotatedRecord.class)))
                .anySatisfy(p -> assertThat(p).contains("BoundUnannotatedRecord#record").contains("memberId"));
        assertThat(MemberContractChecks.handlerProblems(handlers(MemberContractFixtures.BoundBean.class)))
                .anySatisfy(p -> assertThat(p).contains("BoundBean#bean").contains("QueryBean").contains("memberId"))
                .anySatisfy(p -> assertThat(p).contains("BoundBean#ctor").contains("member_id"));
        assertThat(MemberContractChecks.handlerProblems(handlers(MemberContractFixtures.BoundNested.class)))
                .anySatisfy(p -> assertThat(p).contains("BoundNested#nested").contains("filter.memberId"))
                .anySatisfy(p -> assertThat(p).contains("BoundNested#list").contains("items.memberId"));
    }

    @Test
    @DisplayName("oggetti senza memberId, un corpo, un header, i tipi di framework e i handler di backoffice non sono problemi")
    void boundObjectsWithoutAMemberIdAreFine() {
        assertThat(MemberContractChecks.handlerProblems(handlers(MemberContractFixtures.BoundObjectsWithoutMemberId.class))).isEmpty();
        assertThat(MemberContractChecks.handlerProblems(handlers(MemberContractFixtures.BoundModelInBackoffice.class))).isEmpty();
    }

    @Test
    @DisplayName("isMemberIdName riconosce anche i prefissi del binder di Spring")
    void memberIdNamesIncludeTheBinderPrefixes() {
        for (String yes : new String[] {"memberId", "member_id", "!memberId", "_memberId", "!filter.memberId", "MEMBER-ID"}) {
            assertThat(MemberContractChecks.isMemberIdName(yes)).as(yes).isTrue();
        }
        for (String no : new String[] {"member", "memberIds", "id", "!code", null}) {
            assertThat(MemberContractChecks.isMemberIdName(no)).as(String.valueOf(no)).isFalse();
        }
    }

    @Test
    @DisplayName("un nome esplicito diverso non lega memberId: nessun falso positivo")
    void explicitOtherNameIsFine() {
        assertThat(MemberContractChecks.handlerProblems(handlers(MemberContractFixtures.ExplicitNameIsAnotherThing.class))).isEmpty();
    }

    @Test
    @DisplayName("demoPathVariable su un handler non deprecato e handler fuori da /v1/portal/ sono problemi")
    void structuralProblems() {
        assertThat(MemberContractChecks.handlerProblems(handlers(MemberContractFixtures.NotDeprecated.class)))
                .anySatisfy(p -> assertThat(p).contains("demoPathVariable solo su un handler @Deprecated"));
        assertThat(MemberContractChecks.handlerProblems(handlers(MemberContractFixtures.OutsideThePortal.class)))
                .anySatisfy(p -> assertThat(p).contains("solo sotto /v1/portal/"));
    }

    @Test
    @DisplayName("si attiva per controller: senza @MemberEndpoint nessun controllo (un memberId in un handler di backoffice non conta)")
    void inactiveWithoutMemberEndpoints() {
        Map<RequestMappingInfo, HandlerMethod> handlers = handlers(MemberContractFixtures.NoMemberEndpoints.class);
        assertThat(MemberContractChecks.handlerProblems(handlers)).isEmpty();
        assertThat(MemberContractChecks.memberOperations(handlers)).isEmpty();
    }

    // ---- contratto ----

    private static JsonNode spec(String json) {
        return JsonMapper.builder().build().readTree(json);
    }

    private static final String COMPONENTS = """
            "components":{"schemas":{
              "Redeem":{"type":"object","properties":{"rewardCode":{"type":"string"},"memberId":{"type":"string","deprecated":true}}},
              "RedeemStrict":{"type":"object","properties":{"rewardCode":{"type":"string"},"memberId":{"type":"string"}}},
              "Wrapper":{"type":"object","properties":{"inner":{"type":"array","items":{"$ref":"#/components/schemas/RedeemStrict"}}}},
              "Cycle":{"type":"object","properties":{"next":{"$ref":"#/components/schemas/Cycle"}}}}}""";

    @Test
    @DisplayName("contratto: sotto /v1/portal/me nessun parametro né proprietà di corpo memberId, a nessuna profondità")
    void nothingUnderMe() {
        JsonNode spec = spec("""
                {"paths":{
                  "/v1/portal/me/wallet":{"get":{"parameters":[{"name":"currency","in":"query"},{"name":"memberId","in":"query"}]}},
                  "/v1/portal/me/redeem":{"post":{"requestBody":{"content":{"application/json":{"schema":{"$ref":"#/components/schemas/Wrapper"}}}}}},
                  "/v1/portal/me/ok":{"get":{"parameters":[{"name":"size","in":"query"}]}},
                  "/v1/portal/me/deprecated-is-not-enough":{"post":{"requestBody":{"content":{"application/json":{"schema":{"$ref":"#/components/schemas/Redeem"}}}}}}
                }, %s}""".formatted(COMPONENTS));
        List<String> problems = MemberContractChecks.contractProblems(spec, Set.of());
        assertThat(problems).anySatisfy(p -> assertThat(p).contains("GET /v1/portal/me/wallet").contains("memberId"))
                .anySatisfy(p -> assertThat(p).contains("POST /v1/portal/me/redeem").contains("proprietà di corpo"))
                .anySatisfy(p -> assertThat(p).contains("deprecated-is-not-enough"))
                .noneSatisfy(p -> assertThat(p).contains("/v1/portal/me/ok"));
    }

    @Test
    @DisplayName("contratto: su un'operazione del membro un id di percorso e una proprietà di corpo memberId solo se deprecated")
    void legacyOnlyIfDeprecated() {
        JsonNode spec = spec("""
                {"paths":{
                  "/v1/portal/wallets/{memberId}":{"get":{"deprecated":true,"parameters":[{"name":"memberId","in":"path"}]}},
                  "/v1/portal/legacy/{memberId}":{"get":{"parameters":[{"name":"memberId","in":"path"}]}},
                  "/v1/portal/members/{id}":{"get":{"parameters":[{"name":"id","in":"path"}]}},
                  "/v1/portal/members/{id}/referral":{"get":{"deprecated":true,"parameters":[{"name":"id","in":"path"}]}},
                  "/v1/portal/redemptions/{id}":{"get":{"parameters":[{"name":"id","in":"path"}]}},
                  "/v1/portal/redemptions":{"post":{"requestBody":{"content":{"application/json":{"schema":{"$ref":"#/components/schemas/Redeem"}}}}}},
                  "/v1/portal/strict":{"post":{"requestBody":{"content":{"application/json":{"schema":{"$ref":"#/components/schemas/RedeemStrict"}}}}}},
                  "/v1/portal/backoffice/{memberId}":{"get":{"parameters":[{"name":"memberId","in":"path"}]}},
                  "/v1/portal/cyclic":{"post":{"requestBody":{"content":{"application/json":{"schema":{"$ref":"#/components/schemas/Cycle"}}}}}}
                }, %s}""".formatted(COMPONENTS));
        Set<Operation> ops = Set.of(new Operation("get", "/v1/portal/wallets/{memberId}"),
                new Operation("get", "/v1/portal/legacy/{memberId}"), new Operation("get", "/v1/portal/members/{id}"),
                new Operation("get", "/v1/portal/members/{id}/referral"), new Operation("get", "/v1/portal/redemptions/{id}"),
                new Operation("post", "/v1/portal/redemptions"), new Operation("post", "/v1/portal/strict"),
                new Operation("post", "/v1/portal/cyclic"));
        List<String> problems = MemberContractChecks.contractProblems(spec, ops);
        assertThat(problems).anySatisfy(p -> assertThat(p).contains("GET /v1/portal/legacy/{memberId}"))
                .anySatisfy(p -> assertThat(p).contains("GET /v1/portal/members/{id}").contains("«id»"))
                .anySatisfy(p -> assertThat(p).contains("POST /v1/portal/strict").contains("deprecated: true"))
                // Il campo legacy marcato deprecated, l'id di un oggetto («redemptions/{id}»), le operazioni deprecated
                // e quelle che non sono di un handler del membro non sono problemi; un riferimento circolare non cicla.
                .noneSatisfy(p -> assertThat(p).contains("wallets/{memberId}"))
                .noneSatisfy(p -> assertThat(p).contains("members/{id}/referral"))
                .noneSatisfy(p -> assertThat(p).contains("redemptions"))
                .noneSatisfy(p -> assertThat(p).contains("backoffice"))
                .noneSatisfy(p -> assertThat(p).contains("cyclic"));
    }

    @Test
    @DisplayName("contratto: un elenco annidato che raggiunge un memberId non deprecato è un problema")
    void nestedSchemas() {
        JsonNode spec = spec("""
                {"paths":{"/v1/portal/nested":{"post":{"requestBody":{"content":{"application/json":{"schema":{"$ref":"#/components/schemas/Wrapper"}}}}}}}, %s}"""
                .formatted(COMPONENTS));
        assertThat(MemberContractChecks.contractProblems(spec, Set.of(new Operation("post", "/v1/portal/nested"))))
                .anySatisfy(p -> assertThat(p).contains("POST /v1/portal/nested").contains("Wrapper"));
        assertThat(MemberContractChecks.contractProblems(spec, Set.of())).isEmpty();
    }
}
