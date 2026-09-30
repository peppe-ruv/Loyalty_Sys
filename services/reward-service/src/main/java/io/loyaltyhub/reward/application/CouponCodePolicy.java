package io.loyaltyhub.reward.application;

import io.loyaltyhub.reward.domain.CouponCodes;
import io.loyaltyhub.reward.domain.CouponPool;

/**
 * Come si generano i codici di un pool (Q-614, F-CPN-01, ADR-044). Nel profilo {@code demo} i codici sono riproducibili
 * da un seme (docs/10 §1.3: stessi codici a ogni reset); fuori dalla demo vengono da {@code SecureRandom} e nessun
 * seme esiste, né viene scritto nell'audit o nei log. La scelta è una sola bean per profilo, non un parametro.
 */
public interface CouponCodePolicy {

    /** Una generazione: il generatore dei codici e, solo in demo, il seme che lo ha prodotto. */
    record Generation(CouponCodes codes, Long seed) {
    }

    /** Seme da registrare in {@code coupon_pool.seed} per un pool nuovo: {@code 0} quando non esistono semi. */
    long poolSeed(String poolCode);

    /** Avvia la generazione di nuovi codici per {@code pool}, che ne contiene già {@code existing}. */
    Generation start(CouponPool pool, long existing);
}
