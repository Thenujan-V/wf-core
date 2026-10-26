package lk.auradot.wms.identity.org;

import lk.auradot.wms.identity.IdentityIntegrationTest;
import lk.auradot.wms.identity.TestData;
import lk.auradot.wms.identity.TestJwt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Spec §7: "The source is set per field in instance config." The org-chart page must know which fields it may edit. */
@IdentityIntegrationTest
@TestPropertySource(properties = {
        "wms.identity.org-source.manager=LOCAL",
        "wms.identity.org-source.department=KEYCLOAK",
        "wms.identity.org-source.position=LOCAL"})
class OrgSettingsApiTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;

    @BeforeEach
    void seed() {
        TestData data = new TestData(jdbc);
        data.clear();
        data.user("a", "alice", "Alice", "Admin");
        data.user("c", "carol", "Carol", "Clerk");
    }

    @Test
    void tellsAnAdministratorWhichFieldsAreKeptHere() throws Exception {
        mvc.perform(get("/org/settings").with(TestJwt.user("a", "alice", "wms-admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.manager").value("LOCAL"))
                .andExpect(jsonPath("$.department").value("KEYCLOAK"))
                .andExpect(jsonPath("$.position").value("LOCAL"));
    }

    @Test
    void isForAdministratorsOnly() throws Exception {
        mvc.perform(get("/org/settings").with(TestJwt.user("c", "carol", "user"))).andExpect(status().isForbidden());
        mvc.perform(get("/org/settings")).andExpect(status().isUnauthorized());
    }
}
