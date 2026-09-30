package io.loyaltyhub.common.web;

import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * Dove {@link NulRejectingModule} rifiuta un NUL: solo durante la lettura del corpo di una richiesta HTTP gestita da un
 * handler MVC del prodotto, e non se l'handler è {@link NulTolerantBody}. Lo stato è un attributo della richiesta, acceso
 * e spento da {@link NulBodyScopeAdvice} attorno alla conversione del corpo ({@code @RequestBody}, {@code @RequestPart},
 * {@code HttpEntity}).
 *
 * <p>Ogni altra lettura di JSON fa come prima, anche nel thread di una richiesta: il parsing che un handler fa da sé di un
 * testo ricevuto (per esempio le righe di un file d'import, caricato come {@code multipart} e letto da
 * {@code ImportParser}), i consumer Kafka, il caricamento dei seed, i worker dei lavori e i test hanno le loro regole sui
 * NUL (la riga di un file d'import respinta come {@code INVALID}, Q-371) e non passano da un {@code 400}.
 */
final class NulScope {

    /** Attributo di richiesta: {@code TRUE} solo mentre si legge il corpo di un handler che non è {@link NulTolerantBody}. */
    static final String READING_BODY = NulScope.class.getName() + ".READING_BODY";

    private NulScope() {
    }

    static boolean rejecting() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        return attributes != null
                && Boolean.TRUE.equals(attributes.getAttribute(READING_BODY, RequestAttributes.SCOPE_REQUEST));
    }

    /** Accende il rifiuto per la lettura del corpo che sta per cominciare (no-op fuori da una richiesta). */
    static void enter() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes != null) {
            attributes.setAttribute(READING_BODY, Boolean.TRUE, RequestAttributes.SCOPE_REQUEST);
        }
    }

    /** Lo spegne a lettura finita. */
    static void exit() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes != null) {
            attributes.removeAttribute(READING_BODY, RequestAttributes.SCOPE_REQUEST);
        }
    }
}
