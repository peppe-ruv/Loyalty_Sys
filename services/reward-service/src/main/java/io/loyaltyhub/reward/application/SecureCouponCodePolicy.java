package io.loyaltyhub.reward.application;

import io.loyaltyhub.reward.domain.CouponCodes;
import io.loyaltyhub.reward.domain.CouponPool;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Codici imprevedibili ({@code SecureRandom}) ovunque non sia la demo: di default e in {@code enterprise} (Q-614,
 * ADR-044: niente configurazioni insicure). Nessun seme: {@code coupon_pool.seed} resta {@code 0} e non si scrive mai
 * nell'audit. I pool e i codici già esistenti restano validi, cambia solo come si generano i nuovi.
 */
@Component
@Profile("!(demo & !enterprise)")
public class SecureCouponCodePolicy implements CouponCodePolicy {

    @Override
    public long poolSeed(String poolCode) {
        return 0L;
    }

    @Override
    public Generation start(CouponPool pool, long existing) {
        return new Generation(CouponCodes.secure(pool.prefix()), null);
    }
}
