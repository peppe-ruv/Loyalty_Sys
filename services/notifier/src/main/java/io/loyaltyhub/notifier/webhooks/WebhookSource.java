package io.loyaltyhub.notifier.webhooks;

import java.util.List;

/** Porta verso il backoffice: sottoscrizioni webhook attive. */
public interface WebhookSource {
    List<WebhookSubscription> active();
}
