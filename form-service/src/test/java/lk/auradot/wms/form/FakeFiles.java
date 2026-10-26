package lk.auradot.wms.form;

import lk.auradot.wms.form.files.FileVerifier;
import lk.auradot.wms.form.files.FilesUnavailableException;
import lk.auradot.wms.form.files.Verification;
import lk.auradot.wms.form.files.VerifiedFile;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** Stands in for document-service: which user staged which file, and whether it answers at all. */
public class FakeFiles implements FileVerifier {

    private final Map<String, VerifiedFile> staged = new ConcurrentHashMap<>();
    private final List<List<String>> asked = new CopyOnWriteArrayList<>();
    private volatile boolean failing;

    public void reset() {
        staged.clear();
        asked.clear();
        failing = false;
    }

    public void stage(String userId, String documentId, String name) {
        staged.put(userId + "|" + documentId, new VerifiedFile(documentId, name, 10, "application/pdf"));
    }

    public void fail() {
        failing = true;
    }

    /** Each call's document ids, in order. */
    public List<List<String>> asked() {
        return asked;
    }

    @Override
    public Verification verify(String submittedBy, List<String> documentIds) {
        asked.add(List.copyOf(documentIds));
        if (failing) {
            throw new FilesUnavailableException("document-service is down (test)", null);
        }
        List<VerifiedFile> files = new ArrayList<>();
        List<String> unavailable = new ArrayList<>();
        for (String id : documentIds) {
            VerifiedFile file = staged.get(submittedBy + "|" + id);
            if (file == null) {
                unavailable.add(id);
            } else {
                files.add(file);
            }
        }
        return new Verification(files, unavailable);
    }
}
