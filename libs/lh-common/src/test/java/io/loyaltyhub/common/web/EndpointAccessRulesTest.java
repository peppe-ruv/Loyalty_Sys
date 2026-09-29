package io.loyaltyhub.common.web;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.EvaluationResult;
import io.loyaltyhub.testsupport.EndpointAccessRules;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * La regola ArchUnit del deny by default ({@link EndpointAccessRules}, F2-SEC-09) riconosce le violazioni: endpoint
 * senza dichiarazione, {@code @PublicEndpoint} senza motivo, due dichiarazioni sullo stesso elemento; e le regole del
 * membro dal token (Q-410, ADR-048): elenco chiuso di tre dichiarazioni, parametri {@code MemberPrincipal} e
 * {@code MemberSubject} col modo giusto, nessun {@code memberId} legato con un nome esplicito, {@code members = true} solo
 * su GET sotto {@code /v1/portal/}, regola del portale.
 */
class EndpointAccessRulesTest {

    @RestController
    static class Declared {
        @GetMapping("/a")
        @RequiresRole({Role.ADMIN, Role.MARKETING, Role.LEGAL, Role.CARE, Role.ANALYST})
        public void read() {
        }

        @PostMapping("/b")
        @PublicEndpoint(reason = "sonda")
        public void open() {
        }
    }

    @RestController
    @RequiresRole(Role.ADMIN)
    static class DeclaredOnClass {
        @GetMapping("/c")
        public void inherited() {
        }
    }

    @RestController
    static class Undeclared {
        @GetMapping("/d")
        public void missing() {
        }
    }

    @RestController
    static class BlankReason {
        @GetMapping("/e")
        @PublicEndpoint(reason = " ")
        public void blank() {
        }
    }

    @RestController
    static class Both {
        @GetMapping("/f")
        @RequiresRole(Role.ADMIN)
        @PublicEndpoint(reason = "ambiguo")
        public void both() {
        }
    }

    @RestController
    @PublicEndpoint(reason = "")
    static class BlankReasonOnClass {
        @GetMapping("/g")
        public void inherited() {
        }
    }

    /** Base che non è un controller: la mappatura passa ai controller che la estendono. */
    abstract static class MappedBase {
        @GetMapping("/h")
        public void inheritedMapping() {
        }
    }

    @RestController
    static class InheritsMappingUndeclared extends MappedBase {
    }

    @RestController
    @RequiresRole(Role.ADMIN)
    static class InheritsMappingDeclaredOnClass extends MappedBase {
    }

    @RestController
    static class OverridesMappingAndDeclares extends MappedBase {
        @Override
        @RequiresRole(Role.ADMIN)
        public void inheritedMapping() {
        }
    }

    /** Interfaccia con la mappatura: chi la implementa espone l'endpoint. */
    interface MappedApi {
        @GetMapping("/i")
        void mapped();
    }

    @RestController
    static class ImplementsMappedApiUndeclared implements MappedApi {
        @Override
        public void mapped() {
        }
    }

    @RestController
    static class ImplementsMappedApiDeclared implements MappedApi {
        @Override
        @PublicEndpoint(reason = "sonda")
        public void mapped() {
        }
    }

