package io.loyaltyhub.contestservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Programma annuale, missioni, instant win periziabile, registro giocate append-only (RF-20..RF-39) */
@SpringBootApplication
public class ContestServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(ContestServiceApplication.class, args);
    }
}
