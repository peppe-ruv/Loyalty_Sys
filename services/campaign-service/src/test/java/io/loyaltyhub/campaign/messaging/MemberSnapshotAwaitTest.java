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
 * è restituito al listener, che fa {@code nack} invece di dormire (Q-489): qui si simulano le riconsegne e il tempo.
 */
class MemberSnapshotAwaitTest {

    private static final long[] DELAYS = {500, 1000, 2000};
    private static final String KEY = "lh.actions.v1-0@7";

    private final AtomicLong clock = new AtomicLong();

    @Test
    void knownMemberDoesNotWait() {
        MemberSnapshotAwait await = new MemberSnapshotAwait(id -> true, DELAYS, clock::get);

        assertThat(await.retryDelay(KEY, "MBR-000001")).isNull();
        assertThat(await.pendingCount()).isZero();
    }

    @Test
    void actionWithoutMemberDoesNotWait() {
        MemberSnapshotAwait await = new MemberSnapshotAwait(id -> false, DELAYS, clock::get);

        assertThat(await.retryDelay(KEY, null)).isNull();
        assertThat(await.pendingCount()).isZero();
    }

    @Test
    void snapshotArrivingDuringRetriesStopsTheWait() {
        AtomicInteger reads = new AtomicInteger();
        MemberSnapshotAwait await = new MemberSnapshotAwait(id -> reads.incrementAndGet() >= 3, DELAYS, clock::get);

        List<Duration> delays = deliverOnTimeUntilEvaluated(await, "MBR-000123");

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

        List<Duration> delays = deliverOnTimeUntilEvaluated(await, "MBR-999999");

        assertThat(delays).containsExactly(Duration.ofMillis(500), Duration.ofMillis(1000), Duration.ofMillis(2000));
        assertThat(reads).hasValue(4); // consegna iniziale + 3 riconsegne
        assertThat(clock.get()).isEqualTo(Duration.ofMillis(3500).toNanos());
    }

    /** m1: riconsegne anticipate (ribilanciamento) consumano i tentativi ma non il tempo: si attende quel che manca. */
    @Test
    void earlyRedeliveriesDoNotShortenTheWait() {
        MemberSnapshotAwait await = new MemberSnapshotAwait(id -> false, DELAYS, clock::get);

        assertThat(await.retryDelay(KEY, "MBR-000777")).isEqualTo(Duration.ofMillis(500));
        clock.addAndGet(Duration.ofMillis(100).toNanos()); // revoca: il record torna subito
        assertThat(await.retryDelay(KEY, "MBR-000777")).isEqualTo(Duration.ofMillis(1000));
        clock.addAndGet(Duration.ofMillis(100).toNanos());
        assertThat(await.retryDelay(KEY, "MBR-000777")).isEqualTo(Duration.ofMillis(2000));
        clock.addAndGet(Duration.ofMillis(100).toNanos());

        // Tentativi finiti dopo 0,3 s: la rinuncia arriva solo a 3,5 s dalla prima consegna.
        assertThat(await.retryDelay(KEY, "MBR-000777")).isEqualTo(Duration.ofMillis(3200));
        clock.addAndGet(Duration.ofMillis(3200).toNanos());
        assertThat(await.retryDelay(KEY, "MBR-000777")).isNull();
    }

    /** m2: dopo la rinuncia, se la valutazione fallisce e il record torna, non riparte un ciclo di attese. */
    @Test
    void exhaustedRecordIsNotWaitedAgainUntilAcknowledged() {
        MemberSnapshotAwait await = new MemberSnapshotAwait(id -> false, DELAYS, clock::get);
        deliverOnTimeUntilEvaluated(await, "MBR-000888");
        assertThat(await.pendingCount()).isEqualTo(1);

        // La valutazione (NO_MEMBER) fallisce in modo ritentabile: l'error handler riconsegna il record.
        assertThat(await.retryDelay(KEY, "MBR-000888")).isNull();
        assertThat(await.retryDelay(KEY, "MBR-000888")).isNull();

        await.done(KEY); // ack dopo la valutazione riuscita
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

    /** Riconsegne puntuali dello stesso record (il tempo avanza del ritardo chiesto) finché la valutazione può procedere. */
    private List<Duration> deliverOnTimeUntilEvaluated(MemberSnapshotAwait await, String memberId) {
        List<Duration> delays = new ArrayList<>();
        Duration d;
        while ((d = await.retryDelay(KEY, memberId)) != null) {
            delays.add(d);
            clock.addAndGet(d.toNanos());
            assertThat(delays).hasSizeLessThanOrEqualTo(DELAYS.length);
        }
        return delays;
    }
}
