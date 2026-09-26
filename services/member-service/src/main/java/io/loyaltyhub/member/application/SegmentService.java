package io.loyaltyhub.member.application;

import io.loyaltyhub.common.audit.AuditEntry;
import io.loyaltyhub.common.audit.AuditPublisher;
import io.loyaltyhub.common.ids.Ulid;
import io.loyaltyhub.common.web.ActorHolder;
import io.loyaltyhub.common.web.LhException;
import io.loyaltyhub.common.web.PageResponse;
import io.loyaltyhub.member.api.SegmentViews.MemberSample;
import io.loyaltyhub.member.api.SegmentViews.MemberSegmentView;
import io.loyaltyhub.member.api.SegmentViews.PreviewResult;
import io.loyaltyhub.member.api.SegmentViews.RefreshJobOutcome;
import io.loyaltyhub.member.api.SegmentViews.RefreshResult;
import io.loyaltyhub.member.api.SegmentViews.SegmentRequest;
import io.loyaltyhub.member.api.SegmentViews.SegmentView;
import io.loyaltyhub.member.domain.Segment;
import io.loyaltyhub.member.domain.SegmentCriteria;
import io.loyaltyhub.member.domain.SegmentFacts;
import io.loyaltyhub.member.infra.AttributeDefinitionRepository;
import io.loyaltyhub.member.infra.MemberRepository;
import io.loyaltyhub.member.infra.SegmentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Gestione dei segmenti (docs/servizi/member-service.md §3; docs/02 F-SEG-01/02/03; BO-04): elenco, dettaglio,
 * creazione, modifica, anteprima senza salvare, ricalcolo immediato, elenco manuale degli statici, appartenenze di un
 * membro. Ogni scrittura produce audit; ogni variazione di appartenenza il fatto {@code member.segment.entered/left}.
 */
@Service
public class SegmentService {

    /** docs/06 §2: codici leggibili delle entità di configurazione. */
    private static final Pattern CODE = Pattern.compile("^[A-Z][A-Z0-9-]{2,39}$");
    private static final int SAMPLE_SIZE = 10;

    private final SegmentRepository segments;
    private final MemberRepository members;
    private final SegmentRefresher refresher;
    private final AuditPublisher audit;
    private final Clock clock;
    private final AttributeDefinitionRepository attributeDefinitions;

    public SegmentService(SegmentRepository segments, MemberRepository members, SegmentRefresher refresher,
                          AuditPublisher audit, Clock clock, AttributeDefinitionRepository attributeDefinitions) {
        this.segments = segments;
        this.members = members;
        this.refresher = refresher;
        this.audit = audit;
        this.clock = clock;
        this.attributeDefinitions = attributeDefinitions;
    }

    // ---------- letture ----------

    public PageResponse<SegmentView> list(String q, String type, String status, int page, int size) {
        List<SegmentView> all = segments.list(q, type, status).stream().map(SegmentView::of).toList();
        io.loyaltyhub.common.web.PageParams paging = io.loyaltyhub.common.web.PageParams.of(page, size); // SPEC-GAP: Q-332
        int p = paging.page();
        int s = paging.size();
        int from = Math.min(p * s, all.size());
        int to = Math.min(from + s, all.size());
        return PageResponse.of(all.subList(from, to), p, s, all.size());
    }

    public SegmentView get(String idOrCode) {
        return SegmentView.of(require(idOrCode));
    }

    public PageResponse<MemberSample> members(String idOrCode, int page, int size) {
        Segment seg = require(idOrCode);
        // docs/06 §2: size massimo 100 anche per i membri di un segmento (prima 200). SPEC-GAP: Q-332
        io.loyaltyhub.common.web.PageParams paging = io.loyaltyhub.common.web.PageParams.of(page, size);
        int p = paging.page();
        int s = paging.size();
        List<MemberSample> items = segments.members(seg.id(), s, p * s).stream()
                .map(r -> new MemberSample(r.memberId(), name(r.firstName(), r.lastName(), r.nickname(), r.memberId()),
                        r.tier(), r.status(), r.enteredAt()))
                .toList();
        return PageResponse.of(items, p, s, segments.countMembers(seg.id()));
    }

