package io.loyaltyhub.campaign.engine;

import tools.jackson.databind.JsonNode;
import io.loyaltyhub.campaign.domain.Campaign;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Motore regole deterministico (docs/03 §3.5) — classe pura, testabile senza Spring. Valuta un'azione
 * contro le campagne {@code LIVE}, raccoglie i {@code GRANT_POINTS}, applica i {@code MULTIPLIER} e produce
 * gli effetti {@code points.grant} con {@code effectId} idempotente. Sono supportati
 * {@code GRANT_POINTS} ({@code FIXED}/{@code PER_AMOUNT}/{@code LOOKUP}/{@code FROM_FIELD}), {@code MULTIPLIER}, {@code GRANT_PLAYS}, {@code ISSUE_COUPON},
 * {@code AWARD_BADGE} e {@code SEND_MESSAGE} (M6.4); le campagne con effetti sconosciuti o incompleti (es. {@code SEND_MESSAGE}
 * senza {@code templateCode}) restano caricate ma scartate con {@code EFFECT_NOT_SUPPORTED_YET}.
 */
public final class CampaignEngine {

    private static final ZoneId ROME = ZoneId.of("Europe/Rome");

    private final int maxMultiplier;

    public CampaignEngine(int maxMultiplier) {
        this.maxMultiplier = maxMultiplier;
    }

    /** {@code simulate=true} salta la consumazione dei limiti (letti soltanto) — comunque mai scritti qui. */
    public Evaluation evaluate(EvalAction action, MemberSnapshot member, List<Campaign> campaigns, Counters counters) {
        if (member == null || !member.isActive()) {
            return new Evaluation(Evaluation.Outcome.NO_MEMBER, List.of(), List.of());
        }

        // I candidati sono già filtrati dal chiamante (LIVE per la valutazione reale, l'insieme scelto per
        // la simulazione che può includere bozze): qui si filtra solo per tipo di trigger.
        List<Campaign> candidates = new ArrayList<>();
        for (Campaign c : campaigns) {
            if (c.triggersOn(action.type())) {
                candidates.add(c);
            }
        }
        candidates.sort(Comparator.comparingInt(Campaign::priority).reversed().thenComparing(Campaign::code));

        List<Evaluation.CampaignResult> results = new ArrayList<>();
        List<Grant> grants = new ArrayList<>();
        List<Evaluation.ActionEffect> actionEffects = new ArrayList<>();
        List<Mult> multipliers = new ArrayList<>();
        List<String> exclusiveTaken = new ArrayList<>();
        ConditionEvaluator conditions = new ConditionEvaluator(action, member, counters);
        // Match provvisori (codice → nome): gli effetti finali si assegnano dopo i moltiplicatori.
        Map<String, String> matchedNames = new LinkedHashMap<>();

        for (Campaign c : candidates) {
            if (!scheduleContains(c.schedule(), action)) {
                results.add(Evaluation.CampaignResult.skipped(c.code(), c.name(), Evaluation.SkipReason.NOT_IN_SCHEDULE));
                continue;
            }
            if (!audienceOk(c.audience(), member)) {
                results.add(Evaluation.CampaignResult.skipped(c.code(), c.name(), Evaluation.SkipReason.AUDIENCE));
                continue;
            }
            ConditionEvaluator.Result cond = conditions.evaluate(c.conditions());
            if (!cond.pass()) {
                results.add(Evaluation.CampaignResult.skippedConditions(c.code(), c.name(), cond.failed()));
                continue;
            }
            if (c.exclusiveGroup() != null && exclusiveTaken.contains(c.exclusiveGroup())) {
                results.add(Evaluation.CampaignResult.skipped(c.code(), c.name(), Evaluation.SkipReason.EXCLUSIVE));
                continue;
            }
            if (!supported(c.effects())) {
                results.add(Evaluation.CampaignResult.skipped(c.code(), c.name(),
                        Evaluation.SkipReason.EFFECT_NOT_SUPPORTED_YET));
                continue;
            }
            Evaluation.SkipReason limit = checkLimits(c, action, member, counters);
            if (limit != null) {
                results.add(Evaluation.CampaignResult.skipped(c.code(), c.name(), limit));
                continue;
            }
            // raccogli effetti
            collectGrants(c, action, grants);
            collectActionEffects(c, action, actionEffects);
            collectMultipliers(c, multipliers);
            if (c.exclusiveGroup() != null) {
                exclusiveTaken.add(c.exclusiveGroup());
            }
            matchedNames.put(c.code(), c.name());
        }

        // Applica i moltiplicatori e costruisci gli effetti finali.
        List<GrantedEffect> effects = new ArrayList<>();
        Map<String, List<Evaluation.EffectResult>> effectsByCampaign = new LinkedHashMap<>();
        for (Grant g : grants) {
            double factor = multiplierFor(g, multipliers);
            long amount = (long) Math.floor(g.baseAmount * factor);
            if (amount <= 0) {
                continue;
            }
            String effectId = effectId(action.actionId(), g.campaignCode, g.effectIndex);
            effects.add(new GrantedEffect(effectId, g.campaignCode, g.currency, g.baseAmount, factor, amount,
                    g.tierMultiplierApplies, g.pendingDays, g.description));
            effectsByCampaign.computeIfAbsent(g.campaignCode, k -> new ArrayList<>())
                    .add(new Evaluation.EffectResult("GRANT_POINTS", g.currency, amount));
        }
        for (Evaluation.ActionEffect a : actionEffects) {
            // Un messaggio non ha una quantità: nella spiegabilità compare senza amount.
            Long amount = a.type().equals("SEND_MESSAGE") ? null : a.params().path("count").asLong(1);
            effectsByCampaign.computeIfAbsent(a.campaignCode(), k -> new ArrayList<>())
                    .add(new Evaluation.EffectResult(a.type(), null, amount));
        }
        // I MULTIPLIER matchati compaiono nella spiegabilità con il loro fattore (nessun grant proprio).
        for (Mult m : multipliers) {
            effectsByCampaign.computeIfAbsent(m.campaignCode, k -> new ArrayList<>())
                    .add(new Evaluation.EffectResult("MULTIPLIER", m.currency, null));
        }

        for (Map.Entry<String, String> e : matchedNames.entrySet()) {
            results.add(Evaluation.CampaignResult.matched(e.getKey(), e.getValue(),
                    effectsByCampaign.getOrDefault(e.getKey(), List.of())));
        }

        Evaluation.Outcome outcome = matchedNames.isEmpty()
                ? Evaluation.Outcome.NO_MATCH : Evaluation.Outcome.MATCHED;
        return new Evaluation(outcome, results, effects, actionEffects);
    }

