package io.loyaltyhub.ledger;

import io.loyaltyhub.common.test.PostgresIntegrationTest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatusCode;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Le API interne si fidavano della rete: chi arrivava al servizio poteva accreditare punti. Ora esiste un resource
 * server OAuth2 che si accende per ambiente. Questi test provano le due configurazioni sullo stesso servizio: spenta
 * (locale e test, tutto aperto) e accesa (senza token si prende 401, le sonde restano leggibili).
 */
class SecurityChainIntegrationTest {

    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
            properties = {"spring.kafka.listener.auto-startup=false", "loyalty.security.enabled=false"})
    class SicurezzaSpenta extends PostgresIntegrationTest {
        @LocalServerPort int port;
        @MockitoBean KafkaTemplate<String, byte[]> kafka;
        @Autowired RestClient.Builder builder;

        @Test void le_api_rispondono_senza_token() {
            var res = client(builder, port).get().uri("/v1/ledger/members/{id}/wallets", "chiunque")
                    .retrieve().onStatus(s -> true, (rq, rs) -> { }).toBodilessEntity();
            assertThat(res.getStatusCode().value()).isNotEqualTo(401);
        }
    }

    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
            properties = {"spring.kafka.listener.auto-startup=false", "loyalty.security.enabled=true",
                    "spring.security.oauth2.resourceserver.jwt.issuer-uri=",
                    "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks"})
    class SicurezzaAccesa extends PostgresIntegrationTest {
        @LocalServerPort int port;
        @MockitoBean KafkaTemplate<String, byte[]> kafka;
        @Autowired RestClient.Builder builder;

        @Test void senza_token_le_api_rispondono_401() {
            HttpStatusCode status = client(builder, port).get().uri("/v1/ledger/members/{id}/wallets", "chiunque")
                    .retrieve().onStatus(s -> true, (rq, rs) -> { }).toBodilessEntity().getStatusCode();
            assertThat(status.value()).isEqualTo(401);
        }

        /** Le sonde restano aperte: il cluster deve poterle leggere anche quando il servizio non sa validare un token. */
        @Test void le_sonde_restano_leggibili() {
            assertThat(get(builder, port, "/actuator/health").value()).isEqualTo(200);
        }

        /**
         * Un percorso aperto che però non risponde non deve diventare un 401: Spring inoltra il 404 a {@code /error}
         * e, se anche quello è protetto, torna indietro un 401 che parla di credenziali invece che di un percorso
         * sbagliato. È successo davvero mentre si scriveva questo test.
         *
         * <p>Su un percorso <i>protetto</i> invece il 401 è giusto anche se non esiste: chi non ha un token non deve
         * scoprire quali percorsi ci sono.
         */
        @Test void un_percorso_aperto_che_non_esiste_resta_un_404() {
            assertThat(get(builder, port, "/actuator/prometheus").value()).isNotEqualTo(401);
            assertThat(get(builder, port, "/questo-non-esiste").value()).isEqualTo(401);
        }
    }

    private static RestClient client(RestClient.Builder builder, int port) {
        return builder.clone().baseUrl("http://localhost:" + port).build();
    }

    private static HttpStatusCode get(RestClient.Builder builder, int port, String path) {
        return client(builder, port).get().uri(path).retrieve().onStatus(s -> true, (rq, rs) -> { }).toBodilessEntity().getStatusCode();
    }
}
