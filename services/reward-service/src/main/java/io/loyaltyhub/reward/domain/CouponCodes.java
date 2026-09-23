package io.loyaltyhub.reward.domain;

import java.util.SplittableRandom;
import java.util.regex.Pattern;

/**
 * Codici coupon {@code PREFIX-XXXX-XXXX} (docs/servizi/reward-service.md §3). Alfabeto senza caratteri ambigui
 * (niente 0/O, 1/I/L) perché il codice si legge alla cassa. La sequenza dipende solo dal seme: stesso seme, stessi
 * codici (reset demo ripetibile, docs/10 §1).
 */
public final class CouponCodes {

    public static final int MAX_GENERATE = 5000;
    private static final char[] ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789".toCharArray();
    private static final Pattern PREFIX = Pattern.compile("^[A-Z0-9]{2,10}$");
    private static final Pattern CODE = Pattern.compile("^[A-Z0-9][A-Z0-9-]{3,39}$");

    private final SplittableRandom random;
    private final String prefix;

    public CouponCodes(String prefix, long seed) {
        this.prefix = prefix;
        this.random = new SplittableRandom(seed);
    }

    public String next() {
        StringBuilder sb = new StringBuilder(prefix.length() + 10).append(prefix).append('-');
        for (int i = 0; i < 8; i++) {
            if (i == 4) {
                sb.append('-');
            }
            sb.append(ALPHABET[random.nextInt(ALPHABET.length)]);
        }
        return sb.toString();
    }

    /**
     * Seme di una generazione: dipende dal seme del pool e da quanti codici il pool ha già, così due generazioni
     * successive non ripartono dalla stessa sequenza ma restano riproducibili.
     */
    public static long batchSeed(long poolSeed, long existing) {
        return poolSeed * 1_000_003L + existing;
    }

    public static boolean validPrefix(String prefix) {
        return prefix != null && PREFIX.matcher(prefix).matches();
    }

    /** Normalizza un codice importato (maiuscolo, senza spazi) o {@code null} se non è un codice valido. */
    public static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String c = raw.trim().toUpperCase().replace(" ", "");
        return CODE.matcher(c).matches() ? c : null;
    }
}
