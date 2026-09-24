package io.loyaltyhub.member.application;

import io.loyaltyhub.common.event.LhEventFactory;
import io.loyaltyhub.common.event.LhEventTypes;
import io.loyaltyhub.common.event.LhSource;
import io.loyaltyhub.common.outbox.OutboxWriter;
import io.loyaltyhub.member.domain.Segment;
import io.loyaltyhub.member.domain.SegmentCriteria;
import io.loyaltyhub.member.domain.SegmentFacts;
import io.loyaltyhub.member.infra.SegmentFactsRepository;
import io.loyaltyhub.member.infra.SegmentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Ricalcolo dei segmenti (docs/servizi/member-service.md §5; docs/03 §10): confronta l'appartenenza precedente con quella
 * calcolata dai criteri ed emette {@code member.segment.entered/left} (EVT-FACT-06/07) <em>solo per le differenze</em>,
 * via outbox nella stessa transazione della scrittura di {@code segment_member}. Un segmento per transazione, con la
 * riga del segmento bloccata: ricalcoli concorrenti (API, job, reset) si serializzano.
 */
@Service
public class SegmentRefresher {

    private static final Logger log = LoggerFactory.getLogger(SegmentRefresher.class);
    private static final String SERVICE = "member";

    /** Esito del ricalcolo di un segmento. */
    public record Outcome(String code, int entered, int left, int total) {
    }

    private final SegmentRepository segments;
    private final SegmentFactsRepository facts;
    private final OutboxWriter outbox;
    private final LhEventFactory events;
    private final TransactionTemplate tx;
    private final Clock clock;

    public SegmentRefresher(SegmentRepository segments, SegmentFactsRepository facts, OutboxWriter outbox,
                            LhEventFactory events, TransactionTemplate tx, Clock clock) {
        this.segments = segments;
        this.facts = facts;
        this.outbox = outbox;
        this.events = events;
        this.tx = tx;
        this.clock = clock;
    }

    /** Ricalcola un segmento (API {@code POST /v1/segments/{id}/refresh}). */
    public Outcome refresh(String segmentId, Instant asOf, String actor) {
        List<SegmentFacts> all = facts.loadAll(asOf);
        return tx.execute(s -> refreshLocked(segmentId, all, asOf, actor, false));
    }

    /**
     * Ricalcola tutti i segmenti attivi (job, reset). Con {@code reannounce} emette {@code entered} per <em>tutte</em>
     * le appartenenze correnti, non solo le nuove: serve dopo un reset demo, quando gli snapshot degli altri servizi
     * sono stati azzerati (SPEC-GAP: Q-81).
     */
    public List<Outcome> refreshAll(Instant asOf, String actor, boolean reannounce) {
        List<SegmentFacts> all = facts.loadAll(asOf);
        List<Outcome> out = new ArrayList<>();
        for (Segment s : segments.active()) {
            out.add(tx.execute(st -> refreshLocked(s.id(), all, asOf, actor, reannounce)));
        }
        return out;
    }

    /** Anteprima senza scrivere: membri che soddisfano i criteri alla data {@code asOf}, in ordine di id. */
    public List<SegmentFacts> preview(tools.jackson.databind.JsonNode criteria, Instant asOf) {
        return facts.loadAll(asOf).stream().filter(m -> SegmentCriteria.matches(criteria, m, asOf)).toList();
    }

    private Outcome refreshLocked(String segmentId, List<SegmentFacts> all, Instant asOf, String actor, boolean reannounce) {
        Segment seg = segments.lock(segmentId).orElseThrow();
        Set<String> old = segments.memberIds(segmentId);
        if (!seg.isActive()) {
            return new Outcome(seg.code(), 0, 0, old.size());
        }
        Set<String> target;
        if (seg.isDynamic()) {
            target = new LinkedHashSet<>();
            for (SegmentFacts m : all) {
                if (SegmentCriteria.matches(seg.criteria(), m, asOf)) {
                    target.add(m.memberId());
                }
            }
        } else {
            target = old; // statico: l'elenco cambia solo da PUT /members
        }
        return apply(seg, old, target, actor, reannounce);
    }

