package lk.auradot.wms.workflow.forms;

import java.util.Optional;

/** workflow-service's view of form-service (spec §2.2: workflow → form, synchronous). */
public interface FormClient {

    /** A stored version (published or retired), or empty when it does not exist. */
    Optional<FormVersion> version(String key, int version);

    /** Validates and stores a submission; throws {@link FormValidationException} with the field errors. */
    Submitted submit(SubmitForm request);
}
