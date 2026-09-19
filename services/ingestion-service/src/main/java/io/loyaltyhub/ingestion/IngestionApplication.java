package io.loyaltyhub.ingestion;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * ingestion-service — unica porta d'ingresso delle azioni e unico produttore di {@code lh.actions.v1}
 * (docs/04 §3, docs/servizi/ingestion-service.md). In M0.5 è l'archetipo ridotto: riceve un CloudEvent,
 * deduplica e pubblica sul topic via outbox. Validazione, fonti e risoluzione membro arrivano in M1.1.
 */
@SpringBootApplication
public class IngestionApplication {
    public static void main(String[] args) {
        SpringApplication.run(IngestionApplication.class, args);
    }
}
