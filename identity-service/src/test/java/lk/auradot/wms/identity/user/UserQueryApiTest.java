package lk.auradot.wms.identity.user;

import lk.auradot.wms.identity.IdentityIntegrationTest;
import lk.auradot.wms.identity.TestData;
import lk.auradot.wms.identity.TestJwt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@IdentityIntegrationTest
class UserQueryApiTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    void seed() {
        TestData data = new TestData(jdbc);
        data.clear();
        data.user("a", "alice", "Alice", "Admin", null, "Management", true);
        data.user("b", "bob", "Bob", "Manager", "a", "Finance", true);
        data.user("c", "carol", "Carol", "Clerk", "b", "Finance", true);
        data.user("d", "dan_old", "Dan", "Former", "a", "Finance", false);
        data.user("x", "xena", "Xena", "Loop", null, null, true);
        data.user("y", "yuri", "Yuri", "Loop", "x", null, true);
        jdbc.sql("UPDATE app_user SET manager_id = 'y' WHERE id = 'x'").update();
        data.role("a", "wms-admin");
        data.role("a", "user");
        data.role("c", "user");
        data.role("d", "user");
        data.group("g-fin", "Finance");
        data.member("g-fin", "b");
        data.member("g-fin", "c");
    }

    private MockHttpServletRequestBuilder asCarol(MockHttpServletRequestBuilder request) {
        return request.with(TestJwt.user("c", "carol", "user"));
    }

    @Test
    void searchesByNameUsernameOrEmailCaseInsensitively() throws Exception {
        mvc.perform(asCarol(get("/users").param("q", "MANAGER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].username").value("bob"))
                .andExpect(jsonPath("$.items[0].displayName").value("Bob Manager"));
    }

    @Test
    void treatsLikeWildcardsLiterally() throws Exception {
        mvc.perform(asCarol(get("/users").param("q", "%")))
                .andExpect(jsonPath("$.total").value(0));
        mvc.perform(asCarol(get("/users").param("q", "_").param("includeDisabled", "true")))
                .andExpect(jsonPath("$.items[*].username", contains("dan_old")));
    }

    @Test
    void pagesAndExcludesDisabledByDefault() throws Exception {
        mvc.perform(asCarol(get("/users").param("page", "1").param("size", "2")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(5))
                .andExpect(jsonPath("$.items[*].username", contains("carol", "xena")));
    }

    @Test
    void rejectsOversizedPage() throws Exception {
        mvc.perform(asCarol(get("/users").param("size", "101")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IDN_INVALID_PAGE_SIZE"));
    }

    @Test
    void returnsUserDetailWithRolesAndGroups() throws Exception {
        mvc.perform(asCarol(get("/users/c")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.managerId").value("b"))
                .andExpect(jsonPath("$.roles", contains("user")))
                .andExpect(jsonPath("$.groups[0].path").value("/Finance"));
        mvc.perform(asCarol(get("/users/nope")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("IDN_USER_NOT_FOUND"));
    }

    @Test
    void walksManagerChainNearestFirst() throws Exception {
        mvc.perform(asCarol(get("/users/c/managers").param("levels", "2")))
                .andExpect(jsonPath("$[*].username", contains("bob", "alice")));
        mvc.perform(asCarol(get("/users/c/managers").param("levels", "10")))
                .andExpect(jsonPath("$", hasSize(2)));
        mvc.perform(asCarol(get("/users/c/managers")))
                .andExpect(jsonPath("$[*].username", contains("bob")));
    }

    @Test
    void stopsAtManagerCycles() throws Exception {
        mvc.perform(asCarol(get("/users/x/managers").param("levels", "10")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].username", contains("yuri")));
    }

    @Test
    void rejectsOutOfRangeLevels() throws Exception {
        mvc.perform(asCarol(get("/users/c/managers").param("levels", "0")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IDN_INVALID_LEVELS"));
    }

    @Test
    void listsGroupsAndMembers() throws Exception {
        mvc.perform(asCarol(get("/groups")))
                .andExpect(jsonPath("$[*].name", contains("Finance")));
        mvc.perform(asCarol(get("/groups/g-fin/members")))
                .andExpect(jsonPath("$[*].username", contains("bob", "carol")));
        mvc.perform(asCarol(get("/groups/nope/members")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("IDN_GROUP_NOT_FOUND"));
    }

    @Test
    void listsEnabledRoleMembers() throws Exception {
        mvc.perform(asCarol(get("/roles/user/members")))
                .andExpect(jsonPath("$[*].username", contains("alice", "carol")));
        mvc.perform(asCarol(get("/roles/unknown-role/members")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void listsThePeopleWhoReportToSomeoneAndSaysHowManyThereAre() throws Exception {
        mvc.perform(asCarol(get("/users/a/reports")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].id", contains("b")))
                .andExpect(jsonPath("$.items[0].displayName").value("Bob Manager"))
                .andExpect(jsonPath("$.total").value(1));
        mvc.perform(asCarol(get("/users/a/reports").param("includeDisabled", "true")))
                .andExpect(jsonPath("$.items[*].id", contains("b", "d")))
                .andExpect(jsonPath("$.total").value(2));
        mvc.perform(asCarol(get("/users/c/reports"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(0))).andExpect(jsonPath("$.total").value(0));
        mvc.perform(asCarol(get("/users/nobody/reports")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("IDN_USER_NOT_FOUND"));
    }

    @Test
    void aVeryLongListOfReportsIsCutAndSaysSo() throws Exception {
        TestData data = new TestData(jdbc);
        for (int i = 0; i < 501; i++) {
            data.user("r" + i, String.format("rep%03d", i), "Rep", "Number " + i, "x", null, true);
        }

        mvc.perform(asCarol(get("/users/x/reports")))
                .andExpect(jsonPath("$.items", hasSize(500)))
                .andExpect(jsonPath("$.total").value(502)); // yuri and the 501 added here
    }
}
