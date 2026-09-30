package io.loyaltyhub.testsupport;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * Chiavi di prova casuali per lo pseudonimo {@code subjectRef} ({@code LH_SUBJECT_KEY}, ADR-048, Q-552). I test non
 * dichiarano mai chiavi letterali (regola 20, gitleaks): ne generano una nuova a ogni esecuzione. Un valore casuale non è
 * una costante di compilazione: si registra con {@code @DynamicPropertySource}, non in {@code @SpringBootTest(properties)}.
 */
public final class TestSubjectKeys {

    /** Lunghezza della chiave: pari a {@code SubjectRef.MIN_KEY_BYTES} di lh-common (che questo modulo non vede). */
    private static final int KEY_BYTES = 32;
    private static final SecureRandom RANDOM = new SecureRandom();

    private TestSubjectKeys() {
    }

    /** Una chiave nuova di 32 byte da un {@link SecureRandom}. */
    public static byte[] random() {
        byte[] key = new byte[KEY_BYTES];
        RANDOM.nextBytes(key);
        return key;
    }

    /** Codifica Base64 standard della chiave. */
    public static String base64(byte[] key) {
        return Base64.getEncoder().encodeToString(key);
    }

    /** Una chiave nuova di 32 byte, già in Base64. */
    public static String randomBase64() {
        return base64(random());
    }
}
