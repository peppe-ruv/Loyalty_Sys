package io.loyaltyhub.campaign.messaging;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Campaign §5: azione per membro assente dallo snapshot → 3 ritentativi, poi la valutazione (NO_MEMBER). Il ritardo
 * è restituito al listener, che fa {@code nack} invece di dormire (Q-489): qui si simulano le riconsegne.
 */
class MemberSnapshotAwaitTest {

    private static final long[] DELAYS = {500, 1000, 2000};

    private final AtomicLong clock = new AtomicLong();

    @Test
    void knownMemberDoesNotWait() {
        MemberSnapshotAwait await = new MemberSnapshotAwait(id -> true, DELAYS, clock::get);

        assertThat(await.retryDelay("lh.actions.v1-0@1", "MBR-000001")).isNull();
        assertThat(await.pendingCount()).isZero();
    }

    @Test
    void actionWithoutMemberDoesNotWait() {
        MemberSnapshotAwait await = new MemberSnapshotAwait(id -> false, DELAYS, clock::get);

        assertThat(await.retryDelay("lh.actions.v1-0@1", null)).isNull();
        assertThat(await.pendingCount()).isZero();
    }

    @Test
    void snapshotArrivingDuringRetriesStopsTheWait() {
        AtomicInteger reads = new AtomicInteger();
        MemberSnapshotAwait await = new MemberSnapshotAwait(id -> reads.incrementAndGet() >= 3, DELAYS, clock::get);

        List<Duration> delays = deliverUntilEvaluated(await, "lh.actions.v1-0@7", "MBR-000123");

        assertThat(delays).containsExactly(Duration.ofMillis(500), Duration.ofMillis(1000));
        assertThat(await.pendingCount()).isZero();
    }

    @Test
    void missingSnapshotGivesUpAfterThreeRetries() {
        AtomicInteger reads = new AtomicInteger();
        MemberSnapshotAwait await = new MemberSnapshotAwait(id -> {
            reads.incrementAndGet();
            return false;
        }, DELAYS, clock::get);

        List<Duration> delays = deliverUntilEvaluated(await, "lh.actions.v1-1@3", "MBR-999999");

        assertThat(delays).containsExactly(Duration.ofMillis(500), Duration.ofMillis(1000), Duration.ofMillis(2000));
        assertThat(reads).hasValue(4); // consegna iniziale + 3 riconsegne
        assertThat(await.pendingCount()).isZero();
    }

    @Test
    void attemptsAreCountedPerRecord() {
        MemberSnapshotAwait await = new MemberSnapshotAwait(id -> false, DELAYS, clock::get);

        assertThat(await.retryDelay("lh.actions.v1-0@1", "MBR-000500")).isEqualTo(Duration.ofMillis(500));
        assertThat(await.retryDelay("lh.actions.v1-1@1", "MBR-000501")).isEqualTo(Duration.ofMillis(500));
        assertThat(await.retryDelay("lh.actions.v1-0@1", "MBR-000500")).isEqualTo(Duration.ofMillis(1000));
        assertThat(await.pendingCount()).isEqualTo(2);
    }

    @Test
    void staleAttemptsOfRecordsMovedElsewhereArePruned() {
        MemberSnapshotAwait await = new MemberSnapshotAwait(id -> false, DELAYS, clock::get);
        for (int i = 0; i <= 256; i++) {
            await.retryDelay("lh.actions.v1-0@" + i, "MBR-" + i);
        }
        assertThat(await.pendingCount()).isEqualTo(257);

        clock.addAndGet(Duration.ofMinutes(11).toNanos());
        await.retryDelay("lh.actions.v1-1@0", "MBR-NEW");

        assertThat(await.pendingCount()).isEqualTo(1);
    }

    /** Consegne dello stesso record finché la valutazione può procedere; restituisce i ritardi dei nack. */
    private static List<Duration> deliverUntilEvaluated(MemberSnapshotAwait await, String key, String memberId) {
        List<Duration> delays = new ArrayList<>();
        Duration d;
        while ((d = await.retryDelay(key, memberId)) != null) {
            delays.add(d);
            assertThat(delays).hasSizeLessThanOrEqualTo(DELAYS.length);
        }
        return delays;
    }
}
