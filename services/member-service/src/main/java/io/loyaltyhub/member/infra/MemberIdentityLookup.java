package io.loyaltyhub.member.infra;

import io.loyaltyhub.common.identity.MemberSubjectLookup;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Lookup <em>autorevole</em> del membro di un token (F2-SEC-09, ADR-048, Q-550): member-service possiede il legame
 * ({@code member_identity}), quindi un {@code subjectRef} senza riga è un account non registrato
 * ({@code 404 MEMBER_NOT_REGISTERED}), non un fatto ancora in arrivo. Nessun cache: la decisione è sempre quella del database.
 */
@Component
public class MemberIdentityLookup implements MemberSubjectLookup {

    private final MemberIdentityRepository identities;

    public MemberIdentityLookup(MemberIdentityRepository identities) {
        this.identities = identities;
    }

    @Override
    public String modulePackage() {
        return "io.loyaltyhub.member";
    }

    @Override
    public Optional<String> memberId(String subjectRef) {
        return identities.memberIdBySubjectRef(subjectRef);
    }

    @Override
    public boolean authoritative() {
        return true;
    }
}
