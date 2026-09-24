package io.loyaltyhub.ingestion.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Regole di riprova / abbina / abbinamento automatico (F-ING-04, F-ING-09, M7.4). */
class InboundResolutionTest {

    @Test
    void retryOnlyForRejectedAndUnmatched() {
        assertThat(InboundResolution.canRetry(InboundStatus.REJECTED)).isTrue();
        assertThat(InboundResolution.canRetry(InboundStatus.UNMATCHED)).isTrue();
        assertThat(InboundResolution.canRetry(InboundStatus.ACCEPTED)).as("mai una doppia pubblicazione").isFalse();
        assertThat(InboundResolution.canRetry(InboundStatus.DUPLICATE)).isFalse();
        assertThat(InboundResolution.canRetry(null)).isFalse();
    }

    @Test
    void matchOnlyForUnmatched() {
        assertThat(InboundResolution.canMatch(InboundStatus.UNMATCHED)).isTrue();
        assertThat(InboundResolution.canMatch(InboundStatus.REJECTED)).isFalse();
        assertThat(InboundResolution.canMatch(InboundStatus.ACCEPTED)).isFalse();
        assertThat(InboundResolution.canMatch(InboundStatus.DUPLICATE)).isFalse();
    }

    @Test
    void unknownStatusParsesToNull() {
        assertThat(InboundResolution.parseStatus("UNMATCHED")).isEqualTo(InboundStatus.UNMATCHED);
        assertThat(InboundResolution.parseStatus("BOH")).isNull();
        assertThat(InboundResolution.parseStatus(null)).isNull();
    }

    @Test
    void autoMatchKeysFollowThePipelineResolution() {
        InboundResolution.AutoMatchKeys keys = InboundResolution.autoMatchKeys("CRM-9001", "Nuovo.Socio@ClubAurora.example");
        // external: confronto esatto come member_index.external_id; email: minuscolo come member_index.email_lower.
        assertThat(keys.externalSubject()).isEqualTo("external:CRM-9001");
        assertThat(keys.emailSubject()).isEqualTo("email:nuovo.socio@clubaurora.example");
        assertThat(keys.isEmpty()).isFalse();
    }

    @Test
    void autoMatchKeysSkipMissingReferences() {
        assertThat(InboundResolution.autoMatchKeys(null, " ").isEmpty()).isTrue();
        InboundResolution.AutoMatchKeys onlyEmail = InboundResolution.autoMatchKeys("", "a@b.example");
        assertThat(onlyEmail.externalSubject()).isNull();
        assertThat(onlyEmail.emailSubject()).isEqualTo("email:a@b.example");
    }
}
