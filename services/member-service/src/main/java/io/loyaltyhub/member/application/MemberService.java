package io.loyaltyhub.member.application;

import io.loyaltyhub.common.audit.AuditEntry;
import io.loyaltyhub.common.audit.AuditPublisher;
import io.loyaltyhub.common.event.LhEvent;
import io.loyaltyhub.common.event.LhEventFactory;
import io.loyaltyhub.common.event.LhEventTypes;
import io.loyaltyhub.common.ids.Codes;
import io.loyaltyhub.common.outbox.OutboxWriter;
import io.loyaltyhub.common.web.LhException;
import io.loyaltyhub.common.web.PageResponse;
import io.loyaltyhub.member.api.Consents;
import io.loyaltyhub.member.api.CreateMemberRequest;
import io.loyaltyhub.member.api.MemberView;
import io.loyaltyhub.member.api.PortalProfileView;
import io.loyaltyhub.member.api.StatusChangeRequest;
import io.loyaltyhub.member.api.UpdateMemberRequest;
import io.loyaltyhub.member.domain.ActionLabels;
import io.loyaltyhub.member.domain.Anonymization;
import io.loyaltyhub.member.domain.Member;
import io.loyaltyhub.member.domain.MemberAttributes;
import io.loyaltyhub.member.domain.MemberProjection;
import io.loyaltyhub.member.domain.MemberSnapshot;
import io.loyaltyhub.member.domain.MemberStatus;
import io.loyaltyhub.member.domain.ProfileRules;
import io.loyaltyhub.member.infra.AttributeDefinitionRepository;
import io.loyaltyhub.member.infra.MemberProjectionRepository;
import io.loyaltyhub.member.infra.MemberRepository;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import io.loyaltyhub.member.api.NicknamesRequest;
import io.loyaltyhub.member.api.NicknamesResponse;
import io.loyaltyhub.common.privacy.PersonalData;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Anagrafica dei membri (docs/servizi/member-service.md §3, §5): creazione, ricerca, modifica, stati.
 * Ogni scrittura emette il fatto corrispondente su {@code lh.facts.v1} via outbox, nella stessa transazione
 * (registrazione/modifica portano lo <em>snapshot completo</em>, docs §5). Registrazione dal portale con codice
 * amico (F-MBR-06, F-REF-01) e completamento profilo una sola volta con il fatto {@code member.profile.completed}
 * (F-MBR-07).
 */
@Service
public class MemberService {

    /** Codice amico: 8 caratteri {@code A-Z2-9} (docs/03 §2). */
    private static final int REFERRAL_CODE_LEN = 8;

    private final MemberRepository members;
    private final MemberProjectionRepository projections;
    private final LhEventFactory events;
    private final OutboxWriter outbox;
    private final AuditPublisher audit;
    private final Clock clock;
    private final ObjectMapper mapper;
    private final SegmentChangeTracker segmentChanges;
    private final AttributeDefinitionRepository attributeDefinitions;

    public MemberService(MemberRepository members, MemberProjectionRepository projections,
                         LhEventFactory events, OutboxWriter outbox, AuditPublisher audit, Clock clock,
                         ObjectMapper mapper, SegmentChangeTracker segmentChanges,
                         AttributeDefinitionRepository attributeDefinitions) {
        this.members = members;
        this.projections = projections;
        this.events = events;
        this.outbox = outbox;
        this.audit = audit;
        this.clock = clock;
        this.mapper = mapper;
        this.segmentChanges = segmentChanges;
        this.attributeDefinitions = attributeDefinitions;
    }

    /**
     * Etichette attribuite da un'azione esterna ({@link ActionLabels}, SPEC-GAP Q-80): se l'azione ne aggiunge una
     * nuova, aggiorna il membro ed emette {@code member.updated} (snapshot completo) nello stesso tracciato dell'azione.
     * Da chiamare nella transazione del consumo dell'azione.
     */
    public void applyActionLabels(LhEvent<?> action, String shortType) {
        String id = action.memberId();
        if (id == null || !ActionLabels.BY_ACTION.containsKey(shortType)) {
            return;
        }
        Member m = members.findById(id).orElse(null);
        if (m == null || m.status() == MemberStatus.ANONYMIZED) {
            return;
        }
        List<String> labels = ActionLabels.after(m.labels(), shortType);
        if (labels == null) {
            return;
        }
        members.updateLabels(id, labels);
        Member updated = members.findById(id).orElseThrow();
        outbox.write(events.childOf(action, LhEventTypes.Fact.MEMBER_UPDATED, MemberSnapshot.of(updated, tierOf(id))));
        segmentChanges.markChanged();
    }

