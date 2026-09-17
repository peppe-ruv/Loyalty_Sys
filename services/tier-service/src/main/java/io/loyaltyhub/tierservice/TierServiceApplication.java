package io.loyaltyhub.tierservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Soglie, upgrade immediato, verifica annuale con discesa di un livello (RF-10..RF-13) */
@SpringBootApplication
@org.springframework.scheduling.annotation.EnableScheduling
public class TierServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(TierServiceApplication.class, args);
    }
}
