package io.loyaltyhub.common.web;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

/**
 * Autorizzazione degli endpoint prima del controller: deny by default (F2-SEC-09, ADR-042, CLAUDE.md regola 18,
 * docs/06 §3.2), in ogni profilo.
 *
 * <ul>
 *   <li>Ogni metodo di controller dichiara l'accesso con una delle <strong>tre</strong> dichiarazioni
 *       ({@link RequiresRole}, {@link PublicEndpoint}, {@link MemberEndpoint}), sul metodo o sulla classe; la
 *       dichiarazione sul metodo prevale. Se il metodo o la classe portano insieme {@link RequiresRole} e
 *       {@link PublicEndpoint}, vince {@link RequiresRole} (la più restrittiva).</li>
 *   <li>{@link RequiresRole}: semantica invariata (docs/06 §3); violata ⇒ {@link LhException#forbiddenRole}.</li>
 *   <li>{@link PublicEndpoint} con motivo non vuoto: nessun controllo di ruolo.</li>
 *   <li>{@link MemberEndpoint}: il membro viene dal token ({@code enterprise}) o da {@code memberId}/{@code X-LH-Member}
 *       ({@code demo}); l'interceptor lo risolve ({@link MemberPrincipals}) e lo passa al controller come
 *       {@link MemberPrincipal} (Q-410, ADR-048). Insieme a {@link RequiresRole} o {@link PublicEndpoint} sullo stesso
 *       elemento è una combinazione non valida ⇒ {@code ENDPOINT_NOT_DECLARED}.</li>
 *   <li>Nessuna dichiarazione valida, o un {@code @PublicEndpoint} senza motivo ⇒ {@link LhException#endpointNotDeclared}
 *       ({@code 403 ENDPOINT_NOT_DECLARED}) per chiunque, anche per un token di membro; il log dice solo classe e
 *       metodo, mai percorso, parametri o attore.</li>
 * </ul>
 *
 * <p><strong>Token di un membro</strong> ({@code enterprise}, attore {@link ActorContext#member()}): raggiunge soltanto gli
 * handler {@link MemberEndpoint} e le letture {@code @RequiresRole(..., members = true)}; ogni altro handler ⇒
 * {@code 403 FORBIDDEN_ROLE}, e un {@code memberId} (qualunque grafia) o {@code X-LH-Member} nella richiesta ⇒
 * {@code 400 MEMBER_FROM_TOKEN}. Chiude il BOLA a livello di servizio: prima un token di membro valeva {@code ANALYST} e
 * passava ogni lettura del portale.
 *
 * <p>Restano fuori i soli controller dei framework indicati in {@link #FRAMEWORK_PACKAGES} ({@code /error} di Spring
 * Boot, OpenAPI di springdoc): non sono codice del prodotto e non portano annotazioni; nel profilo {@code enterprise}
 * li protegge comunque il filtro OIDC. Un controller in qualunque altro package, anche {@code org.springframework.*}
 * (per esempio Spring Data REST), entra nel prodotto e resta soggetto al deny by default.
 *
 * <p>Il controllo gira sulla sola richiesta iniziale: un dispatch {@link DispatcherType#ASYNC} (risultato di una
 * chiamata asincrona) riguarda una richiesta già autorizzata all'ingresso e non si ricontrolla.
 *
 * <p>{@link #resolve(HandlerMethod)} è l'unica risoluzione della dichiarazione: la usano l'interceptor, l'advice del
 * corpo e i test che percorrono tutti i handler registrati.
 */
