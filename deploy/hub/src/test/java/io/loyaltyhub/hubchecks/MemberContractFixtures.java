package io.loyaltyhub.hubchecks;

import io.loyaltyhub.common.web.MemberEndpoint;
import io.loyaltyhub.common.web.MemberPrincipal;
import io.loyaltyhub.common.web.RequiresRole;
import io.loyaltyhub.common.web.Role;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Controller di prova di {@code MemberContractChecksTest}. Stanno in un package fratello di {@code io.loyaltyhub.hub}, non
 * sotto di esso: l'hub scandisce {@code io.loyaltyhub.hub} anche nei test e un {@code @RestController} con gli stessi percorsi dei
 * servizi manderebbe in errore la mappatura degli IT dell'hub (Ambiguous mapping).
 */
public final class MemberContractFixtures {

    private MemberContractFixtures() {
    }


    @RestController
    public static class Clean {
        @GetMapping("/v1/portal/me/wallet")
        @MemberEndpoint
        public String wallet(MemberPrincipal principal, @RequestParam("currency") String currency) {
            return principal.idOrNull() + currency;
        }

        @GetMapping("/v1/portal/wallets/{memberId}")
        @Deprecated
        @MemberEndpoint(demoPathVariable = "memberId")
        public String legacy(MemberPrincipal principal) {
            return principal.idOrNull();
        }

        @GetMapping("/v1/portal/theme")
        @RequiresRole(value = {Role.ADMIN, Role.ANALYST}, members = true)
        public String theme() {
            return "ok";
        }
    }

    @RestController
    public static class ImplicitName {
        @GetMapping("/v1/portal/coupons")
        @MemberEndpoint
        public String implicit(@RequestParam String memberId, MemberPrincipal principal) {
            return memberId + principal.idOrNull();
        }

        @GetMapping("/v1/portal/plain")
        @MemberEndpoint
        public String plain(String member_id, MemberPrincipal principal) {
            return member_id + principal.idOrNull();
        }

        @GetMapping("/v1/portal/path/{memberId}")
        @MemberEndpoint
        public String path(@PathVariable String memberId, MemberPrincipal principal) {
            return memberId + principal.idOrNull();
        }
    }

    @RestController
    public static class ExplicitNameIsAnotherThing {
        @GetMapping("/v1/portal/explicit")
        @MemberEndpoint
        public String explicit(@RequestParam("owner") String memberId, MemberPrincipal principal) {
            return memberId + principal.idOrNull();
        }
    }

    /** Parametri di una richiesta legati a un DTO: il binder di Spring lega anche {@code ?!memberId=…} e {@code ?_memberId=…}. */
    public record Query(String memberId, String code) {
    }

    /** Un bean con setter. */
    public static class QueryBean {
        private String memberId;
        private String code;

        public String getMemberId() {
            return memberId;
        }

        public void setMemberId(String memberId) {
            this.memberId = memberId;
        }

        public String getCode() {
            return code;
        }

        public void setCode(String code) {
            this.code = code;
        }
    }

    /** Un bean con costruttore. */
    public static class QueryCtor {
        private final String member_id;

        public QueryCtor(String member_id) {
            this.member_id = member_id;
        }

        public String memberIdValue() {
            return member_id;
        }
    }

    public record Outer(Query filter, String code) {
    }

    public record InList(java.util.List<Query> items) {
    }

    public record NoMemberId(String code, int size) {
    }

    @RestController
    public static class BoundModelAttribute {
        @GetMapping("/v1/portal/bound-model")
        @MemberEndpoint
        public String annotated(@ModelAttribute Query query, MemberPrincipal principal) {
            return query.code() + principal.idOrNull();
        }
    }

    @RestController
    public static class BoundUnannotatedRecord {
        @GetMapping("/v1/portal/bound-record")
        @MemberEndpoint
        public String record(Query query, MemberPrincipal principal) {
            return query.code() + principal.idOrNull();
        }
    }

    @RestController
    public static class BoundBean {
        @GetMapping("/v1/portal/bound-bean")
        @MemberEndpoint
        public String bean(QueryBean query, MemberPrincipal principal) {
            return query.getCode() + principal.idOrNull();
        }

        @GetMapping("/v1/portal/bound-ctor")
        @MemberEndpoint
        public String ctor(QueryCtor query, MemberPrincipal principal) {
            return query.memberIdValue() + principal.idOrNull();
        }
    }

    @RestController
    public static class BoundNested {
        @GetMapping("/v1/portal/bound-nested")
        @MemberEndpoint
        public String nested(Outer outer, MemberPrincipal principal) {
            return outer.code() + principal.idOrNull();
        }

        @GetMapping("/v1/portal/bound-list")
        @MemberEndpoint
        public String list(@ModelAttribute InList list, MemberPrincipal principal) {
            return list.items() + principal.idOrNull();
        }
    }

    /** Oggetti senza memberId, un corpo (lo controllano l'advice e il contratto) e i tipi di framework: nessun problema. */
    @RestController
    public static class BoundObjectsWithoutMemberId {
        @GetMapping("/v1/portal/bound-clean")
        @MemberEndpoint
        public String clean(NoMemberId query, MemberPrincipal principal, jakarta.servlet.http.HttpServletRequest request,
                            java.util.Locale locale, java.util.Map<String, Object> model) {
            return query.code() + principal.idOrNull();
        }

        @PostMapping("/v1/portal/bound-body")
        @MemberEndpoint
        public String body(@RequestBody Query body, MemberPrincipal principal) {
            return body.code() + principal.idOrNull();
        }

        @PostMapping("/v1/portal/bound-header")
        @MemberEndpoint
        public String other(@RequestHeader("X-Trace") String trace, MemberPrincipal principal) {
            return trace + principal.idOrNull();
        }
    }

    /** Fuori dai handler del membro non si controlla nulla (si attiva per operazione). */
    @RestController
    public static class BoundModelInBackoffice {
        @GetMapping("/v1/portal/bound-backoffice")
        @RequiresRole({Role.ADMIN, Role.ANALYST})
        public String backoffice(Query query) {
            return query.code();
        }
    }

    @RestController
    public static class NotDeprecated {
        @GetMapping("/v1/portal/legacy/{memberId}")
        @MemberEndpoint(demoPathVariable = "memberId")
        public String legacy(MemberPrincipal principal) {
            return principal.idOrNull();
        }
    }

    @RestController
    public static class OutsideThePortal {
        @PostMapping("/v1/wallets/me")
        @MemberEndpoint
        public String outside(MemberPrincipal principal) {
            return principal.idOrNull();
        }
    }

    @RestController
    public static class NoMemberEndpoints {
        @GetMapping("/v1/portal/legacy-backoffice/{memberId}")
        @RequiresRole({Role.ADMIN, Role.ANALYST})
        public String plain(@PathVariable String memberId) {
            return memberId;
        }
    }
}
