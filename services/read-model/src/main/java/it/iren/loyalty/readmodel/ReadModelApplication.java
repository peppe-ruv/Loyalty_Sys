package it.iren.loyalty.readmodel;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Viste di lettura per sito e widget: saldo, movimenti, premi, concorsi (CQRS) */
@SpringBootApplication
public class ReadModelApplication {
    public static void main(String[] args) {
        SpringApplication.run(ReadModelApplication.class, args);
    }
}
