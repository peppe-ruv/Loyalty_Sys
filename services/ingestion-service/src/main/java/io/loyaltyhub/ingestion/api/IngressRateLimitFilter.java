package io.loyaltyhub.ingestion.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Limite di frequenza degli ingressi esterni (docs/11 §11): al più {@code loyaltyhub.ingestion.rate-limit-per-minute}
 * (default 60) richieste {@code POST /v1/events} e {@code POST /v1/transactions} per indirizzo IP negli ultimi 60
 * secondi, per non saturare il Kafka gratuito. Oltre il limite: {@code 429} {@code application/problem+json} con
 * {@code code} {@code RATE_LIMITED} e {@code Retry-After}.
 *
 * <p>SPEC-GAP: Q-339 — l'indirizzo è il primo di {@code X-Forwarded-For} (la demo sta dietro il proxy di Render e di
 * Vercel), altrimenti quello della connessione; le chiamate dalla stessa macchina (loopback: simulatore e scenari
 * interni, riprocessa DLQ di insight nell'hub, test e prova di fumo in locale) sono esenti. {@code 0} spegne il limite.
 */
@Component
public class IngressRateLimitFilter extends OncePerRequestFilter {

    private static final long WINDOW_MS = 60_000;
    private static final int MAX_TRACKED = 10_000;

    private final int perMinute;
    private final Clock clock;
    private final Map<String, Deque<Long>> hits = new ConcurrentHashMap<>();

    public IngressRateLimitFilter(@Value("${loyaltyhub.ingestion.rate-limit-per-minute:60}") int perMinute, Clock clock) {
        this.perMinute = perMinute;
        this.clock = clock;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return perMinute <= 0 || !"POST".equals(request.getMethod())
                || !("/v1/events".equals(uri) || "/v1/transactions".equals(uri));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String ip = clientIp(request);
        if (isLoopback(ip)) {
            chain.doFilter(request, response);
            return;
        }
        long now = clock.millis();
        long retryAfterMs = admit(ip, now);
        if (retryAfterMs < 0) {
            chain.doFilter(request, response);
            return;
        }
        response.setStatus(429);
        response.setHeader("Retry-After", String.valueOf(Math.max(1, (retryAfterMs + 999) / 1000)));
        response.setContentType("application/problem+json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("""
                {"type":"urn:loyaltyhub:problem:rate-limited","title":"Troppe richieste","status":429,\
                "detail":"Limite di %d eventi al minuto superato per questo indirizzo: riprova tra poco",\
                "code":"RATE_LIMITED","instance":"%s"}""".formatted(perMinute, request.getRequestURI()));
    }

    /** Registra la richiesta se c'è posto nella finestra; altrimenti i millisecondi da attendere. */
    private long admit(String ip, long now) {
        if (hits.size() > MAX_TRACKED) {
            hits.entrySet().removeIf(e -> {
                synchronized (e.getValue()) {
                    Long last = e.getValue().peekLast();
                    return last == null || now - last >= WINDOW_MS;
                }
            });
        }
        Deque<Long> window = hits.computeIfAbsent(ip, k -> new ArrayDeque<>());
        synchronized (window) {
            while (!window.isEmpty() && now - window.peekFirst() >= WINDOW_MS) {
                window.pollFirst();
            }
            if (window.size() >= perMinute) {
                return WINDOW_MS - (now - window.peekFirst());
            }
            window.addLast(now);
            return -1;
        }
    }

    static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            String first = forwarded.split(",")[0].trim();
            if (!first.isEmpty()) {
                return first;
            }
        }
        return request.getRemoteAddr();
    }

    static boolean isLoopback(String ip) {
        if (ip == null) {
            return true;
        }
        String s = ip.startsWith("[") && ip.endsWith("]") ? ip.substring(1, ip.length() - 1) : ip;
        return s.startsWith("127.") || s.equals("::1") || s.equals("0:0:0:0:0:0:0:1") || s.equalsIgnoreCase("localhost");
    }
}
