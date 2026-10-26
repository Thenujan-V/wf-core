package lk.auradot.wms.notification.plan;

/** The kinds of notification a person can switch on or off, with what they get before they choose. */
public enum Kind {
    TASK_ASSIGNED(true, true),
    TASK_AVAILABLE(true, false),
    TASK_REMINDER(true, true),
    TASK_OVERDUE(true, true),
    REQUEST_FINISHED(true, true),
    DOCUMENT_SHARED(true, false);

    private final boolean defaultInApp;
    private final boolean defaultEmail;

    Kind(boolean defaultInApp, boolean defaultEmail) {
        this.defaultInApp = defaultInApp;
        this.defaultEmail = defaultEmail;
    }

    public boolean defaultInApp() {
        return defaultInApp;
    }

    public boolean defaultEmail() {
        return defaultEmail;
    }
}
