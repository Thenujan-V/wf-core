package lk.auradot.wms.document.text;

import lk.auradot.wms.common.messaging.WmsEvents;
import lk.auradot.wms.document.DocumentIntegrationTest;
import lk.auradot.wms.document.Json;
import lk.auradot.wms.document.TestDb;
import lk.auradot.wms.document.TestDocuments;
import lk.auradot.wms.document.TestFiles;
import lk.auradot.wms.document.TestRabbit;
import lk.auradot.wms.document.folder.Folders;
import lk.auradot.wms.document.messaging.MessagingConfig;
import lk.auradot.wms.document.storage.ObjectStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DocumentIntegrationTest
@Import(TestRabbit.class)
class TextExtractionTest {

    @Autowired JdbcClient jdbc;
    @Autowired ObjectStore objects;
    @Autowired RabbitTemplate rabbit;
    @Autowired RabbitListenerEndpointRegistry listeners;
    @Autowired Json json;

    @BeforeEach
    void start() {
        TestDb.clear(jdbc);
        listeners.getListenerContainer(TextExtractionListener.LISTENER_ID).start();
    }

    @AfterEach
    void stop() {
        listeners.getListenerContainer(TextExtractionListener.LISTENER_ID).stop();
    }

    /** A document whose current version's object holds these bytes (or no object at all). */
    String[] document(String name, byte[] content) {
        String doc = TestDocuments.create(jdbc, Folders.ROOT_ID, name, "bob", "1.0");
        String version = jdbc.sql("SELECT current_version_id FROM document WHERE id = :id").param("id", doc).query(String.class).single();
        String key = "objects/" + version;
        if (content != null) {
            assertThat(TestFiles.put(objects.presignUpload(key, content.length, "text/plain", Duration.ofMinutes(5)), content, "text/plain"))
                    .isEqualTo(200);
        }
        return new String[] {doc, version, key};
    }

    void send(String messageId, String[] document) {
        byte[] body = json.write(Map.of("messageId", messageId, "type", MessagingConfig.VERSION_UPLOADED, "documentId", document[0],
                "versionId", document[1], "s3Key", document[2], "fileName", "f.txt", "mimeType", "text/plain")).getBytes(StandardCharsets.UTF_8);
        rabbit.send(WmsEvents.EXCHANGE, MessagingConfig.VERSION_UPLOADED,
                MessageBuilder.withBody(body).setContentType(MessageProperties.CONTENT_TYPE_JSON).setMessageId(messageId).build());
    }

    Optional<String> text(String versionId) {
        return jdbc.sql("SELECT content FROM document_text WHERE version_id = :v").param("v", versionId).query(String.class).optional();
    }

    boolean processed(String messageId) {
        return jdbc.sql("SELECT COUNT(*) FROM processed_message WHERE message_id = :m").param("m", messageId).query(Long.class).single() > 0;
    }

    static void await(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 20_000;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("timed out waiting for the listener");
            }
            Thread.sleep(100);
        }
    }

    @Test
    void storesTheTextOfAnUploadedVersionAndHandlesEachMessageOnce() throws Exception {
        String[] notes = document("Notes.txt", "quarterly budget review".getBytes(StandardCharsets.UTF_8));
        String messageId = UUID.randomUUID().toString();

        send(messageId, notes);
        await(() -> text(notes[1]).isPresent());
        assertThat(text(notes[1])).contains("quarterly budget review");

        jdbc.sql("UPDATE document_text SET content = 'kept' WHERE version_id = :v").param("v", notes[1]).update();
        send(messageId, notes);
        String[] other = document("Other.txt", "second file".getBytes(StandardCharsets.UTF_8));
        send(UUID.randomUUID().toString(), other);
        await(() -> text(other[1]).isPresent());

        assertThat(text(notes[1])).contains("kept");
    }

    @Test
    void aVersionWhoseObjectIsGoneIsConsumedWithoutText() throws Exception {
        String[] gone = document("Gone.txt", null);
        String messageId = UUID.randomUUID().toString();

        send(messageId, gone);
        await(() -> processed(messageId));

        assertThat(text(gone[1])).isEmpty();
    }
}
