package io.loyaltyhub.member.api;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Soprannomi a lotti per il BFF (Q-368): richiesta e risposta di {@code POST /v1/members/nicknames}. Nomi dei record
 * distinti perché diventano i nomi degli schemi in {@code contracts/api/member-service.openapi.yaml}.
 */
public final class MemberNicknames {

    private MemberNicknames() {
    }

    /** Da 1 a 200 id di membro (Q-368). */
    public record NicknamesRequest(List<String> memberIds) {
    }

    /**
     * Un id richiesto e il suo nome da mostrare; {@code nickname} {@code null} (esplicito, non omesso) se il membro non
     * esiste o non ne ha.
     */
    public record MemberNickname(String memberId, @JsonInclude(JsonInclude.Include.ALWAYS) String nickname) {
    }

    public record NicknamesResponse(List<MemberNickname> items) {
    }
}
