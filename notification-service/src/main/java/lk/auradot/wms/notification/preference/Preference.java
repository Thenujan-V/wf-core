package lk.auradot.wms.notification.preference;

import lk.auradot.wms.notification.plan.Kind;

/** Whether a person wants one kind of notification in the app and by email. */
public record Preference(Kind kind, boolean inApp, boolean email) {

    public static Preference defaults(Kind kind) {
        return new Preference(kind, kind.defaultInApp(), kind.defaultEmail());
    }
}
