package io.loyaltyhub.common.ids;

import java.security.SecureRandom;
import java.time.Clock;

/**
 * ULID (26 caratteri, Crockford Base32): 48 bit di timestamp ms + 80 bit di casualità,
 * monotòno crescente entro lo stesso millisecondo (docs/05 §2, Q-27: implementazione minima a mano).
 * Ordinabile lessicograficamente per tempo.
 */
public final class Ulid {

    private static final char[] ENCODING = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private static final Object LOCK = new Object();
    private static long lastTime = -1L;
    private static final byte[] lastRandom = new byte[10];

    private Ulid() {
    }

    /** Genera un ULID sull'orologio di sistema. */
    public static String next() {
        return next(System.currentTimeMillis());
    }

    /** Genera un ULID sull'istante dell'orologio indicato (utile nei test con {@link Clock} fisso). */
    public static String next(Clock clock) {
        return next(clock.millis());
    }

    static String next(long timestamp) {
        synchronized (LOCK) {
            byte[] randomness;
            if (timestamp == lastTime) {
                // Stesso ms: incremento la parte casuale precedente per garantire monotonicità.
                increment(lastRandom);
                randomness = lastRandom;
            } else {
                lastTime = timestamp;
                RANDOM.nextBytes(lastRandom);
                randomness = lastRandom;
            }
            return encode(timestamp, randomness.clone());
        }
    }

    private static void increment(byte[] r) {
        for (int i = r.length - 1; i >= 0; i--) {
            if ((r[i] & 0xFF) == 0xFF) {
                r[i] = 0;
            } else {
                r[i]++;
                return;
            }
        }
    }

    private static String encode(long time, byte[] rnd) {
        char[] out = new char[26];
        // 48 bit di tempo → 10 caratteri.
        out[0] = ENCODING[(int) ((time >>> 45) & 0x1F)];
        out[1] = ENCODING[(int) ((time >>> 40) & 0x1F)];
        out[2] = ENCODING[(int) ((time >>> 35) & 0x1F)];
        out[3] = ENCODING[(int) ((time >>> 30) & 0x1F)];
        out[4] = ENCODING[(int) ((time >>> 25) & 0x1F)];
        out[5] = ENCODING[(int) ((time >>> 20) & 0x1F)];
        out[6] = ENCODING[(int) ((time >>> 15) & 0x1F)];
        out[7] = ENCODING[(int) ((time >>> 10) & 0x1F)];
        out[8] = ENCODING[(int) ((time >>> 5) & 0x1F)];
        out[9] = ENCODING[(int) (time & 0x1F)];
        // 80 bit di casualità → 16 caratteri, letti come flusso di 5 bit.
        long hi = ((long) (rnd[0] & 0xFF) << 32) | ((long) (rnd[1] & 0xFF) << 24)
                | ((rnd[2] & 0xFF) << 16) | ((rnd[3] & 0xFF) << 8) | (rnd[4] & 0xFF);
        long lo = ((long) (rnd[5] & 0xFF) << 32) | ((long) (rnd[6] & 0xFF) << 24)
                | ((rnd[7] & 0xFF) << 16) | ((rnd[8] & 0xFF) << 8) | (rnd[9] & 0xFF);
        for (int i = 0; i < 8; i++) {
            out[10 + i] = ENCODING[(int) ((hi >>> (35 - i * 5)) & 0x1F)];
        }
        for (int i = 0; i < 8; i++) {
            out[18 + i] = ENCODING[(int) ((lo >>> (35 - i * 5)) & 0x1F)];
        }
        return new String(out);
    }
}
