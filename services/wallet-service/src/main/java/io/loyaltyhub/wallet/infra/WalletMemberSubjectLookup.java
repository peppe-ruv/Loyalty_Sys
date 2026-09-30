package io.loyaltyhub.wallet.infra;

import io.loyaltyhub.common.identity.MemberSubjectLookup;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Il membro del token per gli handler {@code @MemberEndpoint} di wallet-service (F2-SEC-09, ADR-048, Q-550): legge la
 * proiezione locale {@code subjectRef → memberId} di {@code member_tier}. Non è autorevole: un legame assente significa
 * «il fatto non è ancora arrivato» ({@code 409 MEMBER_NOT_LINKED} con {@code Retry-After}), non «non registrato».
 */
@Component
public class WalletMemberSubjectLookup implements MemberSubjectLookup {

    private final MemberSubjectRepository subjects;

    public WalletMemberSubjectLookup(MemberSubjectRepository subjects) {
        this.subjects = subjects;
    }

    @Override
    public String modulePackage() {
        return "io.loyaltyhub.wallet";
    }

    @Override
    public Optional<String> memberId(String subjectRef) {
        return subjects.memberIdBySubjectRef(subjectRef);
    }
}
