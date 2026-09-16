package it.iren.loyalty.ledger;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Registro movimenti immutabile a doppia valuta, saldi materializzati, storni, outbox (RF-04, RF-15) */
@SpringBootApplication
public class LedgerApplication {
    public static void main(String[] args) {
        SpringApplication.run(LedgerApplication.class, args);
    }
}
