package io.loyaltyhub.campaign.messaging;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** Campaign §5: azione per membro assente dallo snapshot → 3 ritentativi, poi la valutazione (NO_MEMBER). */
class MemberSnapshotAwaitTest {

    private final List<Long> slept = new ArrayList<>();

    @Test
    void knownMemberDoesNotWait() {
        MemberSnapshotAwait await = new MemberSnapshotAwait(id -> true, new long[]{500, 1000, 2000}, slept::add);

        assertThat(await.await("MBR-000001")).isTrue();
        assertThat(slept).isEmpty();
    }

    @Test
    void actionWithoutMemberDoesNotWait() {
        MemberSnapshotAwait await = new MemberSnapshotAwait(id -> false, new long[]{500, 1000, 2000}, slept::add);

        assertThat(await.await(null)).isTrue();
        assertThat(slept).isEmpty();
    }

    @Test
    void snapshotArrivingDuringRetriesStopsTheWait() {
        AtomicInteger reads = new AtomicInteger();
        MemberSnapshotAwait await = new MemberSnapshotAwait(id -> reads.incrementAndGet() >= 3,
                new long[]{500, 1000, 2000}, slept::add);

        assertThat(await.await("MBR-000123")).isTrue();
        assertThat(slept).containsExactly(500L, 1000L);
    }

    @Test
    void missingSnapshotGivesUpAfterThreeRetries() {
        AtomicInteger reads = new AtomicInteger();
        MemberSnapshotAwait await = new MemberSnapshotAwait(id -> {
            reads.incrementAndGet();
            return false;
        }, new long[]{500, 1000, 2000}, slept::add);

        assertThat(await.await("MBR-999999")).isFalse();
        assertThat(slept).containsExactly(500L, 1000L, 2000L);
        assertThat(reads).hasValue(4); // lettura iniziale + 3 ritentativi
    }
}
