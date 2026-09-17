package io.loyaltyhub.engagementservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Gamification: achievement con streak, challenge a milestone, badge, classifiche con ciclo premiante (RF-90..RF-97) */
@SpringBootApplication
@EnableScheduling
public class EngagementServiceApplication {
    public static void main(String[] args) { SpringApplication.run(EngagementServiceApplication.class, args); }
}
