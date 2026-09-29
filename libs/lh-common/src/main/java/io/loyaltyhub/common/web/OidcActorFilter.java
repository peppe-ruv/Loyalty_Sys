package io.loyaltyhub.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Attore dal token nel profilo {@code enterprise} (ADR-027, docs/18 §3.2, CLAUDE.md regola 6-bis): al posto di
 * {@link ActorFilter}. Ogni richiesta porta {@code Authorization: Bearer}; il token è verificato da {@link JwtDecoder}
 * (firma dal JWKS dell'IdP, {@code iss}, {@code aud=hub}, scadenza) e alimenta {@link ActorHolder} e l'MDC.
 * L'header {@code X-LH-Actor} è ignorato. Token assente o non valido ⇒ 401 RFC 9457 senza dettagli sul motivo.
 * Un token di solo membro ({@code MEMBER}) vale soltanto su {@code /v1/portal/**}; altrove ⇒ 403. Il suo attore è
 * {@code member:-} ({@link ActorContext#member}, Q-556): mai il {@code preferred_username} né l'e-mail (regola 20); il
 * membro lo risolve {@link EndpointAccessInterceptor} per gli handler {@link MemberEndpoint}, e un token di membro
 * raggiunge solo quelli o le letture {@code @RequiresRole(members = true)} (Q-410, ADR-048).
 * Un token di sola fonte ({@code SOURCE}, Q-492) vale soltanto sull'ingresso di ingestion ({@link #SOURCE_INGRESS_PATHS});
 * altrove, compresi {@code /actuator/metrics}, {@code /actuator/prometheus} e {@code /v3/api-docs}, ⇒ 403.
 * Restano liberi solo i probe {@code /actuator/health} e {@code /actuator/info}.
 */
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class OidcActorFilter extends OncePerRequestFilter {

    /** Attributo di richiesta con il {@code sub} di un membro autenticato (usato da {@code MemberPrincipal}, M8.10). */
    public static final String MEMBER_SUBJECT_ATTRIBUTE = "io.loyaltyhub.member.sub";

    /**
     * Attributo di richiesta con emittente e soggetto del token di un membro ({@link MemberTokenClaims}, mascherato nei
     * log). Solo per un token di solo membro non {@code SOURCE}; lo legge {@link MemberPrincipals}. Assente per un
     * operatore, per un token misto e per una fonte: chi non è solo un membro non agisce mai come membro (Q-554).
     */
    public static final String MEMBER_TOKEN_ATTRIBUTE = "io.loyaltyhub.member.token";

    static final String MEMBER_ROLE = "MEMBER";

    /** Unici percorsi raggiungibili con un token di sola fonte (ingresso eventi e transazioni, docs/06 §3.3). */
    static final java.util.Set<String> SOURCE_INGRESS_PATHS =
            java.util.Set.of("/v1/events", "/v1/events/batch", "/v1/transactions");

    private final String service;
    private final JwtDecoder decoder;
    private final String rolesClaim;

    public OidcActorFilter(String service, JwtDecoder decoder, String rolesClaim) {
        this.service = service;
        this.decoder = decoder;
        this.rolesClaim = rolesClaim;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (isProbe(path)) {
            run(ActorContext.ANONYMOUS, request, response, chain);
            return;
        }
        String authorization = request.getHeader("Authorization");
        if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            reject(response, 401, "unauthorized", "UNAUTHORIZED", "Serve un access token valido.", request);
            return;
        }
        Jwt jwt;
        try {
            jwt = decoder.decode(authorization.substring(7).trim());
        } catch (JwtException e) {
            // Nessun dettaglio al chiamante (firma, emittente, audience o scadenza): lo stesso 401 per tutti i casi.
            reject(response, 401, "unauthorized", "UNAUTHORIZED", "Serve un access token valido.", request);
            return;
        }
        List<String> roles = jwt.hasClaim(rolesClaim) ? jwt.getClaimAsStringList(rolesClaim) : List.of();
        ActorContext actor = ActorContext.fromToken(roles, username(jwt), clientId(jwt));
        boolean memberOnly = roles.contains(MEMBER_ROLE) && roles.stream().noneMatch(OidcActorFilter::isOperatorRole);
        if (memberOnly) {
            if (!path.startsWith("/v1/portal/")) {
                reject(response, 403, "forbidden-role", "FORBIDDEN_ROLE", "Il token di un membro vale solo per il portale.",
                        request);
                return;
            }
            request.setAttribute(MEMBER_SUBJECT_ATTRIBUTE, jwt.getSubject());
            if (actor.role() != Role.SOURCE) {
                String issuer = jwt.getClaimAsString("iss");
                String subject = jwt.getSubject();
                if (issuer == null || issuer.isBlank() || subject == null || subject.isBlank()) {
                    // Un token di membro senza emittente o soggetto non identifica nessuno: non è un token valido.
                    reject(response, 401, "unauthorized", "UNAUTHORIZED", "Serve un access token valido.", request);
                    return;
                }
                request.setAttribute(MEMBER_TOKEN_ATTRIBUTE, new MemberTokenClaims(issuer, subject));
                // Q-556: l'attore di un membro è member:<id>; finché non è risolto member:-, mai il preferred_username.
                actor = ActorContext.member(null);
            }
        }
        if (actor.role() == Role.SOURCE && !SOURCE_INGRESS_PATHS.contains(path)) {
            reject(response, 403, "forbidden-role", "FORBIDDEN_ROLE", "Il token di una fonte vale solo per l'ingresso.",
                    request);
            return;
        }
        run(actor, request, response, chain);
    }

    private void run(ActorContext actor, HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        ActorHolder.set(actor);
        MDC.put("service", service);
        MDC.put("actor", actor.asActorString());
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove("service");
            MDC.remove("actor");
            ActorHolder.clear();
        }
    }

    private static boolean isProbe(String path) {
        return path.equals("/actuator/health") || path.startsWith("/actuator/health/") || path.equals("/actuator/info");
    }

    private static boolean isOperatorRole(String name) {
        for (Role r : Role.values()) {
            // SOURCE non è un ruolo operatore (Q-492): un token MEMBER+SOURCE resta di solo membro.
            if (r != Role.SOURCE && r.name().equals(name)) {
                return true;
            }
        }
        return false;
    }

    /** Nome leggibile per audit e log: {@code preferred_username}, poi il client ({@code azp}), poi {@code sub}. */
    private static String username(Jwt jwt) {
        for (String claim : List.of("preferred_username", "azp", "client_id")) {
            String value = jwt.getClaimAsString(claim);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return jwt.getSubject();
    }

    /** Client del token: {@code azp}, poi {@code client_id} (nome dell'attore {@code SOURCE}, Q-492). */
    private static String clientId(Jwt jwt) {
        for (String claim : List.of("azp", "client_id")) {
            String value = jwt.getClaimAsString(claim);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static void reject(HttpServletResponse response, int status, String type, String code, String detail,
                               HttpServletRequest request) throws IOException {
        response.setStatus(status);
        if (status == 401) {
            response.setHeader("WWW-Authenticate", "Bearer");
        }
        response.setContentType("application/problem+json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        String title = status == 401 ? "Non autenticato" : "Operazione non consentita";
        response.getWriter().write("{\"type\":\"" + GlobalExceptionHandler.PROBLEM_TYPE_PREFIX + type + "\",\"title\":\""
                + title + "\",\"status\":" + status + ",\"detail\":\"" + detail + "\",\"instance\":\""
                + json(request.getRequestURI()) + "\",\"code\":\"" + code + "\"}");
    }

    private static String json(String text) {
        StringBuilder out = new StringBuilder();
        for (char c : text.toCharArray()) {
            if (c == '"' || c == '\\') {
                out.append('\\').append(c);
            } else if (c < 0x20) {
                out.append(String.format("\\u%04x", (int) c));
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }
}