public class EndpointAccessInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(EndpointAccessInterceptor.class);

    /**
     * Package dei controller dei framework, esclusi dal deny by default. Elenco stretto: mai l'intero
     * {@code org.springframework.}.
     */
    static final List<String> FRAMEWORK_PACKAGES =
            List.of("org.springframework.boot.", "org.springframework.web.servlet.", "org.springdoc.");

    /**
     * Dichiarazione di accesso risolta per un handler: quella del metodo, se ne porta una, altrimenti quella della classe
     * (le tre annotazioni possono essere presenti insieme, e allora la dichiarazione non è valida se c'è
     * {@code member}; se sono tutte nulle l'handler non dichiara nulla).
     */
    public record Declaration(RequiresRole role, PublicEndpoint open, MemberEndpoint member) {

        /** Dichiarazione senza il membro (compatibilità). */
        public Declaration(RequiresRole role, PublicEndpoint open) {
            this(role, open, null);
        }

        /** {@link MemberEndpoint} insieme a {@link RequiresRole} o {@link PublicEndpoint} sullo stesso livello. */
        public boolean conflicting() {
            return member != null && (role != null || open != null);
        }

        /**
         * Valida: un {@link RequiresRole}, oppure un {@link PublicEndpoint} con motivo non vuoto, oppure un
         * {@link MemberEndpoint} da solo.
         */
        public boolean valid() {
            return !conflicting() && (role != null || member != null || (open != null && !open.reason().isBlank()));
        }
    }

    private final MemberPrincipals principals;

    /**
     * Solo per il profilo demo e per i test: il membro da {@code X-LH-Member} o dal {@code memberId} esplicito, senza
     * lookup né chiave ({@link MemberPrincipals#header()}). Non è il cablaggio di produzione, che passa da
     * {@code LhCommonAutoConfiguration} con la modalità configurata; un interceptor costruito così non risolve mai un
     * token di membro e {@link MemberPrincipals} rifiuta con {@code ENDPOINT_NOT_DECLARED} una richiesta che ne porta uno.
     */
    public EndpointAccessInterceptor() {
        this(MemberPrincipals.header());
    }

    /**
     * @param principals il risolutore del membro, mai {@code null}: un valore mancante non ripiega sul risolutore demo
     *                   (accetterebbe {@code X-LH-Member} e il {@code memberId} esplicito, regole 6-bis e 22)
     * @throws IllegalArgumentException se {@code principals} è {@code null}
     */
    public EndpointAccessInterceptor(MemberPrincipals principals) {
        if (principals == null) {
            throw new IllegalArgumentException("Il risolutore del membro è obbligatorio: nessun ripiego sul profilo demo");
        }
        this.principals = principals;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod method)) {
            return true;
        }
        if (request.getDispatcherType() == DispatcherType.ASYNC) {
            return true;
        }
        Class<?> beanType = method.getBeanType();
        if (isFramework(beanType)) {
            return true;
        }
        Declaration declaration = resolve(method);
        if (!declaration.valid()) {
            log.warn("Endpoint rifiutato: nessuna dichiarazione di accesso valida (deny by default) su {}#{}",
                    beanType.getName(), method.getMethod().getName());
            throw LhException.endpointNotDeclared();
        }
        if (declaration.member() != null) {
            principals.bind(request, declaration.member(), method);
            return true;
        }
        ActorContext actor = ActorHolder.get();
        if (actor.member()) {
            // Token di solo membro (solo enterprise): mai dai parametri, e solo funzioni del membro o letture aperte.
            if (request.getHeader(MemberPrincipals.MEMBER_HEADER) != null || MemberPrincipals.hasMemberIdParameter(request)) {
                throw LhException.memberFromToken(false);
            }
            if (declaration.role() != null && declaration.role().members()) {
                check(declaration.role());
                return true;
            }
            if (declaration.role() == null) {
                return true; // @PublicEndpoint con motivo (oggi nessuno)
            }
            throw LhException.forbiddenRole("Un membro può usare solo le funzioni del portale a lui dedicate");
        }
        if (declaration.role() != null) {
            check(declaration.role());
        }
        return true;
    }

    /**
     * La dichiarazione di accesso di un handler: quella del metodo (anche di un metodo di superclasse o di interfaccia
     * che viene sovrascritto); se il metodo non ne porta, quella della classe. Vince {@link RequiresRole} su
     * {@link PublicEndpoint}; {@link MemberEndpoint} con una delle altre due non è valida ({@link Declaration#valid()}).
     */
    public static Declaration resolve(HandlerMethod method) {
        return resolve(method.getMethod(), method.getBeanType());
    }

    /** Come {@link #resolve(HandlerMethod)} dal metodo e dalla classe del bean (usata dall'advice del corpo). */
    public static Declaration resolve(Method method, Class<?> beanType) {
        RequiresRole role = AnnotatedElementUtils.findMergedAnnotation(method, RequiresRole.class);
        PublicEndpoint open = AnnotatedElementUtils.findMergedAnnotation(method, PublicEndpoint.class);
        MemberEndpoint member = AnnotatedElementUtils.findMergedAnnotation(method, MemberEndpoint.class);
        if (role == null && open == null && member == null) {
            role = AnnotatedElementUtils.findMergedAnnotation(beanType, RequiresRole.class);
            open = AnnotatedElementUtils.findMergedAnnotation(beanType, PublicEndpoint.class);
            member = AnnotatedElementUtils.findMergedAnnotation(beanType, MemberEndpoint.class);
        }
        return new Declaration(role, open, member);
    }

    private static void check(RequiresRole annotation) {
        Role role = ActorHolder.get().role();
        if (role == Role.ADMIN) {
            return;
        }
        Role[] allowed = annotation.value();
        if (allowed.length == 0) {
            // Regola "scrittura": qualunque ruolo operatore tranne ANALYST; SOURCE (integrazione, Q-492) arriva solo
            // dove è elencato.
            if (role.isReadOnly() || role.isIntegration()) {
                throw LhException.forbiddenRole("Il ruolo " + role + " non può eseguire questa operazione");
            }
            return;
        }
        if (Arrays.asList(allowed).contains(role)) {
            return;
        }
        throw LhException.forbiddenRole("Serve uno dei ruoli " + Arrays.toString(allowed) + " (attuale: " + role + ")");
    }

    /** Vero per i controller dei framework esclusi dal deny by default ({@link #FRAMEWORK_PACKAGES}). */
    public static boolean isFramework(Class<?> beanType) {
        String name = beanType.getName();
        return FRAMEWORK_PACKAGES.stream().anyMatch(name::startsWith);
    }
}
