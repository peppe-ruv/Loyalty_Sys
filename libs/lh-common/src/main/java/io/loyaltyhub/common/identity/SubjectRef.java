package io.loyaltyhub.common.identity;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * Lo pseudonimo {@code subjectRef} del legame account↔membro (Q-552, ADR-032, ADR-048): {@code HMAC-SHA256} esadecimale
 * (64 caratteri minuscoli) dell'emittente e del soggetto del token, con la chiave dedicata {@code LH_SUBJECT_KEY}. È
 * l'unica forma in cui il {@code sub} (un dato personale) esce da member-service: viaggia sul bus in
 * {@code member.registered} e {@code member.updated}, mai il {@code sub}.
 *
 * <p>Input a prefisso di lunghezza, così due coppie diverse non producono mai lo stesso testo:
 * {@code <n>:<iss> <m>:<sub>}, dove {@code n} e {@code m} sono le lunghezze in byte UTF-8 di {@code iss} e {@code sub}
 * (per esempio {@code 3:iss 3:sub}); un solo spazio separa le due parti. L'emittente fa parte dell'input: lo stesso
 * {@code sub} presso due IdP dà due pseudonimi. Cambiare chiave cambia ogni pseudonimo (la rotazione ricalcola e
 * ripubblica, fuori da M8.10f).
 */
public final class SubjectRef {

    /** Forma di un pseudonimo: 64 caratteri esadecimali minuscoli. */
    public static final Pattern FORMAT = Pattern.compile("^[0-9a-f]{64}$");

    /** Lunghezza minima della chiave, in byte (dopo la decodifica base64). */
    public static final int MIN_KEY_BYTES = 32;

    private static final String ALGORITHM = "HmacSHA256";

    private SubjectRef() {
    }

    /** Lo pseudonimo di {@code (iss, sub)} con {@code key}. La chiave non si conserva né si logga. */
    public static String of(byte[] key, String iss, String sub) {
        if (key == null || key.length < MIN_KEY_BYTES) {
            throw new IllegalArgumentException("Chiave del soggetto assente o più corta di " + MIN_KEY_BYTES + " byte");
        }
        if (iss == null || iss.isBlank() || sub == null || sub.isBlank()) {
            throw new IllegalArgumentException("Emittente e soggetto sono obbligatori");
        }
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(key, ALGORITHM));
            return HexFormat.of().formatHex(mac.doFinal(input(iss, sub)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 non disponibile", e);
        }
    }

    /** Vero se {@code value} ha la forma di uno pseudonimo. */
    public static boolean isValid(String value) {
        return value != null && FORMAT.matcher(value).matches();
    }

    /** Il testo su cui si calcola l'HMAC (visibile ai test come vettore fisso). */
    static byte[] input(String iss, String sub) {
        byte[] i = iss.getBytes(StandardCharsets.UTF_8);
        byte[] s = sub.getBytes(StandardCharsets.UTF_8);
        String head = i.length + ":";
        String mid = " " + s.length + ":";
        byte[] out = new byte[head.length() + i.length + mid.length() + s.length];
        int pos = 0;
        for (byte[] part : new byte[][] {head.getBytes(StandardCharsets.US_ASCII), i,
                mid.getBytes(StandardCharsets.US_ASCII), s}) {
            System.arraycopy(part, 0, out, pos, part.length);
            pos += part.length;
        }
        return out;
    }
}
