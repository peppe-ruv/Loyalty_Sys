package io.loyaltyhub.common.kafka;

import io.loyaltyhub.common.event.LhFamily;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configurazione {@code loyaltyhub.*}: nomi dei topic e sicurezza Kafka (docs/05 §1, docs/06 §5). */
@ConfigurationProperties(prefix = "loyaltyhub")
public class LoyaltyHubProperties {

    private final Topics topics = new Topics();
    private final Kafka kafka = new Kafka();
    private final Consumer consumer = new Consumer();
    /** Nome del servizio (usato come {@code source} degli eventi e nei log). */
    private String service = "lh-service";

    public Topics getTopics() {
        return topics;
    }

    public Consumer getConsumer() {
        return consumer;
    }

    public Kafka getKafka() {
        return kafka;
    }

    public String getService() {
        return service;
    }

    public void setService(String service) {
        this.service = service;
    }

    /** Topic di destinazione per una famiglia di evento (docs/05 §1). Audit e DLQ sono espliciti. */
    public String topicFor(LhFamily family) {
        return switch (family) {
            case ACTION -> topics.getActions();
            case EFFECT -> topics.getEffects();
            case FACT -> topics.getFacts();
            case AUDIT -> topics.getAudit();
        };
    }

    /** I 5 topic del sistema (ADR-004): esattamente cinque, nomi configurabili. */
    public static class Topics {
        private String actions = "lh.actions.v1";
        private String effects = "lh.effects.v1";
        private String facts = "lh.facts.v1";
        private String audit = "lh.audit.v1";
        private String dlq = "lh.dlq.v1";

        public String getActions() {
            return actions;
        }

        public void setActions(String actions) {
            this.actions = actions;
        }

        public String getEffects() {
            return effects;
        }

        public void setEffects(String effects) {
            this.effects = effects;
        }

        public String getFacts() {
            return facts;
        }

        public void setFacts(String facts) {
            this.facts = facts;
        }

        public String getAudit() {
            return audit;
        }

        public void setAudit(String audit) {
            this.audit = audit;
        }

        public String getDlq() {
            return dlq;
        }

        public void setDlq(String dlq) {
            this.dlq = dlq;
        }

        public java.util.List<String> all() {
            return java.util.List.of(actions, effects, facts, audit, dlq);
        }
    }

    public enum Security {
        PLAINTEXT, SSL_PEM, SASL_SSL
    }

    public static class Kafka {
        private Security security = Security.PLAINTEXT;
        private final Ssl ssl = new Ssl();
        private final Sasl sasl = new Sasl();

        public Security getSecurity() {
            return security;
        }

        public void setSecurity(Security security) {
            this.security = security;
        }

        public Ssl getSsl() {
            return ssl;
        }

        public Sasl getSasl() {
            return sasl;
        }
    }

    /** PEM in base64 (nessun file su disco): CA, certificato e chiave client (docs/06 §5, SSL_PEM). */
    public static class Ssl {
        private String caB64;
        private String certB64;
        private String keyB64;

        public String getCaB64() {
            return caB64;
        }

        public void setCaB64(String caB64) {
            this.caB64 = caB64;
        }

        public String getCertB64() {
            return certB64;
        }

        public void setCertB64(String certB64) {
            this.certB64 = certB64;
        }

        public String getKeyB64() {
            return keyB64;
        }

        public void setKeyB64(String keyB64) {
            this.keyB64 = keyB64;
        }
    }

    public static class Sasl {
        private String username;
        private String password;
        private String mechanism = "SCRAM-SHA-256";

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public String getMechanism() {
            return mechanism;
        }

        public void setMechanism(String mechanism) {
            this.mechanism = mechanism;
        }
    }

    public static class Consumer {
        /**
         * Ritardi tra un tentativo e il successivo: n ritardi = n + 1 tentativi. docs/04 dice "3 tentativi con backoff
         * 1 s / 5 s / 15 s" (tre ritardi = quattro tentativi); vince il conteggio di docs/12 (accettazione M0: DLQ dopo 3
         * tentativi), quindi 1 s e 5 s. SPEC-GAP: Q-131
         */
        private long[] retryBackoffMs = new long[]{1000L, 5000L};

        public long[] getRetryBackoffMs() {
            return retryBackoffMs;
        }

        public void setRetryBackoffMs(long[] retryBackoffMs) {
            this.retryBackoffMs = retryBackoffMs;
        }
    }
}
