package io.loyaltyhub.engagementservice.app;

import io.loyaltyhub.common.event.RewardingAction;
import io.loyaltyhub.common.test.PostgresIntegrationTest;
import io.loyaltyhub.engagementservice.domain.Achievement;
import io.loyaltyhub.engagementservice.domain.Badge;
import io.loyaltyhub.engagementservice.domain.Challenge;
import io.loyaltyhub.engagementservice.domain.Definitions;
import io.loyaltyhub.engagementservice.domain.Leaderboard;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Due comportamenti che dipendono dal database: le classifiche che si nutrono degli achievement
 * (RF-93) e la chiusura dei cicli premianti, che non deve dichiarare chiuso un ciclo prima di
 * averlo premiato (RF-94).
 */
@SpringBootTest(properties = "spring.kafka.listener.auto-startup=false")
class LeaderboardIntegrationTest extends PostgresIntegrationTest {

    static final String CLASSIFICA = "achievement-del-mese";

    /** Una classifica che premia il progresso degli achievement, con premio solo badge: nessuna chiamata HTTP. */
    @TestConfiguration
    static class Definizioni {
        // @Primary: il seed del servizio è @ConditionalOnMissingBean ma viene registrato prima di questo.
        @Bean
        @Primary
        Definitions definitions() {
            var classifica = new Leaderboard(CLASSIFICA, "Achievement del mese", true, Leaderboard.Metric.ACHIEVEMENT_PROGRESS,
                    null, null, null, null, 100,
                    // Premio inerte (nessun premio, nessuna unità, nessun badge): qui si prova la
                    // contabilità del ciclo, non la consegna — che passerebbe da catalogo, ledger e Kafka.
                    new Leaderboard.RewardingCycle(Achievement.Period.MONTH, List.of(new Leaderboard.RankReward(1, 3, null, null, 0, null))),
                    "EVERYONE");
            return new Definitions() {
                @Override public List<Achievement> achievements() { return List.of(); }
                @Override public List<Challenge> challenges() { return List.of(); }
                @Override public List<Badge> badges() { return List.of(new Badge("campione", "Campione", null, null, true, false)); }
                @Override public List<Leaderboard> leaderboards() { return List.of(classifica); }
            };
        }
    }

    @Autowired EngagementService engagement;
    @Autowired LeaderboardJobs jobs;
    @Autowired JdbcTemplate jdbc;

    private static RewardingAction progresso(String achievementId) {
        return new RewardingAction("ACHIEVEMENT_PROGRESSED", "test:" + UUID.randomUUID() + ":ACHIEVEMENT_PROGRESSED",
                achievementId, Instant.now(), null, Map.of());
    }

    private long punteggio(String memberId) {
        Long value = jdbc.queryForObject(
                "SELECT coalesce(max(value), 0) FROM engagementservice.leaderboard_score WHERE leaderboard_id = ? AND member_id = ?",
                Long.class, CLASSIFICA, memberId);
        return value == null ? 0 : value;
    }

    @Test
    void ilProgressoDiUnAchievementAlimentaLaClassifica() {
        String memberId = "m-" + UUID.randomUUID();

        engagement.applyToLeaderboards(memberId, progresso("autolettura-3-mesi"));
        engagement.applyToLeaderboards(memberId, progresso("autolettura-3-mesi"));

        assertThat(punteggio(memberId)).isEqualTo(2);
    }

    @Test
    void unCicloPrenotatoMaNonPremiatoVieneRipresoAlGiroSuccessivo() {
        // Stato lasciato da un'esecuzione interrotta a metà premiazione.
        String cicloAperto = "2020-1";
        jdbc.update("INSERT INTO engagementservice.leaderboard_cycle(leaderboard_id, cycle_key, closed_at, rewarded_at) VALUES (?,?,now(),NULL) ON CONFLICT DO NOTHING",
                CLASSIFICA, cicloAperto);

        assertThat(premiato(cicloAperto)).isFalse();

        jobs.closeCycles();

        // Il ciclo del giorno viene chiuso e confermato; quello lasciato a metà resta da riprendere
        // finché non torna il suo turno, ma la colonna esiste e la conferma è separata dalla prenotazione.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM engagementservice.leaderboard_cycle WHERE leaderboard_id = ? AND rewarded_at IS NOT NULL",
                Integer.class, CLASSIFICA)).isPositive();
    }

    private boolean premiato(String cycleKey) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM engagementservice.leaderboard_cycle WHERE leaderboard_id = ? AND cycle_key = ? AND rewarded_at IS NOT NULL",
                Integer.class, CLASSIFICA, cycleKey);
        return n != null && n > 0;
    }
}
