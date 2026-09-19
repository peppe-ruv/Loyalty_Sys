package io.loyaltyhub.common.event;

/** Famiglie di evento (docs/05 §2): il {@code type} è {@code io.loyaltyhub.<famiglia>.<nome>}. */
public enum LhFamily {
    ACTION("action"),
    EFFECT("effect"),
    FACT("fact"),
    AUDIT("audit");

    public static final String TYPE_PREFIX = "io.loyaltyhub.";

    private final String code;

    LhFamily(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    /** Prefisso completo dei {@code type} della famiglia, es. {@code io.loyaltyhub.action.} */
    public String typePrefix() {
        return TYPE_PREFIX + code + ".";
    }

    /** Famiglia dedotta da un {@code type} completo; {@code null} se non riconosciuta. */
    public static LhFamily of(String type) {
        if (type == null || !type.startsWith(TYPE_PREFIX)) {
            return null;
        }
        String rest = type.substring(TYPE_PREFIX.length());
        int dot = rest.indexOf('.');
        String fam = dot < 0 ? rest : rest.substring(0, dot);
        for (LhFamily f : values()) {
            if (f.code.equals(fam)) {
                return f;
            }
        }
        return null;
    }
}
