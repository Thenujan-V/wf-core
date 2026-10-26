package lk.auradot.wms.document.document;

import lk.auradot.wms.document.DocumentIntegrationTest;
import lk.auradot.wms.document.FakeGroups;
import lk.auradot.wms.document.FakeInstances;
import lk.auradot.wms.document.TestDb;
import lk.auradot.wms.document.TestDocuments;
import lk.auradot.wms.document.access.AclSnapshots;
import lk.auradot.wms.document.folder.Folders;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.UUID;

import static lk.auradot.wms.document.TestJwt.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Spec §6 "Send for approval…": a library document is attached to the request it starts. */
@DocumentIntegrationTest
class LinkApiTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired AclSnapshots snapshots;
    @Autowired FakeGroups groups;
    @Autowired FakeInstances instances;

    String doc;

    @BeforeEach
    void reset() {
        TestDb.clear(jdbc);
        groups.reset();
        instances.reset();
        String finance = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO folder VALUES (:id, :root, 'Finance', FALSE, 'dave', NOW(6))")
                .param("id", finance).param("root", Folders.ROOT_ID).update();
        jdbc.sql("INSERT INTO folder_acl VALUES (:id, 'USER', 'carol', 'WRITE')").param("id", finance).update();
        snapshots.invalidate();
        doc = TestDocuments.create(jdbc, finance, "Contract.pdf", "carol", "1.0");
        instances.grant("carol", "i-1", "contract-approval", true);
        instances.grant("carol", "i-2", "contract-approval", true);
        instances.grant("bob", "i-1", "contract-approval", false);
    }

    ResultActions link(String who, String instanceId) throws Exception {
        return mvc.perform(post("/documents/{id}/link", doc).with(user(who)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"instanceId\":\"" + instanceId + "\"}"));
    }

    @Test
    void linkingLetsTheRequestsPeopleReadADocumentTheirFoldersDoNotShow() throws Exception {
        mvc.perform(get("/documents/{id}", doc).with(user("bob"))).andExpect(status().isNotFound());

        link("carol", "i-1").andExpect(status().isOk())
                .andExpect(jsonPath("$.linkedInstanceId").value("i-1"))
                .andExpect(jsonPath("$.inLibrary").value(true));
        link("carol", "i-1").andExpect(status().isOk());

        mvc.perform(get("/documents/{id}", doc).with(user("bob")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.permission").value("READ"))
                .andExpect(jsonPath("$.inLibrary").value(false));
        mvc.perform(get("/instances/{id}/documents", "i-1").with(user("bob"))).andExpect(jsonPath("$", hasSize(1)));
        assertThat(jdbc.sql("SELECT COUNT(*) FROM outbox WHERE routing_key = 'document.linked'").query(Long.class).single())
                .isEqualTo(1);
    }

    @Test
    void aDocumentBelongsToOneRequestAndOnlyAttachersWithWriteLinkIt() throws Exception {
        link("carol", "i-1").andExpect(status().isOk());
        link("carol", "i-2").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DOC_ALREADY_LINKED"));
        // bob reads it through i-1 now, but reading is not enough to link it.
        link("bob", "i-1").andExpect(status().isForbidden());

        jdbc.sql("UPDATE document SET linked_instance_id = NULL").update();
        instances.grant("carol", "i-3", "contract-approval", false);
        link("carol", "i-3").andExpect(status().isForbidden());
        link("carol", "i-9").andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("DOC_INSTANCE_NOT_FOUND"));
    }

    @Test
    void aLinkedLibraryDocumentStillOpensForItsFolderReadersWhileWorkflowIsDown() throws Exception {
        jdbc.sql("INSERT INTO folder_acl SELECT folder_id, 'USER', 'erin', 'READ' FROM document WHERE id = :d")
                .param("d", doc).update();
        snapshots.invalidate();
        link("carol", "i-1").andExpect(status().isOk());
        instances.fail("i-1");

        mvc.perform(get("/documents/{id}", doc).with(user("erin")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.permission").value("READ"));
        String version = jdbc.sql("SELECT current_version_id FROM document WHERE id = :id").param("id", doc).query(String.class).single();
        mvc.perform(get("/documents/{id}/versions/{v}/content", doc, version).with(user("erin"))).andExpect(status().isOk());
    }

    @Test
    void linkingNeverAsksWorkflowServiceWhileTheDocumentIsLocked() throws Exception {
        link("carol", "i-1").andExpect(status().isOk());
        assertThat(instances.callsInTransaction()).isZero();
    }
}
