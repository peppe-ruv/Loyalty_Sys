package it.iren.loyalty.fraudservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Fraud-service (RF-131): osserva azioni, movimenti, riscatti e giocate, calcola segnali configurabili nel backoffice
 * (frequenza riscatti, account per dispositivo, accumuli anomali, account appena creati, viaggi impossibili, abuso di
 * codici, rapporto resi, anomalie dispositivo, velocità) e produce riskScore, riskLevel e reasonCodes per membro.
 * Pubblica RISK_V1 a ogni cambio di livello; con il blocco automatico congela le unità sul ledger. Le decisioni di
 * marketing restano al decision-service, che legge il livello di rischio dal Customer 360.
 */
@SpringBootApplication
@EnableScheduling
public class FraudServiceApplication {
    public static void main(String[] args) { SpringApplication.run(FraudServiceApplication.class, args); }
}