    // ---------- letture ----------

    public MemberView get(String id) {
        Member m = members.findById(id).orElseThrow(() -> LhException.notFound("Membro non trovato: " + id));
        return MemberView.of(m, projections.findByMemberId(id).orElse(null));
    }

    /** Profilo per il portale (PT-08) con la completezza calcolata sui campi di docs/03 §2. */
    public PortalProfileView portalProfile(String id) {
        Member m = members.findById(id).orElseThrow(() -> LhException.notFound("Membro non trovato: " + id));
        List<String> missing = ProfileRules.missingFields(m);
        return new PortalProfileView(m.id(), m.firstName(), m.lastName(), m.nickname(), m.email(), m.phone(),
                m.birthDate(), m.city(), consentsOf(m), m.referralCode(), m.version(),
                new PortalProfileView.Completeness(missing.isEmpty(), missing));
    }

    public PageResponse<MemberView> search(String q, String status, String tier, String segment, int page, int size) {
        MemberStatus st = parseStatusFilter(status);
        io.loyaltyhub.common.web.PageParams paging = io.loyaltyhub.common.web.PageParams.of(page, size); // SPEC-GAP: Q-332
        int p = paging.page();
        int s = paging.size();
        long total = members.count(q, st, tier, segment);
        List<MemberView> items = members.search(q, st, tier, segment, s, p * s).stream()
                .map(m -> MemberView.of(m, projections.findByMemberId(m.id()).orElse(null)))
                .toList();
        return PageResponse.of(items, p, s, total);
    }

    // ---------- scritture ----------

    @Transactional
    public MemberView create(CreateMemberRequest r) {
        if (r.email() == null || r.email().isBlank()) {
            throw LhException.badRequest("email è obbligatoria");
        }
        if (members.existsByEmail(r.email())) {
            throw LhException.conflict("EMAIL_TAKEN", "E-mail già registrata: " + r.email());
        }
        String referredBy = resolveReferral(r.referralCode());

        String id = members.nextId();
        Instant now = clock.instant();
        String nickname = r.nickname() != null && !r.nickname().isBlank()
                ? r.nickname() : defaultNickname(r.firstName(), r.lastName());
        Member m = new Member(
                id, null, r.firstName(), r.lastName(), nickname, r.email(), r.phone(),
                null, r.gender(), r.city(), MemberStatus.ACTIVE,
                r.channel() != null ? r.channel() : "PORTAL", now,
                uniqueReferralCode(), referredBy, null,
                (r.consents() != null ? r.consents().over(Consents.NONE) : Consents.NONE).toJson(),
                "{}", List.of(), nickname, null, 0);
        members.insert(m);
        projections.upsert(id, "BASE", 0, 0, 0, 0);

        publish(LhEventTypes.Fact.MEMBER_REGISTERED, m, "BASE");
        audit.record("MEMBER", id, AuditEntry.Action.CREATE,
                "Creato membro " + m.displayName() + " (" + orEmpty(m.email()) + ")",
                null, Map.of("firstName", orEmpty(m.firstName()), "lastName", orEmpty(m.lastName()),
                        "email", orEmpty(m.email()), "status", m.status().name(), "channel", orEmpty(m.channel())));
        return MemberView.of(m, MemberProjection.base(id));
    }

