package io.loyaltyhub.reward.domain;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.SplittableRandom;

import static org.assertj.core.api.Assertions.assertThat;

/** Generazione dei codici coupon (F-CPN-01, Q-614): riproducibile dal seme in demo, imprevedibile altrove. */
class CouponCodesTest {

    private static final String FORMAT = "^[A-Z0-9]{2,10}-[A-HJKMNP-Z2-9]{4}-[A-HJKMNP-Z2-9]{4}$";

    /**
     * Codici calcolati con l'algoritmo di prima di Q-614 (seme = hash del codice del pool, batchSeed con 0 codici
     * presenti): il reset demo deve produrre esattamente questi (docs/10 §1.3).
     */
    @Test
    void seededCodesAreTheDemoCodesOfAlwaysAndReproducible() {
        assertThat(firstThree("CAF", -4898811249275050660L, 0))
                .containsExactly("CAF-ATB2-DDFW", "CAF-YXN5-GCVM", "CAF-W67N-HJHC");
        assertThat(firstThree("SHP25", -3837871757373026094L, 0))
                .containsExactly("SHP25-X8H6-96VH", "SHP25-FUQN-XVN5", "SHP25-55F9-TYTD");
        assertThat(firstThree("CIN", -4898811249275050404L, 0))
                .containsExactly("CIN-Q597-AHHZ", "CIN-RTWW-XJ7D", "CIN-763F-TENC");
        // Seconda generazione dello stesso pool (600 codici già presenti): altro seme, altra sequenza, sempre uguale.
        assertThat(firstThree("CAF", -4898811249275050660L, 600).subList(0, 2))
                .containsExactly("CAF-CWAW-USFV", "CAF-Q85N-7XJ4");
    }

    @Test
    void theRandomSourceIsInjected() {
        CouponCodes viaFactory = CouponCodes.seeded("CAF", 42L);
        CouponCodes viaConstructor = new CouponCodes("CAF", new SplittableRandom(42L));
        for (int i = 0; i < 50; i++) {
            assertThat(viaFactory.next()).isEqualTo(viaConstructor.next());
        }
    }

    @Test
    void secureCodesDifferBetweenGenerationsAndKeepFormatAlphabetAndUniqueness() {
        List<String> first = generate(CouponCodes.secure("CAF"), 2000);
        List<String> second = generate(CouponCodes.secure("CAF"), 2000);
        assertThat(first).isNotEqualTo(second);
        assertThat(first.getFirst()).isNotEqualTo(second.getFirst());
        Set<String> all = new HashSet<>(first);
        all.addAll(second);
        assertThat(all).as("nessuna collisione su 4000 codici").hasSize(4000);
        assertThat(all).allSatisfy(c -> assertThat(c).hasSize(3 + 1 + 4 + 1 + 4).matches(FORMAT));
    }

    @Test
    void seededCodesKeepFormatAlphabetAndLength() {
        assertThat(generate(CouponCodes.seeded("SHP25", 7L), 500))
                .allSatisfy(c -> assertThat(c).hasSize(5 + 1 + 4 + 1 + 4).matches(FORMAT));
    }

    private static List<String> firstThree(String prefix, long poolSeed, long existing) {
        return generate(CouponCodes.seeded(prefix, CouponCodes.batchSeed(poolSeed, existing)), 3);
    }

    private static List<String> generate(CouponCodes codes, int n) {
        return java.util.stream.IntStream.range(0, n).mapToObj(i -> codes.next()).toList();
    }
}
