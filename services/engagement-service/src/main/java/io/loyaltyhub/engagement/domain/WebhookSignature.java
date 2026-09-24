package io.loyaltyhub.engagement.domain;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Firma dei webhook (docs/servizi/engagement-service.md §5; F-WBH-01): header {@code X-LH-Signature} =
 * {@code sha256=<HMAC-SHA256(secret, corpo)>} in esadecimale minuscolo, calcolato sui byte UTF-8 del corpo esatto che
 * viene inviato. Lo stesso calcolo è in {@code deploy/webhook-receiver/verify.mjs}.
 */
public final class WebhookSignature {

    public static final String PREFIX = "sha256=";
    private static final String ALGORITHM = "HmacSHA256";
    private static final SecureRandom RANDOM = new SecureRandom();

    private WebhookSignature() {
    }

    public static String sign(String secret, String body) {
        return sign(secret, body.getBytes(StandardCharsets.UTF_8));
    }

    public static String sign(String secret, byte[] body) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            return PREFIX + HexFormat.of().formatHex(mac.doFinal(body));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 non disponibile", e);
        }
    }

    /** Verifica a tempo costante (lato ricevente; usata nei test). */
    public static boolean verify(String secret, byte[] body, String signature) {
        if (signature == null) {
            return false;
        }
        return MessageDigest.isEqual(sign(secret, body).getBytes(StandardCharsets.US_ASCII),
                signature.trim().getBytes(StandardCharsets.US_ASCII));
    }

    /** Segreto nuovo: 32 byte casuali in base64url, prefisso {@code whsec_}. */
    public static String newSecret() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return "whsec_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
