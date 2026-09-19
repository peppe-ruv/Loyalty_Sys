package io.loyaltyhub.common.ids;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class UlidTest {

    @Test
    void has26CrockfordChars() {
        String ulid = Ulid.next();
        assertThat(ulid).hasSize(26);
        assertThat(ulid).matches("[0-9A-HJKMNP-TV-Z]{26}");
    }

    @Test
    void isUniqueAndMonotonicWithinSameMillisecond() {
        Set<String> seen = new HashSet<>();
        String previous = null;
        for (int i = 0; i < 10_000; i++) {
            String ulid = Ulid.next(1_700_000_000_000L); // stesso timestamp per tutti
            assertThat(seen.add(ulid)).as("ULID unico").isTrue();
            if (previous != null) {
                assertThat(ulid).as("monotòno crescente").isGreaterThan(previous);
            }
            previous = ulid;
        }
    }

    @Test
    void sortsByTime() {
        String earlier = Ulid.next(1_000L);
        String later = Ulid.next(2_000L);
        assertThat(later).isGreaterThan(earlier);
    }
}
