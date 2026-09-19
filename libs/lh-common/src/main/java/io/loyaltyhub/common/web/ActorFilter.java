package io.loyaltyhub.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Popola {@link ActorHolder} dall'header {@code X-LH-Actor} e alimenta l'MDC dei log (docs/06 §3, §8):
 * {@code service}, {@code actor}. Pulisce sempre a fine richiesta.
 */
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class ActorFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-LH-Actor";

    private final String service;

    public ActorFilter(String service) {
        this.service = service;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        ActorContext actor = ActorContext.parse(request.getHeader(HEADER));
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
}
