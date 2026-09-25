package io.loyaltyhub.engagement.application;

import io.loyaltyhub.common.approval.ApprovalAction;
import io.loyaltyhub.common.approval.ApprovalHistory;
import io.loyaltyhub.common.approval.ApprovalHistoryStore;
import io.loyaltyhub.common.approval.ApprovalPolicy;
import io.loyaltyhub.common.approval.ApprovalStatus;
import io.loyaltyhub.common.audit.AuditEntry;
import io.loyaltyhub.common.audit.AuditPublisher;
import io.loyaltyhub.common.event.LhEventFactory;
import io.loyaltyhub.common.event.LhEventTypes;
import io.loyaltyhub.common.event.LhSource;
import io.loyaltyhub.common.ids.Ulid;
import io.loyaltyhub.common.outbox.OutboxWriter;
import io.loyaltyhub.common.web.ActorHolder;
import io.loyaltyhub.common.web.LhException;
import io.loyaltyhub.engagement.domain.ContentItem;
import io.loyaltyhub.engagement.domain.ContentSelection;
import io.loyaltyhub.engagement.domain.ContentSelection.Viewer;
import io.loyaltyhub.engagement.infra.ContentRepository;
import io.loyaltyhub.engagement.infra.MemberSnapshotRepository;
import io.loyaltyhub.engagement.infra.PopupViewRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Contenuti del CMS (docs/servizi/engagement-service.md §3, §5; F-CNT-01/02/03/04; BO-18): gestione con lock
 * ottimistico e audit, transizioni senza approvazione ({@code DRAFT → LIVE} diretto) col fatto
 * {@code content.status.changed} e una riga di {@code approval_history} (docs/03 §3.6, docs/06 §7: chi, quando, da/verso),
 * duplicazione, selezione per posizionamento per il portale e anteprima per membro.
 */
@Service
public class ContentService {

    private static final Pattern CODE = Pattern.compile("^[A-Z][A-Z0-9-]{2,39}$");
    private static final int TITLE_MAX = 80;
    private static final int BODY_MAX = 280;

    public record ContentRequest(String code, String kind, String placement, String title, String body, String imageUrl,
                                 String ctaLabel, String ctaTarget, String linkType, String linkCode, JsonNode audience,
                                 Instant startAt, Instant endAt, Integer priority, String frequency, Boolean dismissible,
                                 JsonNode style, Long version) {
    }

    /** Ciò che serve a disegnare un contenuto nel portale (e nell'anteprima): niente pubblico, stato, versioni. */
    public record ContentDisplay(String code, String kind, String placement, String title, String body, String imageUrl,
                                 String ctaLabel, String ctaTarget, String linkType, String linkCode, JsonNode style) {
        static ContentDisplay of(ContentItem c) {
            return new ContentDisplay(c.code(), c.kind(), c.placement(), c.title(), c.body(), c.imageUrl(), c.ctaLabel(),
                    c.ctaTarget(), c.linkType(), c.linkCode(), c.style());
        }
    }

    public record ExcludedView(String code, String title, String reason) {
    }

    /** Pop-up per il portale: come un contenuto, più l'id per registrare la vista e se si può chiudere. */
    public record PopupView(String id, String code, String kind, String title, String body, String imageUrl, String ctaLabel,
                            String ctaTarget, String linkType, String linkCode, JsonNode style, String frequency,
                            boolean dismissible) {
        static PopupView of(ContentItem c) {
            return new PopupView(c.id(), c.code(), c.kind(), c.title(), c.body(), c.imageUrl(), c.ctaLabel(), c.ctaTarget(),
                    c.linkType(), c.linkCode(), c.style(), c.frequency(), c.dismissible());
        }
    }

    /** Pseudo-posizionamento dell'anteprima di BO-18 per i pop-up (non hanno un placement). */
    public static final String POPUP = "POPUP";

    public record Preview(String memberId, String placement, List<ContentDisplay> shown, List<ExcludedView> excluded) {
    }

