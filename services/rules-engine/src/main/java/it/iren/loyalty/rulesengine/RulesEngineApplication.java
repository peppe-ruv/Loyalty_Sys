package it.iren.loyalty.rulesengine;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Valuta le regole pubblicate su ogni azione premiante ed emette movimenti (RF-05..RF-09) */
@SpringBootApplication
public class RulesEngineApplication {
    public static void main(String[] args) {
        SpringApplication.run(RulesEngineApplication.class, args);
    }
}
