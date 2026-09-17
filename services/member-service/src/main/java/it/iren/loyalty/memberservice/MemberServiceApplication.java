package it.iren.loyalty.memberservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Adesione, stato, consensi, etichette, referral, anniversari, anonimizzazione e portabilità GDPR (RF-68, RF-72, RF-73) */
@SpringBootApplication
@EnableScheduling
public class MemberServiceApplication {
    public static void main(String[] args) { SpringApplication.run(MemberServiceApplication.class, args); }
}
