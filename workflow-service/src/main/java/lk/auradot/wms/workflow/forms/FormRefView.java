package lk.auradot.wms.workflow.forms;

/** Which form version the browser should render. */
public record FormRefView(String key, int version) {

    public static FormRefView of(FormRef ref) {
        return ref == null ? null : new FormRefView(ref.key(), ref.version());
    }
}
