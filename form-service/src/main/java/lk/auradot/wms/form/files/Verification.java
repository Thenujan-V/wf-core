package lk.auradot.wms.form.files;

import java.util.List;

/** document-service's answer: the submitter's staged files, and the references that are not (or no longer) theirs. */
public record Verification(List<VerifiedFile> files, List<String> unavailable) {

    public Verification {
        files = files == null ? List.of() : List.copyOf(files);
        unavailable = unavailable == null ? List.of() : List.copyOf(unavailable);
    }
}
