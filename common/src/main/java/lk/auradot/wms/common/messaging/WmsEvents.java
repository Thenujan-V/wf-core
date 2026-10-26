package lk.auradot.wms.common.messaging;

public final class WmsEvents {

    /** Topic exchange for all WMS domain events (spec §2.2); routing keys are {@code <service>.<entity>.<event>}. */
    public static final String EXCHANGE = "wms.events";

    private WmsEvents() {
    }
}
