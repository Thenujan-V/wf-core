package lk.auradot.wms.identity.org;

/** Where each org field's value comes from in this instance; only LOCAL fields can be edited in the org chart. */
public record OrgSettings(OrgSource manager, OrgSource department, OrgSource position) {
}
