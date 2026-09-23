package io.loyaltyhub.reward.domain;

/** Fascia di prezzo: tutti i premi della fascia costano {@code pointsThreshold} PTS (docs/03 §5). */
public record Band(String code, String name, long pointsThreshold, String color, int sortOrder) {
}
