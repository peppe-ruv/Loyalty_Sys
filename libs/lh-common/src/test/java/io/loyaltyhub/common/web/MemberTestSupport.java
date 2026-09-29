package io.loyaltyhub.common.web;

import io.loyaltyhub.common.config.IdentityGuard;
import io.loyaltyhub.common.identity.MemberSubjectLookup;
import io.loyaltyhub.common.identity.SubjectRef;
import io.loyaltyhub.testsupport.OidcTestTokens;
import org.slf4j.MDC;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Supporto dei test del membro dal token (Q-410, ADR-048): token RS256 veri ({@link OidcTestTokens}), lookup in memoria,
 * handler di prova per ogni dichiarazione e due {@link MockMvc} (enterprise con il filtro OIDC, demo con
 * {@link ActorFilter}) con la stessa catena dei servizi: filtro di identità, interceptor, argument resolver, advice del corpo.
 */
public final class MemberTestSupport {

    /** Chiave di prova dello pseudonimo (32 byte calcolati, non è un segreto: nessun valore letterale nel repository). */
    public static final byte[] KEY = testKey();
    public static final OidcTestTokens TOKENS = new OidcTestTokens();

    /** I membri di prova: A e B, con i loro {@code sub}. */
    public static final String SUB_A = "sub-a";
    public static final String SUB_B = "sub-b";
    public static final String ID_A = "MBR-000101";
    public static final String ID_B = "MBR-000102";

    private MemberTestSupport() {
    }

    private static byte[] testKey() {
        byte[] key = new byte[32];
        for (int i = 0; i < key.length; i++) {
            key[i] = (byte) (i * 7 + 3);
        }
        return key;
    }

    public static String ref(String sub) {
        return SubjectRef.of(KEY, TOKENS.issuer(), sub);
    }

    /** Lookup in memoria: {@code subjectRef → memberId}. */
    public static class InMemoryLookup implements MemberSubjectLookup {
        final String module;
        final boolean authoritative;
        final Map<String, String> links = new HashMap<>();
        public int calls;

        public InMemoryLookup(String module, boolean authoritative) {
            this.module = module;
            this.authoritative = authoritative;
        }

        public InMemoryLookup link(String sub, String memberId) {
            links.put(ref(sub), memberId);
            return this;
        }

        @Override
        public String modulePackage() {
            return module;
        }

        @Override
        public Optional<String> memberId(String subjectRef) {
            calls++;
            return Optional.ofNullable(links.get(subjectRef));
        }

        @Override
        public boolean authoritative() {
            return authoritative;
        }
    }

    /** Il modulo delle sonde: il package di questa classe. */
    public static final String MODULE = "io.loyaltyhub.common.web";

    /** Lookup del servizio con A e B legati. */
    public static InMemoryLookup linkedLookup(boolean authoritative) {
        return new InMemoryLookup(MODULE, authoritative).link(SUB_A, ID_A).link(SUB_B, ID_B);
    }

    public static MemberPrincipals oidcPrincipals(MemberSubjectLookup... lookups) {
        return new MemberPrincipals(IdentityMode.OIDC, KEY, List.of(lookups));
    }

    public static NimbusJwtDecoder decoder() {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(TOKENS.publicKey()).build();
        decoder.setJwtValidator(IdentityGuard.validator(TOKENS.issuer(), TOKENS.audience()));
        return decoder;
    }

    /** Enterprise: filtro OIDC, interceptor con il membro dal token, resolver e advice del corpo. */
    public static MockMvc oidc(MemberPrincipals principals) {
        return MockMvcBuilders.standaloneSetup(new Portal(), new BackOffice(), new ClassLevel())
                .setControllerAdvice(new GlobalExceptionHandler(), new MemberBodyAdvice(IdentityMode.OIDC))
                .setCustomArgumentResolvers(new MemberPrincipalArgumentResolver())
                .addFilters(new OidcActorFilter("probe", decoder(), OidcTestTokens.DEFAULT_ROLES_CLAIM))
                .addInterceptors(new EndpointAccessInterceptor(principals))
                .build();
    }

    /** Demo: {@code X-LH-Actor} e il membro da {@code memberId} o {@code X-LH-Member}. */
    public static MockMvc demo() {
        return MockMvcBuilders.standaloneSetup(new Portal(), new BackOffice(), new ClassLevel())
                .setControllerAdvice(new GlobalExceptionHandler(), new MemberBodyAdvice(IdentityMode.HEADER))
                .setCustomArgumentResolvers(new MemberPrincipalArgumentResolver())
                .addFilters(new ActorFilter("probe"))
                .addInterceptors(new EndpointAccessInterceptor(MemberPrincipals.header()))
                .build();
    }

    /** Corpo legacy di una scrittura del portale: il {@code memberId} è un campo deprecato, solo demo. */
    public record WriteRequest(String rewardCode, String memberId) {
    }

    /** Parametri di una richiesta legati a un DTO, con un campo {@code memberId} (binder di Spring). */
    public record BoundQuery(String memberId, String code) {
    }

    /** Corpo con il membro annidato. */
    public record NestedRequest(String rewardCode, Inner shipping) {
        public record Inner(String city, String memberId) {
        }
    }

