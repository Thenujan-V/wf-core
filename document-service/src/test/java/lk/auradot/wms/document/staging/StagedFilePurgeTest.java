package lk.auradot.wms.document.staging;

import lk.auradot.wms.document.DocumentIntegrationTest;
import lk.auradot.wms.document.FakeGroups;
import lk.auradot.wms.document.FakeInstances;
import lk.auradot.wms.document.MutableClock;
import lk.auradot.wms.document.TestDb;
import lk.auradot.wms.document.TestUploads;
import lk.auradot.wms.document.access.AclSnapshots;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static lk.auradot.wms.document.TestJwt.user;
import static org.assertj.core.api.Assertions.assertThat;

/** Staged files nobody submitted are removed after a day; submitted ones and recent ones stay. */
@DocumentIntegrationTest
class StagedFilePurgeTest {

    @Autowired StagedFilePurge purge;
    @Autowired RequestFilesListener listener;
    @Autowired MutableClock clock;
    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JsonMapper json;
    @Autowired AclSnapshots snapshots;
    @Autowired FakeGroups groups;
    @Autowired FakeInstances instances;

    @BeforeEach
    @AfterEach
    void reset() {
        clock.set(MutableClock.DEFAULT);
        TestDb.clear(jdbc);
        snapshots.invalidate();
        groups.reset();
        instances.reset();
    }

    String stage(String fileName) throws Exception {
        return TestUploads.upload(mvc, json, user("carol"), Map.of("staged", true), fileName, "content of " + fileName);
    }

    boolean exists(String documentId) {
        return jdbc.sql("SELECT COUNT(*) FROM document WHERE id = :id").param("id", documentId).query(Long.class).single() == 1;
    }

    @Test
    void stagedFilesNobodySubmittedAreRemovedAfterADay() throws Exception {
        String abandoned = stage("Old.txt");
        String abandonedFolder = jdbc.sql("SELECT folder_id FROM document WHERE id = :id").param("id", abandoned)
                .query(String.class).single();
        String submitted = stage("Kept.txt");
        listener.onFilesSubmitted(MessageBuilder.withBody(json.writeValueAsBytes(Map.of("messageId", "m1",
                "instanceId", "i-1", "processKey", "expense-request", "actorId", "carol",
                "documentIds", List.of(submitted)))).build());

        clock.advance(Duration.ofHours(25));
        String fresh = stage("Fresh.txt");

        assertThat(purge.purge()).isEqualTo(1);
        assertThat(exists(abandoned)).isFalse();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM folder WHERE id = :id").param("id", abandonedFolder)
                .query(Long.class).single()).isZero();
        assertThat(exists(submitted)).isTrue();
        assertThat(exists(fresh)).isTrue();
    }
}
