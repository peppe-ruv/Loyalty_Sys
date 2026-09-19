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

    /** Content-type CloudEvents per il valore del record (docs/05 §2). */
    public static final String CONTENT_TYPE_VALUE = "application/cloudevents+json";

    private LhHeaders() {
    }
}
