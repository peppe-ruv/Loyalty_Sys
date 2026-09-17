package it.iren.loyalty.segmentservice.domain;

import java.util.List;
import java.util.Map;

/**
 * Segmento di membri (RF-65, RF-71): insieme di criteri valutati sul profilo e sulla storia delle azioni.
 * Copre i 14 criteri dei segmenti di Open Loyalty, tradotti sul dominio Iren (transazione = azione con importo,
 * POS = canale, prodotto = riga con SKU/etichetta/marca/categoria) più tier, saldo e consensi.
 * I segmenti sono ricalcolati dallo scheduler ({@code segments.recompute-cron}) e a ogni evento del membro; l'ingresso
 * in un segmento emette l'azione interna SEGMENT_ENTERED, così una regola può premiarlo.
 */
public record Segment(String id, String name, boolean active, Match match, List<Criterion> criteria) {
    /** Tutti i criteri (AND) oppure almeno uno (OR). */
    public enum Match { ALL, ANY }

    public enum Type {
        /** anniversario di adesione entro N giorni (param days) */
        ANNIVERSARY,
        /** importo medio delle azioni con importo (min, max, actionType?, days?) */
        AVG_ACTION_VALUE,
        /** numero di azioni (min, max, actionType?, days?) */
        ACTION_COUNT,
        /** somma degli importi (min, max, actionType?, days?) */
        ACTION_VALUE,
        /** ultima azione tra min e max giorni fa */
        LAST_ACTION_DAYS_AGO,
        /** almeno un'azione tra le date from e to (ISO-8601) */
        ACTION_PERIOD,
        /** righe con uno degli SKU (values) */
        BOUGHT_SKU,
        /** righe con una delle etichette (values) */
        BOUGHT_LABEL,
        /** righe di una delle marche (values) */
        BOUGHT_BRAND,
        /** righe di una delle categorie (values) */
        BOUGHT_CATEGORY,
        /** almeno un'azione da uno dei canali (values) */
        ACTION_IN_CHANNEL,
        /** quota di importo nel canale (channel) almeno minPercent */
        CHANNEL_SHARE,
        /** il membro ha una delle etichette (values, solo chiave) */
        HAS_LABEL,
        /** etichetta del membro con valore (key, value) */
        LABEL_VALUE,
        /** lista statica di id membro (values), es. da CSV caricato dal backoffice */
        STATIC_LIST,
        /** tier corrente tra (values) */
        TIER,
        /** saldo PREMIO disponibile tra min e max */
        POINTS_BALANCE,
        /** consenso (key) con valore true */
        CONSENT
    }

    /** Criterio con parametri liberi; le chiavi riconosciute sono documentate su {@link Type}. */
    public record Criterion(Type type, Map<String, Object> params) {
        public String str(String k) { Object v = params == null ? null : params.get(k); return v == null ? null : v.toString(); }
        public Double num(String k) { String s = str(k); return s == null ? null : Double.parseDouble(s); }
        public int days() { Double d = num("days"); return d == null ? Integer.MAX_VALUE : d.intValue(); }
        @SuppressWarnings("unchecked")
        public List<String> values() {
            Object v = params == null ? null : params.get("values");
            if (v instanceof List<?> l) return l.stream().map(String::valueOf).toList();
            return v == null ? List.of() : List.of(v.toString().split(","));
        }
    }
}
