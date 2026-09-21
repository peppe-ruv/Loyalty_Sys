package io.loyaltyhub.campaign;

import io.loyaltyhub.campaign.engine.CampaignEngine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * campaign-service — il motore regole (docs/04 §3, docs/servizi/campaign-service.md). M1.3: valuta le azioni
 * contro le campagne LIVE, decide gli effetti {@code points.grant} e li pubblica su {@code lh.effects.v1},
 * con registro di spiegabilità e simulazione.
 */
@SpringBootApplication
public class CampaignApplication {

    public static void main(String[] args) {
        SpringApplication.run(CampaignApplication.class, args);
    }

    /** Motore puro (docs/03 §3.5); il tetto del moltiplicatore complessivo è configurabile (default 5). */
    @Bean
    public CampaignEngine campaignEngine(@Value("${loyaltyhub.engine.max-multiplier:5}") int maxMultiplier) {
        return new CampaignEngine(maxMultiplier);
    }
}
