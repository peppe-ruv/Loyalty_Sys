package io.loyaltyhub.reward;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * reward-service — catalogo premi a fasce, stock, pool di coupon e richieste premio (docs/04 §3,
 * docs/servizi/reward-service.md). M4.1: categorie, fasce, premi con ciclo di vita, catalogo del portale con
 * visibilità per tier; M4.2 pool coupon; M4.3 saga di richiesta con il wallet; M4.4 evasione e annullo.
 */
@SpringBootApplication
public class RewardApplication {
    public static void main(String[] args) {
        SpringApplication.run(RewardApplication.class, args);
    }
}