    public List<MemberSegmentView> segmentsOf(String memberId) {
        members.findById(memberId).orElseThrow(() -> LhException.notFound("Membro non trovato: " + memberId));
        return segments.membershipsOf(memberId).stream()
                .map(r -> new MemberSegmentView(r.segmentId(), r.code(), r.name(), r.type(), r.status(), r.enteredAt()))
                .toList();
    }

    /** Anteprima dal vivo (BO-04): conteggio + 10 membri campione, senza salvare né emettere nulla. */
    public PreviewResult preview(JsonNode criteria) {
        requireValidCriteria(criteria);
        Instant now = clock.instant();
        List<SegmentFacts> matching = refresher.preview(criteria, now);
        List<MemberSample> sample = matching.stream().limit(SAMPLE_SIZE)
                .map(m -> new MemberSample(m.memberId(), m.displayName(), m.tier(), m.status(), null))
                .toList();
        return new PreviewResult(matching.size(), sample);
    }

    // ---------- scritture ----------

    @Transactional
    public SegmentView create(SegmentRequest r) {
        String code = r.code() == null ? "" : r.code().trim().toUpperCase();
        if (!CODE.matcher(code).matches()) {
            throw LhException.validation("INVALID_CODE", "Codice non valido: lettere maiuscole, cifre e trattini (3–40)",
                    List.of(new LhException.FieldError("code", "formato non valido")));
        }
        if (r.name() == null || r.name().isBlank()) {
            throw LhException.validation("NAME_REQUIRED", "Il nome è obbligatorio",
                    List.of(new LhException.FieldError("name", "obbligatorio")));
        }
        String type = r.type() == null ? "" : r.type().trim().toUpperCase();
        if (!Segment.STATIC.equals(type) && !Segment.DYNAMIC.equals(type)) {
            throw LhException.validation("INVALID_TYPE", "Tipo non valido: STATIC o DYNAMIC",
                    List.of(new LhException.FieldError("type", "STATIC o DYNAMIC")));
        }
        JsonNode criteria = null;
        if (Segment.DYNAMIC.equals(type)) {
            requireValidCriteria(r.criteria());
            criteria = r.criteria();
        }
        Set<String> initial = Segment.STATIC.equals(type) ? requireMembers(r.memberIds()) : Set.of();
        if (segments.existsByCode(code)) {
            throw LhException.conflict("CODE_TAKEN", "Esiste già un segmento con codice " + code);
        }
        Instant now = clock.instant();
        String actor = actor();
        Segment seg = new Segment(Ulid.next(clock), code, r.name().trim(), blankToNull(r.description()), type, criteria,
                Segment.ACTIVE, 0, null, 0, now, now, actor, actor);
        segments.insert(seg);
        audit.record("SEGMENT", code, AuditEntry.Action.CREATE, "Creato segmento " + code + " (" + type + ")", null,
                Map.of("name", seg.name(), "type", type));
        if (seg.isDynamic()) {
            refresher.refresh(seg.id(), now, actor);
        } else {
            refresher.apply(seg, Set.of(), initial, actor, false);
        }
        return get(seg.id());
    }

