package io.loyaltyhub.insight.api;

import io.loyaltyhub.common.web.LhException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Paginazione di insight senza database (docs/06 §2, Q-532): default, tetto di {@code size}, {@code limit} come
 * sinonimo e, soprattutto, nessun overflow di {@code page * size} (prima dava un {@code OFFSET} negativo e un 500).
 */
class PagingTest {

    private static void assertBadRequest(Runnable call) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(LhException.class,
                e -> assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void defaultsApplyWhenAbsent() {
        Paging p = Paging.of(null, null, null, 50);
        assertThat(p.page()).isZero();
        assertThat(p.size()).isEqualTo(50);
        assertThat(p.offset()).isZero();
    }

    @Test
    void limitIsASynonymOfSizeAndSizeWins() {
        assertThat(Paging.of(0, null, 7, 50).size()).isEqualTo(7);
        assertThat(Paging.of(0, 5, 7, 50).size()).isEqualTo(5);
    }

    @Test
    void sizeIsCappedAt100() {
        assertThat(Paging.of(0, 101, null, 50).size()).isEqualTo(100);
        assertThat(Paging.of(0, null, Integer.MAX_VALUE, 50).size()).isEqualTo(100);
    }

    @Test
    void offsetIsPageTimesSize() {
        assertThat(Paging.of(3, 20, null, 50).offset()).isEqualTo(60);
    }

    @Test
    void negativePageAndNonPositiveSizeAreRejected() {
        assertBadRequest(() -> Paging.of(-1, 10, null, 50));
        assertBadRequest(() -> Paging.of(0, 0, null, 50));
        assertBadRequest(() -> Paging.of(0, null, -5, 50));
        assertBadRequest(() -> Paging.of(0, null, Integer.MIN_VALUE, 50));
    }

    @Test
    void hugePageAndLimitAreRejectedInsteadOfOverflowing() {
        assertBadRequest(() -> Paging.of(Integer.MAX_VALUE, null, Integer.MAX_VALUE, 50));
        assertBadRequest(() -> Paging.of(Integer.MAX_VALUE, null, null, 50));
        assertBadRequest(() -> Paging.of(Integer.MAX_VALUE / 100 + 1, 100, null, 50));
    }

    @Test
    void lastPageThatFitsIsAccepted() {
        int page = Integer.MAX_VALUE / 100;
        Paging p = Paging.of(page, 100, null, 50);
        assertThat(p.offset()).isEqualTo(page * 100);
        assertThat(p.offset()).isPositive();
    }

    @Test
    void offsetNeverWrapsAround() {
        assertThatThrownBy(() -> new Paging(Integer.MAX_VALUE, 100).offset()).isInstanceOf(ArithmeticException.class);
    }
}
