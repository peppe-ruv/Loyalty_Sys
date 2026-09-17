package io.loyaltyhub.readmodel.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Applica un evento a un {@link CustomerContext} restituendone uno nuovo (puro, testabile): azioni recenti (ultime 50),
 * conteggi a 30 giorni, RFM, wallet, tier, segmenti, badge, campagne, offerte, rischio, consensi. È il cuore del
 * Customer 360 "aggiornabile attraverso gli eventi".
 */
public final class ContextProjector {
    private ContextProjector() {}
    static final int RECENT = 50;
    /** Consegne conservate per far decadere la finestra dei contatti a 7 giorni. */
    static final int CONTACTS = 50;

    public static CustomerContext empty(String memberId) {
        return new CustomerContext(memberId, new CustomerContext.Identity("UNKNOWN", null, null, null, Map.of(), Map.of()),
                new CustomerContext.Loyalty(Map.of(), "BASE", 0, null, List.of()),
                new CustomerContext.Behaviour(List.of(), new CustomerContext.Rfm(null, 0, 0, 0, null, null), Map.of(), null),
                new CustomerContext.Engagement(List.of(), Map.of(), Map.of(), List.of(), List.of(), 0, 0, null, Map.of(), List.of()),
                CustomerContext.Risk.NONE, Map.of(), Map.of(), Instant.EPOCH);
    }

    public static CustomerContext onAction(CustomerContext c, String actionType, Instant at, String channel, Double amountEur, String ref, Map<String, Object> attrs) {
        List<CustomerContext.ActionSummary> recent = new ArrayList<>(c.behaviour().recentActions());
        recent.add(0, new CustomerContext.ActionSummary(actionType, at, channel, amountEur, ref));
        if (recent.size() > RECENT) recent = recent.subList(0, RECENT);
        var beh = c.behaviour();
        var rfm = beh.rfm();
        if ("TRANSACTION".equals(actionType)) {
            double amt = amountEur == null ? 0 : amountEur;
            boolean in90 = !at.isBefore(Instant.now().minus(Duration.ofDays(90))), in365 = !at.isBefore(Instant.now().minus(Duration.ofDays(365)));
            rfm = new CustomerContext.Rfm(0, beh.rfm().frequency90d() + (in90 ? 1 : 0), beh.rfm().frequency365d() + (in365 ? 1 : 0), beh.rfm().monetary365d() + (in365 ? amt : 0),
                    beh.rfm().firstTransactionAt() == null ? at : beh.rfm().firstTransactionAt(), beh.rfm().lastTransactionAt() == null || at.isAfter(beh.rfm().lastTransactionAt()) ? at : beh.rfm().lastTransactionAt());
        }
        Map<String, Integer> counts = new HashMap<>();
        Instant since30 = Instant.now().minus(Duration.ofDays(30));
        for (var a : recent) if (a.occurredAt() != null && a.occurredAt().isAfter(since30)) counts.merge(a.actionType(), 1, Integer::sum);
        String preferred = recent.stream().filter(a -> a.channel() != null && !a.channel().isBlank()).collect(java.util.stream.Collectors.groupingBy(CustomerContext.ActionSummary::channel, java.util.stream.Collectors.counting()))
                .entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(beh.preferredChannel());
        var eng = c.engagement();
        List<String> badges = eng.badges(); Map<String, Integer> ach = eng.achievementsCompleted(), ch = eng.challengesCompleted(); List<String> camps = eng.campaignsCompleted30d(); int plays = eng.contestPlays30d();
        switch (actionType) {
            case "BADGE_GRANTED" -> { badges = new ArrayList<>(badges); String b = str(attrs.get("badgeCode")); if (b != null && !badges.contains(b)) badges.add(b); }
            case "ACHIEVEMENT_COMPLETED" -> { ach = new HashMap<>(ach); ach.merge(ref, 1, Integer::sum); }
            case "CHALLENGE_COMPLETED", "MISSION_COMPLETED" -> { ch = new HashMap<>(ch); ch.merge(ref, 1, Integer::sum); }
            case "CAMPAIGN_COMPLETED" -> { camps = new ArrayList<>(camps); camps.add(0, ref); if (camps.size() > 20) camps = camps.subList(0, 20); }
            case "CONTEST_PLAYED", "WHEEL_SPUN" -> plays = eng.contestPlays30d() + 1;
            default -> {}
        }
        return new CustomerContext(c.memberId(), c.identity(), c.loyalty(), new CustomerContext.Behaviour(recent, rfm, counts, preferred),
                new CustomerContext.Engagement(badges, ach, ch, camps, eng.recentOffers(), eng.redemptions90d(), plays, eng.lastContactAt(), eng.contacts7dByChannel(), eng.recentContactsOrEmpty()), c.risk(), c.consents(), c.predictions(), Instant.now());
    }

