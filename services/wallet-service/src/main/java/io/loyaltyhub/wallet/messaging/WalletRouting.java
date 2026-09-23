package io.loyaltyhub.wallet.messaging;

import io.loyaltyhub.common.inbox.EventHandler;
import io.loyaltyhub.common.inbox.EventRouter;
import io.loyaltyhub.common.inbox.IdempotentHandler;
import io.loyaltyhub.common.metrics.LhMetrics;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/** Router degli eventi di wallet (docs/06 §5): solo i propri handler, corretto anche in modalità consolidata (ADR-006). */
@Configuration
public class WalletRouting {

    @Bean("walletEventRouter")
    public EventRouter walletEventRouter(PointsGrantHandler grant, MemberLifecycleHandler lifecycle,
                                         RedemptionHandler redemptions, IdempotentHandler idempotent, LhMetrics metrics) {
        return new EventRouter("lh-wallet", List.<EventHandler>of(grant, lifecycle, redemptions), idempotent, metrics);
    }
}