    /** Transizioni valide (docs/08 §3: solo quelle ammesse dallo stato; i contenuti non passano da approvazione). */
    private static final Map<String, Map<String, String>> TRANSITIONS = Map.of(
            "PUBLISH", Map.of("DRAFT", "LIVE"),
            "PAUSE", Map.of("LIVE", "PAUSED"),
            "RESUME", Map.of("PAUSED", "LIVE"),
            "END", Map.of("LIVE", "ENDED", "PAUSED", "ENDED"),
            "ARCHIVE", Map.of("DRAFT", "ARCHIVED", "ENDED", "ARCHIVED"));

    private final ContentRepository contents;
    private final PopupViewRepository popupViews;
    private final MemberSnapshotRepository members;
    private final LhEventFactory events;
    private final OutboxWriter outbox;
    private final AuditPublisher audit;
    private final ApprovalHistoryStore history;
    private final ObjectMapper mapper;
    private final Clock clock;

    public ContentService(ContentRepository contents, PopupViewRepository popupViews, MemberSnapshotRepository members,
                          LhEventFactory events, OutboxWriter outbox, AuditPublisher audit, ApprovalHistoryStore history,
                          ObjectMapper mapper, Clock clock) {
        this.contents = contents;
        this.popupViews = popupViews;
        this.members = members;
        this.events = events;
        this.outbox = outbox;
        this.audit = audit;
        this.history = history;
        this.mapper = mapper;
        this.clock = clock;
    }

    // ---------- letture ----------

    public List<ContentItem> list(String kind, String placement, String status, String q) {
        return contents.findAll(kind, placement, status, q);
    }

    public ContentItem get(String idOrCode) {
        return contents.find(idOrCode).orElseThrow(() -> LhException.notFound("Contenuto non trovato: " + idOrCode));
    }

    /**
     * Portale: i contenuti che il membro vede adesso nel posizionamento, già ordinati e limitati. Per {@code WIN}
     * {@code prizeCode} filtra la card del premio vinto. Come ogni endpoint del portale richiede un {@code memberId}
     * esplicito (docs/06 §2): senza, {@code 400}.
     */
    public List<ContentDisplay> portal(String memberId, String placement, String prizeCode) {
        if (memberId == null || memberId.isBlank()) {
            throw LhException.badRequest("memberId è obbligatorio");
        }
        String p = placementOrThrow(placement);
        List<ContentItem> candidates = contents.findByPlacement(p);
        if ("WIN".equals(p) && prizeCode != null && !prizeCode.isBlank()) {
            candidates = candidates.stream().filter(c -> prizeCode.trim().equalsIgnoreCase(c.linkCode())).toList();
        }
        return ContentSelection.select(candidates, viewer(memberId), clock.instant(), ContentSelection.LIMITS.get(p))
                .shown().stream().map(ContentDisplay::of).toList();
    }

    /**
     * Portale (F-CNT-02): il prossimo pop-up per il membro, o vuoto. Leggere non consuma il pop-up: la vista si registra
     * con {@link #seen} (docs §5), così un errore di disegno non lo brucia.
     */
    public Optional<PopupView> nextPopup(String memberId) {
        if (memberId == null || memberId.isBlank()) {
            throw LhException.badRequest("memberId è obbligatorio");
        }
        return popupResult(memberId.trim()).shown().stream().findFirst().map(PopupView::of);
    }

    /** Registra la vista (e la chiusura) del pop-up per il membro, nel giorno corrente (Europe/Rome). */
    @Transactional
    public void seen(String idOrCode, String memberId, boolean dismissed) {
        if (memberId == null || memberId.isBlank()) {
            throw LhException.badRequest("memberId è obbligatorio");
        }
        ContentItem c = get(idOrCode);
        if (!"POPUP".equals(c.kind())) {
            throw LhException.notFound("Pop-up non trovato: " + idOrCode);
        }
        Instant now = clock.instant();
        popupViews.recordSeen(c.id(), memberId.trim(), LocalDate.ofInstant(now, ContentSelection.ZONE), now, dismissed);
    }

    private ContentSelection.Result popupResult(String memberId) {
        return ContentSelection.selectPopup(contents.findPopups(), viewer(memberId), clock.instant(),
                popupViews.lastSeenByContent(memberId));
    }

