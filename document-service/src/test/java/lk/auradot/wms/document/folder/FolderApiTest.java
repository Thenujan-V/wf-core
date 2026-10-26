package lk.auradot.wms.document.folder;

import lk.auradot.wms.document.DocumentIntegrationTest;
import lk.auradot.wms.document.FakeGroups;
import lk.auradot.wms.document.TestDb;
import lk.auradot.wms.document.access.AclSnapshots;
import lk.auradot.wms.document.requests.InstanceFolders;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static lk.auradot.wms.document.TestJwt.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DocumentIntegrationTest
class FolderApiTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired AclSnapshots snapshots;
    @Autowired FakeGroups groups;
    @Autowired JsonMapper json;
    @Autowired InstanceFolders instanceFolders;

    @BeforeEach
    void reset() {
        TestDb.clear(jdbc);
        snapshots.invalidate();
        groups.reset();
    }

    @Test
    void requestFoldersAreManagedByTheSystemAndHiddenFromTheLibrary() throws Exception {
        String folder = instanceFolders.ensure("expense-request", "i-1");
        assertThat(instanceFolders.ensure("expense-request", "i-1")).isEqualTo(folder);
        assertThat(instanceFolders.isSystem(folder)).isTrue();
        assertThat(instanceFolders.isSystem(Folders.PROCESSES_ID)).isTrue();

        mvc.perform(get("/folders/root").with(user("carol"))).andExpect(jsonPath("$.children", hasSize(0)));
        mvc.perform(get("/folders/root").with(user("dave", "document-admin")))
                .andExpect(jsonPath("$.children[*].name", contains("Processes")));
        for (MockHttpServletRequestBuilder request : List.of(
                post("/folders").contentType(MediaType.APPLICATION_JSON).content("{\"parentId\":\"" + folder + "\",\"name\":\"x\"}"),
                patch("/folders/{id}", folder).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"x\"}"),
                delete("/folders/{id}", Folders.PROCESSES_ID),
                put("/folders/{id}/acl", folder).contentType(MediaType.APPLICATION_JSON).content("{\"inheritAcl\":true,\"entries\":[]}"))) {
            mvc.perform(request.with(user("dave", "document-admin")))
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DOC_SYSTEM_FOLDER"));
        }
    }

    String create(String parentId, String name, SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor who) throws Exception {
        String body = mvc.perform(post("/folders").with(who).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("parentId", parentId, "name", name))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("id").asString();
    }

    void setAcl(String folderId, String aclJson) throws Exception {
        mvc.perform(put("/folders/{id}/acl", folderId).with(user("dave", "document-admin"))
                        .contentType(MediaType.APPLICATION_JSON).content(aclJson))
                .andExpect(status().isOk());
    }

    @Test
    void everyUserReadsTheRootAndOnlyManagersCreateFoldersThere() throws Exception {
        mvc.perform(get("/folders/root").with(user("carol")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(Folders.ROOT_ID))
                .andExpect(jsonPath("$.name").value("Library"))
                .andExpect(jsonPath("$.permission").value("READ"))
                .andExpect(jsonPath("$.path", hasSize(0)));

        mvc.perform(post("/folders").with(user("carol")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"parentId\":\"" + Folders.ROOT_ID + "\",\"name\":\"Mine\"}"))
                .andExpect(status().isForbidden());

        String finance = create(Folders.ROOT_ID, "Finance", user("dave", "document-admin"));
        mvc.perform(get("/folders/{id}", finance).with(user("carol")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.path[0].name").value("Library"))
                .andExpect(jsonPath("$.parentId").value(Folders.ROOT_ID));
    }

    @Test
    void groupWritersCreateSubfoldersAndNamesAreUniquePerParent() throws Exception {
        String finance = create(Folders.ROOT_ID, "Finance", user("dave", "document-admin"));
        setAcl(finance, """
                {"inheritAcl":true,"entries":[{"principalType":"GROUP","principalId":"g-fin","permission":"WRITE"}]}""");

        create(finance, "Invoices", user("carol"));
        mvc.perform(post("/folders").with(user("bob")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"parentId\":\"" + finance + "\",\"name\":\" invoices \"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DOC_NAME_TAKEN"));
        mvc.perform(get("/folders/{id}", finance).with(user("carol")))
                .andExpect(jsonPath("$.permission").value("WRITE"))
                .andExpect(jsonPath("$.children[*].name", contains("Invoices")));
    }

    @Test
    void aFolderThatBreaksInheritanceIsInvisibleToOutsiders() throws Exception {
        String hr = create(Folders.ROOT_ID, "HR", user("dave", "document-admin"));
        setAcl(hr, """
                {"inheritAcl":false,"entries":[{"principalType":"USER","principalId":"erin","permission":"READ"}]}""");

        mvc.perform(get("/folders/root").with(user("carol"))).andExpect(jsonPath("$.children", hasSize(0)));
        mvc.perform(get("/folders/{id}", hr).with(user("carol")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DOC_FOLDER_NOT_FOUND"));
        mvc.perform(get("/folders/{id}", hr).with(user("erin"))).andExpect(status().isOk());
        mvc.perform(get("/folders/root").with(user("erin"))).andExpect(jsonPath("$.children[*].name", contains("HR")));
    }

    @Test
    void managersSeeOwnAndInheritedEntriesAndCannotLockThemselvesOut() throws Exception {
        String finance = create(Folders.ROOT_ID, "Finance", user("dave", "document-admin"));
        setAcl(finance, """
                {"inheritAcl":true,"entries":[{"principalType":"USER","principalId":"bob","permission":"MANAGE"}]}""");

        mvc.perform(get("/folders/{id}/acl", finance).with(user("bob")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries[0].principalId").value("bob"))
                .andExpect(jsonPath("$.inherited[*].principalId", contains("document-admin", "user")));
        mvc.perform(get("/folders/{id}/acl", finance).with(user("carol"))).andExpect(status().isForbidden());

        mvc.perform(put("/folders/{id}/acl", finance).with(user("bob")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"inheritAcl\":true,\"entries\":[]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DOC_ACL_LOCKOUT"));
        mvc.perform(put("/folders/{id}/acl", finance).with(user("bob")).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"inheritAcl":true,"entries":[
                                  {"principalType":"USER","principalId":"bob","permission":"MANAGE"},
                                  {"principalType":"USER","principalId":"bob","permission":"READ"}]}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DOC_ACL_DUPLICATE"));
    }

    @Test
    void renamesAndDeletesEmptyFoldersButNeverTheRoot() throws Exception {
        String finance = create(Folders.ROOT_ID, "Finance", user("dave", "document-admin"));
        String invoices = create(finance, "Invoices", user("dave", "document-admin"));

        mvc.perform(patch("/folders/{id}", invoices).with(user("dave", "document-admin")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Bills\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Bills"));
        mvc.perform(delete("/folders/{id}", finance).with(user("dave", "document-admin")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DOC_FOLDER_NOT_EMPTY"));
        mvc.perform(delete("/folders/{id}", invoices).with(user("dave", "document-admin"))).andExpect(status().isNoContent());
        mvc.perform(delete("/folders/{id}", Folders.ROOT_ID).with(user("dave", "document-admin")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DOC_ROOT_FOLDER"));
        mvc.perform(delete("/folders/{id}", finance).with(user("carol"))).andExpect(status().isForbidden());
    }

    @Test
    @SuppressWarnings("unchecked")
    void givingAPersonAccessToAFolderIsAnnouncedOnceForThatPerson() throws Exception {
        String finance = create(Folders.ROOT_ID, "Finance", user("dave", "document-admin"));

        setAcl(finance, """
                {"inheritAcl":true,"entries":[
                  {"principalType":"USER","principalId":"carol","permission":"WRITE"},
                  {"principalType":"GROUP","principalId":"g-fin","permission":"READ"}]}""");
        // carol keeps her entry (even with another permission); erin is new.
        setAcl(finance, """
                {"inheritAcl":true,"entries":[
                  {"principalType":"USER","principalId":"carol","permission":"READ"},
                  {"principalType":"USER","principalId":"erin","permission":"READ"}]}""");

        List<Map<String, Object>> shared = jdbc.sql("SELECT payload_json FROM outbox WHERE routing_key = 'document.folder.shared' ORDER BY id")
                .query(String.class).list().stream().map(payload -> (Map<String, Object>) json.readValue(payload, Map.class)).toList();
        assertThat(shared).hasSize(2);
        assertThat(shared.get(0)).containsEntry("userId", "carol").containsEntry("folderId", finance)
                .containsEntry("folderName", "Finance").containsEntry("permission", "WRITE").containsEntry("actorId", "dave");
        assertThat(shared.get(1)).containsEntry("userId", "erin").containsEntry("permission", "READ");
    }
}
