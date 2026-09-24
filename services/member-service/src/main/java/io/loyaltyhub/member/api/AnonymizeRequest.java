package io.loyaltyhub.member.api;

/** Corpo di {@code POST /v1/members/{id}/anonymize} (docs §3): {@code {confirm: "MBR-…"}}, uguale all'id del membro. */
public record AnonymizeRequest(String confirm) {
}