    // ---------- passi ----------

    private boolean scheduleContains(JsonNode schedule, EvalAction action) {
        if (schedule == null || schedule.isEmpty()) {
            return true;
        }
        ZonedDateTime t = action.time().atZone(ROME);
        JsonNode start = schedule.get("startAt");
        if (start != null && !start.isNull() && action.time().isBefore(java.time.Instant.parse(start.asString()))) {
            return false;
        }
        JsonNode end = schedule.get("endAt");
        if (end != null && !end.isNull() && action.time().isAfter(java.time.Instant.parse(end.asString()))) {
            return false;
        }
        JsonNode days = schedule.get("daysOfWeek");
        if (days != null && days.isArray() && !days.isEmpty()) {
            String dow = t.getDayOfWeek().name().substring(0, 3);
            boolean in = false;
            for (JsonNode d : days) {
                if (d.asString("").equalsIgnoreCase(dow)) {
                    in = true;
                    break;
                }
            }
            if (!in) {
                return false;
            }
        }
        JsonNode hours = schedule.get("hours");
        if (hours != null && hours.isArray() && hours.size() == 2) {
            int h = t.getHour();
            if (h < hours.get(0).asInt() || h > hours.get(1).asInt()) {
                return false;
            }
        }
        return true;
    }

    private boolean audienceOk(JsonNode audience, MemberSnapshot member) {
        if (audience == null || audience.isEmpty() || audience.path("all").asBoolean(false)) {
            return true;
        }
        JsonNode tiers = audience.get("tiers");
        boolean hasTiers = tiers != null && tiers.isArray() && !tiers.isEmpty();
        JsonNode segments = audience.get("segments");
        boolean hasSegments = segments != null && segments.isArray() && !segments.isEmpty();
        if (!hasTiers && !hasSegments) {
            return true; // pubblico non ristretto
        }
        if (hasTiers) {
            for (JsonNode tr : tiers) {
                if (tr.asString("").equals(member.tier())) {
                    return true;
                }
            }
        }
        if (hasSegments) {
            for (JsonNode sg : segments) {
                if (member.segments().contains(sg.asString(""))) {
                    return true;
                }
            }
        }
        return false;
    }

