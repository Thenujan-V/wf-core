package lk.auradot.wms.form.files;

import java.util.List;

/** Spec §2.2 "form → document (verify file references)". */
public interface FileVerifier {

    /** Throws {@link FilesUnavailableException} when document-service cannot answer. */
    Verification verify(String submittedBy, List<String> documentIds);
}
