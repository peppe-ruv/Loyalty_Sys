package io.loyaltyhub.hubchecks;

import io.loyaltyhub.common.web.MemberEndpoint;
import io.loyaltyhub.common.web.MemberPrincipal;
import io.loyaltyhub.common.web.RequiresRole;
import io.loyaltyhub.common.web.Role;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
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
