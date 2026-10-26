package lk.auradot.wms.notification.preference;

import lk.auradot.wms.notification.NotificationIntegrationTest;
import lk.auradot.wms.notification.TestDb;
import lk.auradot.wms.notification.TestJwt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Spec §8: "Honours per-user preferences". */
@NotificationIntegrationTest
class PreferenceApiTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;

    @BeforeEach
    void reset() {
        TestDb.clear(jdbc);
    }

    ResultActions save(String who, String body) throws Exception {
        return mvc.perform(put("/preferences").with(TestJwt.user(who, "user")).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    void someoneWhoNeverChoseGetsTheDefaultsForEveryKind() throws Exception {
        mvc.perform(get("/preferences").with(TestJwt.user("bob", "user")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].kind", contains("TASK_ASSIGNED", "TASK_AVAILABLE", "TASK_REMINDER", "TASK_OVERDUE",
                        "REQUEST_FINISHED", "DOCUMENT_SHARED")))
                .andExpect(jsonPath("$[0].inApp").value(true)).andExpect(jsonPath("$[0].email").value(true))
                .andExpect(jsonPath("$[1].inApp").value(true)).andExpect(jsonPath("$[1].email").value(false));
    }

    @Test
    void aPersonsChoicesAreKeptAndKindsTheyLeaveOutGoBackToTheDefaults() throws Exception {
        save("bob", """
                {"preferences":[{"kind":"TASK_ASSIGNED","inApp":true,"email":false},
                                {"kind":"TASK_AVAILABLE","inApp":false,"email":false}]}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].email").value(false))
                .andExpect(jsonPath("$[1].inApp").value(false));
        mvc.perform(get("/preferences").with(TestJwt.user("carol", "user"))).andExpect(jsonPath("$[0].email").value(true));

        save("bob", "{\"preferences\":[{\"kind\":\"TASK_AVAILABLE\",\"inApp\":true,\"email\":true}]}")
                .andExpect(jsonPath("$[0].email").value(true))   // TASK_ASSIGNED is back to its default
                .andExpect(jsonPath("$[1].email").value(true));
    }

    @Test
    void choicesThatDoNotMakeSenseAreRefused() throws Exception {
        save("bob", """
                {"preferences":[{"kind":"TASK_ASSIGNED","inApp":true,"email":false},
                                {"kind":"TASK_ASSIGNED","inApp":false,"email":false}]}""")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("NOTIF_PREFERENCE_DUPLICATE"));
        save("bob", "{\"preferences\":[{\"kind\":\"NO_SUCH_KIND\",\"inApp\":true,\"email\":false}]}")
                .andExpect(status().isBadRequest());
        save("bob", "{\"preferences\":[{\"kind\":\"TASK_ASSIGNED\",\"email\":false}]}").andExpect(status().isBadRequest());
        save("bob", "{}").andExpect(status().isBadRequest());
        mvc.perform(get("/preferences")).andExpect(status().isUnauthorized());
    }
}
