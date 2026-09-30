package io.loyaltyhub.gamification.infra;

import io.loyaltyhub.common.identity.MemberSubjectLookup;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Il membro del token per gli handler {@code @MemberEndpoint} di gamification-service (F2-SEC-09, ADR-048, Q-550): legge
 * la proiezione locale {@code subjectRef → memberId} di {@code gamification_member_snapshot}. Non è autorevole: un legame
 * assente significa «il fatto non è ancora arrivato» ({@code 409 MEMBER_NOT_LINKED} con {@code Retry-After}), non «non
 * registrato».
 */
@Component
public class GamificationMemberSubjectLookup implements MemberSubjectLookup {

    private final MemberSubjectRepository subjects;

    public GamificationMemberSubjectLookup(MemberSubjectRepository subjects) {
        this.subjects = subjects;
    }

    @Override
    public String modulePackage() {
        return "io.loyaltyhub.gamification";
    }

    @Override
    public Optional<String> memberId(String subjectRef) {
        return subjects.memberIdBySubjectRef(subjectRef);
    }
}
