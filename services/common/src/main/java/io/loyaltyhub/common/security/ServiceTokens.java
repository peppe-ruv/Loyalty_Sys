package io.loyaltyhub.common.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * Token di servizio per le chiamate fra servizi (RF-43): flusso <i>client credentials</i> verso l'IAM, con il token
 * tenuto in memoria fino a poco prima della scadenza. Un token per processo, non uno per chiamata: l'IAM non va
 * interrogato a ogni evento, e ogni evento è una chiamata.
 *
 * <p>Se l'IAM non risponde non si inventa un token: la chiamata parte senza e il servizio chiamato risponderà 401.
 * Meglio un errore esplicito, che si vede nelle metriche, di una chiamata che passa senza credenziali.
 */
public class ServiceTokens {
    private static final Logger log = LoggerFactory.getLogger(ServiceTokens.class);
    private static final ParameterizedTypeReference<Map<String, Object>> JSON = new ParameterizedTypeReference<>() { };
    /** Margine di sicurezza: si rinnova prima della scadenza, così nessuna chiamata parte con un token appena scaduto. */
    private static final Duration MARGIN = Duration.ofSeconds(30);

    private final RestClient client;
    private final String tokenUri;
    private final String clientId;
    private final String clientSecret;
    private final String scope;

    private volatile String token;
    private volatile Instant expiresAt = Instant.EPOCH;

    public ServiceTokens(RestClient client, String tokenUri, String clientId, String clientSecret, String scope) {
        this.client = client; this.tokenUri = tokenUri; this.clientId = clientId; this.clientSecret = clientSecret; this.scope = scope;
    }

    /** Token valido, rinnovato se serve; {@code null} se l'IAM non lo rilascia. */
    public synchronized String token() {
        if (token != null && Instant.now().isBefore(expiresAt)) return token;
        if (tokenUri == null || tokenUri.isBlank()) return null;
        var form = new LinkedMultiValueMap<String, String>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret);
        if (scope != null && !scope.isBlank()) form.add("scope", scope);
        try {
            Map<String, Object> res = client.post().uri(tokenUri).contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form).retrieve().body(JSON);
            Object access = res == null ? null : res.get("access_token");
            if (access == null) { log.warn("l'IAM non ha rilasciato un access_token"); return null; }
            long seconds = res.get("expires_in") instanceof Number n ? n.longValue() : 300;
            token = access.toString();
            expiresAt = Instant.now().plusSeconds(Math.max(1, seconds)).minus(MARGIN);
            return token;
        } catch (RuntimeException e) {
            log.warn("token di servizio non ottenuto: {}", e.getMessage());
            token = null;
            expiresAt = Instant.EPOCH;
            return null;
        }
    }
}
