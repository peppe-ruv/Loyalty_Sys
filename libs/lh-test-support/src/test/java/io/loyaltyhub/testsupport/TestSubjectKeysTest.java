package io.loyaltyhub.testsupport;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class TestSubjectKeysTest {

    @Test
    void laChiaveHa32Byte() {
        assertThat(TestSubjectKeys.random()).hasSize(32);
    }

    @Test
    void dueChiamateDanoChiaviDiverse() {
        assertThat(TestSubjectKeys.random()).isNotEqualTo(TestSubjectKeys.random());
        assertThat(TestSubjectKeys.randomBase64()).isNotEqualTo(TestSubjectKeys.randomBase64());
    }

    @Test
    void ilBase64FaIlGiroDiAndataERitorno() {
        byte[] key = TestSubjectKeys.random();
        assertThat(Base64.getDecoder().decode(TestSubjectKeys.base64(key))).isEqualTo(key);
    }

    @Test
    void randomBase64SiDecodificaIn32Byte() {
        assertThat(Base64.getDecoder().decode(TestSubjectKeys.randomBase64())).hasSize(32);
    }
}
