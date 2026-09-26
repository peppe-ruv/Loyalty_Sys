package io.loyaltyhub.member.api;

import java.util.List;

public record NicknamesRequest(List<String> memberIds) {
}
