package io.loyaltyhub.campaign.infra;

import io.loyaltyhub.common.identity.MemberSubjectLookup;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Il membro del token per gli handler {@code @MemberEndpoint} di campaign-service (F2-SEC-09, ADR-048, Q-550): legge la
 * proiezione locale {@code subjectRef → memberId} di {@code member_snapshot}. Non è autorevole: un legame assente significa
 * «il fatto non è ancora arrivato», non «non registrato». L'unico handler di campaign-service, l'elenco «Guadagna», è
 * {@code OPTIONAL}: senza legame (o con il membro anonimizzato) il token riceve la vista generica, non un errore
 * ({@code MemberPrincipals}, Q-554).
 */
@Component
public class CampaignMemberSubjectLookup implements MemberSubjectLookup {

    private final MemberSubjectRepository subjects;

    public CampaignMemberSubjectLookup(MemberSubjectRepository subjects) {
        this.subjects = subjects;
    }

    @Override
    public String modulePackage() {
        return "io.loyaltyhub.campaign";
    }

    @Override
    public Optional<String> memberId(String subjectRef) {
        return subjects.memberIdBySubjectRef(subjectRef);
    }
}
