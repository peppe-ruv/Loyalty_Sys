package io.loyaltyhub.common.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Un errore imprevisto risponde con un dettaglio generico, mai con il messaggio dell'eccezione né con il testo SQL
 * (R-03 di {@code TB-SEC}, docs/06 §2). Dalla correzione del byte NUL (Q-532 causa (3)) nessuna riga di {@code TB-SEC}
 * produce più una 500: questa è la prova che ne prende il posto per la mutazione M1 (dettaglio con {@code ex.toString()}).
 */
class UnexpectedErrorTest {

    @Test
    @DisplayName("500: dettaglio generico, nessun testo dell'eccezione, del driver o dell'SQL")
    void unexpectedErrorDoesNotLeak() {
        var ex = new org.springframework.dao.DataIntegrityViolationException(
                "PreparedStatementCallback; SQL [select * from member.member where first_name ilike ?]; "
                        + "ERROR: invalid byte sequence for encoding \"UTF8\": 0x00");
        var response = new GlobalExceptionHandler().onUnexpected(ex, new MockHttpServletRequest("GET", "/v1/members"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody().getDetail()).isEqualTo("Si è verificato un errore imprevisto");
        String text = response.getBody().toString();
        assertThat(text).doesNotContain("DataIntegrityViolationException").doesNotContain("select").doesNotContain("PSQL")
                .doesNotContain("0x00").doesNotContain("org.springframework");
    }
}