    /** Ciò che un handler vede del membro: id, origine, attore (holder e MDC). */
    static Map<String, Object> seen(MemberPrincipal p) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("member", p.idOrNull());
        out.put("origin", p.origin().name());
        out.put("actor", ActorHolder.get().asActorString());
        out.put("mdc", MDC.get("actor"));
        return out;
    }

    /** Handler del portale di prova: uno per dichiarazione. */
    @RestController
    @RequestMapping("/v1/portal")
    public static class Portal {

        @GetMapping("/required")
        @MemberEndpoint
        public Map<String, Object> required(MemberPrincipal principal) {
            return seen(principal);
        }

        @GetMapping("/required-param")
        @MemberEndpoint
        public Map<String, Object> requiredParam(MemberPrincipal principal) {
            Map<String, Object> out = seen(principal);
            out.put("id", principal.requireParam());
            return out;
        }

        @GetMapping("/optional")
        @MemberEndpoint(MemberEndpoint.Mode.OPTIONAL)
        public Map<String, Object> optional(MemberPrincipal principal) {
            return seen(principal);
        }

        @PostMapping("/members")
        @MemberEndpoint(MemberEndpoint.Mode.REGISTRATION)
        public Map<String, Object> register(MemberSubject subject) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("demo", subject.isDemo());
            out.put("linked", subject.linkedMemberId());
            out.put("ref", subject.subjectRef());
            out.put("actor", ActorHolder.get().asActorString());
            return out;
        }

        @PostMapping("/write")
        @MemberEndpoint
        public Map<String, Object> write(@RequestBody WriteRequest body, MemberPrincipal principal) {
            Map<String, Object> out = seen(principal);
            out.put("id", principal.merge(body.memberId()));
            return out;
        }

        /**
         * Un DTO legato dalla richiesta (query o campo form) con un campo {@code memberId}: il binder di Spring lo lega
         * anche con i prefissi {@code !} e {@code _}, senza che il nome del parametro coincida con {@code memberId}.
         */
        @RequestMapping(path = "/bound", method = {RequestMethod.GET, RequestMethod.POST})
        @MemberEndpoint
        public Map<String, Object> bound(BoundQuery query, MemberPrincipal principal) {
            Map<String, Object> out = seen(principal);
            out.put("boundMemberId", query.memberId());
            out.put("code", query.code());
            return out;
        }

        @PostMapping("/nested")
        @MemberEndpoint
        public Map<String, Object> nested(@RequestBody NestedRequest body, MemberPrincipal principal) {
            return seen(principal);
        }

        @PostMapping("/tree")
        @MemberEndpoint
        public Map<String, Object> tree(@RequestBody tools.jackson.databind.JsonNode body, MemberPrincipal principal) {
            return seen(principal);
        }

        @GetMapping("/owned/{id}")
        @MemberEndpoint
        public Map<String, Object> owned(@org.springframework.web.bind.annotation.PathVariable String id,
                                         MemberPrincipal principal) {
            principal.checkOwner("owner-of-" + id);
            return seen(principal);
        }

        @GetMapping("/wallets/{memberId}")
        @Deprecated
        @MemberEndpoint(demoPathVariable = "memberId")
        public Map<String, Object> legacyWallet(MemberPrincipal principal) {
            return seen(principal);
        }

        @GetMapping("/theme")
        @RequiresRole(value = {Role.ADMIN, Role.MARKETING, Role.LEGAL, Role.CARE, Role.ANALYST}, members = true)
        public Map<String, Object> theme() {
            return Map.of("ok", true);
        }

        @GetMapping("/backoffice-read")
        @RequiresRole({Role.ADMIN, Role.MARKETING, Role.LEGAL, Role.CARE, Role.ANALYST})
        public Map<String, Object> backofficeRead() {
            return Map.of("ok", true);
        }

        @PostMapping("/write-rule")
        @RequiresRole
        public Map<String, Object> writeRule() {
            return Map.of("ok", true);
        }

        @GetMapping("/members-write-rule")
        @RequiresRole(members = true)
        public Map<String, Object> membersWithWriteRule() {
            return Map.of("ok", true);
        }

        @GetMapping("/public")
        @PublicEndpoint(reason = "sonda del test")
        public Map<String, Object> open() {
            return Map.of("ok", true);
        }

        @GetMapping("/undeclared")
        public Map<String, Object> undeclared() {
            return Map.of("ok", true);
        }

        @GetMapping("/combined")
        @MemberEndpoint
        @RequiresRole(Role.ADMIN)
        public Map<String, Object> combined(MemberPrincipal principal) {
            return seen(principal);
        }

        @GetMapping("/combined-public")
        @MemberEndpoint
        @PublicEndpoint(reason = "combinazione non valida")
        public Map<String, Object> combinedPublic(MemberPrincipal principal) {
            return seen(principal);
        }

        /** Il parametro del membro senza dichiarazione: il resolver non ha nulla da consegnare. */
        @GetMapping("/principal-without-declaration")
        @RequiresRole({Role.ADMIN, Role.ANALYST})
        public Map<String, Object> principalWithoutDeclaration(MemberPrincipal principal) {
            return Map.of("member", String.valueOf(principal));
        }
    }

    /** Un endpoint di backoffice fuori dal portale. */
    @RestController
    public static class BackOffice {

        @GetMapping("/v1/campaigns")
        @RequiresRole({Role.ADMIN, Role.MARKETING, Role.LEGAL, Role.CARE, Role.ANALYST})
        public Map<String, Object> campaigns() {
            return Map.of("ok", true);
        }
    }

    /** {@link MemberEndpoint} sulla classe: vale per i metodi che non dichiarano altro. */
    @RestController
    @MemberEndpoint
    @RequestMapping("/v1/portal/class-level")
    public static class ClassLevel {

        @GetMapping
        public Map<String, Object> inherited(MemberPrincipal principal) {
            return seen(principal);
        }

        @GetMapping("/open")
        @PublicEndpoint(reason = "il metodo prevale sulla classe")
        public Map<String, Object> open() {
            return Map.of("ok", true);
        }
    }
}
