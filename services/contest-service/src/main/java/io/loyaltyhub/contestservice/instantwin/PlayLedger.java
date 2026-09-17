package io.loyaltyhub.contestservice.instantwin;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Registro giocate append-only con hash concatenato (RF-33): ogni riga contiene l'hash della precedente,
 * quindi una modifica a posteriori invalida tutte le righe successive. L'export per la verbalizzazione (RF-37)
 * include gli hash e permette la verifica indipendente.
 */
public final class PlayLedger {
    private PlayLedger() {}

    public static String hash(String previousHash, String playId, String memberId, long serverTimestampMillis, String outcome, String matchedInstant) {
        String line = String.join("|", previousHash == null ? "GENESIS" : previousHash, playId, memberId,
                Long.toString(serverTimestampMillis), outcome, matchedInstant == null ? "-" : matchedInstant);
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(line.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
