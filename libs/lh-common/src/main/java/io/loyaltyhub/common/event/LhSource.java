package io.loyaltyhub.common.event;

/** Costruttori degli URN di {@code source} e {@code dataschema} (docs/05 §2). */
public final class LhSource {

    public static final String SOURCE_PREFIX = "urn:loyaltyhub:source:";
    public static final String SERVICE_PREFIX = "urn:loyaltyhub:service:";
    public static final String SCHEMA_PREFIX = "urn:loyaltyhub:schema:";

    /** Fonte interna del ponte fatti→azioni (docs/05 §7). */
    public static final String INTERNAL = SOURCE_PREFIX + "internal";

    private LhSource() {
    }

    /** {@code urn:loyaltyhub:source:<codice fonte>} per le azioni. */
    public static String source(String sourceCode) {
        return SOURCE_PREFIX + sourceCode;
    }

    /** {@code urn:loyaltyhub:service:<servizio>} per effetti, fatti, audit. */
    public static String service(String service) {
        return SERVICE_PREFIX + service;
    }

    /** {@code urn:loyaltyhub:schema:<famiglia>.<nome>:<versione>}. */
    public static String schema(String familyDotName, int version) {
        return SCHEMA_PREFIX + familyDotName + ":" + version;
    }

    /** {@code dataschema} derivato dal {@code type} completo e dalla versione (versione 1 di default). */
    public static String schemaForType(String type, int version) {
        String name = type != null && type.startsWith(LhFamily.TYPE_PREFIX)
                ? type.substring(LhFamily.TYPE_PREFIX.length())
                : type;
        return schema(name, version);
    }
}
