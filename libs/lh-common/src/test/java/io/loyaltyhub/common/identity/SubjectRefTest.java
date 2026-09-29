package io.loyaltyhub.common.identity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** {@link SubjectRef}: lo pseudonimo del legame account↔membro (Q-552, ADR-032, ADR-048). */
class SubjectRefTest {

    /** Chiave di prova di 32 byte (0x00…0x1f): non è un segreto. */
    private static final byte[] KEY = key(32, 0);
    private static final String ISS = "https://idp.example.test/realms/loyaltyhub";

    @Test
    @DisplayName("vettori fissi: HMAC-SHA256 esadecimale (64 caratteri) di «<n>:<iss> <m>:<sub>»")
    void fixedVectors() {
        // Vettori calcolati fuori dal codice (Python: hmac.new(bytes(range(32)), b"3:iss 3:sub", "sha256").hexdigest()).
        assertThat(SubjectRef.of(KEY, "iss", "sub")).isEqualTo("9b9f55757542b664d79781e9e8703e9d0f8dd6e6f34448a6eb756d446cda98d2");
        assertThat(SubjectRef.of(KEY, ISS, "a1b2c3d4-0000-4000-8000-000000000001"))
                .isEqualTo("2701b210ae9aeccc249e3144bf377a220d2ce48c709bff171a577f87a91014fc");
    }

    @Test
    @DisplayName("l'input ha il prefisso di lunghezza in byte UTF-8 e un solo spazio")
    void inputIsLengthPrefixed() {
        assertThat(new String(SubjectRef.input("iss", "sub"), StandardCharsets.UTF_8)).isEqualTo("3:iss 3:sub");
        // Lunghezza in byte, non in caratteri: «é» pesa 2 byte.
        assertThat(new String(SubjectRef.input("é", "sub"), StandardCharsets.UTF_8)).isEqualTo("2:é 3:sub");
    }

    @Test
    @DisplayName("il prefisso di lunghezza rende diverse le coppie che concatenate coinciderebbero")
    void lengthPrefixSeparatesPairs() {
        assertThat(SubjectRef.of(KEY, "ab", "c")).isNotEqualTo(SubjectRef.of(KEY, "a", "bc"));
        assertThat(SubjectRef.of(KEY, "a b", "c")).isNotEqualTo(SubjectRef.of(KEY, "a", "b c"));
        assertThat(SubjectRef.of(KEY, "1:a", "b")).isNotEqualTo(SubjectRef.of(KEY, "1", "a:b"));
    }

    @Test
    @DisplayName("emittente e chiave fanno parte dell'input: cambiano lo pseudonimo; stessa terna, stesso valore")
    void issuerAndKeyAreInputs() {
        String base = SubjectRef.of(KEY, ISS, "sub-1");
        assertThat(SubjectRef.of(KEY, ISS, "sub-1")).isEqualTo(base);
        assertThat(SubjectRef.of(KEY, "https://altro.example.test/realms/x", "sub-1")).isNotEqualTo(base);
        assertThat(SubjectRef.of(KEY, ISS, "sub-2")).isNotEqualTo(base);
        assertThat(SubjectRef.of(key(32, 1), ISS, "sub-1")).isNotEqualTo(base);
        assertThat(SubjectRef.of(key(64, 0), ISS, "sub-1")).isNotEqualTo(base);
    }

    @Test
    @DisplayName("forma: 64 esadecimali minuscoli; il sub grezzo non compare nel valore")
    void shape() {
        String ref = SubjectRef.of(KEY, ISS, "utente.mario@example.test");
        assertThat(ref).hasSize(64).matches("^[0-9a-f]{64}$");
        assertThat(SubjectRef.isValid(ref)).isTrue();
        assertThat(ref).doesNotContain("mario").doesNotContain("example");
        assertThat(SubjectRef.isValid(ref.toUpperCase())).isFalse();
        assertThat(SubjectRef.isValid(ref.substring(1))).isFalse();
        assertThat(SubjectRef.isValid(null)).isFalse();
        assertThat(SubjectRef.isValid("")).isFalse();
    }

    @Test
    @DisplayName("chiave assente o più corta di 32 byte, emittente o soggetto vuoti: rifiutati")
    void invalidInputs() {
        assertThatThrownBy(() -> SubjectRef.of(null, ISS, "s")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SubjectRef.of(key(31, 0), ISS, "s")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining(Base64.getEncoder().encodeToString(key(31, 0)));
        assertThatThrownBy(() -> SubjectRef.of(KEY, null, "s")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SubjectRef.of(KEY, " ", "s")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SubjectRef.of(KEY, ISS, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SubjectRef.of(KEY, ISS, "")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("la chiave passata non viene modificata")
    void keyIsNotMutated() {
        byte[] copy = Arrays.copyOf(KEY, KEY.length);
        SubjectRef.of(KEY, ISS, "sub");
        assertThat(KEY).isEqualTo(copy);
    }

    private static byte[] key(int length, int offset) {
        byte[] k = new byte[length];
        for (int i = 0; i < length; i++) {
            k[i] = (byte) (i + offset);
        }
        return k;
    }
}
