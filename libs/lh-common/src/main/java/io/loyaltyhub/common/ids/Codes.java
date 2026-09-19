package io.loyaltyhub.common.ids;

import java.security.SecureRandom;

/**
 * Generatore di codici leggibili sull'alfabeto {@code A-Z2-9} (niente 0/1/O/I, meno ambiguità):
 * codici coupon, codici oggetto casuali. Con seme fisso per i dati demo (docs/10 §1.3).
 */
public final class Codes {

    /** Alfabeto senza caratteri ambigui (docs/06 §1, pattern coupon). */
    public static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    private static final SecureRandom RANDOM = new SecureRandom();

    private Codes() {
    }

    /** Codice casuale di {@code length} caratteri. */
    public static String random(int length) {
        return random(length, RANDOM);
    }

    /** Codice deterministico da un generatore seminato (reset demo riproducibile). */
    public static String random(int length, java.util.Random random) {
        if (length <= 0) {
            throw new IllegalArgumentException("length deve essere > 0");
        }
        char[] out = new char[length];
        for (int i = 0; i < length; i++) {
            out[i] = ALPHABET.charAt(random.nextInt(ALPHABET.length()));
        }
        return new String(out);
    }

    /** Codice a gruppi separati da trattino, es. {@code ABCD-2345-WXYZ}. */
    public static String grouped(int groups, int groupSize) {
        StringBuilder sb = new StringBuilder();
        for (int g = 0; g < groups; g++) {
            if (g > 0) {
                sb.append('-');
            }
            sb.append(random(groupSize));
        }
        return sb.toString();
    }
}
