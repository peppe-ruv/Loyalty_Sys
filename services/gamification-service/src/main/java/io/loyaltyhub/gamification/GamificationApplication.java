package io.loyaltyhub.gamification;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * gamification-service — concorsi instant win a istanti pre-generati, obiettivi, badge e classifiche (docs/04 §3,
 * docs/servizi/gamification-service.md). M5.1: concorsi, montepremi, generatore di istanti con seme.
 */
@SpringBootApplication
public class GamificationApplication {
    public static void main(String[] args) {
        SpringApplication.run(GamificationApplication.class, args);
    }
}
