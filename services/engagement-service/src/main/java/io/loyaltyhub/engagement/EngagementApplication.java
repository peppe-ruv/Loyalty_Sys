package io.loyaltyhub.engagement;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * engagement-service — il "CMS del programma" (docs/04 §3, docs/servizi/engagement-service.md). M6.0: template dei
 * messaggi, regole di notifica, inbox in-app generata dai fatti e dall'effetto {@code message.send}.
 */
@SpringBootApplication
public class EngagementApplication {
    public static void main(String[] args) {
        SpringApplication.run(EngagementApplication.class, args);
    }
}
