package io.loyaltyhub.engagementservice;

import io.loyaltyhub.common.event.EventTypes;
import io.loyaltyhub.engagementservice.domain.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Definizioni di esempio in memoria; in produzione l'adattatore CMS legge le collezioni achievements, challenges, badges, leaderboards. */
@Configuration
public class EngagementConfig {
    @Bean
    @ConditionalOnMissingBean
    Definitions seedDefinitions() {
        Achievement selfReadingStreak = new Achievement("autolettura-3-mesi", "1", "Autolettura per 3 mesi di fila", true, "SELF_READING_SENT", List.of(),
                Achievement.Metric.OCCURRENCES, null, Achievement.Goal.streak(1, Achievement.Period.MONTH, 3), new Achievement.Limit(1, Achievement.Period.MONTH), new Achievement.Limit(1, Achievement.Period.YEAR));
        Achievement spend500 = new Achievement("spesa-500-90gg", "1", "500 € in 90 giorni", true, EventTypes.ACTION_TRANSACTION, List.of(),
                Achievement.Metric.ATTRIBUTE_SUM, EventTypes.ATTR_AMOUNT_EUR, Achievement.Goal.lastDays(500, 90), Achievement.Limit.NONE, Achievement.Limit.NONE);
        Challenge welcome = new Challenge("benvenuto-2027", "1", "Missione di benvenuto", true, null, null, Challenge.Availability.ALWAYS, Challenge.Visibility.EVERYONE,
                List.of(new Challenge.Milestone("m1", "Attiva la bolletta digitale", Challenge.Milestone.Kind.DIRECT, new Achievement("m1", "1", "m1", true, "DIGITAL_BILL_ACTIVATED", List.of(), Achievement.Metric.OCCURRENCES, null, Achievement.Goal.overall(1), Achievement.Limit.NONE, Achievement.Limit.NONE)),
                        new Challenge.Milestone("m2", "Invia un'autolettura", Challenge.Milestone.Kind.DIRECT, new Achievement("m2", "1", "m2", true, "SELF_READING_SENT", List.of(), Achievement.Metric.OCCURRENCES, null, Achievement.Goal.overall(1), Achievement.Limit.NONE, Achievement.Limit.NONE)),
                        new Challenge.Milestone("m3", "Presenta un amico che aderisce", Challenge.Milestone.Kind.REFERRAL, new Achievement("m3", "1", "m3", true, EventTypes.ACTION_MEMBER_ENROLLED, List.of(), Achievement.Metric.OCCURRENCES, null, Achievement.Goal.overall(1), Achievement.Limit.NONE, Achievement.Limit.NONE))),
                List.of(new Challenge.Rule("r1", Challenge.Rule.Trigger.CHALLENGE_COMPLETED, 1, List.of(new Challenge.Effect("ADD_UNITS", "PREMIO", 500, null, Map.of())))),
                new Challenge.Limit(1, Achievement.Period.TOTAL), 2027, "benvenuto");
        return new Definitions() {
            @Override public List<Achievement> achievements() { return List.of(selfReadingStreak, spend500); }
            @Override public List<Challenge> challenges() { return List.of(welcome); }
            @Override public List<Badge> badges() { return List.of(new Badge("benvenuto", "Benvenuto", "Missione di benvenuto completata", null, true, false), new Badge("early-adopter", "Early adopter", "Tra i primi 10.000 iscritti", null, true, false), new Badge("green", "Green", "Offerta green attiva", null, true, true)); }
            @Override public List<Leaderboard> leaderboards() {
                return List.of(new Leaderboard("punti-mese", "Classifica punti del mese", true, Leaderboard.Metric.UNITS_EARNED, "PREMIO", null, null, "provincia", 1000,
                        new Leaderboard.RewardingCycle(Achievement.Period.MONTH, List.of(new Leaderboard.RankReward(1, 1, null, "PREMIO", 1000, "campione"), new Leaderboard.RankReward(2, 10, null, "PREMIO", 200, null))), "EVERYONE"));
            }
        };
    }
}
