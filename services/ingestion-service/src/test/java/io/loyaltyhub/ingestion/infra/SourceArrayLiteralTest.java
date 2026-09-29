package io.loyaltyhub.ingestion.infra;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Letterale {@code text[]} dei tipi ammessi di una fonte, legato come parametro {@code ?::text[]} (regola 19): ogni
 * elemento resta un elemento, anche con {@code \} o {@code "} nel valore. Test puro, senza database.
 */
class SourceArrayLiteralTest {

    @Test
    @DisplayName("codici normali: invariati rispetto a prima")
    void plainCodes() {
        assertThat(SourceRepository.arrayLiteral(List.of())).isEqualTo("{}");
        assertThat(SourceRepository.arrayLiteral(List.of("purchase.completed", "app.login")))
                .isEqualTo("{\"purchase.completed\",\"app.login\"}");
    }

    @Test
    @DisplayName("\\ e \" neutralizzati: un valore non chiude l'elemento e non ne apre altri")
    void backslashAndQuoteAreEscaped() {
        assertThat(SourceRepository.arrayLiteral(List.of("a\\\",\"b")))
                .as("a\\\",\"b resta un solo elemento")
                .isEqualTo("{\"a\\\\\\\",\\\"b\"}");
        assertThat(SourceRepository.arrayLiteral(List.of("x\\"))).isEqualTo("{\"x\\\\\"}");
    }
}
