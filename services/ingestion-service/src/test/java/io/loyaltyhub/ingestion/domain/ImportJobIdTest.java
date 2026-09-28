package io.loyaltyhub.ingestion.domain;

import io.loyaltyhub.common.ids.Ulid;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Forma dell'id di un lavoro d'import, controllata al confine HTTP prima di ogni uso (BO-32, F2-ING-02). */
class ImportJobIdTest {

    @Test
    void generatedIdsAreWellFormed() {
        for (int i = 0; i < 1000; i++) {
            assertThat(ImportJob.isWellFormedId(Ulid.next())).isTrue();
        }
    }

    @Test
    void anythingElseIsRejected() {
        assertThat(ImportJob.isWellFormedId(null)).isFalse();
        assertThat(ImportJob.isWellFormedId("")).isFalse();
        assertThat(ImportJob.isWellFormedId("01JCQ000000000000000000A0")).as("25 caratteri").isFalse();
        assertThat(ImportJob.isWellFormedId("01JCQ000000000000000000A011")).as("27 caratteri").isFalse();
        assertThat(ImportJob.isWellFormedId("01jcq000000000000000000a01")).as("minuscole").isFalse();
        assertThat(ImportJob.isWellFormedId("01JCLEAN0000000000000000A1")).as("L non è Crockford").isFalse();
        assertThat(ImportJob.isWellFormedId("8ZZZZZZZZZZZZZZZZZZZZZZZZZ")).as("oltre i 48 bit di tempo").isFalse();
        assertThat(ImportJob.isWellFormedId("<script>alert(1)</script>")).isFalse();
        assertThat(ImportJob.isWellFormedId("01JCQ00000000000000000\r\nA01")).isFalse();
    }
}
