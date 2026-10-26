package lk.auradot.wms.document.folder;

public final class Folders {

    /** The library root, seeded by V1__document_schema.sql. */
    public static final String ROOT_ID = "00000000-0000-0000-0000-000000000001";

    /** Parent of the request folders /Processes/<processKey>/<instanceId>, seeded by V2__request_documents.sql. */
    public static final String PROCESSES_ID = "00000000-0000-0000-0000-000000000002";

    /** Parent of the staged-upload folders (one per upload), seeded by V3__staged_uploads.sql. */
    public static final String STAGING_ID = "00000000-0000-0000-0000-000000000003";

    private Folders() {
    }
}
