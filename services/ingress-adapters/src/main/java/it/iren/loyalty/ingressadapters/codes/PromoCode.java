package it.iren.loyalty.ingressadapters.codes;

import java.time.Instant;

/**
 * Codice promozionale o QR (RF-69), equivalente della "QR code rule" di Open Loyalty: generato dal backoffice per una
 * campagna, valido in un periodo, con usi massimi totali e per membro. L'uso valido produce l'azione CODE_REDEEMED con
 * attributi {@code code} e {@code campaign}; i punti li decidono le regole. Kind: QR (stampato, es. sportello o evento),
 * PROMO (alfanumerico, es. newsletter), SINGLE_USE (lotto di codici monouso).
 */
public record PromoCode(String code, String campaign, Kind kind, Instant validFrom, Instant validTo, int maxUses, int maxUsesPerMember, boolean active) {
    public enum Kind { QR, PROMO, SINGLE_USE }

    public enum Verdict { OK, UNKNOWN, INACTIVE, NOT_YET_VALID, EXPIRED, EXHAUSTED, MEMBER_LIMIT }

    public Verdict verdict(Instant at, long usesSoFar, long usesByMember) {
        if (!active) return Verdict.INACTIVE;
        if (validFrom != null && at.isBefore(validFrom)) return Verdict.NOT_YET_VALID;
        if (validTo != null && !at.isBefore(validTo)) return Verdict.EXPIRED;
        int total = kind == Kind.SINGLE_USE ? 1 : maxUses;
        if (total > 0 && usesSoFar >= total) return Verdict.EXHAUSTED;
        int perMember = kind == Kind.SINGLE_USE ? 1 : maxUsesPerMember;
        if (perMember > 0 && usesByMember >= perMember) return Verdict.MEMBER_LIMIT;
        return Verdict.OK;
    }

    /** Normalizzazione dell'input utente: maiuscolo, senza spazi e trattini. */
    public static String normalize(String raw) { return raw == null ? "" : raw.trim().toUpperCase().replaceAll("[\\s-]", ""); }
}
