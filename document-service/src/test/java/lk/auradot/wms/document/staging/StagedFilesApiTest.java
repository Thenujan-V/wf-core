package lk.auradot.wms.document.staging;

import lk.auradot.wms.document.DocumentIntegrationTest;
import lk.auradot.wms.document.FakeGroups;
import lk.auradot.wms.document.FakeInstances;
import lk.auradot.wms.document.TestDb;
import lk.auradot.wms.document.MutableClock;
import lk.auradot.wms.document.TestUploads;
import lk.auradot.wms.document.access.AclSnapshots;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static lk.auradot.wms.document.TestJwt.user;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Spec §2.2 "form → document (verify file references)". */
@DocumentIntegrationTest
class StagedFilesApiTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JsonMapper json;
    @Autowired AclSnapshots snapshots;
    @Autowired FakeGroups groups;
    @Autowired FakeInstances instances;
    @Autowired MutableClock clock;

    @BeforeEach
    void reset() {
        clock.set(MutableClock.DEFAULT);
        TestDb.clear(jdbc);
        snapshots.invalidate();
        groups.reset();
        instances.reset();
    }

    String verify(String who, String role, String submittedBy, List<String> ids, int expectedStatus) throws Exception {
        return mvc.perform(post("/staged-files/verify").with(user(who, role)).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("submittedBy", submittedBy, "documentIds", ids))))
                .andExpect(status().is(expectedStatus)).andReturn().getResponse().getContentAsString();
    }

    @Test
    void theFormServiceLearnsWhichFilesTheSubmitterStaged() throws Exception {
        String mine = TestUploads.upload(mvc, json, user("carol"), Map.of("staged", true), "Receipt.txt", "taxi 1200");
        String bobs = TestUploads.upload(mvc, json, user("bob"), Map.of("staged", true), "Other.txt", "x");

        mvc.perform(post("/staged-files/verify").with(user("service-account-wms-form", "wms-service"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("submittedBy", "carol", "documentIds", List.of(mine, bobs, "nope")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.files", hasSize(1)))
                .andExpect(jsonPath("$.files[0].documentId").value(mine))
                .andExpect(jsonPath("$.files[0].name").value("Receipt.txt"))
                .andExpect(jsonPath("$.files[0].size").value(9))
                .andExpect(jsonPath("$.files[0].mimeType").value("text/plain"))
                .andExpect(jsonPath("$.unavailable", containsInAnyOrder(bobs, "nope")));
    }

    @Test
    void onlyServicesMayAsk() throws Exception {
        verify("carol", "user", "carol", List.of("x"), 403);
    }

    @Test
    void aStagedFileAboutToBeRemovedIsNoLongerAccepted() throws Exception {
        String doc = TestUploads.upload(mvc, json, user("carol"), Map.of("staged", true), "Receipt.txt", "taxi 1200");
        clock.advance(java.time.Duration.ofHours(23).plusMinutes(30));

        mvc.perform(post("/staged-files/verify").with(user("service-account-wms-form", "wms-service"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("submittedBy", "carol", "documentIds", List.of(doc)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.files", hasSize(0)))
                .andExpect(jsonPath("$.unavailable[0]").value(doc));
        clock.set(MutableClock.DEFAULT);
    }
}
