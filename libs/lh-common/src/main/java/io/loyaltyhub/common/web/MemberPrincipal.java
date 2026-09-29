package io.loyaltyhub.common.web;

import java.util.Objects;

/**
 * Il membro di una richiesta a un handler {@link MemberEndpoint} (Q-410, ADR-048): l'unico modo in cui un controller del
 * portale ottiene il {@code memberId}. Lo costruisce {@link EndpointAccessInterceptor}; il controller non legge
 * {@code memberId} da query, corpo, percorso o header.
 *
 * <ul>
 *   <li>{@link Origin#TOKEN} ({@code enterprise}): il membro è risolto dal token; {@link #idOrNull()} non è mai nullo.</li>
 *   <li>{@link Origin#DEMO} ({@code demo}): il membro è il {@code memberId} esplicito o l'header
 *       {@code X-LH-Member}; può mancare (la validazione resta dove è oggi, quindi le risposte demo restano identiche).</li>
 *   <li>{@link Origin#NONE}: nessun membro (un operatore su un handler {@link MemberEndpoint.Mode#OPTIONAL}): vista
 *       generica.</li>
 * </ul>
 *
 * <p>Gli errori sono {@link LhException} con detail in italiano che non ripetono mai l'id ricevuto.
 */
public record MemberPrincipal(String memberId, Origin origin) {

    /** Attributo di richiesta con il principal risolto (letto dall'argument resolver, mai da un controller). */
    public static final String ATTRIBUTE = "io.loyaltyhub.member.principal";

    /** Da dove viene il membro. */
    public enum Origin {
        TOKEN, DEMO, NONE
    }

    public MemberPrincipal {
        Objects.requireNonNull(origin, "origin");
        if (origin == Origin.TOKEN && (memberId == null || memberId.isBlank())) {
            throw new IllegalArgumentException("Un principal da token ha sempre il memberId");
        }
        if (origin == Origin.NONE && memberId != null) {
            throw new IllegalArgumentException("Un principal NONE non ha il memberId");
        }
    }

    /** Principal risolto dal token. */
    public static MemberPrincipal token(String memberId) {
        return new MemberPrincipal(memberId, Origin.TOKEN);
    }

    /** Principal del profilo demo (l'id può mancare). */
    public static MemberPrincipal demo(String memberIdOrNull) {
        return new MemberPrincipal(memberIdOrNull, Origin.DEMO);
    }

    /** Nessun membro (vista generica). */
    public static MemberPrincipal none() {
        return new MemberPrincipal(null, Origin.NONE);
    }

    /** {@code TOKEN}: l'id; {@code DEMO}: l'id esplicito o {@code null}; {@code NONE}: {@code null}. */
    public String idOrNull() {
        return memberId;
    }

    /** Vero se la richiesta ha un membro (con un id). */
    public boolean present() {
        return memberId != null;
    }

    /**
     * L'id del membro, obbligatorio. {@code TOKEN}: l'id. {@code DEMO} senza id: {@code 400 BAD_REQUEST «Parametro
     * obbligatorio assente: memberId»}, identico a ciò che Spring risponde oggi a un parametro di query obbligatorio
     * assente. {@code NONE}: {@code 403 MEMBER_REQUIRED} (non si raggiunge su un handler {@code REQUIRED}).
     */
    public String requireParam() {
        if (memberId != null) {
            return memberId;
        }
        if (origin == Origin.NONE) {
            throw LhException.memberRequired();
        }
        throw LhException.badRequest("Parametro obbligatorio assente: memberId");
    }

    /**
     * Fonde il {@code memberId} di un corpo legacy (campo deprecato, solo profilo demo) con il membro della richiesta.
     * {@code TOKEN}: un valore non nullo, anche uguale al proprio, dà {@code 400 MEMBER_FROM_TOKEN}; nullo ⇒ l'id del
     * token. {@code DEMO}: nullo ⇒ l'id del principal; uguale ⇒ quello; il principal senza id ⇒ quello del corpo;
     * diversi ⇒ {@code 400 MEMBER_MISMATCH}. {@code NONE}: il corpo non può portare un membro
     * ({@code 400 MEMBER_FROM_TOKEN} se non nullo), altrimenti {@code null}.
     */
    public String merge(String legacyBody) {
        if (origin != Origin.DEMO) {
            if (legacyBody != null) {
                throw LhException.memberFromToken(false);
            }
            return memberId;
        }
        if (legacyBody == null) {
            return memberId;
        }
        if (memberId == null || memberId.equals(legacyBody)) {
            return legacyBody;
        }
        throw LhException.memberMismatch();
    }

    /**
     * Il membro della richiesta deve essere il proprietario dell'oggetto: con un membro presente e diverso da
     * {@code owner} ⇒ {@code 404 NOT_FOUND} (l'esistenza dell'oggetto di un altro membro non si rivela); senza membro
     * (solo demo, dove l'id resta facoltativo) passa.
     */
    public void checkOwner(String owner) {
        checkOwner(owner, "Risorsa non trovata");
    }

    /** Come {@link #checkOwner(String)} con il detail del {@code 404} scelto dal chiamante (in demo: quello di oggi). */
    public void checkOwner(String owner, String notFoundDetail) {
        if (memberId != null && !memberId.equals(owner)) {
            throw LhException.notFound(notFoundDetail);
        }
    }
}
