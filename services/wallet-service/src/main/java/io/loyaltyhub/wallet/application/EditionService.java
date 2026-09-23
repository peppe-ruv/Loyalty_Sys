package io.loyaltyhub.wallet.application;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import io.loyaltyhub.common.audit.AuditEntry;
import io.loyaltyhub.common.audit.AuditPublisher;
import io.loyaltyhub.common.event.LhEventFactory;
import io.loyaltyhub.common.event.LhEventTypes;
import io.loyaltyhub.common.outbox.OutboxWriter;
import io.loyaltyhub.common.web.LhException;
import io.loyaltyhub.wallet.domain.Edition;
import io.loyaltyhub.wallet.domain.EditionCloseRule;
import io.loyaltyhub.wallet.domain.MemberTier;
import io.loyaltyhub.wallet.domain.Tier;
import io.loyaltyhub.wallet.infra.EditionRepository;
import io.loyaltyhub.wallet.infra.MemberTierRepository;
import io.loyaltyhub.wallet.infra.TierRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Gestione edizioni (docs/servizi/wallet-service.md §3). */
@Service
public class EditionService {

    private static final Logger log = LoggerFactory.getLogger(EditionService.class);

    private final EditionRepository editions;
    private final MemberTierRepository memberTiers;
    private final TierRepository tiers;
    private final EditionCloseBatchService batchService;
    private final LhEventFactory events;
    private final OutboxWriter outbox;
    private final AuditPublisher audit;
    private final ObjectMapper mapper;

    public EditionService(EditionRepository editions, MemberTierRepository memberTiers,
                          TierRepository tiers, EditionCloseBatchService batchService,
                          LhEventFactory events, OutboxWriter outbox, AuditPublisher audit,
                          ObjectMapper mapper) {
        this.editions = editions;
        this.memberTiers = memberTiers;
        this.tiers = tiers;
        this.batchService = batchService;
        this.events = events;
        this.outbox = outbox;
        this.audit = audit;
        this.mapper = mapper;
    }

    public List<Edition> list() {
        return editions.findAll();
    }

    public record EditionUpdate(String code, String name, LocalDate startDate, LocalDate endDate, LocalDate redemptionGraceUntil) {}

    @Transactional
    public Edition create(EditionUpdate req) {
        if (editions.findByCode(req.code()).isPresent()) {
            throw LhException.conflict("EDITION_EXISTS", "L'edizione " + req.code() + " esiste già.");
        }
        validateDates(req.startDate(), req.endDate(), req.code());

        editions.upsert(req.code(), req.name(), req.startDate(), req.endDate(), req.redemptionGraceUntil(), Edition.PLANNED);
        audit.record("edition", req.code(), AuditEntry.Action.CREATE, "Creata edizione " + req.code(), Map.of(), req);

        return editions.findByCode(req.code()).orElseThrow();
    }

    @Transactional
    public Edition update(String code, EditionUpdate req) {
        Edition existing = editions.findByCode(code)
                .orElseThrow(() -> LhException.notFound("Edizione non trovata: " + code));

        LocalDate start = req.startDate() != null ? req.startDate() : existing.startDate();
        LocalDate end = req.endDate() != null ? req.endDate() : existing.endDate();

        validateDates(start, end, code);

        editions.upsert(code,
                req.name() != null ? req.name() : existing.name(),
                start, end,
                req.redemptionGraceUntil() != null ? req.redemptionGraceUntil() : existing.redemptionGraceUntil(),
                existing.status());

        audit.record("edition", code, AuditEntry.Action.UPDATE, "Aggiornata edizione " + code,
                Map.of("startDate", existing.startDate(), "endDate", existing.endDate()),
                Map.of("startDate", start, "endDate", end));

        return editions.findByCode(code).orElseThrow();
    }

    private void validateDates(LocalDate start, LocalDate end, String currentCode) {
        if (start.isAfter(end)) {
            throw LhException.validation("EDITION_DATES_INVALID", "La data di inizio non può essere successiva alla data di fine.");
        }
        for (Edition e : editions.findAll()) {
            if (!e.code().equals(currentCode)) {
                if (!(end.isBefore(e.startDate()) || start.isAfter(e.endDate()))) {
                    throw LhException.validation("EDITION_OVERLAP", "L'edizione si sovrappone a " + e.code());
                }
            }
        }
    }

    public record ClosePreviewMember(String memberId, String currentTier, long periodSts, String earnedTier, String newTier, EditionCloseRule.Outcome outcome) {}
    public record ClosePreviewSummary(int retained, int downgraded) {}
    public record ClosePreviewResult(ClosePreviewSummary summary, List<ClosePreviewMember> members) {}

    public ClosePreviewResult closeEdition(String code, boolean dryRun) {
        Edition editionToClose = editions.findByCode(code)
                .orElseThrow(() -> LhException.notFound("Edizione non trovata: " + code));

        if (Edition.CLOSED.equals(editionToClose.status())) {
            throw LhException.validation("EDITION_ALREADY_CLOSED", "L'edizione " + code + " è già chiusa.");
        }
        if (!Edition.ACTIVE.equals(editionToClose.status())) {
             throw LhException.validation("EDITION_NOT_ACTIVE", "Si può chiudere solo un'edizione attiva.");
        }

        List<Tier> scale = tiers.findAllByRank();

        int offset = 0;
        int batchSize = 200;
        int totalRetained = 0;
        int totalDowngraded = 0;

        List<ClosePreviewMember> membersPreview = new ArrayList<>();

        while (true) {
            List<MemberTier> batch = memberTiers.findActiveMembers(batchSize, offset);
            if (batch.isEmpty()) {
                break;
            }

            EditionCloseBatchService.BatchResult batchResult = batchService.processBatch(batch, scale, code, dryRun);

            membersPreview.addAll(batchResult.previewMembers());
            totalRetained += batchResult.retained();
            totalDowngraded += batchResult.downgraded();

            offset += batchSize;
        }

        if (!dryRun) {
            finalizeClose(code, totalRetained, totalDowngraded);
        }

        return new ClosePreviewResult(new ClosePreviewSummary(totalRetained, totalDowngraded), membersPreview);
    }

    @Transactional
    protected void finalizeClose(String code, int totalRetained, int totalDowngraded) {
        editions.updateStatus(code, Edition.CLOSED);

        Edition nextEdition = editions.findAll().stream()
                .filter(e -> Edition.PLANNED.equals(e.status()))
                .min(Comparator.comparing(Edition::startDate))
                .orElse(null);

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
