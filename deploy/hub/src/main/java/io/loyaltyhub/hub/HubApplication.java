package io.loyaltyhub.hub;

import io.loyaltyhub.campaign.CampaignApplication;
import io.loyaltyhub.campaign.engine.CampaignEngine;
import io.loyaltyhub.ingestion.IngestionApplication;
import io.loyaltyhub.insight.InsightApplication;
import io.loyaltyhub.reward.RewardApplication;
import io.loyaltyhub.member.MemberApplication;
import io.loyaltyhub.wallet.WalletApplication;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.FullyQualifiedAnnotationBeanNameGenerator;

/**
 * Deployable <strong>consolidato</strong> per la demo ospitata (docs/13 ADR-023): avvia i 4 servizi del core
 * loop (ingestion, member, campaign, wallet) più <strong>insight</strong> (event store + stream SSE del rail
 * live, M2.8 anticipato) e <strong>reward</strong> (catalogo premi, M4) in un solo JVM, mantenendo i confini del codice, uno schema per servizio (search_path
 * multiplo) e un gruppo consumer per servizio. Fuori dalla demo ogni servizio resta un deployable a sé.
 *
 * <p>{@code @SpringBootApplication} scansiona il pacchetto {@code io.loyaltyhub.hub}; una seconda {@code @ComponentScan}
 * porta i bean dei 4 servizi con <strong>nomi pienamente qualificati</strong> (i servizi hanno classi omonime,
 * es. {@code ActionsListener}, che con i nomi brevi collidono). Le classi {@code *Application} dei servizi sono
 * escluse (sarebbero @SpringBootApplication annidate); {@code campaignEngine} — l'unico bean applicativo che
 * viveva in una di esse — è ridichiarato qui. I bean di {@code io.loyaltyhub.common} arrivano dall'auto-config,
 * non dallo scan. La config è {@code hub.yml} (non {@code application.yml}, per non collidere con quelle dei servizi).
 */
@SpringBootApplication
@ComponentScan(
        basePackages = {
                "io.loyaltyhub.hub",
                "io.loyaltyhub.ingestion",
                "io.loyaltyhub.member",
                "io.loyaltyhub.campaign",
                "io.loyaltyhub.wallet",
                "io.loyaltyhub.insight",
                "io.loyaltyhub.reward",
        },
        nameGenerator = FullyQualifiedAnnotationBeanNameGenerator.class,
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = {IngestionApplication.class, MemberApplication.class,
                        CampaignApplication.class, WalletApplication.class, InsightApplication.class,
                        RewardApplication.class}))
public class HubApplication {

    public static void main(String[] args) {
        new SpringApplicationBuilder(HubApplication.class)
                .properties("spring.config.name=hub")
                .run(args);
    }

    /** Motore regole (viveva in {@code CampaignApplication}, esclusa dallo scan). */
    @Bean
    public CampaignEngine campaignEngine(@Value("${loyaltyhub.engine.max-multiplier:5}") int maxMultiplier) {
        return new CampaignEngine(maxMultiplier);
    }
}
