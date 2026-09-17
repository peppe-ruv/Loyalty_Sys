package io.loyaltyhub.decisionservice.app;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.loyaltyhub.decisionservice.domain.DecisionContext;
import io.loyaltyhub.decisionservice.domain.PredictionProvider;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;

/**
 * Interruttore automatico davanti a un provider di previsioni esterno (RF-130). Un provider lento o rotto non deve
 * far pagare il timeout a ogni decisione: dopo una quota di chiamate fallite l'interruttore si apre, le chiamate
 * successive falliscono subito e il {@code CompositePredictionProvider} ricade sul provider a regole. Passata la
 * finestra di attesa lascia passare qualche chiamata di prova e, se vanno bene, si richiude da solo.
 *
 * <p>Le previsioni sono un aiuto al punteggio, non un dato contrattuale: perderle temporaneamente cambia l'ordine
 * delle azioni discrezionali e nient'altro (RF-130). Meglio una decisione più povera subito che una decisione giusta
 * fuori tempo massimo.
 */
public final class BreakerPredictionProvider implements PredictionProvider {

    private final PredictionProvider delegate;
    private final CircuitBreaker breaker;

    public BreakerPredictionProvider(PredictionProvider delegate, int failureRateThreshold, int minimumCalls,
                                     Duration openWait, BiConsumer<String, String> onStateChange) {
        this.delegate = delegate;
        this.breaker = CircuitBreaker.of(delegate.name(), CircuitBreakerConfig.custom()
                .failureRateThreshold(failureRateThreshold)
                .minimumNumberOfCalls(Math.max(1, minimumCalls))
                .slidingWindowSize(Math.max(minimumCalls, 20))
                .waitDurationInOpenState(openWait)
                .permittedNumberOfCallsInHalfOpenState(3)
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                .build());
        if (onStateChange != null) {
            breaker.getEventPublisher().onStateTransition(e -> onStateChange.accept(delegate.name(), e.getStateTransition().getToState().name()));
            onStateChange.accept(delegate.name(), breaker.getState().name());
        }
    }

    @Override public String name() { return delegate.name(); }

    /** Lo stato corrente, per la console e i test: CLOSED, OPEN, HALF_OPEN. */
    public String state() { return breaker.getState().name(); }

    @Override
    public Map<String, Double> predict(DecisionContext ctx, Set<String> keys) {
        try {
            return breaker.executeCallable(() -> delegate.predict(ctx, keys));
        } catch (CallNotPermittedException open) {
            // Interruttore aperto: il composito lo tratta come qualunque altro errore e usa le regole.
            throw new IllegalStateException("provider " + delegate.name() + ": interruttore aperto", open);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
