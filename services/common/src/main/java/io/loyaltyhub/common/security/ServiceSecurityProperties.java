package io.loyaltyhub.common.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configurazione della sicurezza servizio-a-servizio (RF-43). I valori arrivano dall'ambiente: l'emittente e le
 * credenziali cambiano per ambiente e il segreto sta in un gestore di segreti, mai nel repository.
 */
@ConfigurationProperties(prefix = "loyalty.security")
public class ServiceSecurityProperties {

    /** Accende il resource server: spento in locale e nei test, acceso negli ambienti veri. */
    private boolean enabled;

    /** Scope che un token deve portare per parlare con le API interne. */
    private String requiredScope = "loyalty.internal";

    /** Endpoint del token (client credentials) dell'IAM. */
    private String tokenUri = "";

    private String clientId = "";

    private String clientSecret = "";

    /** Accende la propagazione del token nelle chiamate in uscita; di norma si accende insieme al resource server. */
    private Client client = new Client();

    public static class Client {
        private boolean enabled;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getRequiredScope() { return requiredScope; }
    public void setRequiredScope(String requiredScope) { this.requiredScope = requiredScope; }
    public String getTokenUri() { return tokenUri; }
    public void setTokenUri(String tokenUri) { this.tokenUri = tokenUri; }
    public String getClientId() { return clientId; }
    public void setClientId(String clientId) { this.clientId = clientId; }
    public String getClientSecret() { return clientSecret; }
    public void setClientSecret(String clientSecret) { this.clientSecret = clientSecret; }
    public Client getClient() { return client; }
    public void setClient(Client client) { this.client = client; }
}
