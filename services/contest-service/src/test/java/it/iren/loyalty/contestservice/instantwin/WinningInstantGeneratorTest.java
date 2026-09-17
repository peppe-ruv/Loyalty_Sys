package it.iren.loyalty.contestservice.instantwin;

import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WinningInstantGeneratorTest {
    private final Instant start = Instant.parse("2027-01-01T00:00:00Z");
    private final Instant end = Instant.parse("2027-01-31T00:00:00Z");

    @Test
    void generatesDistinctSortedInstantsInsidePeriod() {
        var g = new WinningInstantGenerator();
        List<Instant> instants = g.generate(start, end, 1000);
        assertThat(instants).hasSize(1000).isSorted().allSatisfy(i -> assertThat(i).isBetween(start, end));
        assertThat(new HashSet<>(instants)).hasSize(1000);
    }

    @Test
    void weightedSlotsFollowWeights() {
        var g = new WinningInstantGenerator();
        var day = new WinningInstantGenerator.Slot(start, start.plusSeconds(12 * 3600), 9.0);
        var night = new WinningInstantGenerator.Slot(start.plusSeconds(12 * 3600), start.plusSeconds(24 * 3600), 1.0);
        List<Instant> instants = g.generate(List.of(day, night), 10_000);
        long inDay = instants.stream().filter(i -> i.isBefore(day.to())).count();
        assertThat(inDay).isBetween(8_700L, 9_300L);
    }

    @Test
    void sameSeedSameInstants() throws Exception {
        SecureRandom a = SecureRandom.getInstance("SHA1PRNG"); a.setSeed(42L);
        SecureRandom b = SecureRandom.getInstance("SHA1PRNG"); b.setSeed(42L);
        assertThat(new WinningInstantGenerator(a).generate(start, end, 50)).isEqualTo(new WinningInstantGenerator(b).generate(start, end, 50));
    }

    @Test
    void rejectsImpossibleInput() {
        var g = new WinningInstantGenerator();
        assertThatThrownBy(() -> g.generate(start, start.plusMillis(3), 10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> g.generate(start, end, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void hashChainDetectsTampering() {
        String h1 = PlayLedger.hash(null, "p1", "m1", 1000L, "LOST", null);
        String h2 = PlayLedger.hash(h1, "p2", "m2", 2000L, "WON", "i1");
        String tampered = PlayLedger.hash(PlayLedger.hash(null, "p1", "m1", 1001L, "LOST", null), "p2", "m2", 2000L, "WON", "i1");
        assertThat(h2).hasSize(64).isNotEqualTo(tampered);
    }
}