    /**
     * BO-18 anteprima per membro (F-CNT-04): cosa vede adesso e perché gli altri contenuti sono esclusi. Con
     * {@code placement=POPUP} vale per i pop-up, compresa la frequenza.
     */
    public Preview preview(String memberId, String placement) {
        if (memberId == null || memberId.isBlank()) {
            throw LhException.badRequest("memberId è obbligatorio");
        }
        if (POPUP.equalsIgnoreCase(placement == null ? "" : placement.trim())) {
            ContentSelection.Result r = popupResult(memberId.trim());
            return new Preview(memberId, POPUP, r.shown().stream().map(ContentDisplay::of).toList(),
                    r.excluded().stream().map(e -> new ExcludedView(e.item().code(), e.item().title(), e.reason())).toList());
        }
        String p = placementOrThrow(placement);
        // Per WIN il portale ne mostra una (quella del premio vinto); l'anteprima le mostra tutte, una per premio.
        int limit = "WIN".equals(p) ? Integer.MAX_VALUE : ContentSelection.LIMITS.get(p);
        ContentSelection.Result r = ContentSelection.select(contents.findByPlacement(p), viewer(memberId), clock.instant(), limit);
        return new Preview(memberId, p, r.shown().stream().map(ContentDisplay::of).toList(),
                r.excluded().stream().map(e -> new ExcludedView(e.item().code(), e.item().title(), e.reason())).toList());
    }

    // ---------- scritture ----------

    @Transactional
    public ContentItem create(ContentRequest r) {
        String code = upper(r.code());
        if (code == null || !CODE.matcher(code).matches()) {
            throw LhException.validation("CONTENT_INVALID", "Codice non valido (es. CNT-AUTUNNO).",
                    List.of(new LhException.FieldError("code", "maiuscole, cifre e trattini, 3–40 caratteri")));
        }
        if (contents.existsCode(code)) {
            throw LhException.conflict("CODE_TAKEN", "Contenuto già esistente: " + code);
        }
        ContentItem c = validated(Ulid.next(clock), code, r, null);
        String actor = ActorHolder.get().asActorString();
        contents.insert(c, actor);
        audit.record("CONTENT", code, AuditEntry.Action.CREATE, "Creato contenuto " + c.title(), null, snapshot(c));
        return get(c.id());
    }

    @Transactional
    public ContentItem update(String idOrCode, ContentRequest r) {
        ContentItem current = get(idOrCode);
        if ("ARCHIVED".equals(current.status())) {
            throw LhException.conflict("CONTENT_NOT_EDITABLE", "Un contenuto archiviato non si modifica: duplicalo.");
        }
        if (r.code() != null && !upper(r.code()).equals(current.code())) {
            throw LhException.conflict("CODE_IMMUTABLE", "Il codice di un contenuto non si modifica: " + current.code());
        }
        if (r.kind() != null && !upper(r.kind()).equals(current.kind())) {
            throw LhException.conflict("KIND_IMMUTABLE", "Il tipo di un contenuto non si modifica: duplicalo e cambia tipo.");
        }
        long expected = r.version() != null ? r.version() : current.version();
        ContentItem next = validated(current.id(), current.code(), r, current);
        if ("LIVE".equals(current.status())) {
            List<String> locked = lockedChanges(current, next);
            if (!locked.isEmpty()) {
                throw LhException.conflict("CONTENT_LIVE_LOCKED", "Su un contenuto LIVE si modificano solo titolo, testo, "
                        + "immagine, priorità e fine calendario; campi bloccati: " + String.join(", ", locked)
                        + ". Per cambiarli duplica il contenuto.");
            }
        }
        if (!contents.update(next, expected, ActorHolder.get().asActorString())) {
            throw LhException.conflict("VERSION_CONFLICT", "Il contenuto è stato modificato nel frattempo: ricarica e riprova.");
        }
        Map<String, Object> before = new LinkedHashMap<>();
        Map<String, Object> after = new LinkedHashMap<>();
        TemplateAdminService.diff(snapshot(current), snapshot(next), before, after);
        if (!after.isEmpty()) {
            audit.record("CONTENT", current.code(), AuditEntry.Action.UPDATE, "Modificato contenuto " + next.title(), before, after);
        }
        return get(current.id());
    }

