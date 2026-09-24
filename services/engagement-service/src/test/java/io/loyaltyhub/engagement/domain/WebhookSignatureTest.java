package io.loyaltyhub.engagement.domain;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** Firma HMAC dei webhook (docs/servizi/engagement-service.md §5; F-WBH-01). */
class WebhookSignatureTest {

    /** Stesso vettore di deploy/webhook-receiver/verify.test.mjs: Java e lo script Node devono dare la stessa firma. */
    @Test
    void sharedVectorMatchesTheNodeReceiver() throws Exception {
        Path fixture = Path.of("../../deploy/webhook-receiver/fixtures/firma-esempio.json");
        JsonNode f = new ObjectMapper().readTree(Files.readString(fixture));
        String body = f.path("body").asString();
        assertThat(WebhookSignature.sign(f.path("secret").asString(), body)).isEqualTo(f.path("signature").asString());
        assertThat(WebhookSignature.verify(f.path("secret").asString(), body.getBytes(StandardCharsets.UTF_8),
                f.path("signature").asString())).isTrue();
    }

    @Test
    void signatureIsLowercaseHexWithPrefixAndDependsOnSecretAndBody() {
        String sig = WebhookSignature.sign("whsec_a", "{\"x\":1}");
        assertThat(sig).matches("^sha256=[0-9a-f]{64}$");
        assertThat(WebhookSignature.sign("whsec_b", "{\"x\":1}")).isNotEqualTo(sig);
        assertThat(WebhookSignature.sign("whsec_a", "{\"x\":2}")).isNotEqualTo(sig);
        assertThat(WebhookSignature.verify("whsec_a", "{\"x\": 1}".getBytes(StandardCharsets.UTF_8), sig))
                .as("uno spazio in più cambia la firma: si firma il corpo esatto").isFalse();
        assertThat(WebhookSignature.verify("whsec_a", "{\"x\":1}".getBytes(StandardCharsets.UTF_8), null)).isFalse();
    }

    @Test
    void newSecretsAreRandomAndPrefixed() {
        String a = WebhookSignature.newSecret();
        assertThat(a).startsWith("whsec_").hasSizeGreaterThan(40);
        assertThat(WebhookSignature.newSecret()).isNotEqualTo(a);
    }
}
