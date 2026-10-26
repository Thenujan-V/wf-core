package lk.auradot.wms.document.approval;

/** A person's name for printing; their id when nobody knows them. Throws when identity-service cannot answer. */
public interface People {

    String displayName(String userId);
}
