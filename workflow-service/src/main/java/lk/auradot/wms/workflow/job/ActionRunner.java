package lk.auradot.wms.workflow.job;

import lk.auradot.wms.engine.runtime.ActionRequest;

/**
 * Performs external actions outside any transaction. Emails need no call here: completing the action records
 * the outbox event workflow.email.requested, which notification-service sends.
 */
public class ActionRunner {

    private final WebhookSender webhooks;

    public ActionRunner(WebhookSender webhooks) {
        this.webhooks = webhooks;
    }

    public ActionResult run(ActionRequest request) {
        return switch (request.type()) {
            case WEBHOOK -> webhooks.send(request);
            case SEND_EMAIL, SET_VARIABLE -> ActionResult.ok();
        };
    }
}