    @Transactional
    public MemberView update(String id, UpdateMemberRequest r) {
        Member m = members.findById(id).orElseThrow(() -> LhException.notFound("Membro non trovato: " + id));
        requireNotAnonymized(m);
        if (r.version() != null && r.version() != m.version()) {
            throw LhException.conflict("VERSION_CONFLICT",
                    "Versione non aggiornata: attesa " + m.version() + ", ricevuta " + r.version());
        }
        if (r.email() != null && !r.email().equalsIgnoreCase(orEmpty(m.email())) && members.existsByEmail(r.email())) {
            throw LhException.conflict("EMAIL_TAKEN", "E-mail già registrata: " + r.email());
        }

        String firstName = pick(r.firstName(), m.firstName());
        String lastName = pick(r.lastName(), m.lastName());
        String nickname = pick(r.nickname(), m.nickname());
        String email = pick(r.email(), m.email());
        String phone = pick(r.phone(), m.phone());
        LocalDate birthDate = r.birthDate() != null ? r.birthDate() : m.birthDate();
        String gender = pick(r.gender(), m.gender());
        String city = pick(r.city(), m.city());

        Instant profileCompletedAt = m.profileCompletedAt();
        boolean completesProfile = profileCompletedAt == null
                && ProfileRules.missingFields(firstName, lastName, email, phone, birthDate != null, city).isEmpty();
        if (completesProfile) {
            profileCompletedAt = clock.instant();
        }
        Consents oldConsents = consentsOf(m);
        Consents newConsents = r.consents() != null ? r.consents().over(oldConsents) : oldConsents;

        // Attributi personalizzati ed etichette (F-MBR-03, M6.7): validati sulle definizioni, chiavi interne intatte.
        List<MemberAttributes.Problem> problems = new ArrayList<>();
        JsonNode oldAttributes = mapper.readTree(m.attributesJson() == null ? "{}" : m.attributesJson());
        ObjectNode newAttributes = MemberAttributes.merge(oldAttributes, r.attributes(), attributeDefinitions.byKey(),
                problems);
        List<String> newLabels = r.labels() == null ? m.labels() : MemberAttributes.labels(r.labels(), problems);
        if (!problems.isEmpty()) {
            throw LhException.validation("MEMBER_INVALID", "Attributi o etichette non validi.",
                    problems.stream().map(p -> new LhException.FieldError(p.field(), p.message())).toList());
        }

        boolean ok = members.updateFields(id, m.version(), firstName, lastName, nickname, email, phone,
                birthDate, gender, city, newConsents.toJson(), newAttributes.toString(), profileCompletedAt);
        if (!ok) {
            throw LhException.conflict("VERSION_CONFLICT", "Modifica concorrente sul membro " + id);
        }

        if (!newLabels.equals(m.labels())) {
            members.updateLabels(id, newLabels);
        }
        Member updated = members.findById(id).orElseThrow();
        String tier = tierOf(id);
        segmentChanges.markChanged(); // città ecc. entrano nei criteri dei segmenti
        publish(LhEventTypes.Fact.MEMBER_UPDATED, updated, tier);
        if (completesProfile) {
            // Una sola volta (docs §5): il ponte lo trasforma nell'azione member.profile.completed (CMP-PROFILE).
            outbox.write(events.newRoot(LhEventTypes.Fact.MEMBER_PROFILE_COMPLETED, "member:" + id,
                    Map.of("memberId", id)));
        }

        Map<String, Object> before = new LinkedHashMap<>();
        Map<String, Object> after = new LinkedHashMap<>();
        diff(before, after, "firstName", m.firstName(), updated.firstName());
        diff(before, after, "lastName", m.lastName(), updated.lastName());
        diff(before, after, "nickname", m.nickname(), updated.nickname());
        diff(before, after, "email", m.email(), updated.email());
        diff(before, after, "phone", m.phone(), updated.phone());
        diff(before, after, "birthDate", m.birthDate(), updated.birthDate());
        diff(before, after, "gender", m.gender(), updated.gender());
        diff(before, after, "city", m.city(), updated.city());
        diff(before, after, "consents", oldConsents, newConsents);
        diff(before, after, "attributes", MemberAttributes.visible(oldAttributes), MemberAttributes.visible(newAttributes));
        diff(before, after, "labels", m.labels(), updated.labels());
        if (!after.isEmpty()) {
            audit.record("MEMBER", id, AuditEntry.Action.UPDATE,
                    "Modificato profilo di " + updated.displayName() + " (" + after.size() + " campi)", before, after);
        }
        return MemberView.of(updated, projections.findByMemberId(id).orElse(null));
    }

