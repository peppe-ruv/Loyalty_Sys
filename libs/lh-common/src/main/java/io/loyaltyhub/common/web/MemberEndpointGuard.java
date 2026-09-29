package io.loyaltyhub.common.web;

import io.loyaltyhub.common.config.IdentityGuard;
import io.loyaltyhub.common.identity.MemberSubjectLookup;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Verifica all'avvio che gli endpoint del membro siano configurati in modo sicuro (Q-410, ADR-048, regola 22): altrimenti
 * l'avvio fallisce con {@code INSECURE_CONFIG}, mai con un avviso. Condizioni, su ogni handler registrato:
 * <ol>
 *   <li>ogni {@link MemberEndpoint} sta sotto {@value #PORTAL_PREFIX};</li>
 *   <li>ogni {@link MemberEndpoint#demoPathVariable()} compare nel pattern, e solo su un handler {@link Deprecated};</li>
 *   <li>ogni {@link RequiresRole#members()} {@code = true} è un {@code GET} sotto {@value #PORTAL_PREFIX}, non sotto
 *       {@value #ME_PREFIX} (dati uguali per tutti, mai dati di un membro), e elenca {@link Role#ANALYST} (il ruolo
 *       dell'attore di un membro: senza, risponderebbe 403 a ogni membro);</li>
 *   <li>in {@code oidc}: per ogni modulo con handler {@code REQUIRED} o {@code OPTIONAL} esiste esattamente una
 *       {@link MemberSubjectLookup}; dove c'è {@code REGISTRATION} una lookup autorevole; la chiave
 *       {@code LH_SUBJECT_KEY} è presente e lunga almeno 32 byte.</li>
 * </ol>
 */
public class MemberEndpointGuard implements SmartInitializingSingleton {

    static final String PORTAL_PREFIX = "/v1/portal/";
    static final String ME_PREFIX = "/v1/portal/me";

    private final ObjectProvider<RequestMappingHandlerMapping> mappings;
    private final MemberPrincipals principals;
    private final Environment environment;

    public MemberEndpointGuard(ObjectProvider<RequestMappingHandlerMapping> mappings, MemberPrincipals principals,
                               Environment environment) {
        this.mappings = mappings;
        this.principals = principals;
        this.environment = environment;
    }

    @Override
    public void afterSingletonsInstantiated() {
        verify();
    }

    /** Verifica tutti gli handler registrati; lancia {@link IllegalStateException} ({@code INSECURE_CONFIG}) se insicuri. */
    void verify() {
        Set<String> problems = new LinkedHashSet<>();
        boolean anyMemberEndpoint = false;
        boolean oidc = principals.mode() == IdentityMode.OIDC;
        for (RequestMappingHandlerMapping mapping : mappings.orderedStream().toList()) {
            for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : mapping.getHandlerMethods().entrySet()) {
                HandlerMethod handler = entry.getValue();
                if (EndpointAccessInterceptor.isFramework(handler.getBeanType())) {
                    continue;
                }
                EndpointAccessInterceptor.Declaration declaration = EndpointAccessInterceptor.resolve(handler);
                String where = handler.getBeanType().getSimpleName() + "#" + handler.getMethod().getName();
                Set<String> patterns = patterns(entry.getKey());
                if (declaration.member() != null && !declaration.conflicting()) {
                    anyMemberEndpoint = true;
                    checkMemberEndpoint(declaration.member(), handler, patterns, where, oidc, problems);
                }
                if (declaration.role() != null && declaration.role().members()) {
                    checkMemberRead(declaration.role(), entry.getKey(), patterns, where, problems);
                }
            }
        }
        if (oidc && anyMemberEndpoint && !principals.hasSubjectKey()) {
            IdentityGuard.requireSubjectKey(environment); // INSECURE_CONFIG: assente o corta
            problems.add("la chiave dello pseudonimo non è disponibile");
        }
        if (!problems.isEmpty()) {
            throw new IllegalStateException("INSECURE_CONFIG: endpoint del membro non sicuri (Q-410, ADR-048): "
                    + String.join("; ", problems));
        }
    }

    private void checkMemberEndpoint(MemberEndpoint member, HandlerMethod handler, Set<String> patterns, String where,
                                     boolean oidc, Set<String> problems) {
        for (String pattern : patterns) {
            if (!pattern.startsWith(PORTAL_PREFIX)) {
                problems.add(where + ": @MemberEndpoint solo sotto " + PORTAL_PREFIX);
            }
        }
        String variable = member.demoPathVariable();
        if (!variable.isBlank()) {
            boolean deprecated = handler.hasMethodAnnotation(Deprecated.class)
                    || handler.getBeanType().isAnnotationPresent(Deprecated.class);
            if (!deprecated) {
                problems.add(where + ": demoPathVariable solo su un handler @Deprecated");
            }
            for (String pattern : patterns) {
                if (!pattern.contains("{" + variable + "}") && !pattern.contains("{" + variable + ":")) {
                    problems.add(where + ": demoPathVariable «" + variable + "» non compare nel percorso");
                }
            }
        }
        if (oidc) {
            List<MemberSubjectLookup> lookups = principals.lookupsFor(handler.getBeanType());
            String module = handler.getBeanType().getPackageName();
            if (lookups.size() != 1) {
                problems.add(where + ": serve esattamente una MemberSubjectLookup per il modulo " + module
                        + " (trovate " + lookups.size() + ")");
            } else if (member.value() == MemberEndpoint.Mode.REGISTRATION && !lookups.get(0).authoritative()) {
                problems.add(where + ": la registrazione richiede la lookup autorevole di member-service");
            }
        }
    }

    private static void checkMemberRead(RequiresRole role, RequestMappingInfo info, Set<String> patterns, String where,
                                        Set<String> problems) {
        if (!Arrays.asList(role.value()).contains(Role.ANALYST)) {
            // L'attore di un membro è ANALYST (Q-556): senza, la lettura risponde 403 a ogni membro.
            problems.add(where + ": @RequiresRole(members = true) elenca ANALYST tra i ruoli");
        }
        Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
        if (methods.size() != 1 || !methods.contains(RequestMethod.GET)) {
            problems.add(where + ": @RequiresRole(members = true) solo su GET");
        }
        for (String pattern : patterns) {
            if (!pattern.startsWith(PORTAL_PREFIX)) {
                problems.add(where + ": @RequiresRole(members = true) solo sotto " + PORTAL_PREFIX);
            } else if (pattern.equals(ME_PREFIX) || pattern.startsWith(ME_PREFIX + "/")) {
                problems.add(where + ": @RequiresRole(members = true) mai sotto " + ME_PREFIX);
            }
        }
    }

    private static Set<String> patterns(RequestMappingInfo info) {
        if (info.getPathPatternsCondition() != null) {
            return info.getPathPatternsCondition().getPatternValues();
        }
        return Set.of();
    }
}