    private Evaluation.SkipReason checkLimits(Campaign c, EvalAction action, MemberSnapshot member, Counters counters) {
        JsonNode limits = c.limits();
        if (limits == null || limits.isEmpty()) {
            return null;
        }
        JsonNode perMember = limits.get("perMember");
        if (perMember != null && perMember.isArray()) {
            for (JsonNode lim : perMember) {
                int max = lim.path("max").asInt(Integer.MAX_VALUE);
                String period = lim.path("period").asString("ALWAYS");
                String key = PeriodKeys.of(period, action.time());
                if (counters.memberMatches(c.id(), member.memberId(), period, key) >= max) {
                    return Evaluation.SkipReason.LIMIT;
                }
            }
        }
        // F-CMP-05, docs/03 §3.2: tetto punti per membro e cooldown tra due match dello stesso membro.
        // SPEC-GAP: Q-E9 — entrambi scartano con LIMIT; il tetto è sui punti decisi dalla campagna per il membro da
        // sempre (≥ tetto → scarta, l'ultimo accredito non si riduce, come il budget); il cooldown si misura sul time
        // di business dell'ultimo match (un'azione con time precedente all'ultimo match è dentro il cooldown).
        JsonNode perMemberPoints = limits.get("perMemberPoints");
        if (perMemberPoints != null && perMemberPoints.isNumber()
                && counters.memberPoints(c.id(), member.memberId()) >= perMemberPoints.asLong()) {
            return Evaluation.SkipReason.LIMIT;
        }
        JsonNode cooldown = limits.get("cooldownMinutes");
        if (cooldown != null && cooldown.isNumber() && cooldown.asLong() > 0) {
            Instant last = counters.memberLastMatchAt(c.id(), member.memberId());
            if (last != null && action.time().isBefore(last.plus(Duration.ofMinutes(cooldown.asLong())))) {
                return Evaluation.SkipReason.LIMIT;
            }
        }
        JsonNode global = limits.get("global");
        if (global != null && !global.isNull()) {
            JsonNode maxPoints = global.get("maxPoints");
            if (maxPoints != null && maxPoints.isNumber()
                    && counters.globalPointsDecided(c.id()) >= maxPoints.asLong()) {
                return Evaluation.SkipReason.BUDGET;
            }
            JsonNode maxMatches = global.get("maxMatches");
            if (maxMatches != null && maxMatches.isNumber()
                    && counters.globalMatches(c.id()) >= maxMatches.asLong()) {
                return Evaluation.SkipReason.BUDGET;
            }
        }
        return null;
    }

    // ---------- effetti ----------

    private boolean supported(JsonNode effects) {
        if (effects == null || !effects.isArray()) {
            return false;
        }
        for (JsonNode e : effects) {
            String type = e.path("type").asString("");
            if (type.equals("MULTIPLIER")) {
                continue;
            }
            if (type.equals("GRANT_PLAYS") && !e.path("contestCode").asString("").isBlank()
                    && e.path("count").asInt(1) > 0) {
                continue;
            }
            if (type.equals("ISSUE_COUPON") && (!e.path("rewardCode").asString("").isBlank()
                    || !e.path("rewardCodeField").asString("").isBlank())) {
                continue;
            }
            if (type.equals("AWARD_BADGE") && !e.path("badgeCode").asString("").isBlank()) {
                continue;
            }
            // SPEC-GAP: Q-73 — SEND_MESSAGE senza templateCode (o con params non oggetto) scarta l'intera campagna col
            // motivo esistente EFFECT_NOT_SUPPORTED_YET (scelta conservativa: niente punti a metà; la validazione di
            // gestione lo impedisce già a monte). Il template inesistente non si può verificare qui (è di engagement).
            if (type.equals("SEND_MESSAGE") && !e.path("templateCode").asString("").isBlank()
                    && (!e.has("params") || e.get("params").isNull() || e.get("params").isObject())) {
                continue;
            }
            if (type.equals("GRANT_POINTS")) {
                String mode = e.path("mode").asString("FIXED");
                if (mode.equals("FIXED") || mode.equals("PER_AMOUNT") || mode.equals("FROM_FIELD") || mode.equals("LOOKUP")) {
                    continue;
                }
            }
            return false; // tipo sconosciuto o parametri mancanti
        }
        return true;
    }

