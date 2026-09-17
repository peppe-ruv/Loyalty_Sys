package io.loyaltyhub.ingressadapters;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Adattatori per fonte: validazione, idempotenza, pubblicazione evento canonico (RI-01..RI-04) */
@SpringBootApplication
public class IngressAdaptersApplication {
    public static void main(String[] args) {
        SpringApplication.run(IngressAdaptersApplication.class, args);
    }
}
