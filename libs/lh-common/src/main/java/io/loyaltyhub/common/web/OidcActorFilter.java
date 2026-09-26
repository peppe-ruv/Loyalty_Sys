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
 * Un token di solo membro ({@code MEMBER}) vale soltanto su {@code /v1/portal/**}; altrove ⇒ 403.
 * Restano liberi solo i probe {@code /actuator/health} e {@code /actuator/info}.
 */
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class OidcActorFilter extends OncePerRequestFilter {

    /** Attributo di richiesta con il {@code sub} di un membro autenticato (usato da {@code MemberPrincipal}, M8.10). */
    public static final String MEMBER_SUBJECT_ATTRIBUTE = "io.loyaltyhub.member.sub";

    static final String MEMBER_ROLE = "MEMBER";

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
        ActorContext actor = ActorContext.fromToken(roles, username(jwt));
        boolean memberOnly = roles.contains(MEMBER_ROLE) && roles.stream().noneMatch(OidcActorFilter::isOperatorRole);
        if (memberOnly) {
            if (!path.startsWith("/v1/portal/")) {
                reject(response, 403, "forbidden-role", "FORBIDDEN_ROLE", "Il token di un membro vale solo per il portale.",
                        request);
                return;
            }
            request.setAttribute(MEMBER_SUBJECT_ATTRIBUTE, jwt.getSubject());
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
            if (r.name().equals(name)) {
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
