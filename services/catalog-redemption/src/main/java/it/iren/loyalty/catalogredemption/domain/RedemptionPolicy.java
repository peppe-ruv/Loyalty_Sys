package it.iren.loyalty.catalogredemption.domain;

import java.time.Instant;
import java.util.Set;

/** Controlli di ammissibilità del riscatto (RF-14, RF-75), puri: la stessa logica serve al sito per mostrare "perché non posso riscattare". */
public final class RedemptionPolicy {
    private RedemptionPolicy() {}

    public enum Reason { OK, INACTIVE, NOT_VISIBLE, NOT_ACTIVE_YET, ENDED, TIER_TOO_LOW, SEGMENT_NOT_TARGETED, OUT_OF_STOCK, MEMBER_LIMIT, MEMBER_DAILY_LIMIT, INSUFFICIENT_BALANCE }

    public record MemberState(int tierOrder, Set<String> segments, long premioAvailable, long redemptionsOfThisReward, long redemptionsOfThisRewardToday) {}

    public static Reason check(RewardDefinition r, MemberState m, Instant now) {
        if (!r.active()) return Reason.INACTIVE;
        if (r.visibleFrom() != null && now.isBefore(r.visibleFrom()) || r.visibleTo() != null && !now.isBefore(r.visibleTo())) return Reason.NOT_VISIBLE;
        if (r.activeFrom() != null && now.isBefore(r.activeFrom())) return Reason.NOT_ACTIVE_YET;
        if (r.activeTo() != null && !now.isBefore(r.activeTo())) return Reason.ENDED;
        if (m.tierOrder() < r.minTierOrder()) return Reason.TIER_TOO_LOW;
        if (r.targetSegments() != null && !r.targetSegments().isEmpty() && (m.segments() == null || m.segments().stream().noneMatch(r.targetSegments()::contains))) return Reason.SEGMENT_NOT_TARGETED;
        if (!r.unlimitedStock() && r.stock() <= 0) return Reason.OUT_OF_STOCK;
        if (r.limitPerMember() > 0 && m.redemptionsOfThisReward() >= r.limitPerMember()) return Reason.MEMBER_LIMIT;
        if (r.limitPerMemberPerDay() > 0 && m.redemptionsOfThisRewardToday() >= r.limitPerMemberPerDay()) return Reason.MEMBER_DAILY_LIMIT;
        if (m.premioAvailable() < r.pointsCost()) return Reason.INSUFFICIENT_BALANCE;
        return Reason.OK;
    }
}
