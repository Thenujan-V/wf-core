package lk.auradot.wms.identity.org;

/** Where an org field's value comes from: Keycloak attributes (sync owns it) or admin edits in WMS. */
public enum OrgSource {
    KEYCLOAK,
    LOCAL
}
