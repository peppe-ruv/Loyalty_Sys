package it.iren.loyalty.ledger;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Base dei test di integrazione del ledger: Postgres vero, con le migrazioni Flyway eseguite davvero.
 * Vincoli, trigger di immutabilità e locking sono metà della logica di questo servizio e con un
 * database in memoria non si proverebbero.
 *
 * <p>Il container è uno solo per l'intera esecuzione (avviato nello static initializer e mai fermato:
 * ci pensa Ryuk): avviarne uno per classe costerebbe più dei test stessi.
 *
 * <p>Il relay dell'outbox è l'unico pezzo che parla con Kafka e qui viene messo a dormire: il
 * percorso di scrittura scrive nell'outbox e basta.
 *
 * <p>Dietro un proxy che blocca Docker Hub, esportare
 * {@code TESTCONTAINERS_HUB_IMAGE_NAME_PREFIX=mirror.gcr.io/}; in CI non serve.
 */
@SpringBootTest(properties = "ledger.outbox.relay-ms=3600000")
public abstract class PostgresIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
}
