package io.loyaltyhub.reward.application;

import io.loyaltyhub.reward.domain.CouponCodes;
import io.loyaltyhub.reward.domain.CouponPool;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Codici riproducibili dal seme, solo nel profilo {@code demo} (senza {@code enterprise}): il seme di un pool è un hash
 * del suo codice e quello di ogni generazione deriva da esso e dal numero di codici già presenti (docs/10 §1.3,
 * reward-service §3). L'algoritmo non cambia: i codici del seed demo restano quelli di sempre.
 */
@Component
@Profile("demo & !enterprise")
public class SeededCouponCodePolicy implements CouponCodePolicy {

    /** Seme stabile di un pool: dipende solo dal codice, così un pool ricreato genera gli stessi codici. */
    public static long seedFor(String poolCode) {
        long h = 1125899906842597L;
        for (char c : poolCode.toCharArray()) {
            h = 31 * h + c;
        }
        return h;
    }

    @Override
    public long poolSeed(String poolCode) {
        return seedFor(poolCode);
    }

    @Override
    public Generation start(CouponPool pool, long existing) {
        long seed = CouponCodes.batchSeed(pool.seed(), existing);
        return new Generation(CouponCodes.seeded(pool.prefix(), seed), seed);
    }
}
