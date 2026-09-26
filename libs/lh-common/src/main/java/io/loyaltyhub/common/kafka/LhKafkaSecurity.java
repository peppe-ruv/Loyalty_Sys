package io.loyaltyhub.common.kafka;

import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.common.config.SaslConfigs;
import org.apache.kafka.common.config.SslConfigs;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * Traduce {@code loyaltyhub.kafka.*} nelle proprietà di sicurezza del client Kafka (docs/06 §5).
 * {@code PLAINTEXT} in locale; {@code SSL_PEM} (PEM in base64, nessun file su disco) o {@code SASL_SSL} in demo.
 */
public final class LhKafkaSecurity {

    private LhKafkaSecurity() {
    }

    public static Map<String, Object> properties(LoyaltyHubProperties.Kafka kafka) {
        Map<String, Object> props = new HashMap<>();
        switch (kafka.getSecurity()) {
            case PLAINTEXT -> props.put(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, "PLAINTEXT");
            case SSL_PEM -> {
                LoyaltyHubProperties.Ssl ssl = kafka.getSsl();
                props.put(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, "SSL");
                props.put(SslConfigs.SSL_TRUSTSTORE_TYPE_CONFIG, "PEM");
                props.put(SslConfigs.SSL_TRUSTSTORE_CERTIFICATES_CONFIG, decode(ssl.getCaB64()));
                props.put(SslConfigs.SSL_KEYSTORE_TYPE_CONFIG, "PEM");
                props.put(SslConfigs.SSL_KEYSTORE_CERTIFICATE_CHAIN_CONFIG, decode(ssl.getCertB64()));
                props.put(SslConfigs.SSL_KEYSTORE_KEY_CONFIG, decode(ssl.getKeyB64()));
            }
            case SASL_SSL -> {
                LoyaltyHubProperties.Sasl sasl = kafka.getSasl();
                props.put(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, "SASL_SSL");
                props.put(SaslConfigs.SASL_MECHANISM, sasl.getMechanism());
                String module = sasl.getMechanism().startsWith("SCRAM")
                        ? "org.apache.kafka.common.security.scram.ScramLoginModule"
                        : "org.apache.kafka.common.security.plain.PlainLoginModule";
                props.put(SaslConfigs.SASL_JAAS_CONFIG, "%s required username=\"%s\" password=\"%s\";"
                        .formatted(module, sasl.getUsername(), sasl.getPassword()));
                // docs/11 §3: SASL_SSL usa anche KAFKA_SSL_CA_B64 come truststore (ADR-025: ramo da completare col CA).
                String ca = kafka.getSsl().getCaB64();
                if (ca != null && !ca.isBlank()) {
                    props.put(SslConfigs.SSL_TRUSTSTORE_TYPE_CONFIG, "PEM");
                    props.put(SslConfigs.SSL_TRUSTSTORE_CERTIFICATES_CONFIG, decode(ca));
                }
            }
        }
        return props;
    }

    private static String decode(String b64) {
        if (b64 == null || b64.isBlank()) {
            return "";
        }
        return new String(Base64.getDecoder().decode(b64.trim()), StandardCharsets.UTF_8);
    }
}