    @Transactional
    public ContentItem transition(String idOrCode, String action) {
        ContentItem c = get(idOrCode);
        String a = upper(action);
        String to = a == null ? null : TRANSITIONS.getOrDefault(a, Map.of()).get(c.status());
        if (to == null) {
            throw LhException.conflict("INVALID_TRANSITION", "Azione " + action + " non ammessa da " + c.status() + ".");
        }
        String actor = ActorHolder.get().asActorString();
        changeStatus(c, to, actor);
        history.record(ApprovalPolicy.CONTENT, c.id(), ApprovalStatus.valueOf(c.status()), ApprovalStatus.valueOf(to),
                ApprovalAction.valueOf(a), actor, null, clock.instant());
        audit.record("CONTENT", c.code(), AuditEntry.Action.TRANSITION, c.title() + ": " + c.status() + " → " + to,
                Map.of("status", c.status()), Map.of("status", to));
        return get(c.id());
    }

    /** Storico delle transizioni del contenuto (docs/03 §3.6: chi, quando, da/verso), dal più recente. */
    public List<ApprovalHistory> history(String idOrCode) {
        return history.list(ApprovalPolicy.CONTENT, get(idOrCode).id());
    }

    /** Copia in bozza con codice {@code <CODICE>-COPIA[-n]}: il modo di cambiare tipo o riusare un archiviato. */
    @Transactional
    public ContentItem duplicate(String idOrCode) {
        ContentItem c = get(idOrCode);
        String base = (c.code().length() > 33 ? c.code().substring(0, 33) : c.code()) + "-COPIA";
        String code = base;
        for (int n = 2; contents.existsCode(code); n++) {
            code = base + "-" + n;
        }
        ContentItem copy = new ContentItem(Ulid.next(clock), code, c.kind(), c.placement(), c.title() + " (copia)", c.body(),
                c.imageUrl(), c.ctaLabel(), c.ctaTarget(), c.linkType(), c.linkCode(), c.audience(), c.startAt(), c.endAt(),
                c.priority(), c.frequency(), c.dismissible(), c.style(), "DRAFT", 0, null);
        contents.insert(copy, ActorHolder.get().asActorString());
        audit.record("CONTENT", code, AuditEntry.Action.CREATE, "Duplicato " + c.code() + " in " + code, null, snapshot(copy));
        return get(copy.id());
    }

    /** Fine automatica (docs §5): {@code LIVE}/{@code PAUSED} con {@code end_at} passato → {@code ENDED}. */
    @Transactional
    public int endExpired(Instant asOf) {
        int n = 0;
        for (ContentItem c : contents.expiredBy(asOf)) {
            changeStatus(c, "ENDED", "system");
            history.record(ApprovalPolicy.CONTENT, c.id(), ApprovalStatus.valueOf(c.status()), ApprovalStatus.ENDED,
                    ApprovalAction.END, "system", "Fine calendario", clock.instant());
            audit.recordJob("CONTENT", c.code(), c.title() + ": " + c.status() + " → ENDED (fine calendario)",
                    Map.of("status", c.status()), Map.of("status", "ENDED"));
            n++;
        }
        return n;
    }

    // ---------- interni ----------

    private void changeStatus(ContentItem c, String to, String actor) {
        contents.updateStatus(c.id(), to, actor);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("contentId", c.id());
        data.put("contentCode", c.code());
        data.put("kind", c.kind());
        data.put("previousStatus", c.status());
        data.put("newStatus", to);
        outbox.write(events.newRoot(LhEventTypes.Fact.CONTENT_STATUS_CHANGED, "content:" + c.code(), data,
                LhSource.service("engagement"), actor));
    }

    private Viewer viewer(String memberId) {
        if (memberId == null || memberId.isBlank()) {
            return Viewer.UNKNOWN;
        }
        return members.find(memberId.trim())
                .map(s -> new Viewer(s.tierCode(), s.segments(), s.status(), s.registeredAt()))
                .orElse(Viewer.UNKNOWN);
    }

