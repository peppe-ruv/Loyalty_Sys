package io.loyaltyhub.insight.infra;

import io.loyaltyhub.common.web.LhException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.Map;

/**
 * L'<strong>unica</strong> chiamata HTTP tra servizi ammessa (docs/13 ADR-002 eccezione 1): il <em>riprocessa</em>
 * DLQ di un'azione re-invia l'evento a {@code ingestion POST /v1/events} con lo stesso {@code id}, solo su comando
 * umano (docs/servizi/insight-service.md §5). ADR-003 resta valida: è ingestion a ripubblicarla su
 * {@code lh.actions.v1}.
 *
 * <p>URL base da {@code loyaltyhub.insight.ingestion-url} ({@code LH_INGESTION_URL}, docs/11 §8), letto a ogni
 * chiamata: nell'hub consolidato il default punta allo stesso processo ({@code local.server.port}), noto solo
 * dopo l'avvio del server web.
 */
@Component
public class IngestionClient {

    /** Header che chiede a ingestion la ripubblicazione dell'azione già accettata (valore: id della voce DLQ). */
    public static final String REPROCESS_HEADER = "X-LH-Reprocess";

    private static final Logger log = LoggerFactory.getLogger(IngestionClient.class);
    private static final String URL_PROPERTY = "loyaltyhub.insight.ingestion-url";

    private final Environment env;
    private final RestClient client;

    public IngestionClient(Environment env) {
        this.env = env;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(20));
        this.client = RestClient.builder().requestFactory(factory).build();
    }

    /** Esito di ingestion: {@code status} ({@code ACCEPTED}, {@code DUPLICATE}, …) ed eventuale dettaglio. */
    public record IngestOutcome(String status, String rejectCode, String detail) {
    }

    /**
     * Re-invia l'azione. Ingestion irraggiungibile (addormentata, spenta) → {@code 503 DEPENDENCY_UNAVAILABLE};
     * errore di forma o di ruolo → stesso stato di ingestion con il suo dettaglio.
     */
    public IngestOutcome resend(Map<String, Object> cloudEvent, String actor, String dlqEntryId) {
        String baseUrl = baseUrl();
        try {
            JsonNode body = client.post().uri(baseUrl + "/v1/events")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-LH-Actor", actor)
                    .header(REPROCESS_HEADER, dlqEntryId)
                    .body(cloudEvent)
                    .retrieve()
                    .body(JsonNode.class);
            if (body == null) {
                throw LhException.dependencyUnavailable("Risposta vuota da ingestion");
            }
            return new IngestOutcome(body.path("status").asString(""), textOrNull(body, "rejectCode"),
                    textOrNull(body, "detail"));
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().is5xxServerError()) {
                log.warn("Riprocessa DLQ {}: ingestion ha risposto {}", dlqEntryId, e.getStatusCode());
                throw LhException.dependencyUnavailable("ingestion non disponibile (" + e.getStatusCode().value() + ")");
            }
            throw new LhException(org.springframework.http.HttpStatus.valueOf(e.getStatusCode().value()),
                    "ingestion-refused", "INGESTION_REFUSED",
                    "ingestion ha rifiutato il re-invio: " + e.getResponseBodyAsString(), null);
        } catch (RestClientException e) {
            log.warn("Riprocessa DLQ {}: ingestion non raggiungibile su {}: {}", dlqEntryId, baseUrl, e.toString());
            throw LhException.dependencyUnavailable("ingestion non raggiungibile: riprova quando è sveglia");
        }
    }

    /** URL di ingestion; in mancanza di configurazione, lo stesso processo (hub) o la porta locale di ingestion. */
    String baseUrl() {
        String configured = env.getProperty(URL_PROPERTY);
        if (configured != null && !configured.isBlank()) {
            String url = configured.trim();
            return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        }
        return "http://localhost:8081";
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asString();
    }
}