    public static CustomerContext onWallet(CustomerContext c, String wallet, long active, long earned, long spent, long pending, long blocked, long expired) {
        Map<String, CustomerContext.Wallet> w = new HashMap<>(c.loyalty().wallets());
        w.put(wallet, new CustomerContext.Wallet(active, earned, spent, pending, blocked, expired));
        return with(c, new CustomerContext.Loyalty(w, c.loyalty().tier(), c.loyalty().statusPointsYear(), c.loyalty().tierSince(), c.loyalty().segments()));
    }

    public static CustomerContext onTier(CustomerContext c, String tier, Instant at) {
        return with(c, new CustomerContext.Loyalty(c.loyalty().wallets(), tier, c.loyalty().statusPointsYear(), at, c.loyalty().segments()));
    }

    public static CustomerContext onSegment(CustomerContext c, String segmentId, boolean entered) {
        List<String> s = new ArrayList<>(c.loyalty().segments());
        if (entered) { if (!s.contains(segmentId)) s.add(segmentId); } else s.remove(segmentId);
        return with(c, new CustomerContext.Loyalty(c.loyalty().wallets(), c.loyalty().tier(), c.loyalty().statusPointsYear(), c.loyalty().tierSince(), s));
    }

    public static CustomerContext onMember(CustomerContext c, String status, Instant enrolledAt, String channel, String referredBy, Map<String, String> labels, Map<String, Boolean> consents) {
        return new CustomerContext(c.memberId(), new CustomerContext.Identity(status, enrolledAt, channel, referredBy, labels == null ? Map.of() : labels, c.identity().customFields()),
                c.loyalty(), c.behaviour(), c.engagement(), c.risk(), consents == null ? c.consents() : consents, c.predictions(), Instant.now());
    }

    public static CustomerContext onRedemption(CustomerContext c, String status) {
        var e = c.engagement();
        int r = "CONFIRMED".equals(status) || "DELIVERED".equals(status) ? e.redemptions90d() + 1 : e.redemptions90d();
        return new CustomerContext(c.memberId(), c.identity(), c.loyalty(), c.behaviour(), new CustomerContext.Engagement(e.badges(), e.achievementsCompleted(), e.challengesCompleted(), e.campaignsCompleted30d(), e.recentOffers(), r, e.contestPlays30d(), e.lastContactAt(), e.contacts7dByChannel(), e.recentContactsOrEmpty()), c.risk(), c.consents(), c.predictions(), Instant.now());
    }

    public static CustomerContext onDecision(CustomerContext c, String decisionId, String action, String reference, String channel, Instant at) {
        var e = c.engagement();
        List<CustomerContext.Offer> offers = new ArrayList<>(e.recentOffers());
        offers.add(0, new CustomerContext.Offer(decisionId, action, reference, channel, at, "DECIDED"));
        if (offers.size() > 20) offers = offers.subList(0, 20);
        return new CustomerContext(c.memberId(), c.identity(), c.loyalty(), c.behaviour(), new CustomerContext.Engagement(e.badges(), e.achievementsCompleted(), e.challengesCompleted(), e.campaignsCompleted30d(), offers, e.redemptions90d(), e.contestPlays30d(), e.lastContactAt(), e.contacts7dByChannel(), e.recentContactsOrEmpty()), c.risk(), c.consents(), c.predictions(), Instant.now());
    }

    /**
     * Consegna su un canale (RF-132): registra il contatto e <b>ricalcola</b> la finestra a 7 giorni, base delle
     * regole di pressione commerciale. Incrementarla soltanto, come si faceva prima, la faceva crescere per sempre:
     * un membro contattato molto restava sopra il tetto anche mesi dopo e il motore non trovava più un canale.
     */
    public static CustomerContext onDelivery(CustomerContext c, String channel, Instant at) {
        var e = c.engagement();
        List<CustomerContext.Contact> contacts = new ArrayList<>(e.recentContactsOrEmpty());
        contacts.add(0, new CustomerContext.Contact(channel, at));
        if (contacts.size() > CONTACTS) contacts = contacts.subList(0, CONTACTS);
        // La finestra è ancorata al contatto più recente conosciuto, non all'istante di questo evento: una consegna
        // arrivata in ritardo non deve far rivivere contatti che erano già scaduti.
        Instant ancora = contacts.stream().map(CustomerContext.Contact::at).filter(Objects::nonNull).max(Comparator.naturalOrder()).orElse(at);
        Instant ultimo = e.lastContactAt() == null || at.isAfter(e.lastContactAt()) ? at : e.lastContactAt();
        return new CustomerContext(c.memberId(), c.identity(), c.loyalty(), c.behaviour(),
                new CustomerContext.Engagement(e.badges(), e.achievementsCompleted(), e.challengesCompleted(), e.campaignsCompleted30d(),
                        e.recentOffers(), e.redemptions90d(), e.contestPlays30d(), ultimo, contacts7d(contacts, ancora), contacts),
                c.risk(), c.consents(), c.predictions(), Instant.now());
    }

