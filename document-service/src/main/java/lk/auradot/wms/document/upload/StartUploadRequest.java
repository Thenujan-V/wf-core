package lk.auradot.wms.document.upload;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.Map;

/**
 * One of folderId (a new document; name defaults to the file name), instanceId (an attachment of that request), staged
 * (a file for a form that is being filled in) or documentId (a new version, which needs the caller's check-out). The size and SHA-256 are what the confirm step will insist on.
 */
public record StartUploadRequest(@NotBlank @Size(max = 255) String fileName, @Positive long size,
                                 @NotBlank @Pattern(regexp = "[0-9a-fA-F]{64}") String sha256, @Size(max = 255) String mimeType,
                                 String folderId, String documentId, @Size(max = 255) String name, String typeId,
                                 Map<String, Object> metadata, Boolean major, @Size(max = 1000) String comment,
                                 String instanceId, Boolean staged) {
}
