package io.loyaltyhub.catalogredemption.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;

/**
 * Premio del catalogo (RF-14, RF-74..RF-76): tipi di premio del dominio utility.
 * VOUCHER buono digitale con codice; PHYSICAL premio fisico; SERVICE servizio (es. manutenzione caldaia);
 * CASHBACK accredito in bolletta via il sistema di billing; DISCOUNT_PERCENT / DISCOUNT_VALUE codice sconto
 * percentuale o a valore (su offerte e negozio); FREE_SERVICE servizio gratuito (es. consegna o attivazione gratuita);
 * EVENT_INVITATION invito a evento; GIFT omaggio senza costo in punti (es. benefit di tier); DONATION donazione a ONLUS.
 * I codici dei buoni possono venire da un lotto caricato dal backoffice ({@code couponPoolId}) o dal fornitore (RewardFulfiller).
 */
public record RewardDefinition(
        String id,
        String name,
        Type type,
        BigDecimal valueEur,
        long pointsCost,
        int minTierOrder,
        long stock,
        /** riscatti massimi per membro sull'intero periodo (0 = illimitati) e per giorno */
        int limitPerMember,
        int limitPerMemberPerDay,
        /** finestra di visibilità nel catalogo e finestra di riscatto (RF-42) */
        Instant visibleFrom, Instant visibleTo, Instant activeFrom, Instant activeTo,
        /** destinatari: segmenti ammessi (vuoto = tutti); il tier minimo è {@code minTierOrder} */
        Set<String> targetSegments,
        String category,
        String couponPoolId,
        /** validità del codice/buono dal riscatto, in giorni (0 = senza scadenza) */
        int codeValidityDays,
        boolean active
) {
    public enum Type { VOUCHER, PHYSICAL, SERVICE, CASHBACK, DISCOUNT_PERCENT, DISCOUNT_VALUE, FREE_SERVICE, EVENT_INVITATION, GIFT, DONATION }

    public boolean deliversCode() {
        return type == Type.VOUCHER || type == Type.DISCOUNT_PERCENT || type == Type.DISCOUNT_VALUE || type == Type.EVENT_INVITATION;
    }

    /** Premi che non consumano stock fisico (il tetto è solo economico/di campagna). */
    public boolean unlimitedStock() { return stock < 0; }
}
