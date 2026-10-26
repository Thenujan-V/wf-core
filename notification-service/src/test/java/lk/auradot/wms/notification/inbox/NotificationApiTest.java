package lk.auradot.wms.notification.inbox;

import lk.auradot.wms.notification.MutableClock;
import lk.auradot.wms.notification.NotificationIntegrationTest;
import lk.auradot.wms.notification.RecordingLivePush;
import lk.auradot.wms.notification.TestDb;
import lk.auradot.wms.notification.TestJwt;
import lk.auradot.wms.notification.plan.Kind;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@NotificationIntegrationTest
class NotificationApiTest {

    @Autowired MockMvc mvc;
    @Autowired NotificationStore store;
    @Autowired RecordingLivePush push;
    @Autowired MutableClock clock;
    @Autowired JdbcClient jdbc;

    String first;
    String second;

    @BeforeEach
    void seed() {
        clock.set(MutableClock.DEFAULT);
        TestDb.clear(jdbc);
        push.reset();
        first = store.insert("bob", Kind.TASK_ASSIGNED, "New task: Review", "You have a new task.", "/inbox", clock.instant()).id();
        clock.advance(Duration.ofMinutes(5));
        second = store.insert("bob", Kind.REQUEST_FINISHED, "Your request EXP-7 is approved", "It has finished.", "/requests/i-1", clock.instant()).id();
        store.insert("carol", Kind.TASK_REMINDER, "Reminder: Review", "Still waiting.", "/inbox", clock.instant());
    }

    @AfterEach
    void restoreClock() {
        clock.set(MutableClock.DEFAULT);
    }

    @Test
    void aPersonSeesTheirOwnNotificationsNewestFirst() throws Exception {
        mvc.perform(get("/notifications").with(TestJwt.user("bob", "user")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.unread").value(2))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.items[*].id", contains(second, first)))
                .andExpect(jsonPath("$.items[0].kind").value("REQUEST_FINISHED"))
                .andExpect(jsonPath("$.items[0].title").value("Your request EXP-7 is approved"))
                .andExpect(jsonPath("$.items[0].link").value("/requests/i-1"))
                .andExpect(jsonPath("$.items[0].createdAt").value("2026-10-01T09:05:00Z"))
                .andExpect(jsonPath("$.items[0].readAt").value(nullValue()));
        mvc.perform(get("/notifications/unread-count").with(TestJwt.user("carol", "user")))
                .andExpect(jsonPath("$.count").value(1));
    }

    @Test
    void readingOneOrAllOfThemIsRemembered() throws Exception {
        mvc.perform(post("/notifications/{id}/read", first).with(TestJwt.user("bob", "user"))).andExpect(status().isNoContent());
        mvc.perform(post("/notifications/{id}/read", first).with(TestJwt.user("bob", "user"))).andExpect(status().isNoContent());

        mvc.perform(get("/notifications/unread-count").with(TestJwt.user("bob", "user"))).andExpect(jsonPath("$.count").value(1));
        mvc.perform(get("/notifications").param("unread", "true").with(TestJwt.user("bob", "user")))
                .andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.items[*].id", contains(second)));
        mvc.perform(get("/notifications").with(TestJwt.user("bob", "user")))
                .andExpect(jsonPath("$.total").value(2)).andExpect(jsonPath("$.unread").value(1))
                .andExpect(jsonPath("$.items[1].readAt").value("2026-10-01T09:05:00Z"));

        mvc.perform(post("/notifications/read-all").with(TestJwt.user("bob", "user"))).andExpect(status().isNoContent());
        mvc.perform(get("/notifications/unread-count").with(TestJwt.user("bob", "user"))).andExpect(jsonPath("$.count").value(0));
        mvc.perform(get("/notifications/unread-count").with(TestJwt.user("carol", "user"))).andExpect(jsonPath("$.count").value(1));
    }

    @Test
    void nobodyReadsOrChangesSomeoneElsesNotifications() throws Exception {
        mvc.perform(post("/notifications/{id}/read", first).with(TestJwt.user("carol", "user")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOTIF_NOT_FOUND"));
        mvc.perform(get("/notifications").with(TestJwt.user("alice", "wms-admin")))
                .andExpect(jsonPath("$.total").value(0)).andExpect(jsonPath("$.items", hasSize(0)));
        mvc.perform(get("/notifications")).andExpect(status().isUnauthorized());
    }

    @Test
    void pagesAreBounded() throws Exception {
        mvc.perform(get("/notifications").param("size", "1").param("page", "1").with(TestJwt.user("bob", "user")))
                .andExpect(jsonPath("$.items[*].id", contains(first))).andExpect(jsonPath("$.total").value(2));
        mvc.perform(get("/notifications").param("size", "500").with(TestJwt.user("bob", "user")))
                .andExpect(jsonPath("$.size").value(100));
        mvc.perform(get("/notifications").param("size", "0").param("page", "-2").with(TestJwt.user("bob", "user")))
                .andExpect(jsonPath("$.size").value(1)).andExpect(jsonPath("$.page").value(0));
    }

    @Test
    void readingIsAnnouncedSoThePersonsOtherTabsFollow() throws Exception {
        mvc.perform(post("/notifications/{id}/read", first).with(TestJwt.user("bob", "user"))).andExpect(status().isNoContent());
        mvc.perform(post("/notifications/read-all").with(TestJwt.user("carol", "user"))).andExpect(status().isNoContent());
        mvc.perform(post("/notifications/{id}/read", first).with(TestJwt.user("carol", "user"))).andExpect(status().isNotFound());

        assertThat(push.countChanges()).containsExactly("bob", "carol");
    }

    @Test
    void aBrokenLivePushDoesNotFailReading() throws Exception {
        push.failing(true);

        mvc.perform(post("/notifications/read-all").with(TestJwt.user("bob", "user"))).andExpect(status().isNoContent());

        mvc.perform(get("/notifications/unread-count").with(TestJwt.user("bob", "user"))).andExpect(jsonPath("$.count").value(0));
    }
}