    @Transactional
    public MemberView changeStatus(String id, StatusChangeRequest r) {
        Member m = members.findById(id).orElseThrow(() -> LhException.notFound("Membro non trovato: " + id));
        requireNotAnonymized(m);
        MemberStatus target = parseTargetStatus(r.status());
        MemberStatus previous = m.status();
        if (target == previous) {
            return MemberView.of(m, projections.findByMemberId(id).orElse(null));
        }
        members.updateStatus(id, target);
        segmentChanges.markChanged();

        LhEvent<Map<String, Object>> fact = events.newRoot(
                LhEventTypes.Fact.MEMBER_STATUS_CHANGED, "member:" + id,
                Map.of("memberId", id, "previousStatus", previous.name(), "newStatus", target.name(),
                        "reason", r.reason() != null ? r.reason() : ""));
        outbox.write(fact);

        String reason = r.reason() != null && !r.reason().isBlank() ? " · " + r.reason() : "";
        audit.record("MEMBER", id, AuditEntry.Action.TRANSITION,
                "Stato " + previous.name() + " → " + target.name() + reason,
                Map.of("status", previous.name()), Map.of("status", target.name()));

        Member updated = members.findById(id).orElseThrow();
        return MemberView.of(updated, projections.findByMemberId(id).orElse(null));
    }

    /**
     * Anonimizzazione irreversibile (F-MBR-05, docs/03 §2, M7.5): {@code confirm} deve essere l'id del membro.
     * Nella stessa transazione: riga anonimizzata ({@link Anonymization#apply}), fatto {@code member.status.changed}
     * (→ {@code ANONYMIZED}) e poi {@code member.updated} con lo snapshot già ripulito (gli altri servizi lo usano per
     * cancellare i dati personali dal proprio snapshot, docs/12 §M7). L'audit non riporta alcun dato personale.
     */
    @Transactional
    public MemberView anonymize(String id, String confirm) {
        Member m = members.findById(id).orElseThrow(() -> LhException.notFound("Membro non trovato: " + id));
        if (!Anonymization.confirms(id, confirm)) {
            throw LhException.validation("CONFIRM_MISMATCH", "Per confermare digita l'ID del membro (" + id + ").",
                    List.of(new LhException.FieldError("confirm", "deve essere " + id)));
        }
        if (m.status() == MemberStatus.ANONYMIZED) {
            throw LhException.conflict("MEMBER_ANONYMIZED", "Il membro " + id + " è già anonimizzato.");
        }
        Member redacted = Anonymization.apply(m);
        if (!members.anonymize(redacted, m.version())) {
            throw LhException.conflict("VERSION_CONFLICT", "Modifica concorrente sul membro " + id);
        }
        segmentChanges.markChanged(); // gli anonimizzati escono dai segmenti dinamici al prossimo ricalcolo

        Member updated = members.findById(id).orElseThrow();
        outbox.write(events.newRoot(LhEventTypes.Fact.MEMBER_STATUS_CHANGED, "member:" + id,
                Map.of("memberId", id, "previousStatus", m.status().name(),
                        "newStatus", MemberStatus.ANONYMIZED.name(), "reason", "Anonimizzazione")));
        publish(LhEventTypes.Fact.MEMBER_UPDATED, updated, tierOf(id));

        audit.record("MEMBER", id, AuditEntry.Action.TRANSITION,
                "Membro " + id + " anonimizzato: dati personali rimossi",
                Map.of("status", m.status().name()), Map.of("status", MemberStatus.ANONYMIZED.name()));
        return MemberView.of(updated, projections.findByMemberId(id).orElse(null));
    }

    public NicknamesResponse nicknames(NicknamesRequest request) {
        if (request.memberIds() == null || request.memberIds().isEmpty() || request.memberIds().size() > 200) {
            throw LhException.validation("TOO_MANY_IDS", "I memberIds devono essere tra 1 e 200.");
        }
        List<Member> byIds = members.findByIds(request.memberIds());
        List<NicknamesResponse.Item> items = new ArrayList<>();
        for (Member m : byIds) {
            if (m.status() == MemberStatus.ANONYMIZED) {
                items.add(new NicknamesResponse.Item(m.id(), PersonalData.PLACEHOLDER));
            } else {
                items.add(new NicknamesResponse.Item(m.id(), m.nickname()));
            }
        }
        return new NicknamesResponse(items);
    }

