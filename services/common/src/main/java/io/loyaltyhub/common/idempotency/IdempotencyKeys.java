package io.loyaltyhub.common.idempotency;

import java.util.regex.Pattern;

/**
 * Convenzione delle chiavi di idempotenza (RI-01): {@code <fonte>:<riferimento>:<evento>}, es. {@code sap:INV-2026-000123:PAID}.
 * Unica per fonte, conservata 24 mesi per il controllo duplicati.
 */
public final class IdempotencyKeys {
    private static final Pattern VALID = Pattern.compile("^[a-z0-9-]{2,32}:[A-Za-z0-9._-]{1,128}:[A-Z0-9_]{1,64}$");
    private IdempotencyKeys() {}

    public static boolean isValid(String key) {
        return key != null && VALID.matcher(key).matches();
    }

    public static String source(String key) {
        return key.substring(0, key.indexOf(':'));
    }
}