    /**
     * Porta l'appartenenza di {@code seg} da {@code old} a {@code target} ed emette i fatti. Da chiamare in transazione
     * (usato anche dall'elenco manuale dei segmenti statici e dall'archiviazione).
     */
    Outcome apply(Segment seg, Set<String> old, Set<String> target, String actor, boolean reannounce) {
        Set<String> entered = new LinkedHashSet<>(target);
        entered.removeAll(old);
        Set<String> left = new LinkedHashSet<>(old);
        left.removeAll(target);
        Instant now = clock.instant();
        segments.addMembers(seg.id(), entered, now);
        segments.removeMembers(seg.id(), left);
        segments.markRefreshed(seg.id(), target.size(), now);
        for (String m : reannounce ? target : entered) {
            emit(LhEventTypes.Fact.MEMBER_SEGMENT_ENTERED, m, seg, actor);
        }
        for (String m : left) {
            emit(LhEventTypes.Fact.MEMBER_SEGMENT_LEFT, m, seg, actor);
        }
        if (!entered.isEmpty() || !left.isEmpty()) {
            log.info("Segmento {}: +{} −{} = {}", seg.code(), entered.size(), left.size(), target.size());
        }
        return new Outcome(seg.code(), entered.size(), left.size(), target.size());
    }

    /**
     * Dopo un reset demo: {@code left} per le appartenenze di prima che non esistono più (per codice di segmento), così
     * gli snapshot degli altri servizi non restano con segmenti "fantasma". Da chiamare in transazione.
     */
    public void announceLeftSince(Map<String, Set<String>> before, String actor) {
        Map<String, Segment> byCode = new LinkedHashMap<>();
        for (Segment s : segments.list(null, null, null)) {
            byCode.put(s.code(), s);
        }
        for (Map.Entry<String, Set<String>> e : before.entrySet()) {
            Segment now = byCode.get(e.getKey());
            Set<String> current = now == null ? Set.of() : segments.memberIds(now.id());
            for (String m : e.getValue()) {
                if (!current.contains(m)) {
                    emitLeft(m, e.getKey(), actor);
                }
            }
        }
    }

    /**
     * Ripete gli {@code entered} di tutte le appartenenze correnti dopo {@code delay} (SPEC-GAP: Q-81): al reset demo
     * i servizi si azzerano uno dopo l'altro (BO-30) e uno snapshot azzerato <em>dopo</em> il primo annuncio lo
     * perderebbe. I consumer sono idempotenti (aggiungere un segmento già presente non cambia nulla).
     */
    public void reannounceLater(Duration delay) {
        if (delay == null || delay.isZero() || delay.isNegative()) {
            return;
        }
        Thread.ofVirtual().name("segment-reannounce").start(() -> {
            try {
                Thread.sleep(delay);
                reannounceAll("system");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException e) {
                log.warn("Riannuncio dei segmenti non riuscito: {}", e.getMessage());
            }
        });
    }

    /** {@code entered} per tutte le appartenenze dei segmenti attivi, senza ricalcolare. */
    public int reannounceAll(String actor) {
        Integer n = tx.execute(st -> {
            int count = 0;
            for (Segment s : segments.active()) {
                for (String m : segments.memberIds(s.id())) {
                    emit(LhEventTypes.Fact.MEMBER_SEGMENT_ENTERED, m, s, actor);
                    count++;
                }
            }
            return count;
        });
        return n == null ? 0 : n;
    }

    private void emit(String type, String memberId, Segment seg, String actor) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("segmentCode", seg.code());
        data.put("segmentName", seg.name());
        outbox.write(events.newRoot(type, "member:" + memberId, data, LhSource.service(SERVICE), actor));
    }

    private void emitLeft(String memberId, String code, String actor) {
        outbox.write(events.newRoot(LhEventTypes.Fact.MEMBER_SEGMENT_LEFT, "member:" + memberId,
                Map.of("segmentCode", code), LhSource.service(SERVICE), actor));
    }
}
