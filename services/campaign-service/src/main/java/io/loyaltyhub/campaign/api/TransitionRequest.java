package io.loyaltyhub.campaign.api;

/** Corpo di {@code POST /v1/campaigns/{id}/transitions} (docs/06 §7): {@code {action, comment}}. */
public record TransitionRequest(String action, String comment) {
}
