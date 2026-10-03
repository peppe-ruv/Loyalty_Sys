package io.loyaltyhub.common.web;

import io.loyaltyhub.common.ids.Ulid;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.regex.Pattern;

/**
 * Codice dell'errore (ADR-052 decisione 3, Q-684, Q-716, F2-QA-06): per ogni richiesta HTTP legge {@code X-Correlation-Id}
 * (la stessa forma ammessa dal BFF, {@code [A-Za-z0-9-]{1,64}}, così nei log non entra testo arbitrario), altrimenti ne
 * genera uno (ULID), lo mette nell'MDC con la chiave {@code correlationId} per tutta la richiesta (la stessa dei
 * consumer, {@code EventRouter}), lo restituisce nell'intestazione di risposta e pulisce l'MDC alla fine. Il codice non
 * contiene dati personali. {@link GlobalExceptionHandler} lo scrive nella proprietà {@code correlationId} di ogni problema.
 * Gira prima dei filtri di identità, così anche un rifiuto di autenticazione o del filtro NUL porta il codice.
 */
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Correlation-Id";
    /** Chiave dell'MDC, la stessa dei consumer ({@code EventRouter.MDC_CORRELATION_ID}). */
    public static final String MDC_KEY = "correlationId";

    private static final Pattern VALID = Pattern.compile("[A-Za-z0-9-]{1,64}");

    /** Intestazione se ha una forma sicura, altrimenti un nuovo ULID. */
    static String resolve(String incoming) {
        return incoming != null && VALID.matcher(incoming).matches() ? incoming : Ulid.next();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String id = resolve(request.getHeader(HEADER));
        MDC.put(MDC_KEY, id);
        response.setHeader(HEADER, id);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}
