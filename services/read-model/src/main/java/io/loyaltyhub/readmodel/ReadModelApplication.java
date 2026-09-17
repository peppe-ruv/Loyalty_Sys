package io.loyaltyhub.readmodel;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Context-service (Customer 360, RF-125/RF-126): proiezioni CQRS dai topic di dominio in un profilo per membro
 * (identità loyalty, wallet, tier, azioni recenti, riscatti, giocate, segmenti, badge, campagne, rischio, decisioni,
 * consensi, RFM) e API di contesto a bassa latenza per decision-service, sito e backoffice. Nessuna scrittura dal BFF.
 */
@SpringBootApplication
@EnableScheduling
public class ReadModelApplication {
    public static void main(String[] args) { SpringApplication.run(ReadModelApplication.class, args); }
}
