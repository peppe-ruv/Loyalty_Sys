package io.loyaltyhub.common.web;

import io.loyaltyhub.common.identity.MemberSubjectLookup;
import io.loyaltyhub.common.identity.SubjectRef;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Risolve il membro di una richiesta a un handler {@link MemberEndpoint} (Q-410, ADR-048, docs/06 §3.4): dal token nel
 * profilo {@code enterprise} ({@link IdentityMode#OIDC}), dal {@code memberId} esplicito o da {@code X-LH-Member} nel
 * profilo {@code demo} ({@link IdentityMode#HEADER}, CLAUDE.md regola 6-bis). Lo usa {@link EndpointAccessInterceptor}; il
 * risultato è un attributo di richiesta ({@link MemberPrincipal#ATTRIBUTE}, {@link MemberSubject#ATTRIBUTE}) che l'argument
 * resolver consegna al controller.
 *
 * <p>{@code oidc}, nell'ordine (Q-553, D6):
 * <ol>
 *   <li>header {@code X-LH-Member}, oppure un parametro (query o campo form) {@code memberId} in qualunque grafia ⇒
 *       {@code 400 MEMBER_FROM_TOKEN}, anche se è il proprio;</li>
 *   <li>{@link MemberEndpoint#demoPathVariable()} presente nel percorso ⇒ {@code 403 MEMBER_FROM_TOKEN};</li>
 *   <li>nessun token di solo membro (un operatore, un token misto: Q-554): {@code REQUIRED} e {@code REGISTRATION} ⇒
 *       {@code 403 MEMBER_REQUIRED}; {@code OPTIONAL} ⇒ principal {@link MemberPrincipal.Origin#NONE};</li>
 *   <li>{@link SubjectRef} di {@code (iss, sub)} e ricerca nella lookup del modulo dell'handler: trovato ⇒ principal
 *       {@code TOKEN}, attore e MDC {@code member:<id>}; non trovato: {@code REQUIRED} ⇒ {@code 404
 *       MEMBER_NOT_REGISTERED} se la lookup è autorevole, altrimenti {@code 409 MEMBER_NOT_LINKED}; {@code OPTIONAL} ⇒
 *       {@code NONE}; {@code REGISTRATION} ⇒ un {@link MemberSubject} (con il membro già legato, se c'è).</li>
 * </ol>
 *
 * <p>{@code demo}: l'attore {@code SOURCE} ⇒ {@code 403 FORBIDDEN_ROLE}; le fonti del membro sono
 * {@code X-LH-Member} (forma {@code MBR-nnnnnn}, altrimenti 400), il parametro {@code memberId} e la variabile di
 * percorso legacy: due fonti diverse ⇒ {@code 400 MEMBER_MISMATCH}. L'id può mancare (la validazione resta dove è oggi,
 * quindi le risposte demo restano identiche); se c'è, l'attore diventa {@code member:<id>}.
 */
public class MemberPrincipals {

    private static final Logger log = LoggerFactory.getLogger(MemberPrincipals.class);

    /** Header del profilo demo che il BFF mette dalla persona (Q-555); mai in {@code oidc}. */
    public static final String MEMBER_HEADER = "X-LH-Member";

    private static final Pattern DEMO_MEMBER = Pattern.compile("^MBR-[0-9]{6}$");
    private static final Pattern INDEX_SUFFIX = Pattern.compile("\\[[^\\]]*\\]$");

    private final IdentityMode mode;
    private final byte[] subjectKey;
    private final Supplier<? extends Collection<? extends MemberSubjectLookup>> lookupSource;
    private volatile List<MemberSubjectLookup> lookups;

    /**
     * @param mode       {@code HEADER} (demo) o {@code OIDC} (enterprise)
     * @param subjectKey chiave dello pseudonimo (solo {@code oidc}; già verificata da {@code IdentityGuard})
     * @param lookups    le lookup dei moduli presenti nel processo (uno per servizio; tutti nell'hub)
     */
    public MemberPrincipals(IdentityMode mode, byte[] subjectKey, Collection<? extends MemberSubjectLookup> lookups) {
        this(mode, subjectKey, () -> lookups == null ? List.of() : lookups);
    }

    /**
     * Come sopra, con le lookup lette alla prima richiesta (i bean dei servizi non si creano mentre si raccolgono i
     * configuratori di Spring MVC).
     */
    public MemberPrincipals(IdentityMode mode, byte[] subjectKey,
                            Supplier<? extends Collection<? extends MemberSubjectLookup>> lookupSource) {
        this.mode = mode == null ? IdentityMode.HEADER : mode;
        this.subjectKey = subjectKey == null ? null : subjectKey.clone();
        this.lookupSource = lookupSource;
    }

    /** Profilo demo: nessuna lookup, nessuna chiave. */
    public static MemberPrincipals header() {
        return new MemberPrincipals(IdentityMode.HEADER, null, List.of());
    }

    public IdentityMode mode() {
        return mode;
    }

    /** Vero se è configurata una chiave dello pseudonimo (mai il valore). */
    boolean hasSubjectKey() {
        return subjectKey != null && subjectKey.length >= SubjectRef.MIN_KEY_BYTES;
    }

    /**
     * Le lookup del modulo di {@code beanType}: quelle col prefisso di package più lungo di
     * {@link MemberSubjectLookup#modulePackage()} (nell'hub convivono tutte). Vuoto se nessun modulo la fornisce; più di
     * una se due lookup dichiarano lo stesso package (configurazione sbagliata, la rifiuta {@link MemberEndpointGuard}).
     */
    List<MemberSubjectLookup> lookupsFor(Class<?> beanType) {
        String pkg = beanType.getPackageName();
        int best = -1;
        List<MemberSubjectLookup> found = new ArrayList<>();
        for (MemberSubjectLookup lookup : lookups()) {
            String module = lookup.modulePackage();
            if (module == null || module.isBlank() || !(pkg.equals(module) || pkg.startsWith(module + "."))) {
                continue;
            }
            if (module.length() > best) {
                best = module.length();
                found.clear();
            }
            if (module.length() == best) {
                found.add(lookup);
            }
        }
        return found;
    }

    private List<MemberSubjectLookup> lookups() {
        List<MemberSubjectLookup> resolved = lookups;
        if (resolved == null) {
            Collection<? extends MemberSubjectLookup> source = lookupSource.get();
            resolved = source == null ? List.of() : List.copyOf(source);
            lookups = resolved;
        }
        return resolved;
    }

    /** Risolve il membro e imposta gli attributi di richiesta, l'attore e l'MDC; lancia {@link LhException} se rifiutato. */
    void bind(HttpServletRequest request, MemberEndpoint declaration, HandlerMethod handler) {
        if (mode == IdentityMode.OIDC) {
            bindToken(request, declaration, handler);
        } else {
            bindDemo(request, declaration);
        }
    }

    // ---- enterprise: il membro solo dal token ----

    private void bindToken(HttpServletRequest request, MemberEndpoint declaration, HandlerMethod handler) {
        if (request.getHeader(MEMBER_HEADER) != null || hasMemberIdParameter(request)) {
            throw LhException.memberFromToken(false);
        }
        if (!declaration.demoPathVariable().isBlank() && pathVariables(request).containsKey(declaration.demoPathVariable())) {
            throw LhException.memberFromToken(true);
        }
        MemberEndpoint.Mode kind = declaration.value();
        if (!(request.getAttribute(OidcActorFilter.MEMBER_TOKEN_ATTRIBUTE) instanceof MemberTokenClaims claims)) {
            // Un operatore o un token misto non agisce mai come membro (Q-554).
            if (kind == MemberEndpoint.Mode.OPTIONAL) {
                request.setAttribute(MemberPrincipal.ATTRIBUTE, MemberPrincipal.none());
                return;
            }
            throw LhException.memberRequired();
        }
        List<MemberSubjectLookup> candidates = lookupsFor(handler.getBeanType());
        if (!hasSubjectKey() || candidates.size() != 1
                || (kind == MemberEndpoint.Mode.REGISTRATION && !candidates.get(0).authoritative())) {
            // Non si raggiunge: MemberEndpointGuard rifiuta l'avvio. Chiude comunque, mai aperto.
            log.error("Membro dal token non risolvibile su {}#{}: lookup o chiave non configurate",
                    handler.getBeanType().getName(), handler.getMethod().getName());
            throw LhException.endpointNotDeclared();
        }
        MemberSubjectLookup lookup = candidates.get(0);
        String ref = SubjectRef.of(subjectKey, claims.issuer(), claims.subject());
        Optional<String> linked = lookup.memberId(ref);
        if (kind == MemberEndpoint.Mode.REGISTRATION) {
            request.setAttribute(MemberSubject.ATTRIBUTE,
                    new MemberSubject(claims.issuer(), claims.subject(), ref, linked.orElse(null)));
            linked.ifPresent(MemberPrincipals::actAs);
            return;
        }
        if (linked.isPresent()) {
            request.setAttribute(MemberPrincipal.ATTRIBUTE, MemberPrincipal.token(linked.get()));
            actAs(linked.get());
            return;
        }
        if (kind == MemberEndpoint.Mode.OPTIONAL) {
            request.setAttribute(MemberPrincipal.ATTRIBUTE, MemberPrincipal.none());
            return;
        }
        throw lookup.authoritative() ? LhException.memberNotRegistered() : LhException.memberNotLinked();
    }

    // ---- demo: memberId esplicito o X-LH-Member ----

    private void bindDemo(HttpServletRequest request, MemberEndpoint declaration) {
        if (ActorHolder.get().role() == Role.SOURCE) {
            // Parità con le letture R5 di oggi: l'utenza di integrazione non usa il portale.
            throw LhException.forbiddenRole("Il ruolo SOURCE non può eseguire questa operazione");
        }
        if (declaration.value() == MemberEndpoint.Mode.REGISTRATION) {
            request.setAttribute(MemberSubject.ATTRIBUTE, MemberSubject.demo());
            return;
        }
        Set<String> sources = new LinkedHashSet<>();
        String header = request.getHeader(MEMBER_HEADER);
        if (header != null && !header.isBlank()) {
            if (!DEMO_MEMBER.matcher(header.trim()).matches()) {
                throw LhException.badRequest("Header X-LH-Member non valido: atteso MBR-nnnnnn");
            }
            sources.add(header.trim());
        }
        String param = request.getParameter("memberId");
        if (param != null) {
            sources.add(param);
        }
        if (!declaration.demoPathVariable().isBlank()) {
            String path = pathVariables(request).get(declaration.demoPathVariable());
            if (path != null) {
                sources.add(path);
            }
        }
        if (sources.size() > 1) {
            throw LhException.memberMismatch();
        }
        String id = sources.isEmpty() ? null : sources.iterator().next();
        request.setAttribute(MemberPrincipal.ATTRIBUTE, MemberPrincipal.demo(id));
        if (id != null) {
            actAs(id);
        }
    }

    // ---- supporto ----

    /** L'attore e l'MDC diventano {@code member:<id>} (Q-556); li ripulisce il filtro di identità a fine richiesta. */
    private static void actAs(String memberId) {
        ActorContext actor = ActorContext.member(memberId);
        ActorHolder.set(actor);
        MDC.put("actor", actor.asActorString());
    }

    /** Vero se la richiesta ha un parametro (query o campo form) {@code memberId} in qualunque grafia. */
    static boolean hasMemberIdParameter(HttpServletRequest request) {
        for (String name : request.getParameterMap().keySet()) {
            if (isMemberIdName(name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Vero se {@code name} è {@code memberId} in qualunque grafia: senza distinguere maiuscole, con o senza {@code _} e
     * {@code -} ({@code member_id}, {@code MEMBER-ID}), anche come ultimo segmento di un percorso di proprietà
     * ({@code filter.memberId}, {@code items[0].memberId}).
     */
    public static boolean isMemberIdName(String name) {
        if (name == null) {
            return false;
        }
        String last = name.substring(name.lastIndexOf('.') + 1);
        last = INDEX_SUFFIX.matcher(last).replaceFirst("");
        return last.replace("-", "").replace("_", "").toLowerCase(Locale.ROOT).equals("memberid");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> pathVariables(HttpServletRequest request) {
        Object vars = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        return vars instanceof Map<?, ?> map ? (Map<String, String>) map : Map.of();
    }
}
