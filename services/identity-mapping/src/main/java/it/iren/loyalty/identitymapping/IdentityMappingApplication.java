package it.iren.loyalty.identitymapping;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Mappa sub OIDC verso id CRM e SAP (RI-02) */
@SpringBootApplication
public class IdentityMappingApplication {
    public static void main(String[] args) {
        SpringApplication.run(IdentityMappingApplication.class, args);
    }
}
