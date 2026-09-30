package io.loyaltyhub.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.tomcat.util.http.InvalidParameterException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

import java.io.IOException;
import java.util.Enumeration;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Rifiuta con {@code 400} (RFC 9457, come ogni errore del client) una richiesta il cui percorso decodificato, o il cui
 * nome o valore di un parametro, contiene il carattere NUL ({@code U+0000}): Q-532 causa (3), F2-SEC-12, ADR-042.
 * Senza il filtro il byte arriva intatto a Postgres (un {@code ILIKE} con il testo di {@code q}, un {@code WHERE} sul
 * codice di un percorso) e diventa un 500. Gira <em>prima del binding</em>: nessun handler, interceptor o
 * {@code @RequestParam} vede il valore; dopo i filtri di identità (una richiesta non autenticata riceve la sua
 * risposta, non la validazione). Il 400 lo produce {@link GlobalExceptionHandler} tramite il
 * {@link HandlerExceptionResolver} dell'applicazione: stesso formato di ogni altro errore.
 *
 * <p>Il corpo JSON non è del filtro: lo legge Jackson, e {@link NulRejectingModule} ne rifiuta valori e chiavi.
 * Un corpo {@code multipart} non si legge qui (Tomcat scaricherebbe i file in un filtro): di quelle richieste si
 * controllano solo la query e il percorso.
 *
 * <p><strong>Parametri illeggibili.</strong> Leggere la mappa dei parametri può lanciare {@link InvalidParameterException}
 * (parametro senza nome, codifica non valida: Q-532 causa (1)). Tomcat lancia una sola volta per richiesta, e dal secondo
 * {@code getParameter*} in poi restituisce una mappa parziale, senza un {@code memberId} che invece c'era: il filtro non
 * deve inghiottire l'eccezione né lasciare che il resto della catena veda la mappa parziale. La conserva e inoltra alla
 * catena una richiesta che la rilancia a ogni {@code getParameter*}: il comportamento di prima (un handler che legge i
 * parametri, o il controllo del membro, riceve l'eccezione e {@link GlobalExceptionHandler} risponde 400; uno che non li
 * legge resta 200), fail closed.
 */
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class NulRejectingFilter extends OncePerRequestFilter {

    private final Supplier<HandlerExceptionResolver> resolver;

    /**
     * @param resolver il resolver del contesto web (bean {@code handlerExceptionResolver}), letto alla prima richiesta
     *                 rifiutata: il filtro non ne dipende all'avvio
     */
    public NulRejectingFilter(Supplier<HandlerExceptionResolver> resolver) {
        this.resolver = resolver;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        HttpServletRequest forward = request;
        boolean nul;
        try {
            nul = containsNul(request);
        } catch (InvalidParameterException unreadable) {
            nul = false;
            forward = new UnreadableParameters(request, unreadable);
        }
        if (!nul) {
            chain.doFilter(forward, response);
            return;
        }
        LhException rejection = LhException.badRequest(NulCharacters.REQUEST_MESSAGE);
        HandlerExceptionResolver r = resolver.get();
        if (r == null || r.resolveException(request, response, null, rejection) == null) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST);
        }
    }

    private static boolean containsNul(HttpServletRequest request) {
        if (pathContainsNul(request.getRequestURI())) {
            return true;
        }
        String query = request.getQueryString();
        if (query != null && (NulCharacters.in(query) || hasEncodedNul(query))) {
            return true;
        }
        String contentType = request.getContentType();
        if (contentType != null && contentType.regionMatches(true, 0, "multipart/", 0, "multipart/".length())) {
            return false;
        }
        for (Map.Entry<String, String[]> parameter : request.getParameterMap().entrySet()) {
            if (NulCharacters.in(parameter.getKey())) {
                return true;
            }
            for (String value : parameter.getValue()) {
                if (NulCharacters.in(value)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Percorso decodificato: un NUL già presente o {@code %00}, l'unica codifica di un NUL. Un segmento codificato due
     * volte ({@code %2500}) resta il testo «%00», non un NUL, e passa.
     */
    private static boolean pathContainsNul(String uri) {
        return uri != null && (NulCharacters.in(uri) || hasEncodedNul(uri));
    }

    /** {@code %00}: l'unica codifica percentuale di un NUL (un {@code %} seguito da due zeri). */
    private static boolean hasEncodedNul(String raw) {
        int from = 0;
        while ((from = raw.indexOf('%', from)) >= 0) {
            if (raw.startsWith("00", from + 1)) {
                return true;
            }
            from++;
        }
        return false;
    }

    /** La richiesta con i parametri illeggibili: ogni lettura rilancia l'eccezione di Tomcat, mai una mappa parziale. */
    private static final class UnreadableParameters extends HttpServletRequestWrapper {

        private final InvalidParameterException unreadable;

        UnreadableParameters(HttpServletRequest request, InvalidParameterException unreadable) {
            super(request);
            this.unreadable = unreadable;
        }

        @Override
        public String getParameter(String name) {
            throw unreadable;
        }

        @Override
        public String[] getParameterValues(String name) {
            throw unreadable;
        }

        @Override
        public Map<String, String[]> getParameterMap() {
            throw unreadable;
        }

        @Override
        public Enumeration<String> getParameterNames() {
            throw unreadable;
        }
    }
}