    /**
     * Effetti non monetari, stesso {@code effectId} idempotente degli accrediti: {@code GRANT_PLAYS} → {@code plays.grant}
     * (M5.2), {@code ISSUE_COUPON} → {@code coupon.issue} (M5.3; {@code rewardCode} fisso o letto da
     * {@code rewardCodeField}, docs/03 §3.3), {@code AWARD_BADGE} → {@code badge.award}, {@code SEND_MESSAGE} →
     * {@code message.send} (M6.4; {@code templateCode} + {@code params} opzionali, docs/03 §3.4). Un premio non
     * risolvibile dall'azione non produce effetto.
     */
    private void collectActionEffects(Campaign c, EvalAction action, List<Evaluation.ActionEffect> out) {
        JsonNode effects = c.effects();
        for (int i = 0; i < effects.size(); i++) {
            JsonNode e = effects.get(i);
            String type = e.path("type").asString("");
            if (type.equals("GRANT_PLAYS") || type.equals("AWARD_BADGE") || type.equals("SEND_MESSAGE")) {
                out.add(new Evaluation.ActionEffect(effectId(action.actionId(), c.code(), i), c.code(), type, e));
            } else if (type.equals("ISSUE_COUPON")) {
                String rewardCode = e.path("rewardCode").asString("");
                if (rewardCode.isBlank()) {
                    JsonNode v = navigate(action.data(), e.path("rewardCodeField").asString(""));
                    rewardCode = v == null || v.isNull() ? "" : v.asString("");
                }
                if (!rewardCode.isBlank()) {
                    tools.jackson.databind.node.ObjectNode params = tools.jackson.databind.node.JsonNodeFactory.instance.objectNode();
                    params.put("rewardCode", rewardCode);
                    out.add(new Evaluation.ActionEffect(effectId(action.actionId(), c.code(), i), c.code(), "ISSUE_COUPON", params));
                }
            }
        }
    }

    private void collectGrants(Campaign c, EvalAction action, List<Grant> grants) {
        JsonNode effects = c.effects();
        for (int i = 0; i < effects.size(); i++) {
            JsonNode e = effects.get(i);
            if (!e.path("type").asString("").equals("GRANT_POINTS")) {
                continue;
            }
            Long base = computeBase(e, action);
            if (base == null || base <= 0) {
                continue;
            }
            grants.add(new Grant(c.code(), c.labels(), i, e.path("currency").asString("PTS"), base,
                    e.path("tierMultiplierApplies").asBoolean(false), e.path("pendingDays").asInt(0),
                    e.path("description").asString(null)));
        }
    }

    private Long computeBase(JsonNode effect, EvalAction action) {
        String mode = effect.path("mode").asString("FIXED");
        long value;
        if (mode.equals("FIXED")) {
            value = effect.path("value").asLong(0);
        } else if (mode.equals("PER_AMOUNT")) {
            JsonNode amountNode = navigate(action.data(), effect.path("amountField").asString("data.amount"));
            if (amountNode == null || !amountNode.isNumber()) {
                return null;
            }
            // Aritmetica decimale esatta (docs/03 §3.4: rounding(amount / unitStep) × value): 0.3 / 0.1 = 3, non 2.999…
            JsonNode stepNode = effect.get("unitStep");
            BigDecimal unitStep = stepNode != null && stepNode.isNumber() ? stepNode.decimalValue() : BigDecimal.ONE;
            if (unitStep.signum() <= 0) {
                unitStep = BigDecimal.ONE;
            }
            RoundingMode roundingMode = switch (effect.path("rounding").asString("FLOOR")) {
                case "CEIL" -> RoundingMode.CEILING;
                case "ROUND" -> RoundingMode.HALF_UP;
                default -> RoundingMode.FLOOR;
            };
            long rounded = amountNode.decimalValue().divide(unitStep, 0, roundingMode).longValue();
            value = rounded * effect.path("value").asLong(0);
        } else if (mode.equals("FROM_FIELD")) {
            JsonNode fieldNode = navigate(action.data(), effect.path("amountField").asString(""));
            if (fieldNode == null || !fieldNode.isNumber() || fieldNode.isFloatingPointNumber() || !fieldNode.isIntegralNumber()) {
                return null;
            }
            value = fieldNode.asLong();
        } else if (mode.equals("LOOKUP")) {
            JsonNode fieldNode = navigate(action.data(), effect.path("amountField").asString(""));
            if (fieldNode == null || fieldNode.isMissingNode() || fieldNode.isNull()) {
                return null;
            }
            String key;
            if (fieldNode.isIntegralNumber()) {
                key = String.valueOf(fieldNode.asLong());
            } else if (fieldNode.isNumber()) {
                key = fieldNode.asText();
            } else {
                key = fieldNode.asText();
            }
            JsonNode lookupNode = effect.get("lookup");
            if (lookupNode == null || !lookupNode.has(key)) {
                return null;
            }
            value = lookupNode.get(key).asLong(0);
        } else {
            return null; // Effetti non supportati in M1.3
        }
        if (effect.has("min")) {
            value = Math.max(value, effect.path("min").asLong());
        }
        if (effect.has("max")) {
            value = Math.min(value, effect.path("max").asLong());
        }
        return value;
    }

