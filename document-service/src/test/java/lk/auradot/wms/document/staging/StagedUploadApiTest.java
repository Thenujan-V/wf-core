package lk.auradot.wms.document.staging;

import lk.auradot.wms.document.DocumentIntegrationTest;
import lk.auradot.wms.document.FakeGroups;
import lk.auradot.wms.document.FakeInstances;
import lk.auradot.wms.document.TestDb;
import lk.auradot.wms.document.TestFiles;
import lk.auradot.wms.document.TestUploads;
import lk.auradot.wms.document.access.AclSnapshots;
import lk.auradot.wms.document.folder.Folders;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

import java.util.HashMap;
import java.util.Map;

import static lk.auradot.wms.document.TestJwt.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Spec §5 file fields: a file uploaded while a form is filled in waits, readable by its uploader only. */
@DocumentIntegrationTest
class StagedUploadApiTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JsonMapper json;
    @Autowired AclSnapshots snapshots;
    @Autowired FakeGroups groups;
    @Autowired FakeInstances instances;

    @BeforeEach
    void reset() {
        TestDb.clear(jdbc);
        snapshots.invalidate();
        groups.reset();
        instances.reset();
    }

    String stage(String who, String fileName, String text) throws Exception {
        return TestUploads.upload(mvc, json, user(who), Map.of("staged", true), fileName, text);
    }

    String folderOf(String documentId) {
        return jdbc.sql("SELECT folder_id FROM document WHERE id = :id").param("id", documentId).query(String.class).single();
    }

    @Test
    void aStagedFileWaitsInItsOwnSystemFolderForItsUploaderOnly() throws Exception {
        String doc = stage("carol", "Receipt.txt", "taxi 1200");

        String parent = jdbc.sql("SELECT parent_id FROM folder WHERE id = :id").param("id", folderOf(doc)).query(String.class).single();
        assertThat(parent).isEqualTo(Folders.STAGING_ID);
        mvc.perform(get("/documents/{id}", doc).with(user("carol")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.permission").value("READ"))
                .andExpect(jsonPath("$.inLibrary").value(false))
                .andExpect(jsonPath("$.linkedInstanceId").value(org.hamcrest.Matchers.nullValue()));
        String version = jdbc.sql("SELECT current_version_id FROM document WHERE id = :id").param("id", doc).query(String.class).single();
        mvc.perform(get("/documents/{id}/versions/{v}/content", doc, version).with(user("carol"))).andExpect(status().isOk());

        mvc.perform(get("/documents/{id}", doc).with(user("bob"))).andExpect(status().isNotFound());
        mvc.perform(get("/folders/root").with(user("carol"))).andExpect(jsonPath("$.children", hasSize(0)));
    }

    @Test
    void twoStagedFilesMayShareAName() throws Exception {
        String first = stage("carol", "Receipt.txt", "one");
        String second = stage("carol", "Receipt.txt", "two");

        assertThat(first).isNotEqualTo(second);
        assertThat(folderOf(first)).isNotEqualTo(folderOf(second));
    }

    @Test
    void stagingIsATargetOfItsOwnAndItsFoldersTakeNoDirectUploads() throws Exception {
        String doc = stage("carol", "Receipt.txt", "taxi");
        byte[] body = "x".getBytes();
        Map<String, Object> request = new HashMap<>(Map.of("fileName", "x.txt", "size", body.length,
                "sha256", TestFiles.sha256(body), "mimeType", "text/plain"));

        request.put("staged", true);
        request.put("folderId", Folders.ROOT_ID);
        mvc.perform(post("/uploads").with(user("carol")).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(request)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("DOC_UPLOAD_TARGET"));

        request.remove("staged");
        request.put("folderId", folderOf(doc));
        mvc.perform(post("/uploads").with(user("carol")).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(request)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DOC_SYSTEM_FOLDER"));
    }
}
