package io.loyaltyhub.member;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * member-service — anagrafica dei membri, proiezione saldi/tier e statistiche di attività
 * (docs/04 §3, docs/servizi/member-service.md). M1.2: anagrafica, stati, ricerca, i tre fatti
 * {@code member.registered/updated/status.changed}, consumo azioni→stats e fatti wallet/tier→proiezione.
 */
@SpringBootApplication
public class MemberApplication {
    public static void main(String[] args) {
        SpringApplication.run(MemberApplication.class, args);
    }
}
