package io.loyaltyhub.common.web;

/** Attore corrente della richiesta (ThreadLocal), popolato da {@code ActorFilter}. */
public final class ActorHolder {

    private static final ThreadLocal<ActorContext> CURRENT = ThreadLocal.withInitial(() -> ActorContext.ANONYMOUS);

    private ActorHolder() {
    }

    public static ActorContext get() {
        return CURRENT.get();
    }

    public static void set(ActorContext actor) {
        CURRENT.set(actor == null ? ActorContext.ANONYMOUS : actor);
    }

    public static void clear() {
        CURRENT.remove();
    }
}