    @Transactional
    public SegmentView update(String idOrCode, SegmentRequest r) {
        Segment seg = require(idOrCode);
        if (r.version() != null && r.version() != seg.version()) {
            throw LhException.conflict("VERSION_CONFLICT",
                    "Versione non aggiornata: attesa " + seg.version() + ", ricevuta " + r.version());
        }
        if (r.code() != null && !r.code().trim().equalsIgnoreCase(seg.code())) {
            throw LhException.conflict("SEGMENT_IMMUTABLE_FIELD", "Il codice di un segmento non si cambia: lo usano campagne, premi e contenuti");
        }
        if (r.type() != null && !r.type().trim().equalsIgnoreCase(seg.type())) {
            throw LhException.conflict("SEGMENT_IMMUTABLE_FIELD", "Il tipo di un segmento non si cambia: creane uno nuovo");
        }
        String name = r.name() != null && !r.name().isBlank() ? r.name().trim() : seg.name();
        String description = r.description() != null ? blankToNull(r.description()) : seg.description();
        String status = r.status() != null ? r.status().trim().toUpperCase() : seg.status();
        if (!Segment.ACTIVE.equals(status) && !Segment.ARCHIVED.equals(status)) {
            throw LhException.validation("INVALID_STATUS", "Stato non valido: ACTIVE o ARCHIVED",
                    List.of(new LhException.FieldError("status", "ACTIVE o ARCHIVED")));
        }
        JsonNode criteria = seg.criteria();
        if (seg.isDynamic() && r.criteria() != null && !r.criteria().isNull()) {
            requireValidCriteria(r.criteria());
            criteria = r.criteria();
        }
        Instant now = clock.instant();
        String actor = actor();
        if (!segments.update(seg.id(), seg.version(), name, description, criteria, status, now, actor)) {
            throw LhException.conflict("VERSION_CONFLICT", "Modifica concorrente sul segmento " + seg.code());
        }
        Map<String, Object> before = new LinkedHashMap<>();
        Map<String, Object> after = new LinkedHashMap<>();
        diff(before, after, "name", seg.name(), name);
        diff(before, after, "description", seg.description(), description);
        diff(before, after, "criteria", str(seg.criteria()), str(criteria));
        diff(before, after, "status", seg.status(), status);
        if (!after.isEmpty()) {
            audit.record("SEGMENT", seg.code(), Segment.ARCHIVED.equals(status) && seg.isActive()
                            ? AuditEntry.Action.TRANSITION : AuditEntry.Action.UPDATE,
                    "Modificato segmento " + seg.code() + " (" + String.join(", ", after.keySet()) + ")", before, after);
        }
        Segment updated = require(seg.id());
        if (!updated.isActive() && seg.isActive()) {
            // Archiviato: esce chi c'era, così gli snapshot degli altri servizi non conservano un segmento spento.
            // SPEC-GAP: Q-88 — la spec non dice cosa accade alle appartenenze di un segmento archiviato; codice e tipo
            // sono immutabili (409 SEGMENT_IMMUTABLE_FIELD) perché li citano campagne, premi e contenuti.
            refresher.apply(updated, segments.memberIds(seg.id()), Set.of(), actor, false);
        } else if (updated.isActive() && (updated.isDynamic() && (!Objects.equals(seg.criteria(), criteria) || !seg.isActive()))) {
            refresher.refresh(seg.id(), now, actor);
        }
        return get(seg.id());
    }

    /** Ricalcolo immediato di un segmento → {@code {entered, left, total}} (BO-04 "Ricalcola ora"). */
    @Transactional
    public RefreshResult refresh(String idOrCode) {
        Segment seg = require(idOrCode);
        if (!seg.isActive()) {
            throw LhException.conflict("SEGMENT_ARCHIVED", "Il segmento " + seg.code() + " è archiviato");
        }
        SegmentRefresher.Outcome o = refresher.refresh(seg.id(), clock.instant(), actor());
        audit.record("SEGMENT", seg.code(), AuditEntry.Action.JOB,
                "Ricalcolo " + seg.code() + ": +" + o.entered() + " −" + o.left() + " = " + o.total(),
                null, Map.of("entered", o.entered(), "left", o.left(), "total", o.total()));
        return new RefreshResult(seg.code(), o.entered(), o.left(), o.total());
    }

    /** Solo {@code STATIC}: sostituisce l'elenco dei membri. */
    @Transactional
    public RefreshResult replaceMembers(String idOrCode, List<String> memberIds) {
        Segment seg = require(idOrCode);
        if (seg.isDynamic()) {
            throw LhException.conflict("SEGMENT_NOT_STATIC", "L'elenco manuale vale solo per i segmenti statici");
        }
        if (!seg.isActive()) {
            throw LhException.conflict("SEGMENT_ARCHIVED", "Il segmento " + seg.code() + " è archiviato");
        }
        Set<String> target = requireMembers(memberIds);
        Set<String> old = segments.memberIds(seg.id());
        String actor = actor();
        SegmentRefresher.Outcome o = refresher.apply(seg, old, target, actor, false);
        if (o.entered() > 0 || o.left() > 0) {
            audit.record("SEGMENT", seg.code(), AuditEntry.Action.UPDATE,
                    "Elenco di " + seg.code() + ": +" + o.entered() + " −" + o.left() + " = " + o.total(),
                    Map.of("members", new ArrayList<>(old)), Map.of("members", new ArrayList<>(target)));
        }
        return new RefreshResult(seg.code(), o.entered(), o.left(), o.total());
    }

