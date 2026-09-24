package io.loyaltyhub.wallet.testbook;

import io.loyaltyhub.common.event.LhEvent;
import io.loyaltyhub.wallet.domain.Edition;
import io.loyaltyhub.wallet.domain.Tier;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Supporto del testbook TB-WAL (docs/testbook/TB-WAL-wallet.md): valori letti a runtime dai seed (livelli, valute,
 * edizioni), espressioni sulle soglie ({@code SILVER-1}, {@code GOLD}, {@code PLATINUM+1}), istanti in ora di Roma,
 * costruzione degli eventi in ingresso e orologio controllabile. Non contiene regole: l'oracolo è la specifica.
 */
final class WalTestbook {

    static final ZoneId ROME = ZoneId.of("Europe/Rome");
    static final ObjectMapper MAPPER = new ObjectMapper();
    private static final AtomicInteger SEQ = new AtomicInteger();

    private WalTestbook() {
    }

    // ---------- seed (letti a runtime, docs/10) ----------

    static JsonNode seed(String file) {
        try (InputStream in = WalTestbook.class.getClassLoader().getResourceAsStream("seed/" + file)) {
            if (in == null) {
                throw new IllegalStateException("seed mancante sul classpath: " + file);
            }
            return MAPPER.readTree(in);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Scala dei livelli di {@code seed/tiers.json}, per rank crescente. */
    static List<Tier> seedScale() {
        List<Tier> out = new ArrayList<>();
        for (JsonNode t : seed("tiers.json")) {
            List<String> benefits = new ArrayList<>();
            t.path("benefits").forEach(b -> benefits.add(b.asString()));
            out.add(new Tier(t.path("code").asString(), t.path("name").asString(), t.path("rank").asInt(),
                    t.path("thresholdSts").asLong(), t.path("multiplier").decimalValue(), benefits,
                    t.path("color").asString(null), t.path("icon").asString(null)));
        }
        out.sort(java.util.Comparator.comparingInt(Tier::rank));
        return out;
    }

    static Tier seedTier(String code) {
        return seedScale().stream().filter(t -> t.code().equals(code)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("livello non nel seed: " + code));
    }

    static long threshold(String code) {
        return seedTier(code).thresholdSts();
    }

    static BigDecimal multiplier(String code) {
        return seedTier(code).multiplier();
    }

    /** {@code floor(base × moltiplicatore del livello)} (docs/03 §4.2), moltiplicatore letto dal seed. */
    static long floorTimes(long base, String tier) {
        return BigDecimal.valueOf(base).multiply(multiplier(tier)).setScale(0, RoundingMode.FLOOR).longValueExact();
    }

    /** Edizioni di {@code seed/editions.json}; {@code withGrace=false} le restituisce senza {@code redemptionGraceUntil}. */
    static List<Edition> seedEditions(boolean withGrace) {
        List<Edition> out = new ArrayList<>();
        for (JsonNode e : seed("editions.json")) {
            out.add(new Edition(e.path("code").asString(), e.path("name").asString(),
                    LocalDate.parse(e.path("startDate").asString()), LocalDate.parse(e.path("endDate").asString()),
                    withGrace && e.hasNonNull("redemptionGraceUntil")
                            ? LocalDate.parse(e.get("redemptionGraceUntil").asString()) : null,
                    e.path("status").asString()));
        }
        return out;
    }

    /** Policy di scadenza della valuta nel seed ({@code seed/currencies.json}). */
    static JsonNode seedPolicy(String currency) {
        for (JsonNode c : seed("currencies.json")) {
            if (c.path("code").asString().equals(currency)) {
                return c.get("expiryPolicy");
            }
        }
        throw new IllegalArgumentException("valuta non nel seed: " + currency);
    }

    /**
     * Espressione sulle soglie del seed: numero intero, codice di livello ({@code GOLD} = sua soglia) con
     * eventuale {@code +n}/{@code -n} ({@code SILVER-1}, {@code PLATINUM+5000}).
     */
    static long sts(String expr) {
        String e = expr.trim();
        if (e.matches("-?\\d+")) {
            return Long.parseLong(e);
        }
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("([A-Z]+)\\s*([+-]\\s*\\d+)?").matcher(e);
        if (!m.matches()) {
            throw new IllegalArgumentException("espressione STS non valida: " + expr);
        }
        long base = threshold(m.group(1));
        return m.group(2) == null ? base : base + Long.parseLong(m.group(2).replace(" ", ""));
    }

    // ---------- tempo (Europe/Rome) ----------

    /** Ora locale di Roma ({@code 2026-10-31T23:59:59}) → istante. */
    static Instant rome(String localDateTime) {
        return LocalDateTime.parse(localDateTime).atZone(ROME).toInstant();
    }

    /** Primo istante del giorno successivo a {@code day} in Europe/Rome. */
    static Instant startOfNextDay(LocalDate day) {
        return day.plusDays(1).atStartOfDay(ROME).toInstant();
    }

    /**
     * «Ultimo istante» del giorno {@code day} in Europe/Rome (docs/03 §4.2, wallet-service §5): l'istante cade in quel
     * giorno ed entro l'ultimo secondo (tollera la rappresentazione a secondi dell'esempio di docs/05 §2).
     */
    static boolean isLastInstantOf(Instant i, LocalDate day) {
        Instant next = startOfNextDay(day);
        return i != null && i.isBefore(next) && !i.isBefore(next.minusSeconds(1));
    }

    static String describe(Instant i) {
        return i == null ? "null" : i + " (" + i.atZone(ROME).toLocalDateTime() + " Roma)";
    }

    // ---------- identificativi ed eventi ----------

    /** Membro fresco, mai visto dal seed: nessuna dipendenza dallo stato mutabile dei dati demo. */
    static String freshMember(String rowId) {
        return "MBR-TB-" + rowId.replace("TB-WAL-", "") + "-" + SEQ.incrementAndGet();
    }

    static String freshId(String prefix) {
        return prefix + "-" + System.nanoTime() + "-" + SEQ.incrementAndGet();
    }

    static LhEvent<JsonNode> event(String type, String subject, Instant time, JsonNode data) {
        String id = freshId("EVT");
        return new LhEvent<>("1.0", id, "urn:loyaltyhub:service:testbook", type, subject, time,
                "application/json", null, "aurora", "COR-" + id, null, 0, "SYSTEM:testbook", data);
    }

    /** Effetto {@code points.grant} (EVT-EFF-01, contracts/events/effect/points.grant.schema.json). */
    static LhEvent<JsonNode> grant(String memberId, String effectId, String currency, long amount,
                                   boolean tierMultiplierApplies, int pendingDays, Instant time) {
        ObjectNode d = MAPPER.createObjectNode();
        d.put("effectId", effectId);
        d.put("campaignCode", "CMP-TESTBOOK");
        d.put("actionId", "ACT-" + effectId);
        d.put("actionType", "purchase.completed");
        d.put("currency", currency);
        d.put("baseAmount", amount);
        d.put("campaignMultiplier", 1.0);
        d.put("amount", amount);
        d.put("tierMultiplierApplies", tierMultiplierApplies);
        d.put("pendingDays", pendingDays);
        d.put("description", "Testbook " + effectId);
        return event("io.loyaltyhub.effect.points.grant", "member:" + memberId, time, d);
    }

    static LhEvent<JsonNode> redemptionRequested(String memberId, String redemptionId, long cost) {
        ObjectNode d = MAPPER.createObjectNode();
        d.put("redemptionId", redemptionId);
        d.put("rewardCode", "RWD-TESTBOOK");
        d.put("rewardName", "Premio testbook");
        d.put("pointsCost", cost);
        return event("io.loyaltyhub.fact.reward.redemption.requested", "member:" + memberId, Instant.now(), d);
    }

    static LhEvent<JsonNode> redemptionCancelled(String memberId, String redemptionId, long cost, boolean refund) {
        ObjectNode d = MAPPER.createObjectNode();
        d.put("redemptionId", redemptionId);
        d.put("reason", "Annullo testbook");
        d.put("refund", refund);
        d.put("pointsCost", cost);
        return event("io.loyaltyhub.fact.reward.redemption.cancelled", "member:" + memberId, Instant.now(), d);
    }

    static Map<String, String> row(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    /** Orologio del servizio sostituito nei test d'integrazione: istante fisso, spostabile dal test. */
    static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now;

        MutableClock(Instant start) {
            this.now = new AtomicReference<>(start);
        }

        void set(Instant instant) {
            now.set(instant);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            MutableClock self = this;
            return new Clock() {
                @Override
                public ZoneId getZone() {
                    return zone;
                }

                @Override
                public Clock withZone(ZoneId z) {
                    return self.withZone(z);
                }

                @Override
                public Instant instant() {
                    return self.instant();
                }
            };
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    }
}
