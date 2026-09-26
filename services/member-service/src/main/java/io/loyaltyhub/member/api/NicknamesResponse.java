package io.loyaltyhub.member.api;

import java.util.List;

public record NicknamesResponse(List<Item> items) {
    public record Item(String memberId, String nickname) {}
}
