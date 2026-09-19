package io.loyaltyhub.insight;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * insight-service — la finestra sulla piattaforma (docs/servizi/insight-service.md). M2.1: consuma tutti i
 * topic e conserva gli eventi recenti in un event store con retention. Tracciati, KPI, audit, DLQ e stream
 * SSE arrivano nelle fette successive di M2. Sola lettura: non produce eventi.
 */
@SpringBootApplication
public class InsightApplication {
    public static void main(String[] args) {
        SpringApplication.run(InsightApplication.class, args);
    }
}
