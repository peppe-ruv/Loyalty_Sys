package it.iren.loyalty.common.test;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Base dei test di integrazione: un Postgres vero, condiviso da tutti i moduli, con le migrazioni
 * Flyway eseguite davvero. Vincoli, trigger e locking sono metà della logica di questi servizi e
 * con un database in memoria non si proverebbero.
 *
 * <p>Il container è uno solo per esecuzione (avviato nello static initializer e mai fermato: ci
 * pensa Ryuk); ogni schema è separato per servizio, quindi i moduli non si pestano i piedi.
 *
 * <p>Dietro un proxy che blocca Docker Hub: {@code TESTCONTAINERS_HUB_IMAGE_NAME_PREFIX=mirror.gcr.io/}.
 * La versione dell'API Docker è fissata dal pom padre ({@code docker.api.version}).
 */
public abstract class PostgresIntegrationTest {

    protected static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

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
