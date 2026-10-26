package lk.auradot.wms.identity.jit;

import lk.auradot.wms.identity.IdentityIntegrationTest;
import lk.auradot.wms.identity.TestData;
import lk.auradot.wms.identity.TestJwt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@IdentityIntegrationTest
class MeEndpointTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcClient jdbc;
    TestData data;

    @BeforeEach
    void reset() {
        data = new TestData(jdbc);
        data.clear();
    }

    private String email(String id) {
        return jdbc.sql("SELECT email FROM app_user WHERE id = :id").param("id", id).query(String.class).single();
    }

    @Test
    void firstRequestProvisionsUserFromToken() throws Exception {
        mvc.perform(get("/me").with(TestJwt.user("u-carol", "carol", "user")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("u-carol"))
                .andExpect(jsonPath("$.username").value("carol"))
                .andExpect(jsonPath("$.displayName").value("Carol Test"))
                .andExpect(jsonPath("$.email").value("carol@example.com"))
                .andExpect(jsonPath("$.roles[0]").value("user"))
                .andExpect(jsonPath("$.enabled").value(true));
    }

    @Test
    void refreshesStaleUserButKeepsOrgFields() throws Exception {
        data.user("u-carol", "carol", "Carol", "Old", null, "Finance", true);
        jdbc.sql("UPDATE app_user SET email = 'old@example.com' WHERE id = 'u-carol'").update();
        data.syncedAt("u-carol", Instant.now().minus(Duration.ofHours(2)));

        mvc.perform(get("/me").with(TestJwt.user("u-carol", "carol", "user")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("carol@example.com"))
                .andExpect(jsonPath("$.department").value("Finance"));
    }

    @Test
    void doesNotRewriteFreshUser() throws Exception {
        data.user("u-carol", "carol", "Carol", "Clerk");
        jdbc.sql("UPDATE app_user SET email = 'kept@example.com' WHERE id = 'u-carol'").update();
        data.syncedAt("u-carol", Instant.now().minus(Duration.ofMinutes(5)));

        mvc.perform(get("/me").with(TestJwt.user("u-carol", "carol", "user"))).andExpect(status().isOk());

        assertThat(email("u-carol")).isEqualTo("kept@example.com");
    }

    @Test
    void provisionsFederatedUserWithLongKeycloakId() throws Exception {
        // Keycloak ids for LDAP users that are not imported look like f:<componentId>:<externalId>.
        String federatedId = "f:6a2c1f64-8f3e-4d8e-9f2a-5b1c7d9e0a11:CN=Dilhan Perera,OU=Finance,DC=corp";

        mvc.perform(get("/me").with(TestJwt.user(federatedId, "dilhan", "user")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(federatedId));
    }

    @Test
    void rejectsAnonymousRequest() throws Exception {
        mvc.perform(get("/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void concurrentFirstRequestsAllSucceed() throws Exception {
        int parallel = 8;
        ExecutorService pool = Executors.newFixedThreadPool(parallel);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        Callable<Integer> call = () -> {
            start.await();
            return mvc.perform(get("/me").with(TestJwt.user("u-new", "newbie", "user", "process-designer")))
                    .andReturn().getResponse().getStatus();
        };
        for (int i = 0; i < parallel; i++) {
            results.add(pool.submit(call));
        }
        start.countDown();
        for (Future<Integer> result : results) {
            assertThat(result.get()).isEqualTo(200);
        }
        pool.shutdown();

        assertThat(jdbc.sql("SELECT COUNT(*) FROM app_user WHERE id = 'u-new'").query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT role_name FROM user_role WHERE user_id = 'u-new' ORDER BY role_name")
                .query(String.class).list()).containsExactly("process-designer", "user");
    }

    @Test
    void serviceAccountsAreNotProvisionedAsPeople() throws Exception {
        mvc.perform(get("/users").with(TestJwt.user("svc-workflow", "service-account-wms-workflow")))
                .andExpect(status().isOk());

        assertThat(jdbc.sql("SELECT COUNT(*) FROM app_user WHERE id = 'svc-workflow'").query(Integer.class).single())
                .isZero();
    }
}
