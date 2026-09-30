package io.loyaltyhub.common.demo;

import io.loyaltyhub.common.ids.Codes;

import java.util.Random;

/**
 * Generatore di codici con seme, SOLO per dati demo e test (docs/10 §1.3: reset riproducibile, stessi codici a ogni
 * reset). Non usare mai per codici che devono restare imprevedibili: per quelli esiste {@link Codes#random(int)}, che
 * si appoggia a {@code SecureRandom}. Stesso alfabeto di {@link Codes#ALPHABET}.
 */
public final class SeededCodes {

    private SeededCodes() {
    }

    /** Codice deterministico di {@code length} caratteri da un generatore seminato dal chiamante. */
    public static String random(int length, Random random) {
        if (length <= 0) {
            throw new IllegalArgumentException("length deve essere > 0");
        }
        char[] out = new char[length];
        for (int i = 0; i < length; i++) {
            out[i] = Codes.ALPHABET.charAt(random.nextInt(Codes.ALPHABET.length()));
        }
        return new String(out);
    }
}