    /** Contatti per canale nei 7 giorni che precedono {@code now}, dalle consegne conservate. */
    static Map<String, Integer> contacts7d(List<CustomerContext.Contact> contacts, Instant now) {
        Instant since = now.minus(Duration.ofDays(7));
        Map<String, Integer> out = new HashMap<>();
        for (var k : contacts) if (k.at() != null && k.at().isAfter(since) && k.channel() != null) out.merge(k.channel(), 1, Integer::sum);
        return out;
    }

    public static CustomerContext onRisk(CustomerContext c, int score, String level, List<String> reasons, Instant at) {
        return new CustomerContext(c.memberId(), c.identity(), c.loyalty(), c.behaviour(), c.engagement(), new CustomerContext.Risk(score, level, reasons, at), c.consents(), c.predictions(), Instant.now());
    }

    public static CustomerContext onPredictions(CustomerContext c, Map<String, Double> predictions) {
        return new CustomerContext(c.memberId(), c.identity(), c.loyalty(), c.behaviour(), c.engagement(), c.risk(), c.consents(), predictions, Instant.now());
    }

    /** Ricalcolo della recency a lettura (i giorni passano anche senza eventi). */
    public static CustomerContext refreshed(CustomerContext c, Instant now) {
        var rfm = c.behaviour().rfm();
        Integer recency = rfm.lastTransactionAt() == null ? null : (int) Duration.between(rfm.lastTransactionAt(), now).toDays();
        return new CustomerContext(c.memberId(), c.identity(), c.loyalty(), new CustomerContext.Behaviour(c.behaviour().recentActions(), new CustomerContext.Rfm(recency, rfm.frequency90d(), rfm.frequency365d(), rfm.monetary365d(), rfm.firstTransactionAt(), rfm.lastTransactionAt()), c.behaviour().actionCounts30d(), c.behaviour().preferredChannel()), c.engagement(), c.risk(), c.consents(), c.predictions(), c.updatedAt());
    }

    /**
     * Ricalcolo delle finestre mobili dal dettaglio conservato: azioni recenti e consegne recenti. Le finestre
     * calcolate a evento sanno solo crescere — i 90 giorni scadono anche quando non succede niente — e senza questo
     * passaggio un contesto fermo racconta un cliente più attivo e più contattato di quello che è.
     *
     * <p>Il dettaglio è limitato (ultime {@value #RECENT} azioni, ultime {@value #CONTACTS} consegne): il ricalcolo è
     * esatto finché la finestra ci sta dentro, e per i clienti molto attivi resta una stima per difetto. I contatori
     * senza dettaglio conservato ({@code redemptions90d}, {@code contestPlays30d}) non si ricalcolano qui: li
     * riallinea la riproiezione dai topic.
     */
    public static CustomerContext recomputeWindows(CustomerContext c, Instant now) {
        var beh = c.behaviour();
        var rfm = beh.rfm();
        Instant since30 = now.minus(Duration.ofDays(30)), since90 = now.minus(Duration.ofDays(90)), since365 = now.minus(Duration.ofDays(365));
        int freq90 = 0, freq365 = 0;
        double monetary365 = 0;
        Map<String, Integer> counts = new HashMap<>();
        for (var a : beh.recentActions()) {
            if (a.occurredAt() == null) continue;
            if (a.occurredAt().isAfter(since30)) counts.merge(a.actionType(), 1, Integer::sum);
            if (!"TRANSACTION".equals(a.actionType())) continue;
            if (a.occurredAt().isAfter(since90)) freq90++;
            if (a.occurredAt().isAfter(since365)) { freq365++; monetary365 += a.amountEur() == null ? 0 : a.amountEur(); }
        }
        Integer recency = rfm.lastTransactionAt() == null ? null : (int) Duration.between(rfm.lastTransactionAt(), now).toDays();
        var e = c.engagement();
        var engagement = new CustomerContext.Engagement(e.badges(), e.achievementsCompleted(), e.challengesCompleted(), e.campaignsCompleted30d(),
                e.recentOffers(), e.redemptions90d(), e.contestPlays30d(), e.lastContactAt(),
                contacts7d(e.recentContactsOrEmpty(), now), e.recentContactsOrEmpty());
        return new CustomerContext(c.memberId(), c.identity(), c.loyalty(),
                new CustomerContext.Behaviour(beh.recentActions(), new CustomerContext.Rfm(recency, freq90, freq365, monetary365, rfm.firstTransactionAt(), rfm.lastTransactionAt()), counts, beh.preferredChannel()),
                engagement, c.risk(), c.consents(), c.predictions(), c.updatedAt());
    }

    private static CustomerContext with(CustomerContext c, CustomerContext.Loyalty l) {
        return new CustomerContext(c.memberId(), c.identity(), l, c.behaviour(), c.engagement(), c.risk(), c.consents(), c.predictions(), Instant.now());
    }
    private static String str(Object o) { return o == null ? null : o.toString(); }
}