    private static String placementOrThrow(String placement) {
        String p = upper(placement);
        if (p == null || !ContentItem.PLACEMENTS.contains(p)) {
            throw LhException.badRequest("placement tra " + ContentItem.PLACEMENTS);
        }
        return p;
    }

    /**
     * Valida la richiesta e costruisce il contenuto. {@code PUT} sostituisce tutto (un campo assente si svuota); dal
     * contenuto corrente restano solo codice, tipo, stato e versione.
     */
    private ContentItem validated(String id, String code, ContentRequest r, ContentItem cur) {
        List<LhException.FieldError> errors = new ArrayList<>();
        String kind = cur != null ? cur.kind() : upper(r.kind());
        boolean popup = "POPUP".equals(kind);
        if (kind == null || !ContentItem.KINDS.contains(kind)) {
            errors.add(new LhException.FieldError("kind", "tra " + ContentItem.KINDS));
        }
        String placement = popup ? null : upper(r.placement());
        if (!popup && (placement == null || !ContentItem.PLACEMENTS.contains(placement))) {
            errors.add(new LhException.FieldError("placement", "tra " + ContentItem.PLACEMENTS));
        }
        if ("BANNER".equals(kind) && placement != null && !"CATALOG_TOP".equals(placement)) {
            errors.add(new LhException.FieldError("placement", "un banner va in CATALOG_TOP"));
        }
        String title = trim(r.title());
        if (title == null) {
            errors.add(new LhException.FieldError("title", "obbligatorio"));
        } else if (title.length() > TITLE_MAX) {
            errors.add(new LhException.FieldError("title", "al massimo " + TITLE_MAX + " caratteri"));
        }
        String body = trim(r.body());
        if (body != null && body.length() > BODY_MAX) {
            errors.add(new LhException.FieldError("body", "al massimo " + BODY_MAX + " caratteri"));
        }
        String linkType = upper(r.linkType());
        if (linkType == null) {
            linkType = "NONE";
        }
        if (!ContentItem.LINK_TYPES.contains(linkType)) {
            errors.add(new LhException.FieldError("linkType", "tra " + ContentItem.LINK_TYPES));
        }
        String linkCode = "NONE".equals(linkType) ? null : upper(r.linkCode());
        if (!"NONE".equals(linkType) && linkCode == null) {
            errors.add(new LhException.FieldError("linkCode", "obbligatorio per il collegamento " + linkType));
        }
        if ("PRIZE".equals(linkType) != "WIN".equals(placement)) {
            errors.add(new LhException.FieldError("linkType", "le card WIN (e solo loro) si collegano a un premio in palio"));
        }
        String ctaTarget = "NONE".equals(linkType) ? trim(r.ctaTarget()) : null;
        if (ctaTarget != null && !(ctaTarget.startsWith("/portal") || ctaTarget.matches("^https://\\S+$"))) {
            errors.add(new LhException.FieldError("ctaTarget", "una pagina del portale (/portal…) o un indirizzo https://"));
        }
        Instant startAt = r.startAt();
        Instant endAt = r.endAt();
        if (startAt != null && endAt != null && !endAt.isAfter(startAt)) {
            errors.add(new LhException.FieldError("endAt", "deve seguire l'inizio"));
        }
        int priority = r.priority() != null ? r.priority() : 50;
        if (priority < 0 || priority > 1000) {
            errors.add(new LhException.FieldError("priority", "tra 0 e 1000"));
        }
        String frequency = popup ? upper(pick(r.frequency(), "ONCE")) : null;
        if (popup && !ContentItem.FREQUENCIES.contains(frequency)) {
            errors.add(new LhException.FieldError("frequency", "tra " + ContentItem.FREQUENCIES));
        }
        boolean dismissible = !popup || r.dismissible() == null || r.dismissible();
        JsonNode audience = audience(r.audience());
        JsonNode style = r.style() != null && r.style().isObject() ? r.style() : mapper.createObjectNode();
        String tone = style.path("tone").asString(null);
        if (tone != null && !ContentItem.TONES.contains(tone)) {
            errors.add(new LhException.FieldError("style.tone", "tra " + ContentItem.TONES));
        }
        if (!errors.isEmpty()) {
            throw LhException.validation("CONTENT_INVALID", "Contenuto non valido: controlla i campi evidenziati.", errors);
        }
        return new ContentItem(id, code, kind, placement, title, body, trim(r.imageUrl()), trim(r.ctaLabel()), ctaTarget,
                linkType, linkCode, audience, startAt,
                endAt, priority, frequency, dismissible, style, cur == null ? "DRAFT" : cur.status(),
                cur == null ? 0 : cur.version(), null);
    }

