package lk.auradot.wms.document.messaging;

import lk.auradot.wms.common.messaging.ProcessedMessages;
import lk.auradot.wms.document.DocumentIntegrationTest;
import lk.auradot.wms.document.MutableClock;
import lk.auradot.wms.document.TestDb;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import static org.assertj.core.api.Assertions.assertThat;

@DocumentIntegrationTest
class ProcessedMessagesTest {

    @Autowired JdbcClient jdbc;
    @Autowired ProcessedMessages processed;
    @Autowired MutableClock clock;

    @BeforeEach
    void reset() {
        TestDb.clear(jdbc);
    }

    @Test
    void aMessageIsHandledOncePerConsumer() {
        assertThat(processed.isProcessed("m-1", "text-extraction")).isFalse();
        assertThat(processed.markProcessed("m-1", "text-extraction", clock.instant())).isTrue();
        assertThat(processed.markProcessed("m-1", "text-extraction", clock.instant())).isFalse();
        assertThat(processed.isProcessed("m-1", "text-extraction")).isTrue();
        assertThat(processed.markProcessed("m-1", "another-consumer", clock.instant())).isTrue();
    }
}
