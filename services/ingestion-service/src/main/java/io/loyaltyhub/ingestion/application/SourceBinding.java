package io.loyaltyhub.ingestion.application;

import io.loyaltyhub.common.event.LhSource;
import io.loyaltyhub.common.web.ActorContext;
import io.loyaltyhub.common.web.LhException;
import io.loyaltyhub.common.web.Role;
import tools.jackson.databind.JsonNode;

import static io.loyaltyhub.ingestion.domain.ImportParser.attribute;

/**
 * Legame tra il client autenticato e la fonte dichiarata (Q-492, docs/18 §3.2 e §3.10): il client della fonte
 * {@code <codice>} è {@code src-<codice>} ({@code client_id} ⇔ {@code source} del registro fonti). Quando l'attore è
 * {@link Role#SOURCE}, ogni evento o transazione deve dichiarare la fonte del proprio client; altrimenti
 * {@code 403 SOURCE_MISMATCH} prima di qualunque scrittura o pubblicazione. Gli altri ruoli (in pratica {@code ADMIN},
 * gli unici altri che arrivano all'ingresso) non sono soggetti al controllo: strumenti demo e backoffice inviano per
 * conto di qualunque fonte.
 *
 * <p>Il confronto è esatto sul codice (mai sul suffisso né senza distinguere maiuscole): una fonte con una forma
 * diversa da {@code urn:loyaltyhub:source:<codice>} (o dal codice breve delle transazioni) non coincide, come un client
 * senza prefisso {@code src-}. Il valore dichiarato non si riporta nel messaggio.
 */
public final class SourceBinding {

    static final String DETAIL = "La fonte dichiarata non corrisponde al client autenticato: un client di fonte"
            + " (src-<codice>) può inviare solo per la propria fonte.";

    private SourceBinding() {
    }

    /**
     * Verifica una fonte dichiarata (URN o codice breve) contro l'attore; nessun effetto se non è {@code SOURCE}.
     * Una fonte assente o vuota non è un «altra fonte» ma un errore di forma: la pipeline la respinge prima di scrivere
     * ({@code 400} su evento e transazione, esito {@code INVALID} sull'elemento di un batch), quindi qui non si segnala.
     */
    public static void requireMatch(ActorContext actor, String declaredSource) {
        if (actor.role() != Role.SOURCE || declaredSource == null || declaredSource.isBlank()) {
            return;
        }
        String allowed = actor.sourceCode().orElse(null);
        if (allowed == null || !allowed.equals(codeOf(declaredSource))) {
            throw LhException.sourceMismatch(DETAIL);
        }
    }

    /**
     * Verifica la fonte di ogni elemento di un batch: un solo elemento con una fonte diversa respinge l'intera
     * richiesta, senza elaborare nulla. Un elemento senza {@code source} (o non oggetto) resta un errore di forma
     * dell'elemento ({@code INVALID}, nulla salvato).
     */
    public static void requireMatchAll(ActorContext actor, JsonNode batch) {
        if (actor.role() != Role.SOURCE || batch == null || !batch.isArray()) {
            return;
        }
        for (JsonNode item : batch) {
            requireMatch(actor, item != null && item.isObject() ? attribute(item, "source") : null);
        }
    }

    /** Codice fonte da {@code urn:loyaltyhub:source:<codice>}; senza URN il testo com'è (forma breve delle transazioni). */
    private static String codeOf(String source) {
        return source.startsWith(LhSource.SOURCE_PREFIX) ? source.substring(LhSource.SOURCE_PREFIX.length()) : source;
    }
}