    /**
     * docs/03 §3.6: un oggetto {@code LIVE} si modifica solo nei campi "sicuri" — nome ({@code title}), descrizione
     * ({@code body}), {@code endAt}, priorità, immagine — e per il resto va duplicato (docs/06 §2: {@code 409}).
     * Restituisce i campi non sicuri che la modifica cambierebbe, confrontati dopo la normalizzazione di
     * {@link #validated}. Etichetta della CTA e stile non sono nell'elenco dei sicuri: bloccati anche loro.
     */
    private static List<String> lockedChanges(ContentItem cur, ContentItem next) {
        List<String> locked = new ArrayList<>();
        if (!Objects.equals(cur.placement(), next.placement())) {
            locked.add("placement");
        }
        if (!Objects.equals(cur.audience(), next.audience())) {
            locked.add("audience");
        }
        if (!Objects.equals(micros(cur.startAt()), micros(next.startAt()))) {
            locked.add("startAt");
        }
        if (!Objects.equals(cur.linkType(), next.linkType()) || !Objects.equals(cur.linkCode(), next.linkCode())) {
            locked.add("link");
        }
        if (!Objects.equals(cur.ctaLabel(), next.ctaLabel()) || !Objects.equals(cur.ctaTarget(), next.ctaTarget())) {
            locked.add("cta");
        }
        if (!Objects.equals(cur.frequency(), next.frequency())) {
            locked.add("frequency");
        }
        if (cur.dismissible() != next.dismissible()) {
            locked.add("dismissible");
        }
        if (!Objects.equals(cur.style(), next.style())) {
            locked.add("style");
        }
        return locked;
    }

    private static Instant micros(Instant i) {
        return i == null ? null : i.truncatedTo(ChronoUnit.MICROS);
    }

    /** Pubblico normalizzato: le tre liste sempre presenti, codici in maiuscolo; altre chiavi conservate. */
    private JsonNode audience(JsonNode in) {
        ObjectNode out = in != null && in.isObject() ? ((ObjectNode) in).deepCopy() : mapper.createObjectNode();
        for (String key : List.of("tiers", "segments", "statuses")) {
            ArrayNode arr = mapper.createArrayNode();
            JsonNode src = out.get(key);
            if (src != null && src.isArray()) {
                src.forEach(v -> {
                    String s = upper(v.asString(null));
                    if (s != null) {
                        arr.add(s);
                    }
                });
            }
            out.set(key, arr);
        }
        return out;
    }

    private static Map<String, Object> snapshot(ContentItem c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", c.kind());
        m.put("placement", c.placement());
        m.put("title", c.title());
        m.put("body", c.body());
        m.put("imageUrl", c.imageUrl());
        m.put("ctaLabel", c.ctaLabel());
        m.put("ctaTarget", c.ctaTarget());
        m.put("link", c.linkType() + (c.linkCode() == null ? "" : ":" + c.linkCode()));
        m.put("audience", Objects.toString(c.audience(), null));
        m.put("startAt", Objects.toString(c.startAt(), null));
        m.put("endAt", Objects.toString(c.endAt(), null));
        m.put("priority", c.priority());
        m.put("frequency", c.frequency());
        m.put("dismissible", c.dismissible());
        m.put("style", Objects.toString(c.style(), null));
        return m;
    }

    private static String pick(String value, String fallback) {
        return value != null ? value : fallback;
    }

    private static String trim(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String upper(String s) {
        return s == null || s.isBlank() ? null : s.trim().toUpperCase();
    }
}
