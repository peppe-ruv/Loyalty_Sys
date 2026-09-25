package io.loyaltyhub.wallet.application;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import io.loyaltyhub.common.audit.AuditEntry;
import io.loyaltyhub.common.audit.AuditPublisher;
import io.loyaltyhub.common.event.LhEventFactory;
import io.loyaltyhub.common.event.LhEventTypes;
import io.loyaltyhub.common.ids.Ulid;
import io.loyaltyhub.common.outbox.OutboxWriter;
import io.loyaltyhub.common.web.LhException;
import io.loyaltyhub.wallet.domain.Edition;
import io.loyaltyhub.wallet.domain.EditionCloseRule;
import io.loyaltyhub.wallet.domain.MemberTier;
import io.loyaltyhub.wallet.domain.Tier;
import io.loyaltyhub.wallet.domain.TierHistory;
import io.loyaltyhub.wallet.infra.EditionRepository;
import io.loyaltyhub.wallet.infra.MemberTierRepository;
import io.loyaltyhub.wallet.infra.TierHistoryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Esecuzione della chiusura di edizione (F-TIER-04/05). L'applicazione è <b>un'unica transazione</b>:
 * <ul>
 *   <li>la riga {@code edition} è bloccata ({@code FOR UPDATE}) e il suo stato ricontrollato sotto lock, così due
 *       chiusure concorrenti si serializzano e la seconda riceve {@code EDITION_ALREADY_CLOSED};</li>
 *   <li>i {@code member_tier} sono letti a pagine di {@value #PAGE_SIZE} con {@code FOR UPDATE}: un accredito STS
 *       concorrente aspetta il commit e si somma poi al {@code period_sts} azzerato, invece di essere perso;</li>
 *   <li>un errore a metà annulla tutto (livelli, storico, fatti in outbox, stato edizione): ripetere la chiusura
 *       riparte da dati integri, senza discese doppie.</li>
 * </ul>
 * L'anteprima ({@code dryRun}) è in sola lettura e non prende lock.
 */
@Service
public class EditionCloseBatchService {

    static final int PAGE_SIZE = 200;

    private final EditionRepository editions;
    private final MemberTierRepository memberTiers;
    private final TierHistoryRepository tierHistory;
    private final LhEventFactory events;
    private final OutboxWriter outbox;
    private final AuditPublisher audit;
    private final ObjectMapper mapper;
    private final Clock clock;

    public EditionCloseBatchService(EditionRepository editions,
                                    MemberTierRepository memberTiers,
                                    TierHistoryRepository tierHistory,
                                    LhEventFactory events, OutboxWriter outbox,
                                    AuditPublisher audit, ObjectMapper mapper, Clock clock) {
        this.editions = editions;
        this.memberTiers = memberTiers;
        this.tierHistory = tierHistory;
        this.events = events;
        this.outbox = outbox;
        this.audit = audit;
        this.mapper = mapper;
        this.clock = clock;
    }

    public record BatchResult(int retained, int downgraded, List<EditionService.ClosePreviewMember> previewMembers) {}

    /** Anteprima: calcola gli esiti senza scrivere né bloccare. */
    @Transactional(readOnly = true)
    public BatchResult preview(String code, List<Tier> scale) {
        requireActive(editions.findByCode(code), code);
        return processAll(scale, code, false);
    }

    /** Applicazione atomica e serializzata della chiusura (vedi Javadoc di classe). */
    @Transactional
    public BatchResult apply(String code, List<Tier> scale) {
        Edition closing = requireActive(editions.lockByCode(code), code);
        BatchResult result = processAll(scale, code, true);
        finalizeClose(closing, result.retained(), result.downgraded());
        return result;
    }

    private static Edition requireActive(java.util.Optional<Edition> edition, String code) {
        Edition e = edition.orElseThrow(() -> LhException.notFound("Edizione non trovata: " + code));
        if (Edition.CLOSED.equals(e.status())) {
            throw LhException.validation("EDITION_ALREADY_CLOSED", "L'edizione " + code + " è già chiusa.");
        }
        if (!Edition.ACTIVE.equals(e.status())) {
            throw LhException.validation("EDITION_NOT_ACTIVE", "Si può chiudere solo un'edizione attiva.");
        }
        return e;
    }

    private BatchResult processAll(List<Tier> scale, String code, boolean apply) {
        int retained = 0;
        int downgraded = 0;
        List<EditionService.ClosePreviewMember> members = new ArrayList<>();
        String after = null;
        while (true) {
            List<MemberTier> page = memberTiers.findActiveMembersAfter(after, PAGE_SIZE, apply);
            if (page.isEmpty()) {
                break;
            }
            BatchResult r = processBatch(page, scale, code, !apply);
            retained += r.retained();
            downgraded += r.downgraded();
            members.addAll(r.previewMembers());
            after = page.getLast().memberId();
        }
        return new BatchResult(retained, downgraded, members);
    }

    /** Calcola (e, se non {@code dryRun}, applica) la discesa morbida per una pagina; gira nella transazione del chiamante. */
    BatchResult processBatch(List<MemberTier> batch, List<Tier> scale, String editionCode, boolean dryRun) {
        int retained = 0;
        int downgraded = 0;
        List<EditionService.ClosePreviewMember> membersPreview = new ArrayList<>();

        for (MemberTier mt : batch) {
            EditionCloseRule.Result next = EditionCloseRule.computeNext(mt.tierCode(), mt.periodSts(), scale);

            membersPreview.add(new EditionService.ClosePreviewMember(mt.memberId(), mt.tierCode(), mt.periodSts(), next.earnedTier(), next.newTier(), next.outcome()));

            if (next.outcome() == EditionCloseRule.Outcome.RETAINED) {
                retained++;
            } else {
                downgraded++;
            }

            if (!dryRun) {
                if (next.outcome() == EditionCloseRule.Outcome.RETAINED) {
                    memberTiers.resetPeriodSts(mt.memberId());
                } else {
                    memberTiers.updateTierAndResetSts(mt.memberId(), next.newTier(), mt.tierCode());
                }

                String kind = next.outcome() == EditionCloseRule.Outcome.RETAINED ? TierHistory.RETAIN : TierHistory.DOWNGRADE;
                tierHistory.insert(new TierHistory(Ulid.next(clock), mt.memberId(), mt.tierCode(), next.newTier(), kind, editionCode, clock.instant()));

                ObjectNode data = mapper.createObjectNode();
                if (next.outcome() == EditionCloseRule.Outcome.DOWNGRADED) {
                    data.put("previousTier", mt.tierCode());
                    data.put("newTier", next.newTier());
                    data.put("editionCode", editionCode);
                    outbox.write(events.newRoot(LhEventTypes.Fact.TIER_DOWNGRADED, "member:" + mt.memberId(), data));
                } else {
                    data.put("tier", next.newTier());
                    data.put("editionCode", editionCode);
                    outbox.write(events.newRoot(LhEventTypes.Fact.TIER_RETAINED, "member:" + mt.memberId(), data));
                }
            }
        }
        return new BatchResult(retained, downgraded, membersPreview);
    }

    /**
     * Edizione che segue {@code closing} (docs/03 §4.3: «edizione → CLOSED; la successiva → ACTIVE»): la
     * {@code PLANNED} con l'inizio più vicino dopo la fine di quella chiusa. Una {@code PLANNED} precedente non è «la
     * successiva» e resta com'è; nessuna successiva → nessuna attivazione.
     */
    static java.util.Optional<Edition> nextEdition(Edition closing, List<Edition> all) {
        java.time.LocalDate after = closing.endDate() != null ? closing.endDate() : closing.startDate();
        return all.stream()
                .filter(e -> Edition.PLANNED.equals(e.status()))
                .filter(e -> e.startDate() != null && after != null && e.startDate().isAfter(after))
                .min(Comparator.comparing(Edition::startDate));
    }

    private void finalizeClose(Edition closing, int totalRetained, int totalDowngraded) {
        String code = closing.code();
        editions.updateStatus(code, Edition.CLOSED);

        Edition nextEdition = nextEdition(closing, editions.findAll()).orElse(null);

        if (nextEdition != null) {
            editions.updateStatus(nextEdition.code(), Edition.ACTIVE);
        }

        ObjectNode data = mapper.createObjectNode();
        data.put("editionCode", code);
        data.put("retained", totalRetained);
        data.put("downgraded", totalDowngraded);
        outbox.write(events.newRoot(LhEventTypes.Fact.EDITION_CLOSED, "edition:" + code, data));

        audit.record("edition", code, AuditEntry.Action.TRANSITION, "Chiusa edizione " + code,
                Map.of("status", Edition.ACTIVE), Map.of("status", Edition.CLOSED));
    }
}