    // ---------- interni ----------

    /** Un membro anonimizzato non si modifica più (docs/03 §2: stato irreversibile). */
    private static void requireNotAnonymized(Member m) {
        if (m.status() == MemberStatus.ANONYMIZED) {
            throw LhException.conflict("MEMBER_ANONYMIZED",
                    "Il membro " + m.id() + " è anonimizzato: l'operazione non è più possibile.");
        }
    }

    private void publish(String factType, Member m, String tier) {
        LhEvent<MemberSnapshot> fact = events.newRoot(factType, "member:" + m.id(), MemberSnapshot.of(m, tier));
        outbox.write(fact);
    }

    private String tierOf(String id) {
        return projections.findByMemberId(id).map(MemberProjection::tierCode).orElse("BASE");
    }

    /**
     * Codice amico → invitante (docs/03 §8), normalizzato in maiuscolo. Il legame si crea solo alla registrazione e
     * non è modificabile; {@code REFERRAL_SELF} non può verificarsi qui perché il codice nasce insieme al membro.
     */
    private String resolveReferral(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        String normalized = code.trim().toUpperCase(java.util.Locale.ROOT);
        Member referrer = members.findByReferralCode(normalized)
                .orElseThrow(() -> LhException.validation("REFERRAL_CODE_INVALID", "Codice invito inesistente: " + code));
        // SPEC-GAP: Q-61 — il codice di un membro non ACTIVE (bloccato, anonimizzato) è trattato come non valido.
        if (referrer.status() != MemberStatus.ACTIVE) {
            throw LhException.validation("REFERRAL_CODE_INVALID", "Codice invito non più attivo: " + code);
        }
        return referrer.id();
    }

    private Consents consentsOf(Member m) {
        if (m.consentsJson() == null || m.consentsJson().isBlank()) {
            return Consents.NONE;
        }
        JsonNode node = mapper.readTree(m.consentsJson());
        return new Consents(node.path("marketing").asBoolean(false), node.path("profiling").asBoolean(false));
    }

    private String uniqueReferralCode() {
        for (int attempt = 0; attempt < 10; attempt++) {
            String code = Codes.random(REFERRAL_CODE_LEN);
            if (!members.existsByReferralCode(code)) {
                return code;
            }
        }
        throw LhException.conflict("REFERRAL_CODE_EXHAUSTED", "Impossibile generare un codice invito univoco");
    }

    private static String defaultNickname(String firstName, String lastName) {
        String first = firstName != null ? firstName.trim() : "";
        if (lastName != null && !lastName.isBlank()) {
            return (first + " " + lastName.trim().charAt(0) + ".").trim();
        }
        return first.isBlank() ? "Membro" : first;
    }

    private MemberStatus parseTargetStatus(String status) {
        MemberStatus s = parse(status);
        if (s == null || !(s == MemberStatus.ACTIVE || s == MemberStatus.INACTIVE || s == MemberStatus.BLOCKED)) {
            throw LhException.badRequest("status deve essere ACTIVE, INACTIVE o BLOCKED");
        }
        return s;
    }

    private MemberStatus parseStatusFilter(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        MemberStatus s = parse(status);
        if (s == null) {
            throw LhException.badRequest("status filtro non valido: " + status);
        }
        return s;
    }

    private static MemberStatus parse(String status) {
        if (status == null) {
            return null;
        }
        try {
            return MemberStatus.valueOf(status.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String pick(String incoming, String current) {
        return incoming != null ? incoming : current;
    }

    /** Registra il campo in {@code before}/{@code after} solo se è davvero cambiato (docs/05 §6: solo i campi cambiati). */
    private static void diff(Map<String, Object> before, Map<String, Object> after,
                             String field, Object oldValue, Object newValue) {
        if (!Objects.equals(oldValue, newValue)) {
            before.put(field, oldValue == null ? null : oldValue.toString());
            after.put(field, newValue == null ? null : newValue.toString());
        }
    }

    private static String orEmpty(String v) {
        return v != null ? v : "";
    }
}
