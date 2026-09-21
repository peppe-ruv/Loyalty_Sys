package io.loyaltyhub.member.api;

/** Corpo di {@code POST /v1/members/{id}/status} (docs §3): {@code {status, reason}}. */
public record StatusChangeRequest(String status, String reason) {
}
