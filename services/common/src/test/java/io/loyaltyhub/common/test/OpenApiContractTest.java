package io.loyaltyhub.common.test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.client.RestClient;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contratto REST pubblicato (RF-133). Ogni servizio espone la propria specifica OpenAPI su {@code /v3/api-docs}, ma
 * finché resta solo lì nessuno la può leggere senza avviare il servizio: chi integra non ha un file da cui generare
 * un client, e una modifica incompatibile passa inosservata.
 *
 * <p>Questo test confronta la specifica viva con quella committata in {@code docs/contracts/}: se l'API cambia senza
 * che il contratto sia aggiornato, il test fallisce e dice come rigenerarlo. Non è una comodità, è la rete che
 * impedisce a un cambio di contratto di arrivare in produzione senza che nessuno se ne accorga.
 *
 * <p>Le specifiche generate stanno in {@code docs/contracts/generated/}: sono il riflesso fedele del codice.
 * Accanto, in {@code docs/contracts/}, vivono i contratti curati a mano per chi integra dall'esterno, che aggiungono
 * ciò che il codice non sa dire (autenticazione, semantica degli esiti, esempi).
 *
 * <p>Per rigenerare: {@code mvn -pl <servizio> test -Dopenapi.write=true}.
 */
public abstract class OpenApiContractTest extends PostgresIntegrationTest {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    @LocalServerPort
    protected int port;

    @Autowired
    protected RestClient.Builder builder;

    /** Nome del servizio: dà il nome al file {@code docs/contracts/openapi-<nome>.yaml}. */
    protected abstract String serviceName();

    @Test
    void la_specifica_pubblicata_corrisponde_a_quella_del_servizio() throws Exception {
        String live = builder.clone().baseUrl("http://localhost:" + port).build()
                .get().uri("/v3/api-docs.yaml").retrieve().body(String.class);
        assertThat(live).as("il servizio deve esporre la propria specifica OpenAPI").isNotBlank();

        // La porta del test finirebbe in `servers` e renderebbe il file diverso a ogni esecuzione: l'indirizzo non
        // fa parte del contratto, lo decide chi installa.
        var viva = (com.fasterxml.jackson.databind.node.ObjectNode) YAML.readTree(live);
        viva.remove("servers");
        String normalizzata = YAML.writerWithDefaultPrettyPrinter().writeValueAsString(viva);

        Path file = Path.of("..", "..", "docs", "contracts", "generated", "openapi-" + serviceName() + ".yaml").normalize();
        boolean scrivi = Boolean.getBoolean("openapi.write") || !Files.exists(file);
        if (scrivi) {
            Files.createDirectories(file.getParent());
            Files.writeString(file, intestazione(serviceName()) + normalizzata);
            return;
        }

        JsonNode pubblicata = YAML.readTree(Files.readString(file));
        assertThat((JsonNode) viva)
                .as("la specifica di %s è cambiata: rigenerala con `mvn -pl %s test -Dopenapi.write=true` e "
                        + "committa %s insieme alla modifica", serviceName(), serviceName(), file)
                .isEqualTo(pubblicata);
    }

    private static String intestazione(String service) {
        return """
                # Contratto REST di %s — generato da OpenApiContractTest, non modificare a mano.
                # Rigenerare con: mvn -pl %s test -Dopenapi.write=true
                """.formatted(service, service);
    }
}
