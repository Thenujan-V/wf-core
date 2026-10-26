package lk.auradot.wms.identity.org;

import lk.auradot.wms.identity.IdentityIntegrationTest;
import lk.auradot.wms.identity.TestData;
import lk.auradot.wms.identity.TestJwt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Default config: every org field is sourced from Keycloak. */
@IdentityIntegrationTest
class OrgUpdateKeycloakManagedTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    void seed() {
        TestData data = new TestData(jdbc);
        data.clear();
        data.user("a", "alice", "Alice", "Admin");
        data.user("c", "carol", "Carol", "Clerk", "a", "Finance", true);
    }

    @Test
    void refusesToChangeKeycloakManagedField() throws Exception {
        mvc.perform(put("/users/c/org").with(TestJwt.user("a", "alice", "wms-admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"managerId\":\"a\",\"department\":\"Audit\",\"position\":null}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDN_ORG_FIELD_MANAGED_BY_KEYCLOAK"));
    }

    @Test
    void acceptsUnchangedKeycloakManagedValues() throws Exception {
        mvc.perform(put("/users/c/org").with(TestJwt.user("a", "alice", "wms-admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"managerId\":\"a\",\"department\":\"Finance\",\"position\":null}"))
                .andExpect(status().isOk());
    }
}
