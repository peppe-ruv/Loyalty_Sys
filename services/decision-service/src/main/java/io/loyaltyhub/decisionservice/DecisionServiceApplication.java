package io.loyaltyhub.decisionservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Decision-service (RF-127..RF-130): tra le regole e l'azione. Riceve evento + contesto, raccoglie le azioni
 * ammissibili (campagne, catalogo offerte), applica vincoli e policy configurate nel backoffice, aggiunge le previsioni
 * ({@code PredictionProvider}), sceglie l'azione con più valore e la spiega (decision log). Espone Next Best Action.
 */
@SpringBootApplication
@EnableScheduling
public class DecisionServiceApplication {
    public static void main(String[] args) { SpringApplication.run(DecisionServiceApplication.class, args); }
}
