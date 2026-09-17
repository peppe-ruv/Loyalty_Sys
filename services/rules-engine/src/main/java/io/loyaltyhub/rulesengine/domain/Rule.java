package io.loyaltyhub.rulesengine.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Regola punti dichiarativa (RF-05, RF-63..RF-66): tipo azione, condizioni sugli attributi, punti nelle due valute,
 * punti proporzionali all'importo, moltiplicatore di campagna e per tier, filtro sulle righe, target (tier, segmenti,
 * canali), tetti e limiti d'uso, periodo, punti in sospeso. Versionata e immutabile una volta pubblicata (RF-06).
 * Le regole vengono dal backoffice/CMS tramite {@link RuleSource}; qui non c'è codice per regola.
 *
 * <p>Copre i tipi di maturazione previsti: spesa generica ({@link #pointsPerEur}), moltiplicatore sui punti maturati
 * ({@link #multiplier}, anche per etichetta tramite {@link LineFilter}), acquisto di prodotto (condizioni su righe),
 * azione personalizzata (tipo azione), referral, geolocalizzazione ({@link Operator#GEO_WITHIN}), codice QR
 * (azione CODE_REDEEMED), premio immediato (campo {@link #autoRewardId}).
 */
public record Rule(
        String id,
        String version,
        String actionType,
        List<Condition> conditions,
        long rewardPoints,
        long statusPoints,
        /** moltiplicatore per codice tier, es. {"PLUS": 1.25, "TOP": 1.5}; assente = 1 */
        Map<String, BigDecimal> tierMultipliers,
        /** tetto di punti PREMIO per membro nel periodo (0 = nessun tetto) */
        long capPerMemberPerPeriod,
        Instant validFrom,
        Instant validTo,
        boolean stackable,
        Earning earning,
        Target target,
        Limits limits
) {
    public record Condition(String attribute, Operator op, String value) {}

    /**
     * Operatori: confronto, appartenenza, {@code CONTAINS} su attributi lista (es. etichette), {@code MATCHES} regex
     * (es. prefisso di un codice), {@code EXISTS}, {@code GEO_WITHIN} con valore {@code "lat,lon,raggioMetri"} contro
     * gli attributi {@code lat}/{@code lon} dell'azione.
     */
    public enum Operator { EQ, NE, GT, GTE, LT, LTE, IN, CONTAINS, MATCHES, EXISTS, GEO_WITHIN }

    /**
     * Componente proporzionale e moltiplicatori (RF-63, RF-64).
     * @param pointsPerEur punti PREMIO per euro dell'importo (0 = solo punti fissi)
     * @param amountAttribute attributo dell'importo (default {@code amountEur}); se {@code lines} è presente e c'è un
     *                        {@link LineFilter}, l'importo è la somma delle righe che passano il filtro
     * @param multiplier moltiplicatore di campagna (es. 2 per "punti doppi"), applicato prima di quello per tier
     * @param lineFilter filtro sulle righe della transazione
     * @param autoRewardId premio assegnato automaticamente all'applicazione della regola ("instant reward")
     */
    public record Earning(BigDecimal pointsPerEur, String amountAttribute, BigDecimal multiplier, LineFilter lineFilter, String autoRewardId) {
        public static final Earning NONE = new Earning(BigDecimal.ZERO, "amountEur", BigDecimal.ONE, null, null);
        public BigDecimal multiplierOrOne() { return multiplier == null ? BigDecimal.ONE : multiplier; }
        public BigDecimal pointsPerEurOrZero() { return pointsPerEur == null ? BigDecimal.ZERO : pointsPerEur; }
        public String amountAttributeOrDefault() { return amountAttribute == null ? "amountEur" : amountAttribute; }
    }

    /** Filtro righe: SKU/etichette/categorie incluse o escluse; costi di consegna esclusi con l'etichetta {@code delivery}. */
    public record LineFilter(Set<String> includeSkus, Set<String> excludeSkus, Set<String> includeLabels, Set<String> excludeLabels, Set<String> excludeCategories) {
        public boolean accepts(io.loyaltyhub.common.event.TransactionLine l) {
            if (includeSkus != null && !includeSkus.isEmpty() && (l.sku() == null || !includeSkus.contains(l.sku()))) return false;
            if (excludeSkus != null && l.sku() != null && excludeSkus.contains(l.sku())) return false;
            if (includeLabels != null && !includeLabels.isEmpty() && includeLabels.stream().noneMatch(l::hasLabel)) return false;
            if (excludeLabels != null && excludeLabels.stream().anyMatch(l::hasLabel)) return false;
            if (excludeCategories != null && l.category() != null && excludeCategories.contains(l.category())) return false;
            return true;
        }
    }

    /** Destinatari (RF-65): tier, segmenti e canali ammessi; insiemi vuoti o nulli = tutti. */
    public record Target(Set<String> tiers, Set<String> segments, Set<String> channels) {
        public static final Target ALL = new Target(Set.of(), Set.of(), Set.of());
        public boolean admits(String tier, Set<String> memberSegments, String channel) {
            if (tiers != null && !tiers.isEmpty() && (tier == null || !tiers.contains(tier))) return false;
            if (segments != null && !segments.isEmpty() && (memberSegments == null || memberSegments.stream().noneMatch(segments::contains))) return false;
            if (channels != null && !channels.isEmpty() && (channel == null || !channels.contains(channel))) return false;
            return true;
        }
    }

    /**
     * Limiti (RF-66): usi massimi per membro nel periodo (0 = illimitati), giorni di sospensione prima che i punti
     * siano spendibili (finestra di ripensamento/reso), {@code stopAfter} = ultima regola eseguita.
     */
    public record Limits(int maxUsesPerMemberPerPeriod, int lockDays, boolean stopAfter) {
        public static final Limits NONE = new Limits(0, 0, false);
    }

    /** Costruttore compatto per regole a punti fissi (forma originale RF-05). */
    public Rule(String id, String version, String actionType, List<Condition> conditions, long rewardPoints, long statusPoints,
                Map<String, BigDecimal> tierMultipliers, long capPerMemberPerPeriod, Instant validFrom, Instant validTo, boolean stackable) {
        this(id, version, actionType, conditions, rewardPoints, statusPoints, tierMultipliers, capPerMemberPerPeriod, validFrom, validTo, stackable,
                Earning.NONE, Target.ALL, Limits.NONE);
    }

    public Rule {
        if (earning == null) earning = Earning.NONE;
        if (target == null) target = Target.ALL;
        if (limits == null) limits = Limits.NONE;
    }

    public boolean isActiveAt(Instant t) {
        return (validFrom == null || !t.isBefore(validFrom)) && (validTo == null || t.isBefore(validTo));
    }

    public BigDecimal multiplierFor(String tierCode) {
        if (tierMultipliers == null || tierCode == null) return BigDecimal.ONE;
        return tierMultipliers.getOrDefault(tierCode, BigDecimal.ONE);
    }
}
