package io.loyaltyhub.common.web;

import java.util.List;

/** Risposta paginata standard (docs/06 §2): {@code { items, page }}. */
public record PageResponse<T>(List<T> items, PageInfo page) {

    public record PageInfo(int number, int size, long totalItems, int totalPages) {
    }

    public static <T> PageResponse<T> of(List<T> items, int number, int size, long totalItems) {
        int totalPages = size <= 0 ? 0 : (int) Math.ceil((double) totalItems / size);
        return new PageResponse<>(items, new PageInfo(number, size, totalItems, totalPages));
    }
}