    /** Annotazione qualunque segnata {@code @EndpointAccess}: l'interceptor non la applica, quindi non basta. */
    @EndpointAccess
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.METHOD, ElementType.TYPE})
    @interface Fake {
    }

    @RestController
    static class FakeOnMethod {
        @GetMapping("/j")
        @Fake
        public void fake() {
        }
    }

    @RestController
    @Fake
    static class FakeOnClass {
        @GetMapping("/k")
        public void fake() {
        }
    }

    private static EvaluationResult evaluate(Class<?>... classes) {
        return EndpointAccessRules.rule().evaluate(new ClassFileImporter().importClasses(classes));
    }

    @Test
    void declaredEndpointsPass() {
        assertThat(evaluate(Declared.class, DeclaredOnClass.class).hasViolation()).isFalse();
    }

    @Test
    void undeclaredEndpointFails() {
        EvaluationResult result = evaluate(Declared.class, Undeclared.class);
        assertThat(result.hasViolation()).isTrue();
        assertThat(result.getFailureReport().getDetails())
                .anySatisfy(d -> assertThat(d).contains("Undeclared.missing()").contains("non dichiara"));
    }

    @Test
    void publicEndpointNeedsAReason() {
        assertThat(evaluate(BlankReason.class).getFailureReport().getDetails())
                .anySatisfy(d -> assertThat(d).contains("senza motivo"));
        assertThat(evaluate(BlankReasonOnClass.class).getFailureReport().getDetails())
                .anySatisfy(d -> assertThat(d).contains("senza motivo"));
    }

    @Test
    void bothAnnotationsOnTheSameElementFail() {
        assertThat(evaluate(Both.class).getFailureReport().getDetails())
                .anySatisfy(d -> assertThat(d).contains("sia @RequiresRole sia @PublicEndpoint"));
    }

    @Test
    void mappingInheritedFromANonControllerBaseIsChecked() {
        EvaluationResult result = evaluate(MappedBase.class, InheritsMappingUndeclared.class);
        assertThat(result.hasViolation()).isTrue();
        assertThat(result.getFailureReport().getDetails())
                .anySatisfy(d -> assertThat(d).contains("MappedBase.inheritedMapping()")
                        .contains("ereditato da").contains("InheritsMappingUndeclared").contains("non dichiara"));
        assertThat(evaluate(MappedBase.class, InheritsMappingDeclaredOnClass.class).hasViolation()).isFalse();
        assertThat(evaluate(MappedBase.class, OverridesMappingAndDeclares.class).hasViolation()).isFalse();
    }

    @Test
    void mappingOnAnImplementedInterfaceIsChecked() {
        EvaluationResult result = evaluate(MappedApi.class, ImplementsMappedApiUndeclared.class);
        assertThat(result.hasViolation()).isTrue();
        assertThat(result.getFailureReport().getDetails())
                .anySatisfy(d -> assertThat(d).contains("mapped()").contains("non dichiara"));
        assertThat(evaluate(MappedApi.class, ImplementsMappedApiDeclared.class).hasViolation()).isFalse();
    }

    @Test
    void onlyRequiresRoleAndPublicEndpointCountAsDeclarations() {
        assertThat(evaluate(FakeOnMethod.class).getFailureReport().getDetails())
                .anySatisfy(d -> assertThat(d).contains("FakeOnMethod.fake()").contains("non dichiara"));
        assertThat(evaluate(FakeOnClass.class).getFailureReport().getDetails())
                .anySatisfy(d -> assertThat(d).contains("FakeOnClass.fake()").contains("non dichiara"));
    }

    // ================= membro dal token (Q-410, ADR-048) =================

    @RestController
    @RequestMapping("/v1/portal")
    static class MemberOk {
        @GetMapping("/required")
        @MemberEndpoint
        public String required(MemberPrincipal principal) {
            return principal.idOrNull();
        }

        @GetMapping("/optional")
        @MemberEndpoint(MemberEndpoint.Mode.OPTIONAL)
        public String optional(MemberPrincipal principal) {
            return principal.idOrNull();
        }

        @PostMapping("/members")
        @MemberEndpoint(MemberEndpoint.Mode.REGISTRATION)
        public boolean register(MemberSubject subject) {
            return subject.isDemo();
        }

        /** Passa il principal a un altro metodo: conta come uso. */
        @GetMapping("/passed")
        @MemberEndpoint
        public String passed(MemberPrincipal principal) {
            return describe(principal);
        }

        private static String describe(MemberPrincipal principal) {
            return String.valueOf(principal);
        }

        @GetMapping("/legacy/{memberId}")
        @Deprecated
        @MemberEndpoint(demoPathVariable = "memberId")
        public String legacy(MemberPrincipal principal) {
            return principal.idOrNull();
        }
    }

    @RestController
    @MemberEndpoint
    static class MemberOnClass {
        @GetMapping("/v1/portal/class")
        public String inherited(MemberPrincipal principal) {
            return principal.idOrNull();
        }
    }

    @RestController
    static class MemberWithRole {
        @GetMapping("/v1/portal/a")
        @MemberEndpoint
        @RequiresRole(Role.ADMIN)
        public String both(MemberPrincipal principal) {
            return principal.idOrNull();
        }
    }

    @RestController
    static class MemberWithPublic {
        @GetMapping("/v1/portal/a")
        @MemberEndpoint
        @PublicEndpoint(reason = "combinazione non valida")
        public String both(MemberPrincipal principal) {
            return principal.idOrNull();
        }
    }

    @RestController
    @MemberEndpoint
    static class ClassMemberMethodRole {
        @GetMapping("/v1/portal/a")
        @RequiresRole(Role.ADMIN)
        public String methodWins() {
            return "ok";
        }
    }

    @RestController
    @MemberEndpoint
    @RequiresRole(Role.ADMIN)
    static class ClassBoth {
        @GetMapping("/v1/portal/a")
        public String both(MemberPrincipal principal) {
            return principal.idOrNull();
        }
    }

    @RestController
    static class PrincipalWithoutDeclaration {
        @GetMapping("/v1/portal/a")
        @RequiresRole({Role.ADMIN, Role.ANALYST})
        public String noMember(MemberPrincipal principal) {
            return principal.idOrNull();
        }
    }

    @RestController
    static class SubjectWithoutDeclaration {
        @PostMapping("/v1/portal/a")
        @PublicEndpoint(reason = "sonda")
        public boolean noMember(MemberSubject subject) {
            return subject.isDemo();
        }
    }

    @RestController
    static class SubjectOnRequired {
        @GetMapping("/v1/portal/a")
        @MemberEndpoint
        public boolean wrong(MemberSubject subject) {
            return subject.isDemo();
        }
    }

    @RestController
    static class PrincipalOnRegistration {
        @PostMapping("/v1/portal/a")
        @MemberEndpoint(MemberEndpoint.Mode.REGISTRATION)
        public String wrong(MemberPrincipal principal) {
            return principal.idOrNull();
        }
    }

    @RestController
    static class TwoPrincipals {
        @GetMapping("/v1/portal/a")
        @MemberEndpoint
        public String two(MemberPrincipal one, MemberPrincipal two) {
            return one.idOrNull() + two.idOrNull();
        }
    }

    @RestController
    static class NoMemberParameter {
        @GetMapping("/v1/portal/a")
        @MemberEndpoint
        public String none() {
            return "ok";
        }
    }

    @RestController
    static class PrincipalUnused {
        @GetMapping("/v1/portal/a")
        @MemberEndpoint
        public String unused(MemberPrincipal principal) {
            return "ignora il membro";
        }
    }

    @RestController
    static class BindsMemberIdByName {
        @GetMapping("/v1/portal/a")
        @MemberEndpoint
        public String query(@RequestParam("memberId") String id, MemberPrincipal principal) {
            return id + principal.idOrNull();
        }

        @GetMapping("/v1/portal/b/{memberId}")
        @MemberEndpoint
        public String path(@PathVariable("memberId") String id, MemberPrincipal principal) {
            return id + principal.idOrNull();
        }
    }

    @RestController
    static class BindsMemberIdSpelledDifferently {
        @GetMapping("/v1/portal/a")
        @MemberEndpoint
        public String query(@RequestParam(name = "MEMBER_ID") String id, MemberPrincipal principal) {
            return id + principal.idOrNull();
        }
    }

    @RestController
    static class BindsTheDemoHeader {
        @GetMapping("/v1/portal/a")
        @MemberEndpoint
        public String header(@RequestHeader("X-LH-Member") String id, MemberPrincipal principal) {
            return id + principal.idOrNull();
        }
    }

    @RestController
    static class BindsMemberIdCookie {
        @GetMapping("/v1/portal/a")
        @MemberEndpoint
        public String cookie(@CookieValue(name = "member-id") String id, MemberPrincipal principal) {
            return id + principal.idOrNull();
        }
    }

    /** ArchUnit non vede i nomi impliciti: li copre il controllo dei handler registrati nell'hub (OpenApiExportIT). */
    @RestController
    static class BindsMemberIdImplicitly {
        @GetMapping("/v1/portal/a")
        @MemberEndpoint
        public String implicit(@RequestParam String memberId, MemberPrincipal principal) {
            return memberId + principal.idOrNull();
        }
    }

    @RestController
    static class OtherParametersAreFine {
        @GetMapping("/v1/portal/a")
        @MemberEndpoint
        public String other(@RequestParam("size") String size, @PathVariable("id") String id, MemberPrincipal principal) {
            return size + id + principal.idOrNull();
        }
    }

    @RestController
    @RequestMapping("/v1/portal")
    static class MembersReadOk {
        @GetMapping("/tiers")
        @RequiresRole(value = {Role.ADMIN, Role.ANALYST}, members = true)
        public String tiers() {
            return "ok";
        }
    }

    @RestController
    static class MembersReadOnPost {
        @PostMapping("/v1/portal/tiers")
        @RequiresRole(value = {Role.ADMIN, Role.ANALYST}, members = true)
        public String post() {
            return "ok";
        }
    }

    @RestController
    static class MembersReadAnyVerb {
        @RequestMapping("/v1/portal/tiers")
        @RequiresRole(value = {Role.ADMIN, Role.ANALYST}, members = true)
        public String any() {
            return "ok";
        }
    }

    @RestController
    @RequestMapping("/v1")
    static class MembersReadOutsidePortal {
        @GetMapping("/tiers")
        @RequiresRole(value = {Role.ADMIN, Role.ANALYST}, members = true)
        public String outside() {
            return "ok";
        }
    }

    @RestController
    @RequestMapping("/v1/portal/me")
    static class MembersReadUnderMe {
        @GetMapping("/wallet")
        @RequiresRole(value = {Role.ADMIN, Role.ANALYST}, members = true)
        public String underMe() {
            return "ok";
        }
    }

    @RestController
    @RequiresRole(value = {Role.ADMIN, Role.ANALYST}, members = true)
    static class MembersReadOnClassOutside {
        @GetMapping("/v1/theme")
        public String outside() {
            return "ok";
        }
    }

    // ---- regola del portale (opt-in) ----

    private static EvaluationResult evaluatePortal(Class<?>... classes) {
        return EndpointAccessRules.portalRule().evaluate(new ClassFileImporter().importClasses(classes));
    }

    @RestController
    @RequestMapping("/v1/portal")
    static class PortalGood {
        @GetMapping("/wallet")
        @MemberEndpoint
        public String wallet(MemberPrincipal principal) {
            return principal.idOrNull();
        }

        @GetMapping("/tiers")
        @RequiresRole(value = {Role.ADMIN, Role.ANALYST}, members = true)
        public String tiers() {
            return "ok";
        }

        @GetMapping("/frameworks")
        @RequiresRole(value = {Role.ADMIN, Role.ANALYST}, members = true)
        public String frameworkParameters(jakarta.servlet.http.HttpServletRequest request) {
            return request.getMethod();
        }
    }

    @RestController
    @RequestMapping("/v1")
    static class NotThePortal {
        @GetMapping("/campaigns")
        @RequiresRole({Role.ADMIN, Role.ANALYST})
        public String campaigns(@RequestParam("codes") String codes) {
            return codes;
        }
    }

    @RestController
    @RequestMapping("/v1/portal")
    static class PortalRoleOnly {
        @GetMapping("/legacy")
        @RequiresRole({Role.ADMIN, Role.ANALYST})
        public String legacy() {
            return "ok";
        }
    }

    @RestController
    @RequestMapping("/v1/portal")
    static class PortalPublic {
        @GetMapping("/open")
        @PublicEndpoint(reason = "sonda")
        public String open() {
            return "ok";
        }
    }

    @RestController
    @RequestMapping("/v1/portal")
    static class PortalMembersReadWithRequestParameter {
        @GetMapping("/tiers")
        @RequiresRole(value = {Role.ADMIN, Role.ANALYST}, members = true)
        public String tiers(@RequestParam("code") String code) {
            return code;
        }
    }

    @RestController
    @RequestMapping("/v1/portal")
    static class PortalMembersReadWithBoundObject {
        record Filter(String code) {
        }

        @GetMapping("/tiers")
        @RequiresRole(value = {Role.ADMIN, Role.ANALYST}, members = true)
        public String tiers(Filter filter) {
            return filter.code();
        }
    }

    @RestController
    @RequestMapping("/v1/portal")
    static class PortalMembersReadWithObjectId {
        @GetMapping("/redemptions/{id}")
        @RequiresRole(value = {Role.ADMIN, Role.ANALYST}, members = true)
        public String byId(@PathVariable("id") String id) {
            return id;
        }
    }

    @Test
    void theListOfDeclarationsIsClosedToThree() {
        assertThat(EndpointAccessRules.DECLARATIONS).containsExactly(EndpointAccessRules.REQUIRES_ROLE,
                EndpointAccessRules.PUBLIC_ENDPOINT, EndpointAccessRules.MEMBER_ENDPOINT);
        assertThat(EndpointAccessRules.MEMBER_ENDPOINT).isEqualTo(MemberEndpoint.class.getName());
        assertThat(EndpointAccessRules.MEMBER_PRINCIPAL).isEqualTo(MemberPrincipal.class.getName());
        assertThat(EndpointAccessRules.MEMBER_SUBJECT).isEqualTo(MemberSubject.class.getName());
        assertThat(EndpointAccessRules.REQUIRES_ROLE).isEqualTo(RequiresRole.class.getName());
        assertThat(EndpointAccessRules.PUBLIC_ENDPOINT).isEqualTo(PublicEndpoint.class.getName());
    }

    @Test
    void memberEndpointsWithTheRightShapePass() {
        EvaluationResult result = evaluate(MemberOk.class, MemberOnClass.class, MembersReadOk.class, OtherParametersAreFine.class);
        assertThat(result.hasViolation()).as(String.valueOf(result.getFailureReport().getDetails())).isFalse();
    }

    @Test
    void memberEndpointNeverCoexistsWithRequiresRoleOrPublicEndpoint() {
        assertThat(evaluate(MemberWithRole.class).getFailureReport().getDetails())
                .anySatisfy(d -> assertThat(d).contains("@MemberEndpoint insieme a @RequiresRole o @PublicEndpoint"));
        assertThat(evaluate(MemberWithPublic.class).getFailureReport().getDetails())
                .anySatisfy(d -> assertThat(d).contains("@MemberEndpoint insieme a @RequiresRole o @PublicEndpoint"));
        // Sulla classe: stesso rifiuto se il metodo non dichiara nulla.
        assertThat(evaluate(ClassBoth.class).getFailureReport().getDetails())
                .anySatisfy(d -> assertThat(d).contains("@MemberEndpoint insieme a @RequiresRole o @PublicEndpoint"));
        // Il metodo prevale sulla classe: una classe @MemberEndpoint con un metodo @RequiresRole è valida.
        assertThat(evaluate(ClassMemberMethodRole.class).hasViolation()).isFalse();
    }

    @Test
    void aMemberParameterNeedsMemberEndpoint() {
        assertThat(evaluate(PrincipalWithoutDeclaration.class).getFailureReport().getDetails())
                .anySatisfy(d -> assertThat(d).contains("un parametro MemberPrincipal richiede @MemberEndpoint"));
        assertThat(evaluate(SubjectWithoutDeclaration.class).getFailureReport().getDetails())
                .anySatisfy(d -> assertThat(d).contains("un parametro MemberSubject richiede @MemberEndpoint"));
    }

    @Test
    void theModeDecidesWhichParameterIsExpected() {
        assertThat(evaluate(SubjectOnRequired.class).getFailureReport().getDetails())
                .anySatisfy(d -> assertThat(d).contains("@MemberEndpoint(REQUIRED)").contains("MemberPrincipal"));
        assertThat(evaluate(PrincipalOnRegistration.class).getFailureReport().getDetails())
                .anySatisfy(d -> assertThat(d).contains("@MemberEndpoint(REGISTRATION)").contains("MemberSubject"));
        assertThat(evaluate(TwoPrincipals.class).getFailureReport().getDetails())
                .anySatisfy(d -> assertThat(d).contains("esattamente un parametro MemberPrincipal"));
        assertThat(evaluate(NoMemberParameter.class).getFailureReport().getDetails())
                .anySatisfy(d -> assertThat(d).contains("esattamente un parametro MemberPrincipal"));
    }

    @Test
    void theHandlerMustUseTheMember() {
        assertThat(evaluate(PrincipalUnused.class).getFailureReport().getDetails())
                .anySatisfy(d -> assertThat(d).contains("non usa il MemberPrincipal"));
    }

    @Test
    void aMemberHandlerNeverBindsMemberIdOrTheDemoHeaderByName() {
        assertThat(evaluate(BindsMemberIdByName.class).getFailureReport().getDetails())
                .anySatisfy(d -> assertThat(d).contains("RequestParam").contains("memberId"))
                .anySatisfy(d -> assertThat(d).contains("PathVariable").contains("memberId"));
        assertThat(evaluate(BindsMemberIdSpelledDifferently.class).getFailureReport().getDetails())
                .anySatisfy(d -> assertThat(d).contains("MEMBER_ID"));
        assertThat(evaluate(BindsTheDemoHeader.class).getFailureReport().getDetails())
                .anySatisfy(d -> assertThat(d).contains("RequestHeader").contains("X-LH-Member"));
        assertThat(evaluate(BindsMemberIdCookie.class).getFailureReport().getDetails())
                .anySatisfy(d -> assertThat(d).contains("CookieValue").contains("member-id"));
    }

    /** I nomi impliciti ({@code @RequestParam String memberId}) non sono visibili ad ArchUnit: li copre l'hub. */
    @Test
    void implicitNamesAreNotVisibleToArchUnit() {
        assertThat(evaluate(BindsMemberIdImplicitly.class).hasViolation()).isFalse();
    }

    @Test
    void membersReadIsOnlyAGetUnderThePortalButNotUnderMe() {
        assertThat(evaluate(MembersReadOnPost.class).getFailureReport().getDetails())
                .anySatisfy(d -> assertThat(d).contains("members = true").contains("solo su GET"));
        assertThat(evaluate(MembersReadAnyVerb.class).getFailureReport().getDetails())
                .anySatisfy(d -> assertThat(d).contains("solo su GET"));
        assertThat(evaluate(MembersReadOutsidePortal.class).getFailureReport().getDetails())
                .anySatisfy(d -> assertThat(d).contains("solo sotto /v1/portal/").contains("/v1/tiers"));
        assertThat(evaluate(MembersReadUnderMe.class).getFailureReport().getDetails())
                .anySatisfy(d -> assertThat(d).contains("non è ammesso sotto /v1/portal/me").contains("/v1/portal/me/wallet"));
        assertThat(evaluate(MembersReadOnClassOutside.class).getFailureReport().getDetails())
                .anySatisfy(d -> assertThat(d).contains("solo sotto /v1/portal/"));
    }

    @Test
    void thePortalRuleAcceptsMemberEndpointsAndParameterlessMemberReads() {
        assertThat(evaluatePortal(PortalGood.class, NotThePortal.class).hasViolation()).isFalse();
    }

    @Test
    void thePortalRuleRefusesEveryOtherPortalHandler() {
        assertThat(evaluatePortal(PortalRoleOnly.class).getFailureReport().getDetails())
                .anySatisfy(d -> assertThat(d).contains("PortalRoleOnly.legacy()").contains("è @MemberEndpoint o @RequiresRole(members = true)"));
        assertThat(evaluatePortal(PortalPublic.class).getFailureReport().getDetails())
                .anySatisfy(d -> assertThat(d).contains("PortalPublic.open()"));
    }

    @Test
    void aMemberReadWithRequestBoundParametersMustBeAMemberEndpoint() {
        assertThat(evaluatePortal(PortalMembersReadWithRequestParameter.class).getFailureReport().getDetails())
                .anySatisfy(d -> assertThat(d).contains("non ha parametri legati alla richiesta").contains("RequestParam"));
        assertThat(evaluatePortal(PortalMembersReadWithBoundObject.class).getFailureReport().getDetails())
                .anySatisfy(d -> assertThat(d).contains("non ha parametri legati alla richiesta").contains("Filter"));
        assertThat(evaluatePortal(PortalMembersReadWithObjectId.class).getFailureReport().getDetails())
                .anySatisfy(d -> assertThat(d).contains("PathVariable"));
    }

    @Test
    void checkPortalAppliesBothRules() {
        // Un package senza controller fallisce come check(): la regola non passa a vuoto.
        org.junit.jupiter.api.Assertions.assertThrows(AssertionError.class,
                () -> EndpointAccessRules.checkPortal("io.loyaltyhub.common.web.nonexistent"));
    }
}