    /**
     * Ambito del {@code MULTIPLIER} (docs/03 §3.4: {@code scope: ALL_GRANTS} oppure {@code labels[]}): con {@code labels}
     * dell'effetto non vuote moltiplica solo i {@code GRANT_POINTS} delle campagne che portano almeno una di quelle
     * etichette; altrimenti {@code scope} (assente = {@code ALL_GRANTS}).
     */
    private void collectMultipliers(Campaign c, List<Mult> multipliers) {
        for (JsonNode e : c.effects()) {
            if (e.path("type").asString("").equals("MULTIPLIER")) {
                List<String> labels = new ArrayList<>();
                JsonNode labelsNode = e.get("labels");
                if (labelsNode != null && labelsNode.isArray()) {
                    labelsNode.forEach(l -> labels.add(l.asString("")));
                }
                String scope = labels.isEmpty() ? e.path("scope").asString("ALL_GRANTS") : "LABELS";
                multipliers.add(new Mult(c.code(), e.path("currency").asString("PTS"),
                        e.path("factor").asDouble(1), scope, labels));
            }
        }
    }

    private double multiplierFor(Grant grant, List<Mult> multipliers) {
        double factor = 1.0;
        for (Mult m : multipliers) {
            if (!m.currency.equals(grant.currency) || m.campaignCode.equals(grant.campaignCode)) {
                continue;
            }
            // SPEC-GAP: Q-C46 — scope diverso da ALL_GRANTS senza labels: nessuna campagna nell'ambito (non moltiplica).
            boolean inScope = m.scope.equals("ALL_GRANTS")
                    || (m.scope.equals("LABELS") && labelsIntersect(m.labels, grant.campaignLabels));
            if (inScope) {
                factor *= m.factor;
            }
        }
        return Math.min(factor, maxMultiplier);
    }

    private boolean labelsIntersect(List<String> a, List<String> b) {
        for (String x : a) {
            if (b.contains(x)) {
                return true;
            }
        }
        return false;
    }

    // ---------- utilità ----------

    /** {@code amountField} = {@code data.amount}: naviga il payload dell'azione. */
    private JsonNode navigate(JsonNode data, String amountField) {
        if (data == null) {
            return null;
        }
        String path = amountField.startsWith("data.") ? amountField.substring("data.".length()) : amountField;
        JsonNode node = data;
        for (String seg : path.split("\\.")) {
            node = node.get(seg);
            if (node == null) {
                return null;
            }
        }
        return node;
    }

    /** {@code effectId = sha256(actionId + campaignCode + indice)} troncato a 26 caratteri (docs/03 §3.5). */
    static String effectId(String actionId, String campaignCode, int index) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest((actionId + campaignCode + index).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, 26);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private record Grant(String campaignCode, List<String> campaignLabels, int effectIndex, String currency,
                         long baseAmount, boolean tierMultiplierApplies, int pendingDays, String description) {
    }

    private record Mult(String campaignCode, String currency, double factor, String scope, List<String> labels) {
    }
}
