package io.loyaltyhub.contestservice.wheel;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Optional;

/** Ruota di esempio in memoria (in produzione dalla collezione fortune-wheels del CMS): premi a valore nullo, quindi in modalità PROBABILITY. */
@Configuration
public class WheelConfig {
    @Bean @ConditionalOnMissingBean
    WheelService.WheelSource seedWheels() {
        FortuneWheel demo = new FortuneWheel("ruota-punti", "Ruota dei punti", true, FortuneWheel.Mode.PROBABILITY, null, List.of(
                new FortuneWheel.Slot("s1", "10 punti", 50, null, "PREMIO", 10, -1, true),
                new FortuneWheel.Slot("s2", "50 punti", 20, null, "PREMIO", 50, -1, true),
                new FortuneWheel.Slot("s3", "200 punti", 5, null, "PREMIO", 200, 1000, true),
                new FortuneWheel.Slot("s4", "Ritenta", 25, null, null, 0, -1, false)),
                "PREMIO", 0, 1, Period.DAY, null, null, 500_000, 1, "EVERYONE");
        return new WheelService.WheelSource() {
            @Override public Optional<FortuneWheel> byId(String id) { return demo.id().equals(id) ? Optional.of(demo) : Optional.empty(); }
            @Override public List<FortuneWheel> all() { return List.of(demo); }
        };
    }
}
