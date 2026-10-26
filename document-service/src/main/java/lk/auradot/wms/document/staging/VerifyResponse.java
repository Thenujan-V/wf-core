package lk.auradot.wms.document.staging;

import java.util.List;

/** Which referenced files are the submitter's staged files, and which are not (or no longer) available. */
public record VerifyResponse(List<StagedFile> files, List<String> unavailable) {
}
