package lk.auradot.wms.identity.org;

import lk.auradot.wms.identity.IdentityIntegrationTest;
import lk.auradot.wms.identity.TestData;
import lk.auradot.wms.identity.TestJwt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@IdentityIntegrationTest
@TestPropertySource(properties = {
        "wms.identity.org-source.manager=LOCAL",
        "wms.identity.org-source.department=LOCAL",
        "wms.identity.org-source.position=LOCAL"})
class OrgUpdateLocalTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    void seed() {
        TestData data = new TestData(jdbc);
        data.clear();
        data.user("a", "alice", "Alice", "Admin");
        data.user("b", "bob", "Bob", "Manager", "a", "Finance", true);
        data.user("c", "carol", "Carol", "Clerk", "b", "Finance", true);
    }

    private ResultActions update(String userId, String json, String... roles) throws Exception {
        return mvc.perform(put("/users/" + userId + "/org")
                .with(TestJwt.user("a", "alice", roles))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json));
    }

    @Test
    void adminUpdatesOrgFields() throws Exception {
        update("c", "{\"managerId\":\"a\",\"department\":\" Audit \",\"position\":\"Auditor\"}", "wms-admin")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.managerId").value("a"))
                .andExpect(jsonPath("$.department").value("Audit"))
                .andExpect(jsonPath("$.position").value("Auditor"));
    }

    @Test
    void blankValuesClearFields() throws Exception {
        update("c", "{\"managerId\":\"\",\"department\":null,\"position\":\"  \"}", "wms-admin")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.managerId").doesNotExist())
                .andExpect(jsonPath("$.department").doesNotExist());
    }

    @Test
    void nonAdminIsForbidden() throws Exception {
        update("c", "{\"managerId\":\"a\"}", "user").andExpect(status().isForbidden());
    }

    @Test
    void rejectsSelfUnknownAndCyclicManagers() throws Exception {
        update("c", "{\"managerId\":\"c\"}", "wms-admin")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("IDN_MANAGER_SELF"));
        update("c", "{\"managerId\":\"ghost\"}", "wms-admin")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("IDN_MANAGER_NOT_FOUND"));
        update("a", "{\"managerId\":\"c\"}", "wms-admin")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("IDN_MANAGER_CYCLE"));
    }

    @Test
    void rejectsUnknownUserAndOverlongValues() throws Exception {
        update("ghost", "{}", "wms-admin")
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("IDN_USER_NOT_FOUND"));
        update("c", "{\"department\":\"" + "x".repeat(256) + "\"}", "wms-admin")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }
}