    /** Job demo (BO-30, "macchina del tempo"): ricalcolo di tutti i segmenti attivi alla data {@code asOf}. */
    public RefreshJobOutcome refreshAllJob(Instant asOf) {
        List<SegmentRefresher.Outcome> outcomes = refresher.refreshAll(asOf, actor(), false);
        int entered = outcomes.stream().mapToInt(SegmentRefresher.Outcome::entered).sum();
        int left = outcomes.stream().mapToInt(SegmentRefresher.Outcome::left).sum();
        audit.record("SEGMENT", "refresh-segments", AuditEntry.Action.JOB,
                "Ricalcolo segmenti: " + outcomes.size() + " segmenti, +" + entered + " −" + left, null,
                Map.of("segments", outcomes.size(), "entered", entered, "left", left, "asOf", asOf.toString()));
        return new RefreshJobOutcome("refresh-segments", asOf, outcomes.size(), entered, left);
    }

    // ---------- interni ----------

    private Segment require(String idOrCode) {
        return segments.find(idOrCode).orElseThrow(() -> LhException.notFound("Segmento non trovato: " + idOrCode));
    }

    /**
     * Criteri non validi per forma → 422 {@code INVALID_CRITERIA}; forma corretta ma valore non convertibile nel tipo
     * dichiarato del campo (attributi custom con la loro definizione, contatori numerici…) → 422
     * {@code CONDITION_INVALID} con il percorso del valore (Q-215).
     */
    private void requireValidCriteria(JsonNode criteria) {
        Map<String, String> types = new LinkedHashMap<>();
        attributeDefinitions.findAll().forEach(d -> {
            if (d.type() != null) {
                types.put(d.key(), d.type());
            }
        });
        List<SegmentCriteria.Issue> issues = SegmentCriteria.validate(criteria, types);
        if (issues.isEmpty()) {
            return;
        }
        List<LhException.FieldError> errors = issues.stream()
                .map(i -> new LhException.FieldError(i.field(), i.message())).toList();
        boolean onlyTypes = issues.stream().allMatch(SegmentCriteria.Issue::typeMismatch);
        throw LhException.validation(onlyTypes ? "CONDITION_INVALID" : "INVALID_CRITERIA",
                onlyTypes ? "Condizione non valida: " + issues.get(0).field() + " — " + issues.get(0).message()
                        : "Criteri non validi: " + issues.get(0).message(), errors);
    }

    private Set<String> requireMembers(List<String> memberIds) {
        Set<String> wanted = new LinkedHashSet<>();
        if (memberIds != null) {
            memberIds.stream().filter(Objects::nonNull).map(String::trim).filter(s -> !s.isEmpty()).forEach(wanted::add);
        }
        Set<String> found = new LinkedHashSet<>(members.existingIds(new ArrayList<>(wanted)));
        List<LhException.FieldError> missing = new ArrayList<>();
        for (String id : wanted) {
            if (!found.contains(id)) {
                missing.add(new LhException.FieldError("memberIds", "membro inesistente o anonimizzato: " + id));
            }
        }
        if (!missing.isEmpty()) {
            throw LhException.validation("MEMBER_NOT_FOUND", "Alcuni membri non esistono: " + missing.size(), missing);
        }
        return wanted;
    }

    private static String actor() {
        return ActorHolder.get().asActorString();
    }

    private static String name(String first, String last, String nickname, String id) {
        if (first != null && last != null) {
            return first + " " + last;
        }
        return nickname != null ? nickname : id;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String str(JsonNode n) {
        return n == null ? null : n.toString();
    }

    private static void diff(Map<String, Object> before, Map<String, Object> after, String field, Object oldValue,
                             Object newValue) {
        if (!Objects.equals(oldValue, newValue)) {
            before.put(field, oldValue);
            after.put(field, newValue);
        }
    }
}
