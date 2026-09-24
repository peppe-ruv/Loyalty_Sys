package io.loyaltyhub.common.event;

/** Header Kafka {@code lh-*} duplicati sull'envelope per filtrare senza deserializzare (docs/05 §2). */
public final class LhHeaders {

    public static final String TYPE = "lh-type";
    public static final String CORRELATION_ID = "lh-correlation-id";
    public static final String CAUSATION_ID = "lh-causation-id";
    public static final String HOP = "lh-hop";
    public static final String ACTOR = "lh-actor";
    /** Codice errore aggiunto dal recoverer quando il messaggio finisce in DLQ. */
    public static final String ERROR_CODE = "lh-error-code";

    // --- Header dei record DLQ (docs/04 §5): chi ha fallito, dove, perché e dopo quanti tentativi. ---
    /** Topic da cui proveniva il messaggio fallito. */
    public static final String ORIGINAL_TOPIC = "lh-original-topic";
    /** Gruppo consumer che non è riuscito a elaborarlo (es. {@code lh-campaign}). */
    public static final String CONSUMER = "lh-consumer";
    /** Classe (nome completo) dell'eccezione. */
    public static final String ERROR_CLASS = "lh-error-class";
    /** Messaggio dell'eccezione (troncato). */
    public static final String ERROR_MESSAGE = "lh-error-message";
    /** Numero di tentativi fatti prima della DLQ (1 per gli errori non ritentabili). */
    public static final String ATTEMPTS = "lh-attempts";
    /** {@code true}/{@code false}: l'errore era ritentabile (additivo rispetto a docs/04, per BO-27). */
    public static final String ERROR_RETRYABLE = "lh-error-retryable";
    /** Stack abbreviato (poche righe) per il dettaglio di BO-27 (additivo rispetto a docs/04). */
    public static final String ERROR_STACK = "lh-error-stack";

    /** Content-type CloudEvents per il valore del record (docs/05 §2). */
    public static final String CONTENT_TYPE_VALUE = "application/cloudevents+json";

    private LhHeaders() {
    }
}
