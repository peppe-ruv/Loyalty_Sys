package it.iren.loyalty.catalogredemption;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Catalogo per fascia, stock, riscatto atomico, stati consegna (RF-14..RF-18) */
@SpringBootApplication
public class CatalogRedemptionApplication {
    public static void main(String[] args) {
        SpringApplication.run(CatalogRedemptionApplication.class, args);
    }
}
