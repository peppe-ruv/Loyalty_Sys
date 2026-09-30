package io.loyaltyhub.reward.application;

import io.loyaltyhub.reward.domain.CouponPool;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.StandardEnvironment;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/** Quale generatore di codici sceglie ciascun profilo (Q-614, ADR-044). */
class CouponCodePolicyTest {

    private static CouponPool pool(String code, String prefix, long seed) {
        return new CouponPool("01HPOOL", code, "Pool", prefix, 90, seed, null);
    }

    @Test
    void demoPolicyIsSeededAndExposesTheSeed() {
        CouponCodePolicy policy = new SeededCouponCodePolicy();
        long seed = policy.poolSeed("POOL-CAF");
        assertThat(seed).isEqualTo(SeededCouponCodePolicy.seedFor("POOL-CAF")).isEqualTo(-4898811249275050660L);

        CouponCodePolicy.Generation a = policy.start(pool("POOL-CAF", "CAF", seed), 0);
        CouponCodePolicy.Generation b = policy.start(pool("POOL-CAF", "CAF", seed), 0);
        assertThat(a.seed()).isEqualTo(b.seed()).isNotNull();
        assertThat(a.codes().next()).isEqualTo("CAF-ATB2-DDFW").isEqualTo(b.codes().next());
        assertThat(policy.start(pool("POOL-CAF", "CAF", seed), 600).codes().next()).isEqualTo("CAF-CWAW-USFV");
    }

    @Test
    void nonDemoPolicyHasNoSeedAndTwoGenerationsOfTheSamePoolDiffer() {
        CouponCodePolicy policy = new SecureCouponCodePolicy();
        assertThat(policy.poolSeed("POOL-CAF")).isZero();

        CouponPool pool = pool("POOL-CAF", "CAF", 0);
        CouponCodePolicy.Generation a = policy.start(pool, 0);
        CouponCodePolicy.Generation b = policy.start(pool, 0);
        assertThat(a.seed()).isNull();
        assertThat(b.seed()).isNull();
        List<String> first = IntStream.range(0, 100).mapToObj(i -> a.codes().next()).toList();
        List<String> second = IntStream.range(0, 100).mapToObj(i -> b.codes().next()).toList();
        assertThat(first).isNotEqualTo(second);
        assertThat(first.getFirst()).isNotEqualTo("CAF-ATB2-DDFW");
    }

    @Test
    void onlyDemoWithoutEnterpriseGetsTheSeededPolicy() {
        assertThat(policyFor()).isInstanceOf(SecureCouponCodePolicy.class);
        assertThat(policyFor("demo")).isInstanceOf(SeededCouponCodePolicy.class);
        assertThat(policyFor("demo", "free")).isInstanceOf(SeededCouponCodePolicy.class);
        assertThat(policyFor("enterprise")).isInstanceOf(SecureCouponCodePolicy.class);
        assertThat(policyFor("demo", "enterprise")).isInstanceOf(SecureCouponCodePolicy.class);
    }

    @Import({SeededCouponCodePolicy.class, SecureCouponCodePolicy.class})
    static class Config {
    }

    private static CouponCodePolicy policyFor(String... profiles) {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            StandardEnvironment env = new StandardEnvironment();
            env.setActiveProfiles(profiles);
            ctx.setEnvironment(env);
            ctx.register(Config.class);
            ctx.refresh();
            return ctx.getBean(CouponCodePolicy.class); // una sola bean: altrimenti NoUniqueBeanDefinitionException
        }
    }
}
